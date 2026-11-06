package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

internal class WebRtcPcmBuffer {
    private val queue = ArrayBlockingQueue<ByteArray>(5)
    private var pending = ByteArray(CHUNK_BYTES)
    private var pendingBytes = 0
    private val chunksRead = AtomicLong()
    private val underruns = AtomicLong()
    private val droppedChunks = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var rmsDb = -60f

    fun append(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size)
        var offset = 0
        while (offset < length && !closed) {
            val count = minOf(CHUNK_BYTES - pendingBytes, length - offset)
            bytes.copyInto(pending, pendingBytes, offset, offset + count)
            pendingBytes += count
            offset += count
            if (pendingBytes == CHUNK_BYTES) {
                val chunk = pending
                pending = ByteArray(CHUNK_BYTES)
                pendingBytes = 0
                if (closed) break
                chunksRead.incrementAndGet()
                rmsDb = rmsDb(chunk)
                if (!queue.offer(chunk)) {
                    if (queue.poll() != null) droppedChunks.incrementAndGet()
                    queue.offer(chunk)
                }
                if (closed) queue.clear()
            }
        }
    }

    // The input already contains microphone PCM; clear all of it even on invalid metadata.
    fun overwrite(buffer: ByteBuffer, bytesRead: Int, captureActive: Boolean = true): Boolean {
        check(!buffer.isReadOnly) { "WebRTC audio buffer is read-only" }
        val output = buffer.duplicate()
        output.clear()
        while (output.hasRemaining()) output.put(0.toByte())
        val valid = bytesRead == CHUNK_BYTES && buffer.remaining() >= bytesRead
        if (!valid || !captureActive || closed) return valid
        val chunk = queue.poll()
        if (chunk == null) {
            underruns.incrementAndGet()
        } else {
            output.position(buffer.position())
            output.put(chunk)
        }
        return true
    }

    fun stats(minBufferBytes: Int): PcmCaptureStats = PcmCaptureStats(
        chunksRead = chunksRead.get(),
        underruns = underruns.get(),
        droppedChunks = droppedChunks.get(),
        ringDepth = queue.size,
        minBufferBytes = minBufferBytes,
        rmsDb = rmsDb
    )

    fun close() {
        closed = true
        queue.clear()
    }

    private fun rmsDb(chunk: ByteArray): Float {
        var sum = 0.0
        for (index in chunk.indices step 2) {
            val sample = ((chunk[index].toInt() and 0xFF) or
                (chunk[index + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        val rms = sqrt(sum / (chunk.size / 2)) / 32768.0
        return if (rms == 0.0) -60f else (20.0 * log10(rms)).toFloat().coerceIn(-60f, 0f)
    }

    companion object {
        const val CHUNK_BYTES = WEBRTC_AUDIO_SAMPLE_RATE / 100 * WEBRTC_AUDIO_CHANNELS * 2
    }
}
