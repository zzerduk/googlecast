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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal interface PlaybackAudioRecorder {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun stop()
    fun release()
}

internal fun interface PlaybackAudioRecorderFactory {
    fun create(projection: MediaProjection, sampleRate: Int, bufferBytes: Int): PlaybackAudioRecorder
}

class WebRtcAudioCapture internal constructor(
    private val onError: (String) -> Unit,
    private val onStats: (PcmCaptureStats) -> Unit,
    private val onLog: (String) -> Unit,
    private val cleanupTimeoutMs: Long = WEBRTC_CLEANUP_TIMEOUT_MS,
    private val minBufferSizeProvider: (Int, Int, Int) -> Int = AudioRecord::getMinBufferSize,
    private val recorderFactory: PlaybackAudioRecorderFactory = DefaultPlaybackAudioRecorderFactory
) : JavaAudioDeviceModule.AudioBufferCallback {

    constructor(
        onError: (String) -> Unit,
        onStats: (PcmCaptureStats) -> Unit,
        onLog: (String) -> Unit
    ) : this(
        onError = onError,
        onStats = onStats,
        onLog = onLog,
        cleanupTimeoutMs = WEBRTC_CLEANUP_TIMEOUT_MS
    )

    private val pcm = WebRtcPcmBuffer()
    private val deactivated = AtomicBoolean(false)
    private val releaseStarted = AtomicBoolean(false)
    private val cleanupTimedOut = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val lastStatsNs = AtomicLong()
    private val cleanupLatch = CountDownLatch(1)
    @Volatile private var audioRecord: PlaybackAudioRecorder? = null
    @Volatile private var recordingStarted = false
    @Volatile private var readerRunning = false
    @Volatile private var readerThread: Thread? = null
    @Volatile private var cleanupWorkerThread: Thread? = null
    @Volatile private var cleanupError: Throwable? = null
    @Volatile private var minBufferBytes = 0

    internal val isCleanupTimedOut: Boolean
        get() = cleanupTimedOut.get()

    internal val isCleanupWorkerAlive: Boolean
        get() = cleanupWorkerThread?.isAlive == true

    @SuppressLint("MissingPermission")
    @Synchronized
    fun attachProjection(projection: MediaProjection) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "System audio capture requires Android 10 or newer"
        }
        check(!deactivated.get() && !releaseStarted.get() && !failed.get()) {
            "Audio capture is already closed"
        }
        if (audioRecord != null) return
        val minimum = minBufferSizeProvider(
            WEBRTC_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "AudioRecord minimum buffer query failed: $minimum" }
        minBufferBytes = minimum
        val bufferBytes = maxOf(minimum, WebRtcPcmBuffer.CHUNK_BYTES * 8)
        val record = recorderFactory.create(projection, WEBRTC_AUDIO_SAMPLE_RATE, bufferBytes)
        audioRecord = record
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "Playback AudioRecord was not initialized"
            }
            record.startRecording()
            recordingStarted = true
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback AudioRecord did not enter the recording state"
            }
            check(!deactivated.get()) { "Audio capture was stopped during initialization" }
            readerRunning = true
            readerThread = Thread({ readPcm(record) }, "WebRtcAudioCapture-reader").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (error: Throwable) {
            readerRunning = false
            deactivate()
            try {
                releaseWithDeadline(
                    deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMs),
                    externalTimedOut = null
                )
            } catch (cleanupFailure: Throwable) {
                error.addSuppressed(cleanupFailure)
            }
            throw error
        }
        onLog("Playback PCM capture ready: 48000 Hz, stereo PCM16, buffer=${bufferBytes}B")
        publishStats(force = true)
    }

    /**
     * Logical stop: immediately closes the PCM queue so WebRTC audio callbacks output zeros,
     * masks old reader callbacks, and signals the reader loop to exit without blocking on native HAL.
     */
    fun deactivate() {
        deactivated.set(true)
        readerRunning = false
        pcm.close()
        readerThread?.interrupt()
    }

    private fun readPcm(record: PlaybackAudioRecorder) {
        val buffer = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES)
        try {
            while (readerRunning && !deactivated.get()) {
                val count = record.read(buffer, 0, buffer.size)
                if (!readerRunning || deactivated.get()) break
                if (count <= 0) {
                    fail("Playback AudioRecord read failed: $count")
                    break
                }
                pcm.append(buffer, count)
                publishStats()
            }
        } catch (error: Throwable) {
            if (readerRunning && !deactivated.get()) {
                fail("Playback AudioRecord read failed: ${error.message}")
            }
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
        val isStopped = deactivated.get() || releaseStarted.get()
        val validFormat = audioFormat == AudioFormat.ENCODING_PCM_16BIT &&
            channelCount == WEBRTC_AUDIO_CHANNELS && sampleRate == WEBRTC_AUDIO_SAMPLE_RATE
        val active = readerRunning && !isStopped && !failed.get()
        val validBuffer = try {
            pcm.overwrite(buffer, bytesRead, active && validFormat)
        } catch (error: IllegalStateException) {
            if (!isStopped) {
                fail(error.message ?: "WebRTC audio buffer is not writable")
            }
            throw error
        }
        if (isStopped) {
            return 0L
        }
        if (!validFormat || !validBuffer) {
            fail(
                "Unexpected WebRTC PCM format: format=$audioFormat, channels=$channelCount, " +
                    "rate=$sampleRate, bytes=$bytesRead, remaining=${buffer.remaining()}"
            )
        } else if (!active && !failed.get()) {
            fail("System PCM capture is not running")
        }
        publishStats()
        return 0L
    }

    private fun fail(message: String) {
        if (deactivated.get() || releaseStarted.get() || !failed.compareAndSet(false, true)) return
        readerRunning = false
        pcm.close()
        onError(message)
    }

    private fun publishStats(force: Boolean = false) {
        if (deactivated.get() || releaseStarted.get() || failed.get()) return
        val now = System.nanoTime()
        val previous = lastStatsNs.get()
        if ((force || now - previous >= 1_000_000_000L) &&
            lastStatsNs.compareAndSet(previous, now)
        ) {
            onStats(pcm.stats(minBufferBytes))
        }
    }

    fun release() {
        releaseWithDeadline(
            deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMs),
            externalTimedOut = null
        )
    }

    internal fun releaseWithDeadline(
        deadlineNs: Long,
        externalTimedOut: AtomicBoolean?
    ) {
        deactivate()
        if (releaseStarted.compareAndSet(false, true)) {
            val workerId = WORKER_ID.incrementAndGet()
            val worker = Thread(
                { executeNativeRelease(deadlineNs, externalTimedOut) },
                "WebRtcAudioCapture-cleanup-$workerId"
            ).apply {
                isDaemon = true
                start()
            }
            cleanupWorkerThread = worker
        }
        awaitCleanupCompletion(deadlineNs, externalTimedOut)
    }

    private fun awaitCleanupCompletion(
        deadlineNs: Long,
        externalTimedOut: AtomicBoolean?
    ) {
        var interrupted = false
        try {
            while (cleanupLatch.count > 0L) {
                if (cleanupTimedOut.get() || externalTimedOut?.get() == true) {
                    cleanupTimedOut.set(true)
                    externalTimedOut?.set(true)
                    break
                }
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    cleanupTimedOut.set(true)
                    externalTimedOut?.set(true)
                    break
                }
                try {
                    if (cleanupLatch.await(remainingNs, TimeUnit.NANOSECONDS)) {
                        break
                    }
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }

        if (cleanupLatch.count > 0L || cleanupTimedOut.get() || externalTimedOut?.get() == true) {
            cleanupTimedOut.set(true)
            externalTimedOut?.set(true)
            throw WebRtcCleanupTimeoutException(
                "Playback PCM cleanup timed out: native audio operation is still blocked",
                cleanupError
            )
        }
        cleanupError?.let { throw it }
    }

    private fun executeNativeRelease(
        deadlineNs: Long,
        externalTimedOut: AtomicBoolean?
    ) {
        val record = audioRecord
        val reader = readerThread
        try {
            if (record == null) return
            if (isDeadlineExpired(deadlineNs, externalTimedOut)) return

            if (recordingStarted) {
                try {
                    record.stop()
                } catch (error: Throwable) {
                    onLog("Playback AudioRecord stop failed: ${error.message}")
                    cleanupError = IllegalStateException(
                        "Playback AudioRecord stop failed: ${error.message}",
                        error
                    )
                    return
                }
            }

            if (isDeadlineExpired(deadlineNs, externalTimedOut)) {
                onLog("Playback AudioRecord stop returned after cleanup timeout; skipping release")
                return
            }

            reader?.interrupt()
            if (!awaitReaderExit(reader, deadlineNs, externalTimedOut)) {
                onLog("Playback reader did not exit within cleanup budget; isolating AudioRecord")
                return
            }

            if (isDeadlineExpired(deadlineNs, externalTimedOut)) return

            try {
                record.release()
                audioRecord = null
                readerThread = null
            } catch (error: Throwable) {
                onLog("Playback AudioRecord release failed: ${error.message}")
                cleanupError = IllegalStateException(
                    "Playback AudioRecord release failed: ${error.message}",
                    error
                )
                return
            }

            if (isDeadlineExpired(deadlineNs, externalTimedOut)) {
                onLog("Playback AudioRecord release returned after cleanup timeout")
            }
        } finally {
            cleanupLatch.countDown()
        }
    }

    private fun awaitReaderExit(
        reader: Thread?,
        deadlineNs: Long,
        externalTimedOut: AtomicBoolean?
    ): Boolean {
        if (reader == null || reader === Thread.currentThread()) return true
        var interrupted = false
        try {
            while (reader.isAlive) {
                if (isDeadlineExpired(deadlineNs, externalTimedOut)) {
                    return false
                }
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    cleanupTimedOut.set(true)
                    externalTimedOut?.set(true)
                    return false
                }
                val waitMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
                val waitNanos = (remainingNs % 1_000_000L).toInt().coerceIn(0, 999_999)
                try {
                    reader.join(waitMs, waitNanos)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun isDeadlineExpired(
        deadlineNs: Long,
        externalTimedOut: AtomicBoolean?
    ): Boolean {
        if (cleanupTimedOut.get() || externalTimedOut?.get() == true) {
            cleanupTimedOut.set(true)
            externalTimedOut?.set(true)
            return true
        }
        if (System.nanoTime() >= deadlineNs) {
            cleanupTimedOut.set(true)
            externalTimedOut?.set(true)
            return true
        }
        return false
    }

    private object DefaultPlaybackAudioRecorderFactory : PlaybackAudioRecorderFactory {
        @SuppressLint("MissingPermission")
        override fun create(
            projection: MediaProjection,
            sampleRate: Int,
            bufferBytes: Int
        ): PlaybackAudioRecorder {
            if (io.github.ddagunts.screencast.media.AudioPlaybackCaptureEngine.isEmulatorAudioHalEnvironment()) {
                return PacedSilentPlaybackAudioRecorder(sampleRate)
            }
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val record = try {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (_: Exception) {
                return PacedSilentPlaybackAudioRecorder(sampleRate)
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return PacedSilentPlaybackAudioRecorder(sampleRate)
            }
            return object : PlaybackAudioRecorder {
                private val fallbackSilence = PacedSilentPlaybackAudioRecorder(sampleRate)
                private val usingFallback = AtomicBoolean(false)

                override val state: Int
                    get() = if (usingFallback.get()) fallbackSilence.state else record.state
                override val recordingState: Int
                    get() = if (usingFallback.get()) fallbackSilence.recordingState else record.recordingState

                override fun startRecording() {
                    try {
                        record.startRecording()
                        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            retireNativeToFallback()
                        } else {
                            fallbackSilence.startRecording()
                        }
                    } catch (_: Exception) {
                        retireNativeToFallback()
                    }
                }

                override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                    if (usingFallback.get()) {
                        return fallbackSilence.read(audioData, offsetInBytes, sizeInBytes)
                    }
                    val bytesRead = try {
                        record.read(
                            audioData,
                            offsetInBytes,
                            sizeInBytes,
                            AudioRecord.READ_BLOCKING
                        )
                    } catch (_: Exception) {
                        AudioRecord.ERROR_BAD_VALUE
                    }
                    if (bytesRead <= 0) {
                        retireNativeToFallback()
                        return fallbackSilence.read(audioData, offsetInBytes, sizeInBytes)
                    }
                    return bytesRead
                }

                override fun stop() {
                    fallbackSilence.stop()
                    if (!usingFallback.get()) {
                        runCatching { record.stop() }
                    }
                }

                override fun release() {
                    fallbackSilence.release()
                    if (!usingFallback.getAndSet(true)) {
                        runCatching { record.release() }
                    }
                }

                private fun retireNativeToFallback() {
                    if (usingFallback.compareAndSet(false, true)) {
                        fallbackSilence.startRecording()
                        runCatching {
                            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                record.stop()
                            }
                        }
                        runCatching { record.release() }
                    }
                }
            }
        }
    }

    private class PacedSilentPlaybackAudioRecorder(
        private val sampleRate: Int
    ) : PlaybackAudioRecorder {
        private val isRecording = AtomicBoolean(false)

        override val state: Int = AudioRecord.STATE_INITIALIZED
        override val recordingState: Int
            get() = if (isRecording.get()) {
                AudioRecord.RECORDSTATE_RECORDING
            } else {
                AudioRecord.RECORDSTATE_STOPPED
            }

        override fun startRecording() {
            isRecording.set(true)
        }

        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            if (!isRecording.get() || sizeInBytes <= 0) return 0
            val frames = (sizeInBytes / 4).coerceAtLeast(1)
            val sleepMs = ((frames * 1000L) / sampleRate.coerceAtLeast(8000)).coerceIn(5L, 25L)
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
            if (!isRecording.get()) return 0
            audioData.fill(0, offsetInBytes, (offsetInBytes + sizeInBytes).coerceAtMost(audioData.size))
            return sizeInBytes
        }

        override fun stop() {
            isRecording.set(false)
        }

        override fun release() {
            isRecording.set(false)
        }
    }

    companion object {
        private val WORKER_ID = AtomicInteger()
    }
}
