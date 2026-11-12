package io.github.ddagunts.screencast.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import com.example.BuildConfig
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.target.ProfileType
import io.github.ddagunts.screencast.target.PublicApiTargetResolver
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.ShizukuConnectionState
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.TargetResolutionResult
import io.github.ddagunts.screencast.target.UserProfileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class MultiProfileCatalogSnapshot(
    val profiles: List<UserProfileInfo>,
    val catalogApps: List<TargetAppEntry>,
    val searchableApps: List<TargetAppEntry>
)

data class LeaseAcquisitionOutcome(
    val success: Boolean,
    val activeTargetPlayerCount: Int = 0,
    val confirmedMutedPlayerCount: Int = 0,
    val pendingRestoreCount: Int = 0,
    val lastMixerMutedByMask: Int = 0,
    val lastMuteConfirmationLatencyMs: Long? = null,
    val maxObservedNewTrackMuteLatencyMs: Long? = null,
    val error: String? = null
)

interface ShizukuMuteBackend {
    val connectionState: StateFlow<ShizukuConnectionState>
    fun refreshShizukuStatus()
    fun requestPermission(requestCode: Int = 4101)
    fun queryCatalog(
        context: Context,
        importedTargets: Set<CaptureTarget>,
        includeOtherApps: Boolean = false
    ): MultiProfileCatalogSnapshot
    fun resolveTargetEntries(
        context: Context,
        targets: Set<CaptureTarget>
    ): List<TargetAppEntry> = queryCatalog(context, targets, includeOtherApps = false)
        .catalogApps
        .filter { it.target in targets }
    fun resolveAndValidateTargets(context: Context, selectedTargets: Set<CaptureTarget>): TargetResolutionResult
    fun acquireOrRenewLease(targetUids: Set<Int>, leaseDurationMs: Long): LeaseAcquisitionOutcome
    fun releaseLeaseImmediately(): LeaseAcquisitionOutcome
    fun hasActiveStartedPlaybackForUids(targetUids: Set<Int>): Boolean
    fun addFailureListener(listener: (String) -> Unit)
    fun removeFailureListener(listener: (String) -> Unit)
    fun addStateSnapshotListener(listener: (LeaseAcquisitionOutcome) -> Unit)
    fun removeStateSnapshotListener(listener: (LeaseAcquisitionOutcome) -> Unit)
}

/**
 * Coordinates Shizuku `UserService` binding, multi-profile UID resolution,
 * 3-second clock-time VolumeShaper mute lease with 1-second renewal,
 * and immediate non-blocking session restoration.
 */
object ShizukuSessionMuteController {

    const val LEASE_DURATION_MS: Long = 3_000L
    const val RENEWAL_INTERVAL_MS: Long = 1_000L
    const val USER_SERVICE_PROTOCOL_VERSION: Int = 2

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val leaseLock = Any()
    private val leaseGeneration = AtomicLong(0L)
    @Volatile
    private var activeLeaseGeneration: Long = 0L
    @Volatile
    private var renewalJob: Job? = null
    @Volatile
    private var sessionFailureHandler: ((String) -> Unit)? = null

    private val _muteTelemetry = MutableStateFlow(SessionMuteTelemetry())
    val muteTelemetry: StateFlow<SessionMuteTelemetry> = _muteTelemetry.asStateFlow()

    @Volatile
    var backend: ShizukuMuteBackend = RealShizukuMuteBackend()
        internal set

    private val backendFailureListener: (String) -> Unit = { reason ->
        onRemoteMuteFailure(reason)
    }

