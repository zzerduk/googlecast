package io.github.ddagunts.screencast.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.cast.CastSdkSessionController
import io.github.ddagunts.screencast.cast.CastSdkSessionStatus
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.media.AudioCastCaptureService
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.media.PhoneGlobalMediaMuteController
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.CaptureTargetRepository
import io.github.ddagunts.screencast.target.PendingGlobalMuteRecovery
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.ShizukuConnectionState
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.TargetPreset
import io.github.ddagunts.screencast.target.UserProfileInfo
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCastViewModel(application: Application) : AndroidViewModel(application) {

    val config: StateFlow<AudioStreamConfig> = AudioCastSessionCoordinator.config
    val telemetry: StateFlow<AudioPipelineTelemetry> = AudioCastSessionCoordinator.telemetry
    val castStatus: StateFlow<CastSdkSessionStatus> = CastSdkSessionController.status
    val combinedLogs: StateFlow<List<String>> = AudioCastSessionCoordinator.protocolLogs

    val selectedTargets: StateFlow<Set<CaptureTarget>> = CaptureTargetRepository.selectedTargets
    val importedTargets: StateFlow<Set<CaptureTarget>> = CaptureTargetRepository.importedTargets
    val presets: StateFlow<List<TargetPreset>> = CaptureTargetRepository.presets
    val profiles: StateFlow<List<UserProfileInfo>> = CaptureTargetRepository.profiles
    val catalogApps: StateFlow<List<TargetAppEntry>> = CaptureTargetRepository.catalogApps
    val searchableApps: StateFlow<List<TargetAppEntry>> = CaptureTargetRepository.searchableApps
    val muteTelemetry: StateFlow<SessionMuteTelemetry> = ShizukuSessionMuteController.muteTelemetry

    val pendingGlobalMuteRecovery: StateFlow<PendingGlobalMuteRecovery?> =
        PhoneGlobalMediaMuteController.pendingRecovery
    val isGlobalMediaMuteCompatible: StateFlow<Boolean> =
        PhoneGlobalMediaMuteController.isGlobalMediaMuteCompatible
    val globalMediaMuteDisabledReason: StateFlow<String?> =
        PhoneGlobalMediaMuteController.globalMediaMuteDisabledReason

    private val _showOutputModeDialog = MutableStateFlow(false)
    val showOutputModeDialog: StateFlow<Boolean> = _showOutputModeDialog.asStateFlow()

    private val captureRequestGuard = AtomicBoolean(false)
    private val _isRequestingCapture = MutableStateFlow(false)
    val isRequestingCapture: StateFlow<Boolean> = _isRequestingCapture.asStateFlow()
    private var requestedChannel: CastSignalingChannel? = null

    private val _isTargetEditingLocked = MutableStateFlow(false)
    val isTargetEditingLocked: StateFlow<Boolean> = _isTargetEditingLocked.asStateFlow()

    @Volatile
    internal var preflightSuspendHookForTesting: (suspend () -> Unit)? = null

    init {
        CaptureTargetRepository.initialize(application)
        viewModelScope.launch(Dispatchers.IO) {
            PhoneGlobalMediaMuteController.initialize(
                context = application,
                isSessionCurrentlyActive = telemetry.value.isSessionActive
            )
            ShizukuSessionMuteController.syncShizukuConnectionState()
        }
        CastSdkSessionController.initialize(application, AudioCastSessionCoordinator::appendLog)
        viewModelScope.launch {
            combine(
                _isRequestingCapture,
                telemetry,
                CaptureTargetRepository.frozenTargets
            ) { requesting, tel, frozen ->
                requesting ||
                    tel.isSessionActive ||
                    tel.isCaptureLocked ||
                    tel.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT ||
                    frozen != null
            }.collect { locked ->
                _isTargetEditingLocked.value = locked
            }
        }
    }

    override fun onCleared() {
        if (captureRequestGuard.get() || _isRequestingCapture.value) {
            releaseCaptureRequestSlot()
        }
        super.onCleared()
    }

    private fun acquireCaptureRequestSlot(): Boolean {
        if (isCaptureLockedInProcess() || telemetry.value.isSessionActive) return false
        if (!captureRequestGuard.compareAndSet(false, true)) return false
        _isRequestingCapture.value = true
        computeEditingLockedNow()
        return true
    }

    private fun releaseCaptureRequestSlot() {
        requestedChannel = null
        captureRequestGuard.set(false)
        _isRequestingCapture.value = false
        CaptureTargetRepository.clearFrozenTargetsSnapshot()
        computeEditingLockedNow()
    }

    private fun computeEditingLockedNow(): Boolean {
        val current = telemetry.value
        val locked = _isRequestingCapture.value ||
            captureRequestGuard.get() ||
            current.isSessionActive ||
            current.isCaptureLocked ||
            AudioCastSessionCoordinator.isCaptureLocked ||
            current.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT ||
            CaptureTargetRepository.frozenTargets.value != null
        _isTargetEditingLocked.value = locked
        return locked
    }

    private fun isCaptureLockedInProcess(): Boolean {
        val current = telemetry.value
        return AudioCastSessionCoordinator.isCaptureLocked ||
            current.isCaptureLocked ||
            current.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT
    }

    fun onAppResumed() {
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuSessionMuteController.syncShizukuConnectionState()
            CaptureTargetRepository.onAppResumed(getApplication())
        }
    }

    fun refreshTargetCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuSessionMuteController.syncShizukuConnectionState()
            CaptureTargetRepository.refreshCatalog(getApplication())
        }
    }

    fun onSearchQueryChanged(query: String) {
        CaptureTargetRepository.onSearchQueryChanged(getApplication(), query)
    }

    fun requestShizukuPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuSessionMuteController.requestShizukuPermission()
            CaptureTargetRepository.refreshCatalog(getApplication())
        }
    }

    fun toggleTargetAppSelection(target: CaptureTarget) {
        val locked = computeEditingLockedNow()
        if (!CaptureTargetRepository.toggleTargetSelection(getApplication(), target, locked)) {
            AudioCastSessionCoordinator.appendLog("Target selection is locked during authorization or active casting.")
        }
    }

    fun clearTargetAppSelections() {
        val locked = computeEditingLockedNow()
        CaptureTargetRepository.clearSelectedTargets(getApplication(), locked)
    }

    fun importTargetApp(packageName: String, userSerialNumber: Long): Result<CaptureTarget> {
        val locked = computeEditingLockedNow()
        val result = CaptureTargetRepository.importTargetApp(
            context = getApplication(),
            packageName = packageName,
            userSerialNumber = userSerialNumber,
            selectImmediately = true,
            isEditingLocked = locked
        )
        result.onSuccess { target ->
            AudioCastSessionCoordinator.appendLog("Added target app: ${target.toStorageKey()}")
        }.onFailure { err ->
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = err.message) }
        }
        return result
    }

    fun removeImportedTarget(target: CaptureTarget) {
        val locked = computeEditingLockedNow()
        CaptureTargetRepository.removeImportedTarget(getApplication(), target, locked)
    }

    fun saveCurrentSelectionAsPreset(name: String): Result<TargetPreset> {
        val locked = computeEditingLockedNow()
        val result = CaptureTargetRepository.saveCurrentSelectionAsPreset(
            context = getApplication(),
            rawName = name,
            isEditingLocked = locked
        )
        result.onSuccess { preset ->
            AudioCastSessionCoordinator.appendLog(
                "Saved target preset '${preset.name}' (${preset.targets.size} apps)."
            )
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = null) }
        }.onFailure { err ->
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = err.message) }
        }
        return result
    }

    fun applyTargetPreset(name: String): Boolean {
        val locked = computeEditingLockedNow()
        val applied = CaptureTargetRepository.applyPreset(
            context = getApplication(),
            presetName = name,
            isEditingLocked = locked
        )
        if (applied) {
            AudioCastSessionCoordinator.appendLog("Applied target preset '$name'.")
        } else if (locked) {
            AudioCastSessionCoordinator.appendLog("Preset switching is locked during authorization or active casting.")
        }
        return applied
    }

    fun deleteTargetPreset(name: String): Boolean {
        val locked = computeEditingLockedNow()
        val deleted = CaptureTargetRepository.deletePreset(
            context = getApplication(),
            presetName = name,
            isEditingLocked = locked
        )
        if (deleted) {
            AudioCastSessionCoordinator.appendLog("Deleted target preset '$name' (imported apps retained).")
        }
        return deleted
    }

    fun setSpeakerVolume(volumeFraction: Float): Boolean {
        return CastSdkSessionController.setSpeakerVolume(volumeFraction.toDouble())
    }

    fun setSpeakerMuted(muted: Boolean): Boolean {
        return CastSdkSessionController.setSpeakerMuted(muted)
    }

    fun confirmRestoreLeftoverMediaVolume() {
        viewModelScope.launch(Dispatchers.IO) {
            confirmRestoreLeftoverMediaVolumeSuspend()
        }
    }

    suspend fun confirmRestoreLeftoverMediaVolumeSuspend(): Result<Unit> = withContext(Dispatchers.IO) {
        val result = PhoneGlobalMediaMuteController.confirmRestoreLeftoverVolume(getApplication())
        result.fold(
            onSuccess = {
                AudioCastSessionCoordinator.updateTelemetry { it.copy(error = null) }
                AudioCastSessionCoordinator.appendLog("User confirmed restoring leftover global media volume.")
            },
            onFailure = { err ->
                val msg = err.message ?: "恢复原媒体音量失败。"
                AudioCastSessionCoordinator.updateTelemetry { it.copy(error = msg) }
                AudioCastSessionCoordinator.appendLog("Leftover media volume restore failed: $msg")
            }
        )
        result
    }

    fun dismissLeftoverMediaVolumeRecovery() {
        viewModelScope.launch(Dispatchers.IO) {
            dismissLeftoverMediaVolumeRecoverySuspend()
        }
    }

    suspend fun dismissLeftoverMediaVolumeRecoverySuspend(): Result<Unit> = withContext(Dispatchers.IO) {
        val result = PhoneGlobalMediaMuteController.dismissLeftoverRecoveryKeepCurrent(getApplication())
        result.fold(
            onSuccess = {
                AudioCastSessionCoordinator.updateTelemetry { it.copy(error = null) }
                AudioCastSessionCoordinator.appendLog("User chose to keep current media volume and clear leftover record.")
            },
            onFailure = { err ->
                val msg = err.message ?: "清除媒体静音恢复记录失败。"
                AudioCastSessionCoordinator.updateTelemetry { it.copy(error = msg) }
                AudioCastSessionCoordinator.appendLog("Failed to clear leftover media volume record: $msg")
            }
        )
        result
    }

    fun setGlobalMediaMuteCompatibility(compatible: Boolean, reason: String? = null) {
        PhoneGlobalMediaMuteController.setGlobalMediaMuteCompatibility(
            context = getApplication(),
            compatible = compatible,
            disabledReason = reason
        )
    }

    fun dismissOutputModeDialog() {
        _showOutputModeDialog.value = false
    }

    /**
     * Entry point when user clicks "Start Audio Cast":
     * - Acquires the capture request lock BEFORE the first suspension point to prevent double-tap re-entry.
     * - Refuses to start if an unhandled global media mute recovery record exists.
     * - If Shizuku is `READY`, defaults to [PhoneOutputMode.SHIZUKU_SESSION_MUTE] and proceeds immediately.
     * - If Shizuku is unavailable or permission is not granted, opens the fallback output mode dialog
     *   so the user can explicitly choose between [PhoneOutputMode.GLOBAL_MEDIA_MUTE] and
     *   [PhoneOutputMode.CAPTURE_ONLY_UNMUTED].
     */
    suspend fun initiateStartCastFlow(requireConnectedCastReceiver: Boolean = true): Boolean {
        if (!acquireCaptureRequestSlot()) return false
        var keepRequestLock = false
        try {
            preflightSuspendHookForTesting?.invoke()
            val hasUnhandledRecovery = withContext(Dispatchers.IO) {
                PhoneGlobalMediaMuteController.hasUnhandledRecoveryRecord(getApplication())
            }
            if (hasUnhandledRecovery) {
                failPreflightPreparation(
                    "存在未处理的媒体音量恢复记录，请先在恢复弹窗中选择“恢复原音量”或“保留当前设置”后再启动新投放。"
                )
                return false
            }

            val currentTargets = selectedTargets.value
            if (currentTargets.isEmpty()) {
                failPreflightPreparation("请先添加并至少勾选 1 个目标应用（空目标禁止启动）。")
                return false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                failPreflightPreparation("WebRTC playback capture requires Android 10 or later.")
                return false
            }
            if (requireConnectedCastReceiver && !castStatus.value.isConnected) {
                failPreflightPreparation(castStatus.value.error ?: "请先通过右上角 Cast 按钮连接音箱接收端。")
                return false
            }

            withContext(Dispatchers.IO) {
                ShizukuSessionMuteController.syncShizukuConnectionState()
            }
            val shizukuState = muteTelemetry.value.shizukuState
            return if (shizukuState == ShizukuConnectionState.READY) {
                _showOutputModeDialog.value = false
                keepRequestLock = prepareCaptureRequestLocked(
                    requireConnectedCastReceiver = requireConnectedCastReceiver,
                    outputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE,
                    skipInitialRecoveryCheck = true
                )
                keepRequestLock
            } else {
                _showOutputModeDialog.value = true
                false
            }
        } catch (e: CancellationException) {
            releaseCaptureRequestSlot()
            throw e
        } finally {
            if (!keepRequestLock) {
                releaseCaptureRequestSlot()
            }
        }
    }

    suspend fun confirmFallbackOutputModeAndPrepare(
        chosenMode: PhoneOutputMode,
        requireConnectedCastReceiver: Boolean = true
    ): Boolean {
        _showOutputModeDialog.value = false
        AudioCastSessionCoordinator.updateTelemetry {
            it.copy(activeOutputMode = chosenMode)
        }
        return prepareCaptureRequest(
            requireConnectedCastReceiver = requireConnectedCastReceiver,
            outputMode = chosenMode
        )
    }

    suspend fun prepareCaptureRequest(
        requireConnectedCastReceiver: Boolean = true,
        outputMode: PhoneOutputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE
    ): Boolean {
        if (!acquireCaptureRequestSlot()) {
            return false
        }
        var keepRequestLock = false
        try {
            keepRequestLock = prepareCaptureRequestLocked(
                requireConnectedCastReceiver = requireConnectedCastReceiver,
                outputMode = outputMode,
                skipInitialRecoveryCheck = false
            )
            return keepRequestLock
        } catch (e: CancellationException) {
            releaseCaptureRequestSlot()
            throw e
        } finally {
            if (!keepRequestLock) {
                releaseCaptureRequestSlot()
            }
        }
    }

    private suspend fun prepareCaptureRequestLocked(
        requireConnectedCastReceiver: Boolean,
        outputMode: PhoneOutputMode,
        skipInitialRecoveryCheck: Boolean
    ): Boolean {
        if (!skipInitialRecoveryCheck) {
            preflightSuspendHookForTesting?.invoke()
            val hasUnhandledRecovery = withContext(Dispatchers.IO) {
                PhoneGlobalMediaMuteController.hasUnhandledRecoveryRecord(getApplication())
            }
            if (hasUnhandledRecovery) {
                failPreflightPreparation(
                    "存在未处理的媒体音量恢复记录，请先在恢复弹窗中选择“恢复原音量”或“保留当前设置”后再启动新投放。"
                )
                return false
            }
        }

        val currentTargets = selectedTargets.value
        if (currentTargets.isEmpty()) {
            failPreflightPreparation("请先添加并至少勾选 1 个要投音的目标应用（空目标禁止启动）。")
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            failPreflightPreparation("WebRTC playback capture requires Android 10 or later.")
            return false
        }
        if (requireConnectedCastReceiver) {
            if (!castStatus.value.isConnected) {
                failPreflightPreparation(castStatus.value.error ?: "请先通过右上角 Cast 按钮连接音箱接收端。")
                return false
            }
            requestedChannel = CastSdkSessionController.channel
            if (requestedChannel == null) {
                failPreflightPreparation("The Cast session ended. Reconnect before requesting capture.")
                return false
            }
        }

        // Freeze selected CaptureTarget snapshot and lock target editing immediately before preflight IO
        CaptureTargetRepository.freezeSelectedTargetsSnapshot(outputMode)
        computeEditingLockedNow()

        withContext(Dispatchers.IO) {
            ShizukuSessionMuteController.syncShizukuConnectionState()
        }
        val shizukuState = muteTelemetry.value.shizukuState
        if (outputMode == PhoneOutputMode.SHIZUKU_SESSION_MUTE &&
            shizukuState != ShizukuConnectionState.READY
        ) {
            failPreflightPreparation(
                "Shizuku Shell 服务未就绪 (${shizukuState.statusLabel})，无法执行定向静音，请授权 Shizuku 或选择备选输出模式。"
            )
            return false
        }
        if (outputMode == PhoneOutputMode.GLOBAL_MEDIA_MUTE &&
            !PhoneGlobalMediaMuteController.isGlobalMediaMuteCompatible.value
        ) {
            val disabledReason = PhoneGlobalMediaMuteController.globalMediaMuteDisabledReason.value
                ?: "本机整体媒体静音会导致系统回采静音，已禁用该选项，请选择“仅采集不静音”。"
            failPreflightPreparation(disabledReason)
            return false
        }

        val preflightCheck = try {
            CaptureTargetRepository.resolveAndValidateFrozenTargets(
                context = getApplication(),
                outputModeOverride = outputMode
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failPreflightPreparation(e.message ?: "所选目标应用校验异常。")
            return false
        }
        if (!preflightCheck.isValid) {
            failPreflightPreparation(preflightCheck.failureReason ?: "所选目标应用校验未通过。")
            return false
        }

        AudioCastSessionCoordinator.updateTelemetry {
            it.copy(
                activeOutputMode = outputMode,
                error = null
            )
        }
        return true
    }

    private fun failPreflightPreparation(message: String) {
        val channel = requestedChannel
        releaseCaptureRequestSlot()
        if (!isCaptureLockedInProcess()) {
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = message) }
        }
        AudioCastSessionCoordinator.appendLog(message)
        channel?.endSession()
    }

    fun captureAuthorizationFailed(message: String) {
        val channel = requestedChannel
        releaseCaptureRequestSlot()
        if (!isCaptureLockedInProcess()) {
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = message) }
        }
        AudioCastSessionCoordinator.launchRestoreAsync(
            appContext = getApplication(),
            failureReason = message
        ) { restoreError ->
            val combinedMsg = AudioCastSessionCoordinator.combineErrorMessages(message, restoreError) ?: message
            if (!isCaptureLockedInProcess()) {
                AudioCastSessionCoordinator.updateTelemetry { it.copy(error = combinedMsg) }
            }
        }
        AudioCastSessionCoordinator.appendLog(message)
        channel?.endSession()
    }

    fun startAudioCastWithProjection(resultCode: Int, resultData: Intent?) {
        if (isCaptureLockedInProcess()) {
            releaseCaptureRequestSlot()
            return
        }
        if (!_isRequestingCapture.value) {
            captureAuthorizationFailed("Capture authorization request expired. Start again with fresh authorization.")
            return
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            captureAuthorizationFailed("Audio capture authorization was denied.")
            return
        }
        if (requestedChannel != null &&
            (requestedChannel?.isConnected != true || CastSdkSessionController.channel !== requestedChannel)
        ) {
            captureAuthorizationFailed("The selected Cast session changed. Start again with fresh authorization.")
            return
        }
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            captureAuthorizationFailed("RECORD_AUDIO permission is required.")
            return
        }
        val channel = requestedChannel
        requestedChannel = null
        captureRequestGuard.set(false)
        _isRequestingCapture.value = false
        val frozenMode = CaptureTargetRepository.frozenOutputMode.value
            ?: PhoneOutputMode.SHIZUKU_SESSION_MUTE
        val frozenTargets = CaptureTargetRepository.frozenTargets.value ?: selectedTargets.value

        val serviceIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_START_AUDIO_CAST
            putExtra(AudioCastCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCastCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(AudioCastCaptureService.EXTRA_OUTPUT_MODE, frozenMode.name)
        }
        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(
                isSessionActive = true,
                activeTargets = frozenTargets,
                activeOutputMode = frozenMode
            )
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
        } catch (e: Exception) {
            AudioCastSessionCoordinator.updateTelemetry { AudioPipelineTelemetry() }
            channel?.endSession()
            captureAuthorizationFailed(e.message ?: "The capture service could not start.")
        }
    }

    fun stopAudioCast() {
        val wasSessionActive = telemetry.value.isSessionActive
        releaseCaptureRequestSlot()
        if (isCaptureLockedInProcess()) {
            AudioCastSessionCoordinator.launchRestoreAsync(getApplication(), null)
            CastSdkSessionController.endSession()
            return
        }
        if (!wasSessionActive) {
            AudioCastSessionCoordinator.updateTelemetry {
                AudioPipelineTelemetry(
                    activeOutputMode = it.activeOutputMode,
                    webRtcStage = WebRtcStage.IDLE,
                    error = null
                )
            }
            AudioCastSessionCoordinator.launchRestoreAsync(getApplication(), null) { restoreErr ->
                if (restoreErr != null) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            webRtcStage = WebRtcStage.ERROR,
                            error = restoreErr
                        )
                    }
                }
            }
            CastSdkSessionController.endSession()
            return
        }
        val app = getApplication<Application>()
        val stopIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_STOP_AUDIO_CAST
        }
        try {
            app.startService(stopIntent)
        } catch (e: Exception) {
            AudioCastSessionCoordinator.launchRestoreAsync(getApplication(), e.message) { restoreErr ->
                val combined = AudioCastSessionCoordinator.combineErrorMessages(
                    e.message ?: "The capture service could not be stopped.",
                    restoreErr
                )
                AudioCastSessionCoordinator.updateTelemetry {
                    it.copy(error = combined)
                }
            }
        }
    }
}
