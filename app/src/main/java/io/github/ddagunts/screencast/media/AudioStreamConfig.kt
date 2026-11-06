package io.github.ddagunts.screencast.media

import io.github.ddagunts.screencast.webrtc.WebRtcStage

enum class AudioCastMode {
    WEBRTC, AAC
}

// AAC tuning is retained independently of WebRTC's fixed PCM format.
data class AudioStreamConfig(
    val isAudioOnlyLowLatencyMode: Boolean = true,
    val sampleRateHz: Int = 48000,
    val bitrateBps: Int = 192000,
    val preBurstBufferMs: Int = 300,
    val silenceInjectorTickerMs: Long = 15L,
    // Legacy video/HLS fields retained only as disabled inspection state when Audio-Only mode is active
    val legacyVideoResolution: String = "1280x720 (Disabled — Headless Speaker)",
    val legacyH264BitrateKbps: Int = 0,
    val legacyHlsSegmentDurationSec: Int = 0,
    val legacyHlsPlaylistWindow: Int = 0,
    val mode: AudioCastMode = AudioCastMode.WEBRTC
) {
    val sampleRateIndex: Int
        get() = if (sampleRateHz == 44100) 4 else 3 // 3 = 48000Hz, 4 = 44100Hz

    /**
     * Each AAC-LC frame represents 1024 PCM samples per channel (~21.33ms at 48kHz, ~23.22ms at 44.1kHz).
     */
    val singleAacFrameDurationMs: Double
        get() = (1024.0 * 1000.0) / sampleRateHz.toDouble()

    /**
     * Number of pre-encoded silent ADTS AAC frames to flush in a single TCP burst upon connection.
     */
    val preBurstFrameCount: Int
        get() = ((preBurstBufferMs.coerceIn(0, 1000)) / singleAacFrameDurationMs).toInt().coerceAtLeast(0)

    /**
     * Estimated end-to-end audio latency in milliseconds based on capture buffer + pre-burst + network jitter.
     */
    val estimatedEndToEndLatencyMs: Int
        get() = 140 + (preBurstBufferMs.coerceIn(0, 1000) * 0.85).toInt()
}

data class AudioPipelineTelemetry(
    val isStreaming: Boolean = false,
    val streamUrl: String = "",
    val activeServerPort: Int = 0,
    val connectedSpeakerClients: Int = 0,
    val adtsFramesEmitted: Long = 0L,
    val totalBytesStreamed: Long = 0L,
    val silenceFramesInjected: Long = 0L,
    val isInjectingSilenceNow: Boolean = false,
    val preBurstFramesFlushedLastClient: Int = 0,
    val currentRmsDb: Float = -60f,
    val audioRecordMinBufferBytes: Int = 0,
    val videoPipelineAllocated: Boolean = false,
    val isSessionActive: Boolean = false,
    val webRtcStage: WebRtcStage = WebRtcStage.IDLE,
    val audioPacketsSent: Long = 0,
    val rttMs: Double? = null,
    val pcmChunksRead: Long = 0,
    val pcmUnderruns: Long = 0,
    val pcmDroppedChunks: Long = 0,
    val pcmRingDepth: Int = 0,
    val error: String? = null
)
