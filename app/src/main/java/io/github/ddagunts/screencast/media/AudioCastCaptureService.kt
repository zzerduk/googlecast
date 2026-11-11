package io.github.ddagunts.screencast.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import io.github.ddagunts.screencast.cast.CastSdkSessionController
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import io.github.ddagunts.screencast.target.CaptureTargetRepository
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.webrtc.WebRtcAudioSession
import io.github.ddagunts.screencast.webrtc.WebRtcCleanupTimeoutException
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object AudioCastSessionCoordinator {
    private val muteStateMutex = Mutex()

    private val _config = MutableStateFlow(AudioStreamConfig())
    val config: StateFlow<AudioStreamConfig> = _config.asStateFlow()

    private val _telemetry = MutableStateFlow(AudioPipelineTelemetry())
    val telemetry: StateFlow<AudioPipelineTelemetry> = _telemetry.asStateFlow()

    private val _protocolLogs = MutableStateFlow<List<String>>(
        listOf(
            "Ready: WebRTC Per-App UID Whitelist + Multi-Mode Phone Audio Output (Shizuku / Global Mute / Capture Only).",
            "WebRTC Cast Receiver: 9098830C (48 kHz / Stereo / PCM16).",
            "Add and select at least one target app instance before starting."
        )
    )
    val protocolLogs: StateFlow<List<String>> = _protocolLogs.asStateFlow()

    @Volatile
    var isCaptureLocked: Boolean = false
        private set

    suspend fun <T> withMuteLock(block: suspend () -> T): T {
        return muteStateMutex.withLock { block() }
    }

    /**
     * Serializes and executes phone audio restoration on [Dispatchers.IO], returning a non-null
     * error message if either Shizuku session restoration or Global Media Mute restoration failed.
     */
    suspend fun restorePhoneAudioSerialized(
        appContext: Context?,
        failureReason: String? = null
    ): String? = withMuteLock {
        withContext(Dispatchers.IO) {
            restorePhoneAudioLocked(appContext, failureReason)
        }
    }

    internal fun tryRestorePhoneAudioImmediate(
        appContext: Context?,
        failureReason: String? = null
    ): Pair<Boolean, String?> {
        if (!muteStateMutex.tryLock()) {
            return false to null
        }
        return try {
            true to restorePhoneAudioLocked(appContext, failureReason)
        } finally {
            muteStateMutex.unlock()
        }
    }

    internal fun restorePhoneAudioLocked(
        appContext: Context?,
        failureReason: String? = null
    ): String? {
        val shizukuOutcome = ShizukuSessionMuteController.stopLeaseAndRestoreImmediately(failureReason = failureReason)
        val globalRestoreResult = if (appContext != null) {
            PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(appContext)
        } else {
            Result.success(null)
        }
        CaptureTargetRepository.clearFrozenTargetsSnapshot()

        val errors = mutableListOf<String>()
        globalRestoreResult.fold(
            onSuccess = { msg ->
                if (msg != null) {
                    appendLog(msg)
                }
            },
            onFailure = { err ->
                val msg = err.message ?: "恢复手机原媒体音量失败。"
                appendLog("Global media mute restore failed: $msg")
                errors.add(msg)
            }
        )

        if (!shizukuOutcome.success || shizukuOutcome.pendingRestoreCount > 0) {
            val shizukuErr = shizukuOutcome.error
                ?: "仍有 ${shizukuOutcome.pendingRestoreCount} 个受控音频会话尚未确认恢复。"
            appendLog("Shizuku session mute restore incomplete: $shizukuErr")
            errors.add(shizukuErr)
        }

        return errors.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    fun updateConfig(transform: (AudioStreamConfig) -> AudioStreamConfig) {
        if (isCaptureLocked) return
        _config.update(transform)
    }

    fun updateTelemetry(transform: (AudioPipelineTelemetry) -> AudioPipelineTelemetry) {
        _telemetry.update { current ->
            val updated = transform(current)
            if (isCaptureLocked || current.isCaptureLocked || current.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT) {
                isCaptureLocked = true
                updated.copy(
                    isStreaming = false,
                    isSessionActive = false,
                    isPhoneMuted = false,
                    hasValidAudioSignal = false,
                    webRtcStage = WebRtcStage.CLEANUP_TIMEOUT,
                    isCaptureLocked = true,
                    error = current.error ?: updated.error
                        ?: "Native audio cleanup timed out; capture is locked in this process."
                )
            } else {
                updated
            }
        }
    }

    fun markCleanupTimeout(message: String, appContext: Context? = null) {
        val restoreError = restorePhoneAudioLocked(appContext, failureReason = message)
        val combinedMessage = combineErrorMessages(message, restoreError) ?: message
        isCaptureLocked = true
        _telemetry.update { current ->
            current.copy(
                isStreaming = false,
                isSessionActive = false,
                isPhoneMuted = false,
                hasValidAudioSignal = false,
                webRtcStage = WebRtcStage.CLEANUP_TIMEOUT,
                isCaptureLocked = true,
                error = combinedMessage
            )
        }
        appendLog("Audio cast cleanup timed out: $combinedMessage")
    }

    internal fun combineErrorMessages(primary: String?, secondary: String?): String? {
        return when {
            primary.isNullOrBlank() && secondary.isNullOrBlank() -> null
            primary.isNullOrBlank() -> secondary
            secondary.isNullOrBlank() -> primary
            primary.contains(secondary) -> primary
            else -> "$primary（$secondary）"
        }
    }

    internal fun resetForTesting(appContext: Context? = null) {
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
        if (appContext != null) {
            PhoneGlobalMediaMuteController.resetForTesting(appContext)
        }
        CaptureTargetRepository.resetForTesting()
        isCaptureLocked = false
        _config.value = AudioStreamConfig()
        _telemetry.value = AudioPipelineTelemetry()
        _protocolLogs.value = listOf(
            "Ready: WebRTC audio receiver 9098830C."
        )
    }

    fun appendLog(message: String) {
        _protocolLogs.update { current ->
            (current + message).takeLast(60)
        }
    }
}

class AudioCastCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var castStatusJob: Job? = null
    private var stopJob: Job? = null
    private var sessionRunning = false
    @Volatile private var generation = 0L
    @Volatile private var stopping = false
    @Volatile private var destroyed = false
    @Volatile private var cleanupTimedOut = false
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var webRtcSession: WebRtcAudioSession? = null
    private var webRtcChannel: CastSignalingChannel? = null
    @Volatile private var frozenSessionOutputMode: PhoneOutputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE
    @Volatile private var isOutputModeInitialized: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AUDIO_CAST -> {
                if (cleanupTimedOut || AudioCastSessionCoordinator.isCaptureLocked) {
                    teardownScope.launch {
                        val restoreErr = AudioCastSessionCoordinator.restorePhoneAudioSerialized(
                            appContext = applicationContext,
                            failureReason = null
                        )
                        if (restoreErr != null) {
                            AudioCastSessionCoordinator.updateTelemetry {
                                it.copy(error = AudioCastSessionCoordinator.combineErrorMessages(it.error, restoreErr))
                            }
                        }
                    }
                    return START_NOT_STICKY
                }
                stopCastingSession()
            }
            ACTION_START_AUDIO_CAST -> {
                if (sessionRunning || stopping || cleanupTimedOut || AudioCastSessionCoordinator.isCaptureLocked) {
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val requestedModeName = intent.getStringExtra(EXTRA_OUTPUT_MODE)
                val requestedMode = requestedModeName?.let { name ->
                    runCatching { PhoneOutputMode.valueOf(name) }.getOrNull()
                }
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startCastingPipeline(resultCode, resultData, requestedMode)
            }
            else -> {
                stopCastingSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, types)
            } catch (e: SecurityException) {
                if (types != ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    throw e
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshForegroundNotification() {
        if (destroyed || !sessionRunning) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildForegroundNotification())
        }
    }

    private fun startCastingPipeline(
        resultCode: Int,
        resultData: Intent?,
        requestedMode: PhoneOutputMode?
    ) {
        if (AudioCastSessionCoordinator.isCaptureLocked) return
        sessionRunning = true
        stopping = false
        isOutputModeInitialized = false
        val token = ++generation

        val frozenMode = requestedMode
            ?: CaptureTargetRepository.frozenOutputMode.value
            ?: PhoneOutputMode.SHIZUKU_SESSION_MUTE
        frozenSessionOutputMode = frozenMode

        // Always satisfy Android 14+ foreground service start contract on the main thread
        runCatching { promoteToForeground() }

        val frozenTargets = CaptureTargetRepository.frozenTargets.value
            ?: CaptureTargetRepository.selectedTargets.value

        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(
                isSessionActive = true,
                activeTargets = frozenTargets,
                activeOutputMode = frozenMode,
                webRtcStage = WebRtcStage.CAPTURING
            )
        }

        // sessionJob covers the entire startup and capture lifecycle
        sessionJob = serviceScope.launch {
            var muteEngagedByThisToken = false
            try {
                check(resultCode == Activity.RESULT_OK && resultData != null) {
                    "Audio capture authorization was denied."
                }
                check(
                    ContextCompat.checkSelfPermission(
                        this@AudioCastCaptureService,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    "RECORD_AUDIO permission is required."
                }
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "WebRTC playback capture requires Android 10 or later."
                }
                if (PhoneGlobalMediaMuteController.hasUnhandledRecoveryRecord(applicationContext)) {
                    error("存在未处理的媒体音量恢复记录，请先处理恢复弹窗后再启动投放。")
                }
                webRtcChannel = CastSdkSessionController.channel
                check(webRtcChannel?.isConnected == true) {
                    "Select a Cast receiver before starting WebRTC audio."
                }

                // Step 1: Re-validate frozen target UIDs on Dispatchers.IO
                val resolution = CaptureTargetRepository.resolveAndValidateFrozenTargets(
                    context = applicationContext,
                    outputModeOverride = frozenMode
                )
                if (generation != token || stopping) {
                    return@launch
                }
                if (!resolution.isValid || resolution.resolvedUids.isEmpty()) {
                    error(resolution.failureReason ?: "目标应用完整 UID 解析与共享 UID 校验未通过。")
                }
                val targetUids = resolution.resolvedUids
                AudioCastSessionCoordinator.appendLog(
                    "Resolved target UIDs for mode=${frozenMode.name}: ${targetUids.sorted()} " +
                        "from ${frozenTargets.map { it.toStorageKey() }}"
                )

                // Step 2: Serialized output mode engagement on Dispatchers.IO
                AudioCastSessionCoordinator.withMuteLock {
                    if (generation != token || stopping) {
                        return@withMuteLock
                    }
                    withContext(Dispatchers.IO) {
                        when (frozenMode) {
                            PhoneOutputMode.SHIZUKU_SESSION_MUTE -> {
                                val leaseOutcome = ShizukuSessionMuteController.startSessionMuteLease(
                                    targetUids = targetUids,
                                    onFatalMuteError = { muteError ->
                                        serviceScope.launch(Dispatchers.Main.immediate) {
                                            if (generation == token && !stopping) {
                                                stopCastingSession(muteError)
                                            }
                                        }
                                    }
                                )
                                if (!leaseOutcome.success) {
                                    error(leaseOutcome.error ?: "初始播放器会话静音确认失败，已恢复会话并终止投放。")
                                }
                                muteEngagedByThisToken = true
                                isOutputModeInitialized = true
                                if (generation != token || stopping) {
                                    AudioCastSessionCoordinator.restorePhoneAudioLocked(
                                        appContext = applicationContext,
                                        failureReason = null
                                    )
                                    muteEngagedByThisToken = false
                                    isOutputModeInitialized = false
                                    return@withContext
                                }
                                AudioCastSessionCoordinator.updateTelemetry {
                                    it.copy(
                                        activeTargets = frozenTargets,
                                        activeTargetUids = targetUids,
                                        activeOutputMode = frozenMode,
                                        isInitialMuteConfirmed = true,
                                        isPhoneMuted = true
                                    )
                                }
                                AudioCastSessionCoordinator.appendLog(
                                    "Shizuku session mute confirmed via getMutedBy() " +
                                        "(active=${leaseOutcome.activeTargetPlayerCount}, " +
                                        "muted=${leaseOutcome.confirmedMutedPlayerCount}, " +
                                        "mask=0x${leaseOutcome.lastMixerMutedByMask.toString(16)})"
                                )
                            }

                            PhoneOutputMode.GLOBAL_MEDIA_MUTE -> {
                                val engageResult = PhoneGlobalMediaMuteController.engageGlobalMediaMute(applicationContext)
                                if (engageResult.isFailure) {
                                    error(
                                        engageResult.exceptionOrNull()?.message
                                            ?: "整体媒体静音执行失败，已回滚原媒体音量并终止投放。"
                                    )
                                }
                                muteEngagedByThisToken = true
                                isOutputModeInitialized = true
                                if (generation != token || stopping) {
                                    AudioCastSessionCoordinator.restorePhoneAudioLocked(
                                        appContext = applicationContext,
                                        failureReason = null
                                    )
                                    muteEngagedByThisToken = false
                                    isOutputModeInitialized = false
                                    return@withContext
                                }
                                AudioCastSessionCoordinator.updateTelemetry {
                                    it.copy(
                                        activeTargets = frozenTargets,
                                        activeTargetUids = targetUids,
                                        activeOutputMode = frozenMode,
                                        isInitialMuteConfirmed = false,
                                        isPhoneMuted = true
                                    )
                                }
                                AudioCastSessionCoordinator.appendLog(
                                    "Global media mute engaged for session."
                                )
                            }

                            PhoneOutputMode.CAPTURE_ONLY_UNMUTED -> {
                                isOutputModeInitialized = true
                                AudioCastSessionCoordinator.updateTelemetry {
                                    it.copy(
                                        activeTargets = frozenTargets,
                                        activeTargetUids = targetUids,
                                        activeOutputMode = frozenMode,
                                        isInitialMuteConfirmed = false,
                                        isPhoneMuted = false
                                    )
                                }
                                AudioCastSessionCoordinator.appendLog(
                                    "Capture-only (unmuted) mode active: phone volume and sessions left untouched."
                                )
                            }
                        }
                    }
                }

                if (generation != token || stopping) {
                    if (muteEngagedByThisToken) {
                        withContext(NonCancellable) {
                            AudioCastSessionCoordinator.restorePhoneAudioSerialized(
                                appContext = applicationContext,
                                failureReason = null
                            )
                        }
                        muteEngagedByThisToken = false
                    }
                    return@launch
                }

                // Step 3: MediaProjection and Cast SDK stay on Main thread
                refreshForegroundNotification()

                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = manager.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection could not be acquired.")
                mediaProjection = projection
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (generation == token && !stopping && !cleanupTimedOut) {
                            stopCastingSession("MediaProjection authorization was revoked.")
                        }
                    }
                }
                projectionCallback = callback
                projection.registerCallback(callback, Handler(Looper.getMainLooper()))
                runWebRtcPipeline(projection, targetUids, token)
            } catch (e: CancellationException) {
                if (muteEngagedByThisToken && (generation != token || stopping)) {
                    withContext(NonCancellable) {
                        AudioCastSessionCoordinator.restorePhoneAudioSerialized(
                            appContext = applicationContext,
                            failureReason = null
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                if (generation == token && !stopping) {
                    stopCastingSession(e.message ?: "Unable to start audio capture.")
                } else if (muteEngagedByThisToken) {
                    withContext(NonCancellable) {
                        AudioCastSessionCoordinator.restorePhoneAudioSerialized(
                            appContext = applicationContext,
                            failureReason = e.message
                        )
                    }
                }
            }
        }
    }

    private fun computeCurrentPhoneMutedState(): Boolean {
        return when (frozenSessionOutputMode) {
            PhoneOutputMode.SHIZUKU_SESSION_MUTE ->
                AudioCastSessionCoordinator.telemetry.value.isInitialMuteConfirmed &&
                    ShizukuSessionMuteController.muteTelemetry.value.leaseActive
            PhoneOutputMode.GLOBAL_MEDIA_MUTE ->
                PhoneGlobalMediaMuteController.isPhoneCurrentlyMutedByGlobalMode(applicationContext)
            PhoneOutputMode.CAPTURE_ONLY_UNMUTED ->
                false
        }
    }

    private suspend fun runWebRtcPipeline(
        projection: MediaProjection,
        targetUids: Set<Int>,
        token: Long
    ) {
        val channel = webRtcChannel?.takeIf { it.isConnected }
            ?: error("The Cast session ended before capture started.")
        val session = WebRtcAudioSession(
            context = applicationContext,
            channel = channel,
            onStage = { stage ->
                if (stage == WebRtcStage.CLEANUP_TIMEOUT) {
                    AudioCastSessionCoordinator.markCleanupTimeout(
                        message = AudioCastSessionCoordinator.telemetry.value.error
                            ?: "WebRTC native audio cleanup timed out; capture is locked in this process.",
                        appContext = applicationContext
                    )
                } else if (generation == token && !stopping) {
                    val modeReady = isOutputModeInitialized
                    val phoneMuted = computeCurrentPhoneMutedState()
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            webRtcStage = stage,
                            activeOutputMode = frozenSessionOutputMode,
                            isStreaming = modeReady && stage == WebRtcStage.SENDING,
                            isPhoneMuted = phoneMuted,
                            isHardwareCaptureActive = stage == WebRtcStage.CAPTURING ||
                                stage == WebRtcStage.NEGOTIATING ||
                                stage == WebRtcStage.CONNECTED ||
                                stage == WebRtcStage.SENDING
                        )
                    }
                    refreshForegroundNotification()
                }
            },
            onStats = { stats ->
                if (generation == token && !stopping) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            totalBytesStreamed = stats.audioBytesSent,
                            audioPacketsSent = stats.audioPacketsSent,
                            rttMs = stats.rttMs
                        )
                    }
                }
            },
            onPcmStats = { stats ->
                if (generation == token && !stopping) {
                    ShizukuSessionMuteController.recordAudioRmsForGateComparison(stats.rmsDb)
                    val hasValidSignal = stats.rmsDb > -59.5f
                    val phoneMuted = computeCurrentPhoneMutedState()
                    val previousSignal = AudioCastSessionCoordinator.telemetry.value.hasValidAudioSignal
                    val previousMuted = AudioCastSessionCoordinator.telemetry.value.isPhoneMuted
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            pcmChunksRead = stats.chunksRead,
                            pcmUnderruns = stats.underruns,
                            pcmDroppedChunks = stats.droppedChunks,
                            pcmRingDepth = stats.ringDepth,
                            audioRecordMinBufferBytes = stats.minBufferBytes,
                            currentRmsDb = stats.rmsDb,
                            hasValidAudioSignal = hasValidSignal,
                            isPhoneMuted = phoneMuted
                        )
                    }
                    if (hasValidSignal != previousSignal || phoneMuted != previousMuted) {
                        refreshForegroundNotification()
                    }
                }
            },
            onError = { message ->
                if (generation == token && !stopping) {
                    AudioCastSessionCoordinator.updateTelemetry { it.copy(error = message) }
                }
                serviceScope.launch(Dispatchers.Main) {
                    if (generation == token && !stopping) stopCastingSession(message)
                }
            },
            onLog = { message ->
                if (generation == token || stopping) AudioCastSessionCoordinator.appendLog(message)
            }
        )
        webRtcSession = session
        castStatusJob = serviceScope.launch {
            CastSdkSessionController.status.collect { status ->
                if ((!status.isConnected || CastSdkSessionController.channel !== channel) &&
                    generation == token && !stopping
                ) {
                    stopCastingSession(
                        AudioCastSessionCoordinator.telemetry.value.error
                            ?: status.error ?: "The Cast session ended or was replaced."
                    )
                }
            }
        }
        session.start(projection, targetUids)
    }

    private fun stopCastingSession(error: String? = null) {
        if (cleanupTimedOut) return
        // Invalidate generation FIRST so any in-flight startup task cannot mute after stop begins
        generation++

        if (stopping) {
            if (error != null) {
                AudioCastSessionCoordinator.updateTelemetry {
                    it.copy(error = AudioCastSessionCoordinator.combineErrorMessages(it.error, error))
                }
            }
            return
        }

        if (!sessionRunning) {
            teardownScope.launch {
                val restoreError = AudioCastSessionCoordinator.restorePhoneAudioSerialized(
                    appContext = applicationContext,
                    failureReason = error
                )
                isOutputModeInitialized = false
                val combinedError = AudioCastSessionCoordinator.combineErrorMessages(error, restoreError)
                if (combinedError != null) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            webRtcStage = WebRtcStage.ERROR,
                            error = combinedError
                        )
                    }
                }
                if (!destroyed && !AudioCastSessionCoordinator.isCaptureLocked) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
            return
        }

        stopping = true
        var finalError = error
        val startingJob = sessionJob
        sessionJob = null
        startingJob?.cancel()
        castStatusJob?.cancel()
        castStatusJob = null

        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null

        AudioCastSessionCoordinator.updateTelemetry {
            it.copy(
                isStreaming = false,
                isPhoneMuted = false,
                hasValidAudioSignal = false,
                webRtcStage = WebRtcStage.STOPPING,
                error = error ?: it.error
            )
        }
        if (error != null) AudioCastSessionCoordinator.appendLog("Audio cast failed: $error")

        stopJob = teardownScope.launch {
            var timedOutCleanup = false
            var nativeCleanupFailed = false
            val session = webRtcSession
            webRtcSession = null
            try {
                // Step 1: Complete phone audio restoration attempt FIRST before waiting on WebRTC/HAL cleanup
                val restoreError = AudioCastSessionCoordinator.restorePhoneAudioSerialized(
                    appContext = applicationContext,
                    failureReason = finalError
                )
                isOutputModeInitialized = false
                finalError = AudioCastSessionCoordinator.combineErrorMessages(finalError, restoreError)

                // Step 2: Release WebRTC & HAL capture resources even if restoration failed
                try {
                    session?.stop()
                } catch (e: WebRtcCleanupTimeoutException) {
                    timedOutCleanup = true
                    finalError = AudioCastSessionCoordinator.combineErrorMessages(
                        e.message ?: "WebRTC native audio cleanup timed out.",
                        restoreError
                    )
                } catch (e: Exception) {
                    if (session?.isCleanupTimedOut == true) {
                        timedOutCleanup = true
                        finalError = AudioCastSessionCoordinator.combineErrorMessages(
                            e.message ?: "WebRTC native audio cleanup timed out.",
                            restoreError
                        )
                    } else {
                        nativeCleanupFailed = true
                        AudioCastSessionCoordinator.appendLog("WebRTC cleanup: ${e.message}")
                        finalError = AudioCastSessionCoordinator.combineErrorMessages(
                            finalError,
                            "WebRTC cleanup failed: ${e.message}"
                        )
                        session?.close()
                    }
                }
                if (session?.isCleanupTimedOut == true) {
                    timedOutCleanup = true
                }
                if (!timedOutCleanup) {
                    runCatching { startingJob?.join() }
                }
                webRtcChannel?.endSession()
                webRtcChannel = null
            } finally {
                if (timedOutCleanup) {
                    cleanupTimedOut = true
                    sessionRunning = false
                    stopping = false
                    val timeoutMessage = finalError
                        ?: "WebRTC native audio cleanup timed out; capture is locked in this process."
                    AudioCastSessionCoordinator.markCleanupTimeout(timeoutMessage, applicationContext)
                    if (!destroyed) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(
                            NOTIFICATION_ID,
                            buildForegroundNotification(
                                isCleanupTimedOut = true,
                                timeoutMessage = timeoutMessage
                            )
                        )
                    }
                } else {
                    val projection = mediaProjection
                    mediaProjection = null
                    if (!nativeCleanupFailed && projection != null) {
                        withContext(Dispatchers.IO) {
                            runCatching { projection.stop() }.onFailure {
                                finalError = AudioCastSessionCoordinator.combineErrorMessages(
                                    finalError,
                                    "MediaProjection cleanup failed: ${it.message}"
                                )
                            }
                        }
                    }
                    sessionRunning = false
                    stopping = false
                    if (!destroyed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    AudioCastSessionCoordinator.updateTelemetry {
                        AudioPipelineTelemetry(
                            activeOutputMode = frozenSessionOutputMode,
                            webRtcStage = if (finalError != null) WebRtcStage.ERROR else WebRtcStage.IDLE,
                            error = finalError
                        )
                    }
                    AudioCastSessionCoordinator.appendLog(
                        if (finalError == null) "Audio cast session stopped and phone audio restored."
                        else "Audio cast stopped with error ($finalError)."
                    )
                }
                if (destroyed) teardownScope.cancel()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        stopCastingSession()
        serviceScope.cancel()
        if (stopJob?.isActive != true) teardownScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    internal fun buildForegroundNotification(
        isCleanupTimedOut: Boolean = false,
        timeoutMessage: String? = null
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tel = AudioCastSessionCoordinator.telemetry.value
        val castConnected = CastSdkSessionController.status.value.isConnected
        val mode = tel.activeOutputMode

        val titleText = if (isCleanupTimedOut) {
            "Audio Cleanup Timed Out (Capture Locked)"
        } else {
            "ScreenCast 投音 • ${mode.badgeLabel}"
        }

        val bodyText = if (isCleanupTimedOut) {
            timeoutMessage
                ?: "Native audio cleanup did not finish; restart the app before capturing again."
        } else {
            val speakerPart = if (castConnected) "音箱已连接" else "音箱未连接"
            val streamPart = if (tel.isStreaming) "流正在发送" else "阶段:${tel.webRtcStage.name}"
            val audioPart = if (tel.hasValidAudioSignal) {
                "有效音频 (${tel.currentRmsDb.roundToInt()} dBFS)"
            } else {
                "尚无有效音频"
            }
            val mutePart = if (tel.isPhoneMuted) {
                "手机已静音 (${mode.badgeLabel})"
            } else {
                "手机未静音 (${mode.badgeLabel})"
            }
            "$speakerPart • $streamPart • $audioPart • $mutePart"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(pendingOpen)
            .setOngoing(true)

        if (!isCleanupTimedOut) {
            val stopIntent = Intent(this, AudioCastCaptureService::class.java).apply {
                action = ACTION_STOP_AUDIO_CAST
            }
            val pendingStop = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop_casting),
                pendingStop
            )
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START_AUDIO_CAST = "io.github.ddagunts.screencast.action.START_AUDIO_CAST"
        const val ACTION_STOP_AUDIO_CAST = "io.github.ddagunts.screencast.action.STOP_AUDIO_CAST"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_OUTPUT_MODE = "extra_output_mode"

        private const val CHANNEL_ID = "screencast_audio_low_latency"
        private const val NOTIFICATION_ID = 8009
    }
}
