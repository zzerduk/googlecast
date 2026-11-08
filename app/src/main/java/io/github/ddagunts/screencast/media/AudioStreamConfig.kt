package io.github.ddagunts.screencast.media

import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.webrtc.WebRtcStage

data class AudioStreamConfig(
    val sampleRateHz: Int = 48000,
    val channels: Int = 2,
    val receiverAppId: String = "9098830C"
)

data class AudioPipelineTelemetry(
    val isStreaming: Boolean = false,
    val totalBytesStreamed: Long = 0L,
    val currentRmsDb: Float = -60f,
    val audioRecordMinBufferBytes: Int = 0,
    val isSessionActive: Boolean = false,
    val webRtcStage: WebRtcStage = WebRtcStage.IDLE,
    val audioPacketsSent: Long = 0,
    val rttMs: Double? = null,
    val pcmChunksRead: Long = 0,
    val pcmUnderruns: Long = 0,
    val pcmDroppedChunks: Long = 0,
    val pcmRingDepth: Int = 0,
    val isCaptureLocked: Boolean = false,
    val activeTargets: Set<CaptureTarget> = emptySet(),
    val activeTargetUids: Set<Int> = emptySet(),
    val activeOutputMode: PhoneOutputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE,
    val isInitialMuteConfirmed: Boolean = false,
    val isPhoneMuted: Boolean = false,
    val hasValidAudioSignal: Boolean = false,
    val isHardwareCaptureActive: Boolean = false,
    val isHardwareCaptureDegraded: Boolean = false,
    val error: String? = null
)