    private val backendStateListener: (LeaseAcquisitionOutcome) -> Unit = { outcome ->
        val isLeaseCurrentlyActive = activeLeaseGeneration != 0L
        _muteTelemetry.update { current ->
            val maxLat = outcome.maxObservedNewTrackMuteLatencyMs?.takeIf { it >= 0L }
                ?: current.maxObservedNewTrackMuteLatencyMs
            val lastLat = outcome.lastMuteConfirmationLatencyMs?.takeIf { it >= 0L }
                ?: current.lastMuteConfirmationLatencyMs
            val updatedFailureReason = when {
                !outcome.error.isNullOrBlank() -> outcome.error
                outcome.pendingRestoreCount == 0 && outcome.success -> null
                else -> current.muteFailureReason
            }
            if (!isLeaseCurrentlyActive && !current.leaseActive) {
                current.copy(
                    pendingRestoreCount = outcome.pendingRestoreCount,
                    muteFailureReason = updatedFailureReason
                )
            } else {
                current.copy(
                    activeTargetPlayerCount = outcome.activeTargetPlayerCount,
                    confirmedMutedPlayerCount = outcome.confirmedMutedPlayerCount,
                    pendingRestoreCount = outcome.pendingRestoreCount,
                    lastMixerMutedByMask = outcome.lastMixerMutedByMask,
                    lastMuteConfirmationLatencyMs = lastLat,
                    maxObservedNewTrackMuteLatencyMs = maxLat,
                    isNewTrackLatencyWithinGate = maxLat?.let { it <= 100L } ?: current.isNewTrackLatencyWithinGate,
                    muteFailureReason = updatedFailureReason
                )
            }
        }
    }

    init {
        attachBackendListeners(backend)
    }

    internal fun installBackendForTesting(testBackend: ShizukuMuteBackend) {
        stopLeaseAndRestoreImmediately()
        backend.removeFailureListener(backendFailureListener)
        backend.removeStateSnapshotListener(backendStateListener)
        backend = testBackend
        attachBackendListeners(testBackend)
        syncShizukuConnectionState()
    }

    internal fun resetToDefaultBackend() {
        PublicApiTargetResolver.resetEnvironmentForTesting()
        installBackendForTesting(RealShizukuMuteBackend())
        _muteTelemetry.value = SessionMuteTelemetry(shizukuState = backend.connectionState.value)
    }

    private fun attachBackendListeners(targetBackend: ShizukuMuteBackend) {
        targetBackend.addFailureListener(backendFailureListener)
        targetBackend.addStateSnapshotListener(backendStateListener)
        controllerScope.launch {
            targetBackend.connectionState.collect { state ->
                _muteTelemetry.update { it.copy(shizukuState = state) }
                if (_muteTelemetry.value.leaseActive && state != ShizukuConnectionState.READY) {
                    onRemoteMuteFailure("Shizuku 权限或服务连接中断 ($state)，已恢复已处理会话并终止投放。")
                }
            }
        }
    }

    fun syncShizukuConnectionState() {
        backend.refreshShizukuStatus()
        _muteTelemetry.update { it.copy(shizukuState = backend.connectionState.value) }
    }

    fun requestShizukuPermission() {
        backend.requestPermission()
        syncShizukuConnectionState()
    }

    fun queryCatalog(
        context: Context,
        importedTargets: Set<CaptureTarget>,
        includeOtherApps: Boolean = false
    ): MultiProfileCatalogSnapshot {
        syncShizukuConnectionState()
        return if (backend.connectionState.value == ShizukuConnectionState.READY) {
            backend.queryCatalog(context, importedTargets, includeOtherApps)
        } else {
            PublicApiTargetResolver.queryCatalog(context, importedTargets, includeOtherApps)
        }
    }

    fun resolveTargetEntries(
        context: Context,
        targets: Set<CaptureTarget>
    ): List<TargetAppEntry> {
        if (targets.isEmpty()) return emptyList()
        syncShizukuConnectionState()
        return if (backend.connectionState.value == ShizukuConnectionState.READY) {
            backend.resolveTargetEntries(context, targets)
        } else {
            PublicApiTargetResolver.resolveTargetEntries(context, targets)
        }
    }

