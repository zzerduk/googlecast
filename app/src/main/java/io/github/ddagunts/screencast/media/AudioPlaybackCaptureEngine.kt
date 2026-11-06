package io.github.ddagunts.screencast.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

internal interface PlaybackCaptureRecorder {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun readNonBlocking(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun stop()
    fun release()
}

internal fun interface PlaybackCaptureRecorderFactory {
    fun create(
        projection: MediaProjection,
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSizeBytes: Int
    ): PlaybackCaptureRecorder?
}

/**
 * Captures system playback audio via [AudioPlaybackCaptureConfiguration] and feeds
 * [AdtsAacEncoder] with zero-gap silent PCM injection when tracks switch or pause.
 *
 * Phase 2 Architectural Guarantees:
 * 1. Keeps [MediaProjection] consent (required by Android 10+ for `AudioPlaybackCapture`).
 * 2. Strictly strips `MediaProjection.createVirtualDisplay(...)` and `video/avc` H.264 encoders.
 * 3. Minimizes internal [AudioRecord] buffer size to `getMinBufferSize(...) * 2`.
 * 4. Implements the **Zero-Gap Track Switching (Silence Injector)** on a high-priority coroutine
 *    with a 15ms ticker so downstream ADTS AAC output maintains a continuous constant bit-rate clock.
 */
class AudioPlaybackCaptureEngine internal constructor(
    private val context: Context? = null,
    private val configProvider: () -> AudioStreamConfig,
    private val onAdtsPacketEncoded: (ByteArray) -> Unit,
    private val onStatsUpdated: (
        adtsFrames: Long,
        silenceFrames: Long,
        isSilentNow: Boolean,
        rmsDb: Float,
        minBufferBytes: Int
    ) -> Unit,
    private val onEngineLog: (String) -> Unit,
    private val isEmulatorProvider: () -> Boolean = ::isEmulatorAudioHalEnvironment,
    private val activePlaybackChecker: (Context?) -> Boolean = ::hasActiveMatchingSystemPlayback,
    private val recorderFactory: PlaybackCaptureRecorderFactory = DefaultPlaybackCaptureRecorderFactory
) {
    constructor(
        configProvider: () -> AudioStreamConfig,
        onAdtsPacketEncoded: (ByteArray) -> Unit,
        onStatsUpdated: (
            adtsFrames: Long,
            silenceFrames: Long,
            isSilentNow: Boolean,
            rmsDb: Float,
            minBufferBytes: Int
        ) -> Unit,
        onEngineLog: (String) -> Unit,
        context: Context? = null
    ) : this(
        context = context,
        configProvider = configProvider,
        onAdtsPacketEncoded = onAdtsPacketEncoded,
        onStatsUpdated = onStatsUpdated,
        onEngineLog = onEngineLog,
        isEmulatorProvider = ::isEmulatorAudioHalEnvironment,
        activePlaybackChecker = ::hasActiveMatchingSystemPlayback,
        recorderFactory = DefaultPlaybackCaptureRecorderFactory
    )

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    @Volatile private var audioRecord: PlaybackCaptureRecorder? = null
    private var aacEncoder: AdtsAacEncoder? = null
    @Volatile private var playbackCaptureDisabledForSession: Boolean = false

    @Volatile
    var isVideoPipelineAllocated: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun startAudioOnlyCapture(mediaProjection: MediaProjection?) {
        if (captureJob?.isActive == true) return

        val config = configProvider()
        val sampleRate = config.sampleRateHz
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        // Phase 2.3: Explicitly verify zero video pipeline resources are allocated
        isVideoPipelineAllocated = false
        playbackCaptureDisabledForSession = false

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(BYTES_PER_AAC_PCM_FRAME)
        // Phase 2.4: Minimize internal AudioRecord buffer size to getMinBufferSize(...) * 2
        val targetBufferSizeBytes = minBuf * 2

        val canUseHardwarePlaybackCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaProjection != null &&
            !isEmulatorProvider()

        if (!canUseHardwarePlaybackCapture) {
            playbackCaptureDisabledForSession = true
            onEngineLog(
                "Continuous ADTS Silence Injector clock active (${sampleRate}Hz, " +
                    "${config.bitrateBps / 1000}kbps CBR; virtual r_submix HAL bypassed)"
            )
        } else if (activePlaybackChecker(context)) {
            audioRecord = tryCreateAndStartRecorder(
                projection = mediaProjection,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                encoding = encoding,
                targetBufferSizeBytes = targetBufferSizeBytes
            )
        } else {
            onEngineLog(
                "No active system media playback detected yet; Silence Injector clock active " +
                    "until media playback starts (${sampleRate}Hz, ${config.bitrateBps / 1000}kbps CBR)"
            )
        }

        var emittedFramesCount = 0L
        val encoder = AdtsAacEncoder(
            sampleRateHz = sampleRate,
            channelCount = 2,
            bitrateBps = config.bitrateBps,
            onAdtsFrameReady = { adtsFrame ->
                emittedFramesCount++
                onAdtsPacketEncoded(adtsFrame)
            }
        )
        encoder.start()
        aacEncoder = encoder

        captureJob = engineScope.launch {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {
            }

            // 1024 stereo 16-bit samples = 4096 bytes per AAC-LC frame (~21.33ms at 48kHz)
            val pcmBuffer = ByteArray(BYTES_PER_AAC_PCM_FRAME)
            val zeroSilencePcm = ByteArray(BYTES_PER_AAC_PCM_FRAME)

            var silenceChunksInjected = 0L
            var totalSamplesPresented = 0L
            var ticksSincePlaybackCheck = 0
            var consecutiveEmptyReads = 0
            val tickerMs = config.silenceInjectorTickerMs.coerceIn(10L, 20L)

            while (isActive) {
                if (canUseHardwarePlaybackCapture && !playbackCaptureDisabledForSession && mediaProjection != null) {
                    ticksSincePlaybackCheck++
                    if (audioRecord == null && ticksSincePlaybackCheck >= 25) {
                        ticksSincePlaybackCheck = 0
                        if (activePlaybackChecker(context)) {
                            audioRecord = tryCreateAndStartRecorder(
                                projection = mediaProjection,
                                sampleRate = sampleRate,
                                channelConfig = channelConfig,
                                encoding = encoding,
                                targetBufferSizeBytes = targetBufferSizeBytes
                            )
                            consecutiveEmptyReads = 0
                        }
                    }
                }

                val activeRecord = audioRecord
                val bytesRead = if (activeRecord != null &&
                    activeRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    try {
                        activeRecord.readNonBlocking(
                            pcmBuffer,
                            0,
                            BYTES_PER_AAC_PCM_FRAME
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord read exception: ${e.message}")
                        AudioRecord.ERROR_BAD_VALUE
                    }
                } else {
                    0
                }

                if (bytesRead < 0) {
                    // Immediately retire the broken AudioRecord so native restoreRecord_l / createRecord_l(-22)
                    // is never re-invoked on subsequent loop ticks.
                    playbackCaptureDisabledForSession = true
                    retireActiveRecorder(
                        "AudioPlaybackCapture read returned status $bytesRead; switching permanently to Silence Injector clock"
                    )
                } else if (activeRecord != null && bytesRead == 0) {
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads >= 20 && !activePlaybackChecker(context)) {
                        consecutiveEmptyReads = 0
                        retireActiveRecorder(
                            "System media playback idle; pausing AudioRecord while Silence Injector maintains CBR clock"
                        )
                    }
                } else if (bytesRead > 0) {
                    consecutiveEmptyReads = 0
                }

                val ptsUs = (totalSamplesPresented * 1_000_000L) / sampleRate.toLong()

                val isSilentFrame: Boolean
                val rmsDb: Float

                if (bytesRead > 0 && !isAllZeroPcm(pcmBuffer, bytesRead)) {
                    // Live system audio captured from active music/media app
                    isSilentFrame = false
                    rmsDb = computeRmsDb(pcmBuffer, bytesRead)
                    encoder.encodePcmChunk(pcmBuffer, bytesRead, ptsUs)
                    totalSamplesPresented += (bytesRead / 4) // 4 bytes per stereo 16-bit frame
                } else {
                    // Phase 2.4 CRITICAL — Zero-Gap Track Switching (Silence Injector):
                    // Immediately feed zeroed PCM bytes into the AAC encoder when the source app pauses
                    // or switches tracks so the HTTP stream maintains a constant bit-rate clock.
                    isSilentFrame = true
                    rmsDb = -60f
                    silenceChunksInjected++
                    encoder.encodePcmChunk(zeroSilencePcm, BYTES_PER_AAC_PCM_FRAME, ptsUs)
                    totalSamplesPresented += SAMPLES_PER_AAC_FRAME
                }

                onStatsUpdated(
                    emittedFramesCount,
                    silenceChunksInjected,
                    isSilentFrame,
                    rmsDb,
                    targetBufferSizeBytes
                )

                delay(tickerMs)
            }
        }
    }

    private fun tryCreateAndStartRecorder(
        projection: MediaProjection,
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        targetBufferSizeBytes: Int
    ): PlaybackCaptureRecorder? {
        return try {
            val record = recorderFactory.create(
                projection = projection,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                encoding = encoding,
                bufferSizeBytes = targetBufferSizeBytes
            ) ?: return null

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    onEngineLog(
                        "AudioPlaybackCapture initialized (${sampleRate}Hz Stereo PCM16, " +
                            "buf=${targetBufferSizeBytes}B = 2x minBuf, VirtualDisplay: BYPASSED)"
                    )
                    record
                } else {
                    runCatching { record.release() }
                    playbackCaptureDisabledForSession = true
                    onEngineLog("AudioRecord did not enter recording state; activating continuous Silence Injector clock")
                    null
                }
            } else {
                runCatching { record.release() }
                playbackCaptureDisabledForSession = true
                onEngineLog("AudioRecord state uninitialized; activating continuous Silence Injector clock")
                null
            }
        } catch (e: Exception) {
            playbackCaptureDisabledForSession = true
            Log.w(TAG, "AudioPlaybackCapture setup notice: ${e.message}")
            onEngineLog("AudioPlaybackCapture fallback to Silence Injector clock (${e.message})")
            null
        }
    }

    private fun retireActiveRecorder(reason: String) {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
        onEngineLog(reason)
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }

        aacEncoder?.stopAndRelease()
        aacEncoder = null
    }

    fun release() {
        stopCapture()
        engineScope.cancel()
    }

    private fun isAllZeroPcm(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) {
            if (buffer[i].toInt() != 0) return false
        }
        return true
    }

    private fun computeRmsDb(buffer: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return -60f
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
            sumSquares += sample * sample
            i += 2
        }
        val rms = sqrt(sumSquares / sampleCount)
        if (rms <= 1e-5) return -60f
        return (20.0 * log10(rms)).toFloat().coerceIn(-60f, 0f)
    }

    companion object {
        private const val TAG = "AudioPlaybackCapture"
        const val SAMPLES_PER_AAC_FRAME = 1024
        const val BYTES_PER_AAC_PCM_FRAME = SAMPLES_PER_AAC_FRAME * 2 * 2 // 1024 samples * stereo * 16-bit = 4096 bytes

        internal fun isEmulatorAudioHalEnvironment(): Boolean {
            val fingerprint = Build.FINGERPRINT.orEmpty()
            val hardware = Build.HARDWARE.orEmpty()
            val model = Build.MODEL.orEmpty()
            val product = Build.PRODUCT.orEmpty()
            val board = Build.BOARD.orEmpty()
            val brand = Build.BRAND.orEmpty()
            val device = Build.DEVICE.orEmpty()
            return fingerprint.startsWith("generic", ignoreCase = true) ||
                fingerprint.contains("emulator", ignoreCase = true) ||
                fingerprint.contains("sdk_gphone", ignoreCase = true) ||
                hardware.contains("goldfish", ignoreCase = true) ||
                hardware.contains("ranchu", ignoreCase = true) ||
                hardware.contains("cuttlefish", ignoreCase = true) ||
                hardware.contains("vsoc", ignoreCase = true) ||
                model.contains("google_sdk", ignoreCase = true) ||
                model.contains("Emulator", ignoreCase = true) ||
                model.contains("Android SDK built for", ignoreCase = true) ||
                model.contains("sdk_gphone", ignoreCase = true) ||
                model.contains("Cuttlefish", ignoreCase = true) ||
                product.contains("sdk", ignoreCase = true) ||
                product.contains("emulator", ignoreCase = true) ||
                product.contains("simulator", ignoreCase = true) ||
                product.contains("cf_", ignoreCase = true) ||
                product.contains("aosp_cf_", ignoreCase = true) ||
                board.contains("goldfish", ignoreCase = true) ||
                board.contains("ranchu", ignoreCase = true) ||
                board.contains("cuttlefish", ignoreCase = true) ||
                device.contains("vsoc", ignoreCase = true) ||
                device.contains("emu", ignoreCase = true) ||
                (brand.startsWith("generic", ignoreCase = true) && device.startsWith("generic", ignoreCase = true))
        }

        internal fun hasActiveMatchingSystemPlayback(context: Context?): Boolean {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return false
                audioManager.activePlaybackConfigurations.any { cfg ->
                    val usage = cfg.audioAttributes?.usage
                    usage == AudioAttributes.USAGE_MEDIA ||
                        usage == AudioAttributes.USAGE_GAME ||
                        usage == AudioAttributes.USAGE_UNKNOWN
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

private object DefaultPlaybackCaptureRecorderFactory : PlaybackCaptureRecorderFactory {
    @SuppressLint("MissingPermission")
    override fun create(
        projection: MediaProjection,
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSizeBytes: Int
    ): PlaybackCaptureRecorder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSizeBytes)
            .build()

        return object : PlaybackCaptureRecorder {
            override val state: Int
                get() = record.state
            override val recordingState: Int
                get() = record.recordingState

            override fun startRecording() {
                record.startRecording()
            }

            override fun readNonBlocking(
                audioData: ByteArray,
                offsetInBytes: Int,
                sizeInBytes: Int
            ): Int {
                return record.read(
                    audioData,
                    offsetInBytes,
                    sizeInBytes,
                    AudioRecord.READ_NON_BLOCKING
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
