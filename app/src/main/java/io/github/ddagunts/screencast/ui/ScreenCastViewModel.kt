package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.cast.CastDeviceDiscovery
import io.github.ddagunts.screencast.cast.CastDeviceModel
import io.github.ddagunts.screencast.media.AdtsAacEncoder
import io.github.ddagunts.screencast.media.AudioCastCaptureService
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ScreenCastViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = CastDeviceDiscovery(application)

    val devices: StateFlow<List<CastDeviceModel>> = discovery.devices
    val isScanning: StateFlow<Boolean> = discovery.isScanning
    val config: StateFlow<AudioStreamConfig> = AudioCastSessionCoordinator.config
    val telemetry: StateFlow<AudioPipelineTelemetry> = AudioCastSessionCoordinator.telemetry

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val combinedLogs: StateFlow<List<String>> = combine(
        discovery.discoveryLog,
        AudioCastSessionCoordinator.protocolLogs
    ) { mDnsLogs, castLogs ->
        (mDnsLogs + castLogs).takeLast(50)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        startMdnsDiscovery()
    }

    fun startMdnsDiscovery() {
        discovery.startDiscovery()
    }

    fun stopMdnsDiscovery() {
        discovery.stopDiscovery()
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
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_START_AUDIO_CAST
            putExtra(AudioCastCaptureService.EXTRA_RESULT_CODE, resultCode)
            if (resultData != null) {
                putExtra(AudioCastCaptureService.EXTRA_RESULT_DATA, resultData)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(serviceIntent)
        } else {
            app.startService(serviceIntent)
        }
    }

    fun stopAudioCast() {
        val app = getApplication<Application>()
        val stopIntent = Intent(app, AudioCastCaptureService::class.java).apply {
            action = AudioCastCaptureService.ACTION_STOP_AUDIO_CAST
        }
        app.startService(stopIntent)
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
