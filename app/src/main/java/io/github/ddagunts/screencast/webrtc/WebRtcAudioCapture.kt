package io.github.ddagunts.screencast.webrtc

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import io.github.ddagunts.screencast.media.PlaybackCaptureConfigBuilder
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
    fun create(
        projection: MediaProjection,
        sampleRate: Int,
        bufferBytes: Int,
        targetUids: Set<Int>
    ): PlaybackAudioRecorder

    companion object {
        fun fromLegacy(
            legacy: (MediaProjection, Int, Int) -> PlaybackAudioRecorder
        ): PlaybackAudioRecorderFactory = PlaybackAudioRecorderFactory { projection, sampleRate, bufferBytes, _ ->
            legacy(projection, sampleRate, bufferBytes)
        }
    }
}

class WebRtcAudioCapture internal constructor(
    private val onError: (String) -> Unit,
    private val onStats: (PcmCaptureStats) -> Unit,
    private val onLog: (String) -> Unit,
    private val cleanupTimeoutMs: Long = WEBRTC_CLEANUP_TIMEOUT_MS,
    private val minBufferSizeProvider: (Int, Int, Int) -> Int = AudioRecord::getMinBufferSize,
    private val recorderFactory: PlaybackAudioRecorderFactory = DefaultPlaybackAudioRecorderFactory
) : JavaAudioDeviceModule.AudioBufferCallback {

    internal constructor(
        onError: (String) -> Unit,
        onStats: (PcmCaptureStats) -> Unit,
        onLog: (String) -> Unit,
        cleanupTimeoutMs: Long = WEBRTC_CLEANUP_TIMEOUT_MS,
        minBufferSizeProvider: (Int, Int, Int) -> Int = AudioRecord::getMinBufferSize,
        recorderFactory: (MediaProjection, Int, Int) -> PlaybackAudioRecorder
    ) : this(
        onError = onError,
        onStats = onStats,
        onLog = onLog,
        cleanupTimeoutMs = cleanupTimeoutMs,
        minBufferSizeProvider = minBufferSizeProvider,
        recorderFactory = PlaybackAudioRecorderFactory.fromLegacy(recorderFactory)
    )

    constructor(
        onError: (String) -> Unit,
        onStats: (PcmCaptureStats) -> Unit,
        onLog: (String) -> Unit
    ) : this(
        onError = onError,
        onStats = onStats,
        onLog = onLog,
        cleanupTimeoutMs = WEBRTC_CLEANUP_TIMEOUT_MS,
        minBufferSizeProvider = AudioRecord::getMinBufferSize,
        recorderFactory = DefaultPlaybackAudioRecorderFactory
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
    @Volatile var activeTargetUids: Set<Int> = emptySet()
        private set

    internal val isCleanupTimedOut: Boolean
        get() = cleanupTimedOut.get()

    internal val isCleanupWorkerAlive: Boolean
        get() = cleanupWorkerThread?.isAlive == true

    @SuppressLint("MissingPermission")
    @Synchronized
    fun attachProjection(projection: MediaProjection, targetUids: Set<Int> = emptySet()) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "System audio capture requires Android 10 or newer"
        }
        check(!deactivated.get() && !releaseStarted.get() && !failed.get()) {
            "Audio capture is already closed"
        }
        if (audioRecord != null) return
        activeTargetUids = targetUids
        val minimum = minBufferSizeProvider(
            WEBRTC_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "AudioRecord minimum buffer query failed: $minimum" }
        minBufferBytes = minimum
        val bufferBytes = maxOf(minimum, WebRtcPcmBuffer.CHUNK_BYTES * 8)
        val record = recorderFactory.create(
            projection = projection,
            sampleRate = WEBRTC_AUDIO_SAMPLE_RATE,
            bufferBytes = bufferBytes,
            targetUids = targetUids
        )
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
        onLog(
            "Playback PCM capture ready: 48000 Hz, stereo PCM16, buffer=${bufferBytes}B, " +
                "matchingUids=${targetUids.sorted()}"
        )
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

    /**
     * Hardware recorder factory that uses [PlaybackCaptureConfigBuilder] to apply
     * `addMatchingUid` for every target UID alongside `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`.
     *
     * Never substitutes a silent fallback recorder when hardware capture fails—failures surface
     * immediately so hardware capture failure or pure silence never masquerades as valid capture.
     */
    internal object DefaultPlaybackAudioRecorderFactory : PlaybackAudioRecorderFactory {
        @SuppressLint("MissingPermission")
        override fun create(
            projection: MediaProjection,
            sampleRate: Int,
            bufferBytes: Int,
            targetUids: Set<Int>
        ): PlaybackAudioRecorder {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "AudioPlaybackCapture requires Android 10+"
            }
            check(targetUids.isNotEmpty()) {
                "Target UID whitelist must not be empty when creating WebRTC PlaybackAudioRecorder"
            }
            val captureConfig = PlaybackCaptureConfigBuilder.build(
                projection = projection,
                targetUids = targetUids
            )
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()

            return object : PlaybackAudioRecorder {
                override val state: Int
                    get() = record.state
                override val recordingState: Int
                    get() = record.recordingState

                override fun startRecording() {
                    record.startRecording()
                }

                override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
                    return record.read(
                        audioData,
                        offsetInBytes,
                        sizeInBytes,
                        AudioRecord.READ_BLOCKING
                    )
                }

                override fun stop() {
                    record.stop()
                }

                override fun release() {
                    record.release()
                }
            }
        }
    }

    companion object {
        private val WORKER_ID = AtomicInteger()
    }
}
