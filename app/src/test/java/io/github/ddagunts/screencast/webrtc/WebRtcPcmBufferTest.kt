package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcPcmBufferTest {
    @Test
    fun shortReadsAreAccumulatedIntoOneChunk() {
        val pcm = WebRtcPcmBuffer()
        val first = ByteArray(600) { 1 }
        val second = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES - first.size) { 2 }
        pcm.append(first, first.size)
        assertEquals(0L, pcm.stats(0).chunksRead)
        pcm.append(second, second.size)
        val output = ByteBuffer.allocate(WebRtcPcmBuffer.CHUNK_BYTES)

        assertTrue(pcm.overwrite(output, output.capacity()))
        assertArrayEquals(first + second, output.array())
        assertEquals(1L, pcm.stats(0).chunksRead)
    }

    @Test
    fun fullRingDropsOldestRatherThanGrowingLatency() {
        val pcm = WebRtcPcmBuffer()
        repeat(6) { index ->
            val chunk = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { index.toByte() }
            pcm.append(chunk, chunk.size)
        }
        val output = ByteBuffer.allocate(WebRtcPcmBuffer.CHUNK_BYTES)

        assertTrue(pcm.overwrite(output, output.capacity()))
        assertTrue(output.array().all { it == 1.toByte() })
        assertEquals(1L, pcm.stats(0).droppedChunks)
        assertEquals(4, pcm.stats(0).ringDepth)
    }

    @Test
    fun underrunOverwritesEveryMicrophoneByteWithoutMovingPosition() {
        val pcm = WebRtcPcmBuffer()
        val output = ByteBuffer.wrap(ByteArray(WebRtcPcmBuffer.CHUNK_BYTES + 8) { 99 })
        output.position(4)
        output.limit(output.capacity() - 4)
        val position = output.position()
        val limit = output.limit()

        assertTrue(pcm.overwrite(output, WebRtcPcmBuffer.CHUNK_BYTES))
        assertTrue(output.array().all { it == 0.toByte() })
        assertEquals(position, output.position())
        assertEquals(limit, output.limit())
        assertEquals(1L, pcm.stats(0).underruns)
    }

    @Test
    fun validPcmUsesOriginalPositionAndClearsSurroundingMicrophoneBytes() {
        val pcm = WebRtcPcmBuffer()
        val chunk = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 7 }
        pcm.append(chunk, chunk.size)
        val output = ByteBuffer.wrap(ByteArray(chunk.size + 8) { 99 })
        output.position(4)
        output.limit(chunk.size + 4)

        assertTrue(pcm.overwrite(output, chunk.size))
        assertArrayEquals(chunk, output.array().copyOfRange(4, chunk.size + 4))
        assertTrue(output.array().take(4).all { it == 0.toByte() })
        assertTrue(output.array().takeLast(4).all { it == 0.toByte() })
        assertEquals(4, output.position())
        assertEquals(chunk.size + 4, output.limit())
    }

    @Test
    fun malformedLengthCannotExposeMicrophoneOrConsumeQueuedPcm() {
        val pcm = WebRtcPcmBuffer()
        val chunk = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 7 }
        pcm.append(chunk, chunk.size)
        val output = ByteBuffer.wrap(ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 99 })

        assertFalse(pcm.overwrite(output, output.capacity() + 1))
        assertTrue(output.array().all { it == 0.toByte() })
        assertEquals(1, pcm.stats(0).ringDepth)
    }

    @Test
    fun inactiveCaptureOverwritesMicrophoneAndDoesNotConsumeSystemPcm() {
        val pcm = WebRtcPcmBuffer()
        val chunk = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 7 }
        pcm.append(chunk, chunk.size)
        val output = ByteBuffer.wrap(ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 99 })

        assertTrue(pcm.overwrite(output, output.capacity(), captureActive = false))
        assertTrue(output.array().all { it == 0.toByte() })
        assertEquals(1, pcm.stats(0).ringDepth)
    }

    @Test
    fun closedCaptureCannotPublishQueuedOrNewPcm() {
        val pcm = WebRtcPcmBuffer()
        val chunk = ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 7 }
        pcm.append(chunk, chunk.size)
        pcm.close()
        pcm.append(chunk, chunk.size)
        val output = ByteBuffer.wrap(ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 99 })

        assertTrue(pcm.overwrite(output, output.capacity()))
        assertTrue(output.array().all { it == 0.toByte() })
        assertEquals(0, pcm.stats(0).ringDepth)
        assertEquals(1L, pcm.stats(0).chunksRead)
    }

    @Test(expected = IllegalStateException::class)
    fun readOnlyBufferFailsBeforeMicrophoneDataCanBeForwarded() {
        WebRtcPcmBuffer().overwrite(
            ByteBuffer.allocate(WebRtcPcmBuffer.CHUNK_BYTES).asReadOnlyBuffer(),
            WebRtcPcmBuffer.CHUNK_BYTES
        )
    }
}
