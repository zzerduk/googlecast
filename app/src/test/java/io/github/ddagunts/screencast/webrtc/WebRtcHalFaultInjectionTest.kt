package io.github.ddagunts.screencast.webrtc

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.ui.ScreenCastViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebRtcHalFaultInjectionTest {

    private lateinit var context: Context
    private lateinit var projection: MediaProjection
    private val latchWaiters = mutableListOf<CountDownLatch>()

    @Before
    fun setUp() {
        AudioCastSessionCoordinator.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        projection = Shadow.newInstanceOf(MediaProjection::class.java)
    }

    @After
    fun tearDown() {
        latchWaiters.forEach { it.countDown() }
        latchWaiters.clear()
        AudioCastSessionCoordinator.resetForTesting()
    }

    private fun newLatch(): CountDownLatch = CountDownLatch(1).also { latchWaiters.add(it) }

    private fun awaitUninterruptibly(latch: CountDownLatch, timeoutMs: Long = 5_000L) {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        try {
            while (latch.count > 0L) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                try {
                    if (latch.await(remainingNs, TimeUnit.NANOSECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun stuckReadTimesOutWithinBudgetWithoutReleasingActiveAudioRecordOrDependentObjects() = runBlocking {
        val readEntered = newLatch()
        val readBlocked = newLatch()
        val recorder = FaultyRecorder(
            onRead = {
                readEntered.countDown()
                awaitUninterruptibly(readBlocked)
                WebRtcPcmBuffer.CHUNK_BYTES
            }
        )
        val backend = FakePeerBackend()
        val channel = FakeSignalingChannel()
        val stages = mutableListOf<WebRtcStage>()
        val errors = mutableListOf<String>()
        val logs = mutableListOf<String>()

        val session = createSession(
            channel = channel,
            recorder = recorder,
            backend = backend,
            cleanupTimeoutMs = 120L,
            onStage = { stages.add(it) },
            onError = { errors.add(it) },
            onLog = { logs.add(it) }
        )

        session.start(projection)
        assertTrue(readEntered.await(2, TimeUnit.SECONDS))

        val stopResult = runCatching { session.stop() }
        assertTrue(stopResult.exceptionOrNull() is WebRtcCleanupTimeoutException)
        assertTrue(session.isCleanupTimedOut)
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
        assertTrue(channel.receiverRemoved)
        assertTrue(channel.sessionEnded)
        assertEquals(1, recorder.stopCalls.get())
        // Because readerThread is still blocked inside read(), release() must NOT be called concurrently.
        assertEquals(0, recorder.releaseCalls.get())
        // Dependent WebRTC objects must NOT be disposed when capture cleanup timed out.
        assertEquals(0, backend.closePeerCalls.get())
        assertEquals(0, backend.disposeTrackCalls.get())
        assertEquals(0, backend.disposeSourceCalls.get())
        assertEquals(0, backend.releaseModuleCalls.get())
        assertEquals(0, backend.disposePeerCalls.get())
        assertEquals(0, backend.disposeFactoryCalls.get())
        assertFalse(logs.any { it.contains("stopped", ignoreCase = true) || it.contains("released", ignoreCase = true) })

        // Late return from read() after CLEANUP_TIMEOUT must not trigger release(), errors, or state changes.
        readBlocked.countDown()
        delay(50)
        assertEquals(0, recorder.releaseCalls.get())
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
        assertEquals(1, errors.size)
    }

    @Test
    fun stuckStopTimesOutAndLateReturnDoesNotInvokeReleaseOrDependentDisposals() = runBlocking {
        val stopBlocked = newLatch()
        val recorder = FaultyRecorder(
            onStop = {
                awaitUninterruptibly(stopBlocked)
            }
        )
        val backend = FakePeerBackend()
        val channel = FakeSignalingChannel()
        val stages = mutableListOf<WebRtcStage>()
        val errors = mutableListOf<String>()

        val session = createSession(
            channel = channel,
            recorder = recorder,
            backend = backend,
            cleanupTimeoutMs = 120L,
            onStage = { stages.add(it) },
            onError = { errors.add(it) }
        )

        session.start(projection)

        val startNs = System.nanoTime()
        val stopResult = runCatching { session.stop() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue(stopResult.exceptionOrNull() is WebRtcCleanupTimeoutException)
        assertTrue("Expected bounded stop wait, took ${elapsedMs}ms", elapsedMs < 1_000L)
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
        assertTrue(channel.receiverRemoved)
        assertTrue(channel.sessionEnded)
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(0, recorder.releaseCalls.get())
        assertEquals(0, backend.disposeFactoryCalls.get())

        // Unblock stop() late after timeout; verify release() and dependent disposals are still not called.
        stopBlocked.countDown()
        delay(50)
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(0, recorder.releaseCalls.get())
        assertEquals(0, backend.closePeerCalls.get())
        assertEquals(0, backend.disposeFactoryCalls.get())
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
        assertEquals(1, errors.size)
    }

    @Test
    fun stuckReleaseTimesOutAndLateReturnDoesNotDisposeDependentObjectsOrDuplicateRelease() = runBlocking {
        val releaseBlocked = newLatch()
        val recorder = FaultyRecorder(
            onRelease = {
                awaitUninterruptibly(releaseBlocked)
            }
        )
        val backend = FakePeerBackend()
        val channel = FakeSignalingChannel()
        val stages = mutableListOf<WebRtcStage>()

        val session = createSession(
            channel = channel,
            recorder = recorder,
            backend = backend,
            cleanupTimeoutMs = 120L,
            onStage = { stages.add(it) }
        )

        session.start(projection)

        val firstStop = runCatching { session.stop() }
        assertTrue(firstStop.exceptionOrNull() is WebRtcCleanupTimeoutException)
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(1, recorder.releaseCalls.get())
        assertEquals(0, backend.closePeerCalls.get())
        assertEquals(0, backend.disposeTrackCalls.get())
        assertEquals(0, backend.releaseModuleCalls.get())
        assertEquals(0, backend.disposePeerCalls.get())
        assertEquals(0, backend.disposeFactoryCalls.get())

        // Repeated stop() must not call stop() or release() again.
        val secondStop = runCatching { session.stop() }
        assertTrue(secondStop.exceptionOrNull() is WebRtcCleanupTimeoutException)
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(1, recorder.releaseCalls.get())

        // Late return from release() after timeout must not run dependent WebRTC disposals.
        releaseBlocked.countDown()
        delay(50)
        assertEquals(1, recorder.releaseCalls.get())
        assertEquals(0, backend.closePeerCalls.get())
        assertEquals(0, backend.disposeFactoryCalls.get())
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
    }

    @Test
    fun cleanupUsesSingleTotalBudgetRatherThanResettingPerStep() = runBlocking {
        // Total budget is 150ms. stop() takes 90ms and release() takes 90ms (180ms > 150ms).
        val recorder = FaultyRecorder(
            onStop = { Thread.sleep(90) },
            onRelease = { Thread.sleep(90) }
        )
        val backend = FakePeerBackend()
        val channel = FakeSignalingChannel()
        val stages = mutableListOf<WebRtcStage>()

        val session = createSession(
            channel = channel,
            recorder = recorder,
            backend = backend,
            cleanupTimeoutMs = 150L,
            onStage = { stages.add(it) }
        )

        session.start(projection)

        val result = runCatching { session.stop() }
        assertTrue(result.exceptionOrNull() is WebRtcCleanupTimeoutException)
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, stages.last())
        // Because the single 150ms budget expired during release(), dependent peer disposals must not run.
        delay(80)
        assertEquals(0, backend.closePeerCalls.get())
        assertEquals(0, backend.disposeFactoryCalls.get())
    }

    @Test
    fun stopAndReleaseExceptionsAbortDependentDisposalsWithoutDuplicateRelease() = runBlocking {
        // Case 1: stop() throws -> release() and dependent WebRTC disposals must not run.
        val stopThrowRecorder = FaultyRecorder(
            onStop = { throw IllegalStateException("HAL stop failure") }
        )
        val stopThrowBackend = FakePeerBackend()
        val session1 = createSession(
            channel = FakeSignalingChannel(),
            recorder = stopThrowRecorder,
            backend = stopThrowBackend,
            cleanupTimeoutMs = 500L
        )
        session1.start(projection)
        val err1 = runCatching { session1.stop() }.exceptionOrNull()
        assertTrue(err1 is IllegalStateException)
        assertFalse(err1 is WebRtcCleanupTimeoutException)
        assertEquals(1, stopThrowRecorder.stopCalls.get())
        assertEquals(0, stopThrowRecorder.releaseCalls.get())
        assertEquals(0, stopThrowBackend.closePeerCalls.get())
        assertEquals(0, stopThrowBackend.disposeFactoryCalls.get())

        // Case 2: release() throws -> dependent WebRTC disposals must not run, and repeated stop() does not re-release.
        val releaseThrowRecorder = FaultyRecorder(
            onRelease = { throw IllegalStateException("HAL release failure") }
        )
        val releaseThrowBackend = FakePeerBackend()
        val session2 = createSession(
            channel = FakeSignalingChannel(),
            recorder = releaseThrowRecorder,
            backend = releaseThrowBackend,
            cleanupTimeoutMs = 500L
        )
        session2.start(projection)
        val err2 = runCatching { session2.stop() }.exceptionOrNull()
        assertTrue(err2 is IllegalStateException)
        assertEquals(1, releaseThrowRecorder.stopCalls.get())
        assertEquals(1, releaseThrowRecorder.releaseCalls.get())
        assertEquals(0, releaseThrowBackend.closePeerCalls.get())
        assertEquals(0, releaseThrowBackend.disposeTrackCalls.get())
        assertEquals(0, releaseThrowBackend.disposeFactoryCalls.get())

        // Repeated stop() must not invoke release() again.
        runCatching { session2.stop() }
        assertEquals(1, releaseThrowRecorder.releaseCalls.get())
    }

    @Test
    fun readExceptionTriggersErrorAndSingleCleanupWithoutInfiniteRetry() = runBlocking {
        val readFailedLatch = CountDownLatch(1)
        val recorder = FaultyRecorder(
            onRead = {
                throw IllegalStateException("HAL read exploded")
            }
        )
        val backend = FakePeerBackend()
        val stages = mutableListOf<WebRtcStage>()
        val errors = mutableListOf<String>()

        val session = createSession(
            channel = FakeSignalingChannel(),
            recorder = recorder,
            backend = backend,
            cleanupTimeoutMs = 500L,
            onStage = {
                stages.add(it)
                if (it == WebRtcStage.ERROR) readFailedLatch.countDown()
            },
            onError = { errors.add(it) }
        )

        session.start(projection)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (readFailedLatch.count > 0L && System.nanoTime() < deadline) {
            delay(10)
        }
        assertEquals(0L, readFailedLatch.count)
        session.stop()

        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("HAL read exploded"))
        assertEquals(1, recorder.startCalls.get())
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(1, recorder.releaseCalls.get())
        assertEquals(1, backend.closePeerCalls.get())
        assertEquals(1, backend.disposeFactoryCalls.get())
    }

    @Test
    fun logicalStopImmediatelyOutputsZeroPcmEndsCastSessionAndIgnoresLateCallbacks() = runBlocking {
        val releaseBlocked = newLatch()
        var activeCapture: WebRtcAudioCapture? = null
        val recorder = FaultyRecorder(
            onRelease = {
                awaitUninterruptibly(releaseBlocked)
            }
        )
        val backend = FakePeerBackend()
        val channel = FakeSignalingChannel(
            onEndSession = {
                // Verify Cast session ends BEFORE native release finishes!
                assertEquals(1L, releaseBlocked.count)
            }
        )
        val stages = mutableListOf<WebRtcStage>()
        val errors = mutableListOf<String>()
        val statsReceived = AtomicInteger(0)

        val testMainDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val session = WebRtcAudioSession(
            context = context,
            channel = channel,
            onStage = { stages.add(it) },
            onStats = { statsReceived.incrementAndGet() },
            onPcmStats = { statsReceived.incrementAndGet() },
            onError = { errors.add(it) },
            onLog = {},
            cleanupTimeoutMs = 150L,
            mainDispatcher = testMainDispatcher,
            captureFactory = { onError, onStats, onLog ->
                WebRtcAudioCapture(
                    onError = onError,
                    onStats = onStats,
                    onLog = onLog,
                    cleanupTimeoutMs = 150L,
                    minBufferSizeProvider = { _, _, _ -> WebRtcPcmBuffer.CHUNK_BYTES * 2 },
                    recorderFactory = { _, _, _ -> recorder }
                ).also { activeCapture = it }
            },
            peerBackendFactory = { backend }
        )

        session.start(projection)
        val capture = requireNotNull(activeCapture)

        // Start stop() in background while release is blocked, then verify immediate logical stop state.
        val stopDeferred = CoroutineScope(testMainDispatcher).async {
            runCatching { session.stop() }
        }
        yield()
        delay(25)

        // Cast callback removal and Cast session termination must already have happened without waiting for release!
        assertTrue(channel.receiverRemoved)
        assertTrue(channel.sessionEnded)

        // Audio callback onBuffer must output zeros and NOT trigger errors even with invalid/late callbacks.
        val micBuffer = ByteBuffer.wrap(ByteArray(WebRtcPcmBuffer.CHUNK_BYTES) { 0x7F.toByte() })
        capture.onBuffer(
            buffer = micBuffer,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            channelCount = WEBRTC_AUDIO_CHANNELS,
            sampleRate = WEBRTC_AUDIO_SAMPLE_RATE,
            bytesRead = WebRtcPcmBuffer.CHUNK_BYTES,
            captureTimestampNs = 12345L
        )
        assertTrue(micBuffer.array().all { it == 0.toByte() })

        // Deliver late callbacks from Cast and PeerConnection; all must be ignored.
        val statsBeforeLateCallbacks = statsReceived.get()
        channel.deliverLateMessage("""{"type":"ANSWER","sdp":"v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"}""")
        backend.emitLateCallbacks()
        assertEquals(0, backend.setRemoteAnswerCalls.get())
        assertEquals(statsBeforeLateCallbacks, statsReceived.get())
        assertTrue(errors.isEmpty())

        releaseBlocked.countDown()
        val outcome = stopDeferred.await()
        assertTrue(outcome.isSuccess)
    }

    @Test
    fun peerCloseAndStartupCleanupNeverRunOnMainThread() = runBlocking {
        val buildPeerThread = AtomicReference<Looper?>()
        val closePeerThread = AtomicReference<Looper?>()
        val recorder = FaultyRecorder()
        val backend = FakePeerBackend(
            onBuildPeer = { buildPeerThread.set(Looper.myLooper()) },
            onClosePeer = { closePeerThread.set(Looper.myLooper()) }
        )
        val session = createSession(
            channel = FakeSignalingChannel(),
            recorder = recorder,
            backend = backend,
            cleanupTimeoutMs = 500L
        )

        session.start(projection)
        session.stop()

        assertNotEquals(Looper.getMainLooper(), buildPeerThread.get())
        assertNotEquals(Looper.getMainLooper(), closePeerThread.get())
        assertEquals(1, backend.closePeerCalls.get())
        assertEquals(1, backend.disposeFactoryCalls.get())
    }

    @Test
    fun cleanupTimeoutLocksSameProcessCaptureAndRetainsError() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = ScreenCastViewModel(app)

        AudioCastSessionCoordinator.markCleanupTimeout(
            "WebRTC native audio cleanup timed out after 3000 ms"
        )

        assertTrue(AudioCastSessionCoordinator.isCaptureLocked)
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, viewModel.telemetry.value.webRtcStage)
        assertTrue(viewModel.telemetry.value.isCaptureLocked)
        assertFalse(viewModel.prepareCaptureRequest(requireConnectedCastReceiver = false))

        // Attempting to stop or start with projection must not clear CLEANUP_TIMEOUT or error.
        viewModel.stopAudioCast()
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, viewModel.telemetry.value.webRtcStage)
        assertEquals(
            "WebRTC native audio cleanup timed out after 3000 ms",
            viewModel.telemetry.value.error
        )

        viewModel.startAudioCastWithProjection(Activity.RESULT_OK, Intent())
        assertFalse(viewModel.telemetry.value.isSessionActive)
        assertEquals(WebRtcStage.CLEANUP_TIMEOUT, viewModel.telemetry.value.webRtcStage)
    }

    private suspend fun createSession(
        channel: FakeSignalingChannel,
        recorder: FaultyRecorder,
        backend: FakePeerBackend,
        cleanupTimeoutMs: Long,
        onStage: (WebRtcStage) -> Unit = {},
        onError: (String) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): WebRtcAudioSession {
        val testMainDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        return WebRtcAudioSession(
            context = context,
            channel = channel,
            onStage = onStage,
            onStats = {},
            onPcmStats = {},
            onError = onError,
            onLog = onLog,
            cleanupTimeoutMs = cleanupTimeoutMs,
            mainDispatcher = testMainDispatcher,
            captureFactory = { err, stats, log ->
                WebRtcAudioCapture(
                    onError = err,
                    onStats = stats,
                    onLog = log,
                    cleanupTimeoutMs = cleanupTimeoutMs,
                    minBufferSizeProvider = { _, _, _ -> WebRtcPcmBuffer.CHUNK_BYTES * 2 },
                    recorderFactory = { _, _, _ -> recorder }
                )
            },
            peerBackendFactory = { backend }
        )
    }

    private class FaultyRecorder(
        private val onRead: (() -> Int)? = null,
        private val onStop: (() -> Unit)? = null,
        private val onRelease: (() -> Unit)? = null
    ) : PlaybackAudioRecorder {
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        private val recording = AtomicBoolean(false)

        override val state: Int = AudioRecord.STATE_INITIALIZED
        override val recordingState: Int
            get() = if (recording.get()) AudioRecord.RECORDSTATE_RECORDING else AudioRecord.RECORDSTATE_STOPPED

        override fun startRecording() {
            startCalls.incrementAndGet()
            recording.set(true)
        }

        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            onRead?.let { return it() }
            if (!recording.get()) return 0
            Thread.sleep(10)
            audioData.fill(3, offsetInBytes, offsetInBytes + sizeInBytes)
            return sizeInBytes
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            recording.set(false)
            onStop?.invoke()
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            onRelease?.invoke()
        }
    }

    private class FakePeerBackend(
        private val onBuildPeer: (() -> Unit)? = null,
        private val onClosePeer: (() -> Unit)? = null
    ) : WebRtcPeerBackend {
        val setRemoteAnswerCalls = AtomicInteger(0)
        val disableTrackCalls = AtomicInteger(0)
        val closePeerCalls = AtomicInteger(0)
        val disposeTrackCalls = AtomicInteger(0)
        val disposeSourceCalls = AtomicInteger(0)
        val releaseModuleCalls = AtomicInteger(0)
        val disposePeerCalls = AtomicInteger(0)
        val disposeFactoryCalls = AtomicInteger(0)

        private var recordErrorCallback: ((String) -> Unit)? = null
        private var iceCandidateCallback: ((IceCandidate) -> Unit)? = null
        private var iceStateCallback: ((PeerConnection.IceConnectionState) -> Unit)? = null
        private var connectionStateCallback: ((PeerConnection.PeerConnectionState) -> Unit)? = null

        override fun buildPeer(
            context: Context,
            capture: WebRtcAudioCapture,
            onRecordError: (String) -> Unit,
            onTrackError: (String) -> Unit,
            onIceCandidate: (IceCandidate) -> Unit,
            onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit,
            onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
            onLog: (String) -> Unit,
            isStillActive: () -> Boolean
        ) {
            onBuildPeer?.invoke()
            recordErrorCallback = onRecordError
            iceCandidateCallback = onIceCandidate
            iceStateCallback = onIceConnectionChange
            connectionStateCallback = onConnectionChange
        }

        override suspend fun createOfferAndSetLocalDescription(
            isStillActive: () -> Boolean,
            onLog: (String) -> Unit
        ): String {
            return "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        }

        override suspend fun setRemoteAnswer(sdp: String, isStillActive: () -> Boolean) {
            setRemoteAnswerCalls.incrementAndGet()
        }

        override fun addIceCandidate(candidate: IceCandidate): Boolean = true

        override fun pollStats(
            onStats: (WebRtcStats) -> Unit,
            onFailure: (Exception) -> Unit
        ) {
            onStats(WebRtcStats(audioBytesSent = 1024L, audioPacketsSent = 8L, rttMs = 12.0))
        }

        fun emitLateCallbacks() {
            recordErrorCallback?.invoke("late recorder error")
            iceCandidateCallback?.invoke(IceCandidate("0", 0, "late-candidate"))
            iceStateCallback?.invoke(PeerConnection.IceConnectionState.FAILED)
            connectionStateCallback?.invoke(PeerConnection.PeerConnectionState.CONNECTED)
        }

        override fun disableAudioTrack() {
            disableTrackCalls.incrementAndGet()
        }

        override fun closePeer() {
            onClosePeer?.invoke()
            closePeerCalls.incrementAndGet()
        }

        override fun disposeTrack() {
            disposeTrackCalls.incrementAndGet()
        }

        override fun disposeSource() {
            disposeSourceCalls.incrementAndGet()
        }

        override fun releaseAudioModule() {
            releaseModuleCalls.incrementAndGet()
        }

        override fun disposePeer() {
            disposePeerCalls.incrementAndGet()
        }

        override fun disposeFactory() {
            disposeFactoryCalls.incrementAndGet()
        }
    }

    private class FakeSignalingChannel(
        private val onEndSession: (() -> Unit)? = null
    ) : CastSignalingChannel {
        override var isConnected: Boolean = true
        var receiverRemoved: Boolean = false
        var sessionEnded: Boolean = false
        private var receiver: ((String) -> Unit)? = null
        val sentMessages = mutableListOf<String>()

        override fun setMessageReceiver(receiver: (String) -> Unit) {
            this.receiver = receiver
        }

        override fun removeMessageReceiver() {
            receiverRemoved = true
            receiver = null
        }

        fun deliverLateMessage(message: String) {
            receiver?.invoke(message)
        }

        override suspend fun send(message: String) {
            sentMessages.add(message)
        }

        override fun endSession() {
            sessionEnded = true
            isConnected = false
            onEndSession?.invoke()
        }
    }
}