    fun resolveAndValidateTargets(
        context: Context,
        selectedTargets: Set<CaptureTarget>,
        outputMode: PhoneOutputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE
    ): TargetResolutionResult {
        syncShizukuConnectionState()
        val isShizukuReady = backend.connectionState.value == ShizukuConnectionState.READY
        return when {
            outputMode == PhoneOutputMode.SHIZUKU_SESSION_MUTE -> {
                if (!isShizukuReady) {
                    TargetResolutionResult(
                        isValid = false,
                        failureReason = "Shizuku Shell 服务未就绪 (${backend.connectionState.value.statusLabel})，定向静音无法启动。"
                    )
                } else {
                    backend.resolveAndValidateTargets(context, selectedTargets)
                }
            }
            isShizukuReady -> backend.resolveAndValidateTargets(context, selectedTargets)
            else -> PublicApiTargetResolver.resolveAndValidateTargets(context, selectedTargets)
        }
    }

    fun startSessionMuteLease(
        targetUids: Set<Int>,
        onFatalMuteError: (String) -> Unit
    ): LeaseAcquisitionOutcome {
        renewalJob?.cancel()
        renewalJob = null

        val myGeneration: Long
        val initialOutcome: LeaseAcquisitionOutcome
        synchronized(leaseLock) {
            renewalJob?.cancel()
            renewalJob = null
            myGeneration = leaseGeneration.incrementAndGet()
            activeLeaseGeneration = 0L
            sessionFailureHandler = onFatalMuteError

            syncShizukuConnectionState()
            if (backend.connectionState.value != ShizukuConnectionState.READY) {
                val msg = "Shizuku Shell 服务未就绪 (${backend.connectionState.value.statusLabel})，无法安全执行会话静音。"
                _muteTelemetry.update {
                    it.copy(
                        leaseActive = false,
                        resolvedTargetUids = emptySet(),
                        muteFailureReason = msg
                    )
                }
                return LeaseAcquisitionOutcome(success = false, error = msg)
            }

            initialOutcome = backend.acquireOrRenewLease(targetUids, LEASE_DURATION_MS)
            if (!initialOutcome.success) {
                val err = initialOutcome.error ?: "初始播放器会话静音确认失败。"
                val releaseOutcome = if (initialOutcome.pendingRestoreCount > 0) {
                    initialOutcome
                } else {
                    runCatching { backend.releaseLeaseImmediately() }.getOrDefault(initialOutcome)
                }
                _muteTelemetry.update {
                    it.copy(
                        leaseActive = false,
                        resolvedTargetUids = emptySet(),
                        pendingRestoreCount = releaseOutcome.pendingRestoreCount.coerceAtLeast(initialOutcome.pendingRestoreCount),
                        muteFailureReason = err
                    )
                }
                return initialOutcome.copy(
                    pendingRestoreCount = releaseOutcome.pendingRestoreCount.coerceAtLeast(initialOutcome.pendingRestoreCount),
                    error = err
                )
            }

            activeLeaseGeneration = myGeneration
            val initialLat = initialOutcome.lastMuteConfirmationLatencyMs?.takeIf { it >= 0L }
            val maxLat = initialOutcome.maxObservedNewTrackMuteLatencyMs?.takeIf { it >= 0L } ?: initialLat

            _muteTelemetry.update {
                it.copy(
                    shizukuState = backend.connectionState.value,
                    resolvedTargetUids = targetUids,
                    activeTargetPlayerCount = initialOutcome.activeTargetPlayerCount,
                    confirmedMutedPlayerCount = initialOutcome.confirmedMutedPlayerCount,
                    pendingRestoreCount = initialOutcome.pendingRestoreCount,
                    lastMixerMutedByMask = initialOutcome.lastMixerMutedByMask,
                    lastMuteConfirmationLatencyMs = initialLat,
                    maxObservedNewTrackMuteLatencyMs = maxLat,
                    isNewTrackLatencyWithinGate = maxLat?.let { ms -> ms <= 100L },
                    leaseActive = true,
                    leaseDurationMs = LEASE_DURATION_MS,
                    leaseRenewalCount = 1L,
                    muteFailureReason = null
                )
            }

            renewalJob = controllerScope.launch {
                while (isActive && activeLeaseGeneration == myGeneration) {
                    delay(RENEWAL_INTERVAL_MS)
                    if (!isActive || activeLeaseGeneration != myGeneration) break
                    val failure = performSingleRenewalStep(myGeneration, targetUids) { isActive }
                    if (failure != null) {
                        val err = failure.error ?: "会话静音租约续期或新轨静音确认失败。"
                        onRemoteMuteFailure(err, releaseRemotelyAsync = true)
                        break
                    }
                }
            }
        }

        return initialOutcome
    }

