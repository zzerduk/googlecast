package com.example

import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.ddagunts.screencast.media.AudioCastMode
import io.github.ddagunts.screencast.media.AudioPlaybackCaptureEngine
import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.media.PlaybackCaptureRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioCastModeTest {
    @Test
    fun webRtcIsDefaultAndAacSettingsRemainAvailable() {
        val config = AudioStreamConfig()
        assertEquals(AudioCastMode.WEBRTC, config.mode)
        val aac = config.copy(mode = AudioCastMode.AAC)
        assertEquals(48000, aac.sampleRateHz)
        assertEquals(192000, aac.bitrateBps)
        assertEquals(300, aac.preBurstBufferMs)
        assertEquals(config.preBurstFrameCount, aac.preBurstFrameCount)
    }

    @Test
    fun aacEngineRetiresRecorderAfterSingleNegativeReadAndNeverPollsDeadTrackAgain() = runBlocking {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val readCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val silenceFrames = AtomicLong(0L)
        val logs = mutableListOf<String>()
        val isRecording = AtomicBoolean(false)

        val faultyRecorder = object : PlaybackCaptureRecorder {
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

            override fun readNonBlocking(
                audioData: ByteArray,
                offsetInBytes: Int,
                sizeInBytes: Int
            ): Int {
                readCalls.incrementAndGet()
                return -22 // Simulate AudioFlinger -EINVAL / ERROR_BAD_VALUE on native read
            }

            override fun stop() {
                stopCalls.incrementAndGet()
                isRecording.set(false)
            }

            override fun release() {
                releaseCalls.incrementAndGet()
                isRecording.set(false)
            }
        }

        val engine = AudioPlaybackCaptureEngine(
            context = null,
            configProvider = { AudioStreamConfig(mode = AudioCastMode.AAC, silenceInjectorTickerMs = 10L) },
            onAdtsPacketEncoded = {},
            onStatsUpdated = { _, injectedSilence, _, _, _ ->
                silenceFrames.set(injectedSilence)
            },
            onEngineLog = { synchronized(logs) { logs.add(it) } },
            isEmulatorProvider = { false },
            activePlaybackChecker = { true },
            recorderFactory = { _, _, _, _, _ -> faultyRecorder }
        )

        try {
            engine.startAudioOnlyCapture(projection)
            val deadline = System.nanoTime() + 1_500_000_000L
            while (silenceFrames.get() < 4L && System.nanoTime() < deadline) {
                delay(15)
            }
        } finally {
            engine.release()
        }

        assertEquals(1, readCalls.get())
        assertEquals(1, stopCalls.get())
        assertEquals(1, releaseCalls.get())
        assertTrue(silenceFrames.get() >= 4L)
        assertTrue(
            synchronized(logs) {
                logs.any { it.contains("status -22") && it.contains("Silence Injector") }
            }
        )
    }

    @Test
    fun aacEngineBypassesRSubmixRecorderOnEmulatorEnvironment() = runBlocking {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val factoryCalls = AtomicInteger(0)
        val silenceFrames = AtomicLong(0L)

        val engine = AudioPlaybackCaptureEngine(
            context = null,
            configProvider = { AudioStreamConfig(mode = AudioCastMode.AAC, silenceInjectorTickerMs = 10L) },
            onAdtsPacketEncoded = {},
            onStatsUpdated = { _, injectedSilence, _, _, _ ->
                silenceFrames.set(injectedSilence)
            },
            onEngineLog = {},
            isEmulatorProvider = { true },
            activePlaybackChecker = { true },
            recorderFactory = { _, _, _, _, _ ->
                factoryCalls.incrementAndGet()
                null
            }
        )

        try {
            engine.startAudioOnlyCapture(projection)
            val deadline = System.nanoTime() + 1_000_000_000L
            while (silenceFrames.get() < 2L && System.nanoTime() < deadline) {
                delay(15)
            }
        } finally {
            engine.release()
        }

        assertEquals(0, factoryCalls.get())
        assertTrue(silenceFrames.get() >= 2L)
    }
}
