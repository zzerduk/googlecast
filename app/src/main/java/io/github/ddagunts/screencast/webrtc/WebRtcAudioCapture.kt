package io.github.ddagunts.screencast.webrtc

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WebRtcAudioCapture(
    private val onError: (String) -> Unit,
    private val onStats: (PcmCaptureStats) -> Unit,
    private val onLog: (String) -> Unit
) : JavaAudioDeviceModule.AudioBufferCallback {

    private val pcm = WebRtcPcmBuffer()
    private val released = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val lastStatsNs = AtomicLong()
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var readerRunning = false
    private var readerThread: Thread? = null
    @Volatile private var minBufferBytes = 0

    @SuppressLint("MissingPermission")
    @Synchronized
    fun attachProjection(projection: MediaProjection) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "System audio capture requires Android 10 or newer"
        }
        check(!released.get() && !failed.get()) { "Audio capture is already closed" }
        if (audioRecord != null) return
        val minimum = AudioRecord.getMinBufferSize(
            WEBRTC_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "AudioRecord minimum buffer query failed: $minimum" }
        minBufferBytes = minimum
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WEBRTC_AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val bufferBytes = maxOf(minimum, WebRtcPcmBuffer.CHUNK_BYTES * 8)
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "Playback AudioRecord was not initialized"
            }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback AudioRecord did not enter the recording state"
            }
            audioRecord = record
            readerRunning = true
            readerThread = Thread({ readPcm(record) }, "WebRtcAudioCapture-reader").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (error: Exception) {
            readerRunning = false
            audioRecord = null
            runCatching { record.stop() }
            record.release()
            throw error
        }
        onLog("Playback PCM capture ready: 48000 Hz, stereo PCM16, buffer=${bufferBytes}B")
        publishStats(force = true)
    }

    private fun readPcm(record: AudioRecord) {
        val buffer = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES)
        try {
            while (readerRunning) {
                val count = record.read(
                    buffer, 0, buffer.size, AudioRecord.READ_BLOCKING
                )
                if (!readerRunning) break
                if (count <= 0) {
                    fail("Playback AudioRecord read failed: $count")
                    break
                }
                pcm.append(buffer, count)
                publishStats()
            }
        } catch (error: Exception) {
            if (readerRunning) fail("Playback AudioRecord read failed: ${error.message}")
        } finally {
            readerRunning = false
        }
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimestampNs: Long
    ): Long {
        val validFormat = audioFormat == AudioFormat.ENCODING_PCM_16BIT &&
            channelCount == WEBRTC_AUDIO_CHANNELS && sampleRate == WEBRTC_AUDIO_SAMPLE_RATE
        val active = readerRunning && !released.get() && !failed.get()
        val validBuffer = try {
            pcm.overwrite(buffer, bytesRead, active && validFormat)
        } catch (error: IllegalStateException) {
            fail(error.message ?: "WebRTC audio buffer is not writable")
            throw error
        }
        if (!validFormat || !validBuffer) {
            fail(
                "Unexpected WebRTC PCM format: format=$audioFormat, channels=$channelCount, " +
                    "rate=$sampleRate, bytes=$bytesRead, remaining=${buffer.remaining()}"
            )
        } else if (!active && !released.get() && !failed.get()) {
            fail("System PCM capture is not running")
        }
        publishStats()
        return 0L
    }

    private fun fail(message: String) {
        if (released.get() || !failed.compareAndSet(false, true)) return
        readerRunning = false
        pcm.close()
        onError(message)
    }

    private fun publishStats(force: Boolean = false) {
        if (released.get()) return
        val now = System.nanoTime()
        val previous = lastStatsNs.get()
        if ((force || now - previous >= 1_000_000_000L) &&
            lastStatsNs.compareAndSet(previous, now)
        ) {
            onStats(pcm.stats(minBufferBytes))
        }
    }

    // Call off main: a stuck READ_BLOCKING gets one release fallback, never an unbounded join.
    @Synchronized
    fun release() {
        if (!released.compareAndSet(false, true)) return
        readerRunning = false
        pcm.close()
        val record = audioRecord
        val reader = readerThread
        var interrupted = false
        fun awaitReader(): Boolean {
            if (reader == null || reader === Thread.currentThread()) return true
            val deadline = System.nanoTime() + 1_000_000_000L
            while (reader.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    reader.join(maxOf(1L, remaining / 1_000_000L))
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        }
        try {
            runCatching { record?.stop() }.onFailure {
                onLog("Playback AudioRecord stop failed: ${it.message}")
            }
            reader?.interrupt()
            if (awaitReader()) {
                record?.release()
            } else {
                onLog("Playback reader did not stop; releasing AudioRecord to unblock it")
                val releaseFailure = runCatching { record?.release() }.exceptionOrNull()
                if (!awaitReader()) {
                    throw IllegalStateException(
                        "Playback PCM cleanup failed: native reader is still running",
                        releaseFailure
                    )
                }
                if (releaseFailure != null) throw releaseFailure
            }
        } finally {
            audioRecord = null
            readerThread = null
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
