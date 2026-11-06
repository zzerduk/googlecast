package io.github.ddagunts.screencast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Hardware/Software AAC-LC encoder (`audio/mp4a-latm`) wrapping raw AAC access units
 * in 7-byte ADTS headers for progressive chunked HTTP streaming to 1st-Gen Google Home.
 *
 * Phase 3 Requirements:
 * - MIME type: [MediaFormat.MIMETYPE_AUDIO_AAC] (`audio/mp4a-latm`)
 * - Profile: [MediaCodecInfo.CodecProfileLevel.AACObjectLC]
 * - Bitrate: `192000` or `128000` bps in CBR mode ([MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR])
 * - Max Input Size: `8192` bytes
 * - Skips [MediaCodec.BUFFER_FLAG_CODEC_CONFIG] (CSD-0 is not sent raw in ADTS)
 * - Prepends 7-byte ADTS header (`packetLen = rawLen + 7`) to every encoded payload
 */
class AdtsAacEncoder(
    private val sampleRateHz: Int = 48000,
    private val channelCount: Int = 2,
    private val bitrateBps: Int = 192000,
    private val onAdtsFrameReady: (ByteArray) -> Unit
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val sampleRateIndex: Int = if (sampleRateHz == 44100) 4 else 3

    @Volatile
    private var isStarted = false

    fun start() {
        if (isStarted) return
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRateHz,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE_BYTES)
        }

        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        codec = mediaCodec
        isStarted = true
    }

    /**
     * Feeds a chunk of 16-bit stereo PCM samples (real audio or zeroed silence from the
     * Silence Injector) into the encoder and immediately drains all ready ADTS packets.
     */
    fun encodePcmChunk(pcmBytes: ByteArray, length: Int, presentationTimeUs: Long) {
        val activeCodec = codec ?: return
        if (!isStarted || length <= 0) return

        try {
            val inputIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = activeCodec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val bytesToWrite = length.coerceAtMost(inputBuffer.remaining())
                    inputBuffer.put(pcmBytes, 0, bytesToWrite)
                    activeCodec.queueInputBuffer(
                        inputIndex,
                        0,
                        bytesToWrite,
                        presentationTimeUs,
                        0
                    )
                }
            }
            drainEncodedAdtsFrames(activeCodec)
        } catch (e: Exception) {
            Log.w(TAG, "AAC encode step error: ${e.message}")
        }
    }

    private fun drainEncodedAdtsFrames(activeCodec: MediaCodec) {
        while (true) {
            val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            if (outputIndex < 0) {
                break
            }

            try {
                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val rawLen = bufferInfo.size
                if (!isCodecConfig && rawLen > 0) {
                    val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + rawLen)

                        val packetLen = rawLen + ADTS_HEADER_LENGTH
                        val adtsHeader = createAdtsHeader(
                            packetLength = packetLen,
                            sampleRateIndex = sampleRateIndex,
                            channelCount = channelCount
                        )
                        val adtsPacket = ByteArray(packetLen)
                        System.arraycopy(adtsHeader, 0, adtsPacket, 0, ADTS_HEADER_LENGTH)
                        outputBuffer.get(adtsPacket, ADTS_HEADER_LENGTH, rawLen)

                        onAdtsFrameReady(adtsPacket)
                    }
                }
            } finally {
                activeCodec.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    fun stopAndRelease() {
        isStarted = false
        val activeCodec = codec ?: return
        codec = null
        try {
            activeCodec.stop()
        } catch (_: Exception) {
        }
        try {
            activeCodec.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AdtsAacEncoder"
        const val ADTS_HEADER_LENGTH = 7
        const val MAX_INPUT_SIZE_BYTES = 8192
        private const val INPUT_TIMEOUT_US = 2_000L
        private const val OUTPUT_TIMEOUT_US = 0L

        /**
         * Phase 3.2 Exact ADTS 7-Byte Header Generator:
         * `sampleRateIndex`: 3 = 48000Hz, 4 = 44100Hz
         * `profile`: 2 = AAC LC
         */
        fun createAdtsHeader(
            packetLength: Int,
            sampleRateIndex: Int = 3,
            channelCount: Int = 2
        ): ByteArray {
            val profile = 2 // AAC LC
            return byteArrayOf(
                0xFF.toByte(),
                0xF9.toByte(), // MPEG-2, Layer 0, no CRC
                (((profile - 1) shl 6) + (sampleRateIndex shl 2) + (channelCount shr 2)).toByte(),
                (((channelCount and 3) shl 6) + (packetLength shr 11)).toByte(),
                ((packetLength and 0x7FF) shr 3).toByte(),
                (((packetLength and 7) shl 5) + 0x1F).toByte(),
                0xFC.toByte()
            )
        }

        /**
         * Generates a valid, standards-compliant silent stereo AAC-LC frame wrapped in a 7-byte
         * ADTS header for the Phase 4.2 Pre-burst Latency Hack.
         *
         * Raw AAC-LC CPE (Channel Pair Element) silent payload (`0x21, 0x00, 0x49, 0x90, 0x02, 0x19, 0x00, 0x23, 0x80`)
         * decodes to 1024 zeroed stereo PCM samples (~21.33ms at 48kHz).
         */
        fun createSilentAdtsFrame(sampleRateIndex: Int = 3, channelCount: Int = 2): ByteArray {
            val silentRawAacPayload = byteArrayOf(
                0x21.toByte(),
                0x00.toByte(),
                0x49.toByte(),
                0x90.toByte(),
                0x02.toByte(),
                0x19.toByte(),
                0x00.toByte(),
                0x23.toByte(),
                0x80.toByte()
            )
            val packetLength = ADTS_HEADER_LENGTH + silentRawAacPayload.size
            val header = createAdtsHeader(
                packetLength = packetLength,
                sampleRateIndex = sampleRateIndex,
                channelCount = channelCount
            )
            return header + silentRawAacPayload
        }
    }
}
