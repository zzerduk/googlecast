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
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.CaptureTargetRepository
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.ShizukuConnectionState
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.UserProfileInfo
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ScreenCastViewModel(application: Application) : AndroidViewModel(application) {

    val config: StateFlow<AudioStreamConfig> = AudioCastSessionCoordinator.config
    val telemetry: StateFlow<AudioPipelineTelemetry> = AudioCastSessionCoordinator.telemetry
    val castStatus: StateFlow<CastSdkSessionStatus> = CastSdkSessionController.status
    val combinedLogs: StateFlow<List<String>> = AudioCastSessionCoordinator.protocolLogs

    val selectedTargets: StateFlow<Set<CaptureTarget>> = CaptureTargetRepository.selectedTargets
    val importedTargets: StateFlow<Set<CaptureTarget>> = CaptureTargetRepository.importedTargets
    val profiles: StateFlow<List<UserProfileInfo>> = CaptureTargetRepository.profiles
    val catalogApps: StateFlow<List<TargetAppEntry>> = CaptureTargetRepository.catalogApps
    val searchableApps: StateFlow<List<TargetAppEntry>> = CaptureTargetRepository.searchableApps
    val muteTelemetry: StateFlow<SessionMuteTelemetry> = ShizukuSessionMuteController.muteTelemetry

    private val _isRequestingCapture = MutableStateFlow(false)
    val isRequestingCapture: StateFlow<Boolean> = _isRequestingCapture.asStateFlow()
    private var requestedChannel: CastSignalingChannel? = null

    private val _isTargetEditingLocked = MutableStateFlow(false)
    val isTargetEditingLocked: StateFlow<Boolean> = _isTargetEditingLocked.asStateFlow()

    init {
        CaptureTargetRepository.initialize(application)
        ShizukuSessionMuteController.syncShizukuConnectionState()
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

    private fun computeEditingLockedNow(): Boolean {
        val current = telemetry.value
        val locked = _isRequestingCapture.value ||
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

    fun refreshTargetCatalog() {
        ShizukuSessionMuteController.syncShizukuConnectionState()
        CaptureTargetRepository.refreshCatalog(getApplication())
    }

    fun requestShizukuPermission() {
        ShizukuSessionMuteController.requestShizukuPermission()
        CaptureTargetRepository.refreshCatalog(getApplication())
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

    fun prepareCaptureRequest(requireConnectedCastReceiver: Boolean = true): Boolean {
        if (isCaptureLockedInProcess()) {
            return false
        }
        if (_isRequestingCapture.value || telemetry.value.isSessionActive) {
            return false
        }
        val currentTargets = selectedTargets.value
        if (currentTargets.isEmpty()) {
            captureAuthorizationFailed("请先添加并至少勾选 1 个要投音与定向静音的目标应用（空目标禁止启动）。")
            return false
        }
        ShizukuSessionMuteController.syncShizukuConnectionState()
        val shizukuState = muteTelemetry.value.shizukuState
        if (shizukuState != ShizukuConnectionState.READY) {
            captureAuthorizationFailed(
                "Shizuku Shell 服务未就绪 (${shizukuState.statusLabel})，请先启动并授权 Shizuku 后再开始投音。"
            )
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            captureAuthorizationFailed("WebRTC playback capture requires Android 10 or later.")
            return false
        }
        if (requireConnectedCastReceiver) {
            if (!castStatus.value.isConnected) {
                captureAuthorizationFailed(castStatus.value.error ?: "请先通过右上角 Cast 按钮连接音箱接收端。")
                return false
            }
            requestedChannel = CastSdkSessionController.channel
            if (requestedChannel == null) {
                captureAuthorizationFailed("The Cast session ended. Reconnect before requesting capture.")
                return false
            }
        }

        // Freeze selected CaptureTarget snapshot at authorization time & validate immediately
        CaptureTargetRepository.freezeSelectedTargetsSnapshot()
        val preflightCheck = CaptureTargetRepository.resolveAndValidateFrozenTargets(getApplication())
        if (!preflightCheck.isValid) {
            CaptureTargetRepository.clearFrozenTargetsSnapshot()
            captureAuthorizationFailed(preflightCheck.failureReason ?: "所选目标应用校验未通过。")
            return false
        }

        _isRequestingCapture.value = true
        computeEditingLockedNow()
        AudioCastSessionCoordinator.updateTelemetry { it.copy(error = null) }
        return true
    }

    fun captureAuthorizationFailed(message: String) {
        val channel = requestedChannel
        requestedChannel = null
        _isRequestingCapture.value = false
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately(message)
        CaptureTargetRepository.clearFrozenTargetsSnapshot()
        computeEditingLockedNow()
        if (!isCaptureLockedInProcess()) {
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = message) }
        }
        AudioCastSessionCoordinator.appendLog(message)
        channel?.endSession()
    }

    fun startAudioCastWithProjection(resultCode: Int, resultData: Intent?) {
        if (isCaptureLockedInProcess()) {
            requestedChannel = null
            _isRequestingCapture.value = false
            CaptureTargetRepository.clearFrozenTargetsSnapshot()
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
        _isRequestingCapture.value = false
        val serviceIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_START_AUDIO_CAST
            putExtra(AudioCastCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCastCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(
                isSessionActive = true,
                activeTargets = CaptureTargetRepository.frozenTargets.value ?: selectedTargets.value
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
        requestedChannel = null
        _isRequestingCapture.value = false
        // Immediate non-blocking restoration of any session mute envelope
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
        CaptureTargetRepository.clearFrozenTargetsSnapshot()
        computeEditingLockedNow()
        if (isCaptureLockedInProcess()) {
            CastSdkSessionController.endSession()
            return
        }
        if (!telemetry.value.isSessionActive) {
            CastSdkSessionController.endSession()
            AudioCastSessionCoordinator.updateTelemetry { AudioPipelineTelemetry() }
            return
        }
        val app = getApplication<Application>()
        val stopIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_STOP_AUDIO_CAST
        }
        try {
            app.startService(stopIntent)
        } catch (e: Exception) {
            AudioCastSessionCoordinator.updateTelemetry {
                it.copy(error = e.message ?: "The capture service could not be stopped.")
            }
        }
    }
}