    internal fun runSingleRenewalForTesting() {
        val gen = activeLeaseGeneration
        val uids = _muteTelemetry.value.resolvedTargetUids
        if (gen == 0L || uids.isEmpty()) return
        performSingleRenewalStep(gen, uids) { true }
    }

    private fun performSingleRenewalStep(
        myGeneration: Long,
        targetUids: Set<Int>,
        isJobActive: () -> Boolean
    ): LeaseAcquisitionOutcome? {
        var failedRenewalOutcome: LeaseAcquisitionOutcome? = null
        synchronized(leaseLock) {
            if (!isJobActive() || activeLeaseGeneration != myGeneration) {
                return null
            }
            val renewOutcome = backend.acquireOrRenewLease(targetUids, LEASE_DURATION_MS)
            if (activeLeaseGeneration != myGeneration) {
                // Lease was stopped concurrently or superseded; ensure remote lease is released
                runCatching { backend.releaseLeaseImmediately() }
                return null
            }
            if (!renewOutcome.success) {
                failedRenewalOutcome = renewOutcome
                return@synchronized
            }
            val latestLat = renewOutcome.lastMuteConfirmationLatencyMs?.takeIf { it >= 0L }
            val latestMax = renewOutcome.maxObservedNewTrackMuteLatencyMs?.takeIf { it >= 0L }
            _muteTelemetry.update { current ->
                val mergedMax = latestMax ?: current.maxObservedNewTrackMuteLatencyMs
                current.copy(
                    activeTargetPlayerCount = renewOutcome.activeTargetPlayerCount,
                    confirmedMutedPlayerCount = renewOutcome.confirmedMutedPlayerCount,
                    pendingRestoreCount = renewOutcome.pendingRestoreCount,
                    lastMixerMutedByMask = renewOutcome.lastMixerMutedByMask,
                    lastMuteConfirmationLatencyMs = latestLat ?: current.lastMuteConfirmationLatencyMs,
                    maxObservedNewTrackMuteLatencyMs = mergedMax,
                    isNewTrackLatencyWithinGate = mergedMax?.let { ms -> ms <= 100L },
                    leaseActive = true,
                    leaseRenewalCount = current.leaseRenewalCount + 1L
                )
            }
        }
        return failedRenewalOutcome
    }

    fun stopLeaseAndRestoreImmediately(failureReason: String? = null): LeaseAcquisitionOutcome {
        renewalJob?.cancel()
        return synchronized(leaseLock) {
            leaseGeneration.incrementAndGet()
            activeLeaseGeneration = 0L
            val job = renewalJob
            renewalJob = null
            job?.cancel()
            sessionFailureHandler = null
            val releaseOutcome = runCatching {
                backend.releaseLeaseImmediately()
            }.getOrElse { e ->
                LeaseAcquisitionOutcome(
                    success = false,
                    error = "释放会话静音租约异常：${e.message}"
                )
            }
            val effectiveError = when {
                !releaseOutcome.success -> releaseOutcome.error ?: failureReason ?: "部分受控音频会话尚未确认恢复"
                failureReason != null -> failureReason
                else -> null
            }
            _muteTelemetry.update { current ->
                current.copy(
                    leaseActive = false,
                    activeTargetPlayerCount = releaseOutcome.activeTargetPlayerCount,
                    confirmedMutedPlayerCount = releaseOutcome.confirmedMutedPlayerCount,
                    pendingRestoreCount = releaseOutcome.pendingRestoreCount,
                    muteFailureReason = effectiveError ?: if (releaseOutcome.pendingRestoreCount > 0) current.muteFailureReason else null
                )
            }
            releaseOutcome.copy(error = effectiveError)
        }
    }

    fun hasActiveStartedPlaybackForUids(targetUids: Set<Int>): Boolean {
        if (targetUids.isEmpty()) return false
        return backend.hasActiveStartedPlaybackForUids(targetUids)
    }

