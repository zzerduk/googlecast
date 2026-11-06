package io.github.ddagunts.screencast.webrtc

const val WEBRTC_RECEIVER_APP_ID = "9098830C"
const val WEBRTC_NAMESPACE = "urn:x-cast:io.github.ddagunts.screencast.webrtc"
const val WEBRTC_AUDIO_SAMPLE_RATE = 48_000
const val WEBRTC_AUDIO_CHANNELS = 2

enum class WebRtcStage {
    IDLE, CAPTURING, NEGOTIATING, CONNECTED, SENDING, STOPPING, ERROR
}

data class WebRtcStats(
    val audioBytesSent: Long = 0,
    val audioPacketsSent: Long = 0,
    val rttMs: Double? = null
)

data class PcmCaptureStats(
    val chunksRead: Long = 0,
    val underruns: Long = 0,
    val droppedChunks: Long = 0,
    val ringDepth: Int = 0,
    val minBufferBytes: Int = 0,
    val rmsDb: Float = -60f
)
