package io.github.ddagunts.screencast.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.cast.CastDeviceDiscovery
import io.github.ddagunts.screencast.cast.CastDeviceModel
import io.github.ddagunts.screencast.cast.CastSdkSessionController
import io.github.ddagunts.screencast.cast.CastSdkSessionStatus
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.media.AdtsAacEncoder
import io.github.ddagunts.screencast.media.AudioCastCaptureService
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.media.AudioCastMode
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ScreenCastViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = CastDeviceDiscovery(application)

    val devices: StateFlow<List<CastDeviceModel>> = discovery.devices
    val isScanning: StateFlow<Boolean> = discovery.isScanning
    val config: StateFlow<AudioStreamConfig> = AudioCastSessionCoordinator.config
    val telemetry: StateFlow<AudioPipelineTelemetry> = AudioCastSessionCoordinator.telemetry
    val castStatus: StateFlow<CastSdkSessionStatus> = CastSdkSessionController.status

    private val _isRequestingCapture = MutableStateFlow(false)
    val isRequestingCapture: StateFlow<Boolean> = _isRequestingCapture.asStateFlow()
    private var requestedMode: AudioCastMode? = null
    private var requestedChannel: CastSignalingChannel? = null
    private var modeChangeJob: Job? = null

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val combinedLogs: StateFlow<List<String>> = combine(
        discovery.discoveryLog,
        AudioCastSessionCoordinator.protocolLogs
    ) { mDnsLogs, castLogs ->
        (mDnsLogs + castLogs).takeLast(50)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        CastSdkSessionController.initialize(application, AudioCastSessionCoordinator::appendLog)
        if (config.value.mode == AudioCastMode.AAC) startMdnsDiscovery()
    }

    private fun isCaptureLockedInProcess(): Boolean {
        val current = telemetry.value
        return AudioCastSessionCoordinator.isCaptureLocked ||
            current.isCaptureLocked ||
            current.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT
    }

    fun startMdnsDiscovery() {
        discovery.startDiscovery()
    }

    fun stopMdnsDiscovery() {
        discovery.stopDiscovery()
    }

    fun setCastMode(mode: AudioCastMode) {
        if (isCaptureLockedInProcess()) return
        if (mode == config.value.mode || modeChangeJob?.isActive == true || _isRequestingCapture.value) return
        modeChangeJob = viewModelScope.launch {
            if (telemetry.value.isSessionActive) {
                stopAudioCast()
                telemetry.first { !it.isSessionActive }
            }
            if (isCaptureLockedInProcess()) return@launch
            CastSdkSessionController.endSession()
            AudioCastSessionCoordinator.updateConfig { it.copy(mode = mode) }
            AudioCastSessionCoordinator.updateTelemetry { AudioPipelineTelemetry() }
            if (mode == AudioCastMode.AAC) {
                startMdnsDiscovery()
            } else {
                stopMdnsDiscovery()
                CastSdkSessionController.initialize(
                    getApplication<Application>(), AudioCastSessionCoordinator::appendLog
                )
            }
        }
    }

    fun prepareCaptureRequest(): Boolean {
        if (isCaptureLockedInProcess()) {
            return false
        }
        if (_isRequestingCapture.value || telemetry.value.isSessionActive || modeChangeJob?.isActive == true) {
            return false
        }
        if (config.value.mode == AudioCastMode.WEBRTC) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                captureAuthorizationFailed("WebRTC playback capture requires Android 10 or later.")
                return false
            }
            if (!castStatus.value.isConnected) {
                captureAuthorizationFailed(castStatus.value.error ?: "Select a Cast receiver before starting audio.")
                return false
            }
        }
        requestedMode = config.value.mode
        requestedChannel = if (requestedMode == AudioCastMode.WEBRTC) CastSdkSessionController.channel else null
        if (requestedMode == AudioCastMode.WEBRTC && requestedChannel == null) {
            captureAuthorizationFailed("The Cast session ended. Reconnect before requesting capture.")
            return false
        }
        _isRequestingCapture.value = true
        AudioCastSessionCoordinator.updateTelemetry { it.copy(error = null) }
        return true
    }

    fun captureAuthorizationFailed(message: String) {
        val mode = requestedMode ?: config.value.mode
        val channel = requestedChannel
        requestedMode = null
        requestedChannel = null
        _isRequestingCapture.value = false
        if (!isCaptureLockedInProcess()) {
            AudioCastSessionCoordinator.updateTelemetry { it.copy(error = message) }
        }
        AudioCastSessionCoordinator.appendLog(message)
        if (mode == AudioCastMode.WEBRTC) channel?.endSession()
    }

    fun selectDevice(device: CastDeviceModel) {
        _selectedDeviceId.value = device.id
        AudioCastSessionCoordinator.selectedDevice = device
        AudioCastSessionCoordinator.appendLog(
            "Selected target speaker: ${device.friendlyName} (${device.modelName}, ca=${device.capabilityMask}, videoOut=${device.hasVideoOut})"
        )
    }

    fun addManualGoogleHomeDevice(
        friendlyName: String,
        hostAddress: String,
        port: Int,
        modelName: String,
        capabilityMask: Int
    ) {
        val added = discovery.addManualDevice(
            friendlyName = friendlyName,
            hostAddress = hostAddress,
            port = port,
            modelName = modelName,
            capabilityMask = capabilityMask
        )
        selectDevice(added)
    }

    fun setAudioOnlyLowLatencyMode(enabled: Boolean) {
        AudioCastSessionCoordinator.updateConfig {
            it.copy(isAudioOnlyLowLatencyMode = enabled)
        }
    }

    fun setPreBurstBufferMs(preBurstMs: Int) {
        val clamped = preBurstMs.coerceIn(0, 1000)
        AudioCastSessionCoordinator.updateConfig {
            it.copy(preBurstBufferMs = clamped)
        }
    }

    fun setSampleRateHz(sampleRateHz: Int) {
        val validRate = if (sampleRateHz == 44100) 44100 else 48000
        AudioCastSessionCoordinator.updateConfig {
            it.copy(sampleRateHz = validRate)
        }
    }

    fun setBitrateBps(bitrateBps: Int) {
        val validBitrate = if (bitrateBps == 128000) 128000 else 192000
        AudioCastSessionCoordinator.updateConfig {
            it.copy(bitrateBps = validBitrate)
        }
    }

    fun startAudioCastWithProjection(resultCode: Int, resultData: Intent?) {
        if (isCaptureLockedInProcess()) {
            requestedMode = null
            requestedChannel = null
            _isRequestingCapture.value = false
            return
        }
        val mode = requestedMode ?: run {
            captureAuthorizationFailed("Capture authorization request expired. Start again with fresh authorization.")
            return
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            captureAuthorizationFailed("Audio capture authorization was denied.")
            return
        }
        if (mode != config.value.mode ||
            (mode == AudioCastMode.WEBRTC &&
                (requestedChannel?.isConnected != true || CastSdkSessionController.channel !== requestedChannel))
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
        requestedMode = null
        requestedChannel = null
        _isRequestingCapture.value = false
        val serviceIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_START_AUDIO_CAST
            putExtra(AudioCastCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCastCaptureService.EXTRA_MODE, mode.name)
            putExtra(AudioCastCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(isSessionActive = true)
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
        requestedMode = null
        requestedChannel = null
        _isRequestingCapture.value = false
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

    fun previewAdtsHeaderHex(): String {
        val cfg = config.value
        val header = AdtsAacEncoder.createAdtsHeader(
            packetLength = 384 + AdtsAacEncoder.ADTS_HEADER_LENGTH,
            sampleRateIndex = cfg.sampleRateIndex,
            channelCount = 2
        )
        return header.joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
    }

    override fun onCleared() {
        discovery.stopDiscovery()
        super.onCleared()
    }
}