    fun recordAudioRmsForGateComparison(rmsDb: Float) {
        if (rmsDb <= -59.5f) return
        _muteTelemetry.update { current ->
            if (current.leaseActive && current.confirmedMutedPlayerCount > 0) {
                current.copy(
                    postMuteActiveRmsDb = rmsDb,
                    preMuteBaselineRmsDb = current.preMuteBaselineRmsDb ?: rmsDb
                )
            } else {
                current.copy(preMuteBaselineRmsDb = rmsDb)
            }
        }
    }

    /**
     * Handles remote mute failure notifications without synchronously calling back into
     * `releaseMuteLease` on the incoming Binder callback stack.
     */
    private fun onRemoteMuteFailure(reason: String, releaseRemotelyAsync: Boolean = false) {
        leaseGeneration.incrementAndGet()
        activeLeaseGeneration = 0L
        val job = renewalJob
        renewalJob = null
        job?.cancel()
        val handler = sessionFailureHandler
        sessionFailureHandler = null
        _muteTelemetry.update { current ->
            current.copy(
                leaseActive = false,
                activeTargetPlayerCount = 0,
                confirmedMutedPlayerCount = 0,
                muteFailureReason = reason
            )
        }
        if (releaseRemotelyAsync) {
            controllerScope.launch {
                stopLeaseAndRestoreImmediately(failureReason = reason)
            }
        }
        handler?.invoke(reason)
    }
}

internal class RealShizukuMuteBackend : ShizukuMuteBackend {

    private val _connectionState = MutableStateFlow(ShizukuConnectionState.UNAVAILABLE)
    override val connectionState: StateFlow<ShizukuConnectionState> = _connectionState.asStateFlow()

    private val failureListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(LeaseAcquisitionOutcome) -> Unit>()

    @Volatile private var remoteService: IShizukuAudioMuteService? = null
    @Volatile private var bindingInProgress: Boolean = false

    internal val shizukuCallback = object : IShizukuMuteCallback.Stub() {
        override fun onSessionStateUpdated(jsonSnapshot: String?) {
            if (jsonSnapshot.isNullOrBlank()) return
            val parsed = parseLeaseOutcome(jsonSnapshot)
            for (listener in stateListeners) {
                listener(parsed)
            }
        }

        override fun onMuteFailure(reason: String?) {
            val msg = reason ?: "Shizuku 会话静音失败"
            for (listener in failureListeners) {
                listener(msg)
            }
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingInProgress = false
            if (binder != null && binder.pingBinder()) {
                val service = IShizukuAudioMuteService.Stub.asInterface(binder)
                remoteService = service
                runCatching { service.registerCallback(shizukuCallback) }
                _connectionState.value = ShizukuConnectionState.READY
            } else {
                remoteService = null
                _connectionState.value = ShizukuConnectionState.ERROR
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindingInProgress = false
            remoteService = null
            _connectionState.value = ShizukuConnectionState.UNAVAILABLE
            for (listener in failureListeners) {
                listener("Shizuku UserService 连接已断开。")
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remoteService = null
        bindingInProgress = false
        _connectionState.value = ShizukuConnectionState.UNAVAILABLE
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserServiceIfNeeded()
            } else {
                _connectionState.value = ShizukuConnectionState.PERMISSION_REQUIRED
            }
        }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
    }

    override fun refreshShizukuStatus() {
        try {
            if (!Shizuku.pingBinder()) {
                remoteService = null
                bindingInProgress = false
                _connectionState.value = ShizukuConnectionState.UNAVAILABLE
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                remoteService = null
                bindingInProgress = false
                _connectionState.value = ShizukuConnectionState.PERMISSION_REQUIRED
                return
            }
            val currentService = remoteService
            if (currentService != null && currentService.asBinder().pingBinder()) {
                _connectionState.value = ShizukuConnectionState.READY
            } else {
                bindUserServiceIfNeeded()
            }
        } catch (_: Throwable) {
            _connectionState.value = ShizukuConnectionState.UNAVAILABLE
        }
    }

    private fun bindUserServiceIfNeeded() {
        if (bindingInProgress) return
        try {
            bindingInProgress = true
            _connectionState.value = ShizukuConnectionState.CONNECTING
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, ShizukuAudioMuteUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("audio_mute_shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE * 100 + ShizukuSessionMuteController.USER_SERVICE_PROTOCOL_VERSION)
            Shizuku.bindUserService(args, userServiceConnection)
        } catch (_: Throwable) {
            bindingInProgress = false
            _connectionState.value = ShizukuConnectionState.ERROR
        }
    }

    override fun requestPermission(requestCode: Int) {
        try {
            if (!Shizuku.pingBinder()) {
                _connectionState.value = ShizukuConnectionState.UNAVAILABLE
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserServiceIfNeeded()
            } else {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Throwable) {
            _connectionState.value = ShizukuConnectionState.UNAVAILABLE
        }
    }

    override fun queryCatalog(
        context: Context,
        importedTargets: Set<CaptureTarget>,
        includeOtherApps: Boolean
    ): MultiProfileCatalogSnapshot {
        val service = remoteService?.takeIf { it.asBinder().pingBinder() }
        if (service != null) {
            try {
                val payload = importedTargets.map { it.toStorageKey() }
                val rawJson = service.queryMultiProfileCatalog(payload, includeOtherApps)
                return enrichLabelsWithPackageManager(context, parseCatalogJson(rawJson))
            } catch (_: Throwable) {
            }
        }
        return queryLocalFallbackCatalog(context, importedTargets, includeOtherApps)
    }

    override fun resolveAndValidateTargets(
        context: Context,
        selectedTargets: Set<CaptureTarget>
    ): TargetResolutionResult {
        if (selectedTargets.isEmpty()) {
            return TargetResolutionResult(
                isValid = false,
                failureReason = "请先添加并勾选至少 1 个目标应用（空目标禁止启动投音）。"
            )
        }
        val service = remoteService?.takeIf { it.asBinder().pingBinder() }
            ?: return TargetResolutionResult(
                isValid = false,
                failureReason = "Shizuku Shell 服务未连接，无法核验跨空间目标完整 UID 与建立会话静音租约。"
            )
        return try {
            val rawJson = service.resolveAndValidateTargets(selectedTargets.map { it.toStorageKey() })
            parseResolutionResultJson(rawJson)
        } catch (e: Throwable) {
            TargetResolutionResult(
                isValid = false,
                failureReason = "通过 Shizuku 核验目标 UID 失败：${e.message}"
            )
        }
    }

    override fun acquireOrRenewLease(
        targetUids: Set<Int>,
        leaseDurationMs: Long
    ): LeaseAcquisitionOutcome {
        val service = remoteService?.takeIf { it.asBinder().pingBinder() }
            ?: return LeaseAcquisitionOutcome(
                success = false,
                error = "Shizuku UserService 未连接，无法建立或续期会话静音租约。"
            )
        return try {
            val raw = service.acquireOrRenewMuteLease(targetUids.toIntArray(), leaseDurationMs)
            parseLeaseOutcome(raw)
        } catch (e: Throwable) {
            LeaseAcquisitionOutcome(
                success = false,
                error = "Shizuku 会话静音调用异常：${e.message}"
            )
        }
    }

    override fun releaseLeaseImmediately(): LeaseAcquisitionOutcome {
        val service = remoteService ?: return LeaseAcquisitionOutcome(success = true)
        return try {
            val raw = service.releaseMuteLease()
            if (raw.isNullOrBlank()) {
                LeaseAcquisitionOutcome(success = true)
            } else {
                parseLeaseOutcome(raw)
            }
        } catch (e: Throwable) {
            LeaseAcquisitionOutcome(
                success = false,
                error = "Shizuku 释放会话静音失败：${e.message}"
            )
        }
    }

    override fun hasActiveStartedPlaybackForUids(targetUids: Set<Int>): Boolean {
        val service = remoteService?.takeIf { it.asBinder().pingBinder() } ?: return false
        return try {
            val raw = service.queryPlaybackSnapshot(targetUids.toIntArray())
            val obj = JSONObject(raw)
            obj.optBoolean("hasActiveStartedPlayback", false)
        } catch (_: Throwable) {
            false
        }
    }

    override fun addFailureListener(listener: (String) -> Unit) {
        failureListeners.addIfAbsent(listener)
    }

    override fun removeFailureListener(listener: (String) -> Unit) {
        failureListeners.remove(listener)
    }

    override fun addStateSnapshotListener(listener: (LeaseAcquisitionOutcome) -> Unit) {
        stateListeners.addIfAbsent(listener)
    }

    override fun removeStateSnapshotListener(listener: (LeaseAcquisitionOutcome) -> Unit) {
        stateListeners.remove(listener)
    }

    internal fun parseLeaseOutcome(rawJson: String): LeaseAcquisitionOutcome {
        return try {
            val obj = JSONObject(rawJson)
            val lastLat = obj.optLong("lastMuteConfirmationLatencyMs", -1L).takeIf { it >= 0L }
            val maxLat = obj.optLong("maxObservedNewTrackMuteLatencyMs", -1L).takeIf { it >= 0L }
            val err = if (obj.isNull("error")) null else obj.optString("error").takeIf { it.isNotBlank() }
            LeaseAcquisitionOutcome(
                success = obj.optBoolean("success", false),
                activeTargetPlayerCount = obj.optInt("activeTargetPlayerCount", 0),
                confirmedMutedPlayerCount = obj.optInt("confirmedMutedPlayerCount", 0),
                pendingRestoreCount = obj.optInt("pendingRestoreCount", 0),
                lastMixerMutedByMask = obj.optInt("lastMixerMutedByMask", 0),
                lastMuteConfirmationLatencyMs = lastLat,
                maxObservedNewTrackMuteLatencyMs = maxLat,
                error = err
            )
        } catch (e: Throwable) {
            LeaseAcquisitionOutcome(success = false, error = e.message)
        }
    }

    internal fun parseCatalogJson(rawJson: String): MultiProfileCatalogSnapshot {
        val obj = JSONObject(rawJson)
        val profilesArr = obj.optJSONArray("profiles")
        val profiles = buildList {
            if (profilesArr != null) {
                for (i in 0 until profilesArr.length()) {
                    val p = profilesArr.getJSONObject(i)
                    add(
                        UserProfileInfo(
                            userSerialNumber = p.optLong("userSerialNumber", 0L),
                            userId = p.optInt("userId", 0),
                            label = p.optString("label", "主空间"),
                            profileType = runCatching {
                                ProfileType.valueOf(p.optString("profileType", "PRIMARY"))
                            }.getOrDefault(ProfileType.PRIMARY),
                            isRunning = p.optBoolean("isRunning", true)
                        )
                    )
                }
            }
        }

        fun parseAppList(arrayName: String): List<TargetAppEntry> {
            val arr = obj.optJSONArray(arrayName) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val pkg = item.optString("packageName")
                    if (pkg.isBlank()) continue
                    val serial = item.optLong("userSerialNumber", 0L)
                    val pType = runCatching {
                        ProfileType.valueOf(item.optString("profileType", "PRIMARY"))
                    }.getOrDefault(ProfileType.PRIMARY)
                    val siblingsArr = item.optJSONArray("sharedUidPackages")
                    val siblings = buildList {
                        if (siblingsArr != null) {
                            for (j in 0 until siblingsArr.length()) {
                                add(siblingsArr.getString(j))
                            }
                        }
                    }
                    val uid = if (item.isNull("resolvedUid")) null else item.optInt("resolvedUid")
                    val reason = if (item.isNull("statusReason")) null else item.optString("statusReason").takeIf { it.isNotBlank() }
                    val appCategory = if (item.has("appCategory") && !item.isNull("appCategory")) {
                        item.optInt("appCategory", ApplicationInfo.CATEGORY_UNDEFINED)
                    } else {
                        ApplicationInfo.CATEGORY_UNDEFINED
                    }
                    val isIdentityVerified = if (item.has("isIdentityVerified")) {
                        item.optBoolean("isIdentityVerified", uid != null && uid > 0)
                    } else {
                        uid != null && uid > 0
                    }
                    add(
                        TargetAppEntry(
                            target = CaptureTarget(pkg, serial),
                            appLabel = item.optString("appLabel", pkg),
                            profileType = pType,
                            profileLabel = item.optString("profileLabel", pType.badgeLabel),
                            isInstalled = item.optBoolean("isInstalled", true),
                            isProfileRunning = item.optBoolean("isProfileRunning", true),
                            isSystemApp = item.optBoolean("isSystemApp", false),
                            appCategory = appCategory,
                            resolvedUid = uid,
                            sharedUidPackages = siblings,
                            isIdentityVerified = isIdentityVerified,
                            statusReason = reason
                        )
                    )
                }
            }
        }

        return MultiProfileCatalogSnapshot(
            profiles = profiles,
            catalogApps = parseAppList("catalogApps"),
            searchableApps = parseAppList("searchableApps")
        )
    }

    private fun enrichLabelsWithPackageManager(
        context: Context,
        snapshot: MultiProfileCatalogSnapshot
    ): MultiProfileCatalogSnapshot {
        val pm = context.packageManager
        val labelCache = HashMap<String, String>()
        fun resolveLocalizedLabel(pkg: String, fallback: String): String {
            labelCache[pkg]?.let { return it }
            val resolved = runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallback
            labelCache[pkg] = resolved
            return resolved
        }

        return snapshot.copy(
            catalogApps = snapshot.catalogApps.map {
                it.copy(appLabel = resolveLocalizedLabel(it.target.packageName, it.appLabel))
            }.sortedWith(compareBy({ it.profileType.ordinal }, { it.appLabel.lowercase() })),
            searchableApps = snapshot.searchableApps.map {
                it.copy(appLabel = resolveLocalizedLabel(it.target.packageName, it.appLabel))
            }.sortedWith(compareBy({ it.isSystemApp }, { it.profileType.ordinal }, { it.appLabel.lowercase() }))
        )
    }

    private fun parseResolutionResultJson(rawJson: String): TargetResolutionResult {
        val obj = JSONObject(rawJson)
        val isValid = obj.optBoolean("isValid", false)
        val resolvedUids = buildSet {
            val arr = obj.optJSONArray("resolvedUids")
            if (arr != null) {
                for (i in 0 until arr.length()) add(arr.getInt(i))
            }
        }
        val targetUidMap = buildMap {
            val arr = obj.optJSONArray("targetUidMap")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val target = CaptureTarget.fromStorageKey(entry.optString("target")) ?: continue
                    put(target, entry.optInt("uid"))
                }
            }
        }
        val unavailable = buildList {
            val arr = obj.optJSONArray("unavailableTargets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val target = CaptureTarget.fromStorageKey(entry.optString("target")) ?: continue
                    add(target to entry.optString("reason", "不可用"))
                }
            }
        }
        val conflicts = buildMap<Int, List<String>> {
            val conflictObj = obj.optJSONObject("sharedUidConflicts")
            if (conflictObj != null) {
                val keys = conflictObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val uid = k.toIntOrNull() ?: continue
                    val arr = conflictObj.optJSONArray(k) ?: continue
                    val pkgs = (0 until arr.length()).map { arr.getString(it) }
                    put(uid, pkgs)
                }
            }
        }
        val reason = if (obj.isNull("failureReason")) null else obj.optString("failureReason").takeIf { it.isNotBlank() }
        return TargetResolutionResult(
            isValid = isValid,
            resolvedUids = resolvedUids,
            targetUidMap = targetUidMap,
            sharedUidConflicts = conflicts,
            unavailableTargets = unavailable,
            failureReason = reason
        )
    }

    private fun queryLocalFallbackCatalog(
        context: Context,
        importedTargets: Set<CaptureTarget>,
        includeOtherApps: Boolean
    ): MultiProfileCatalogSnapshot {
        return PublicApiTargetResolver.queryCatalog(context, importedTargets, includeOtherApps)
    }
}
