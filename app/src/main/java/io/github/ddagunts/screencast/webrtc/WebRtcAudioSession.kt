package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Looper
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface WebRtcPeerBackend {
    fun buildPeer(
        context: Context,
        capture: WebRtcAudioCapture,
        onRecordError: (String) -> Unit,
        onTrackError: (String) -> Unit,
        onIceCandidate: (IceCandidate) -> Unit,
        onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit,
        onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
        onLog: (String) -> Unit,
        isStillActive: () -> Boolean
    )

    suspend fun createOfferAndSetLocalDescription(
        isStillActive: () -> Boolean,
        onLog: (String) -> Unit
    ): String

    suspend fun setRemoteAnswer(
        sdp: String,
        isStillActive: () -> Boolean
    )

    fun addIceCandidate(candidate: IceCandidate): Boolean
    fun pollStats(onStats: (WebRtcStats) -> Unit, onFailure: (Exception) -> Unit)
    fun disableAudioTrack()
    fun closePeer()
    fun disposeTrack()
    fun disposeSource()
    fun releaseAudioModule()
    fun disposePeer()
    fun disposeFactory()
}

class WebRtcAudioSession internal constructor(
    context: Context,
    private val channel: CastSignalingChannel,
    private val onStage: (WebRtcStage) -> Unit,
    private val onStats: (WebRtcStats) -> Unit,
    private val onPcmStats: (PcmCaptureStats) -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val cleanupTimeoutMs: Long = WEBRTC_CLEANUP_TIMEOUT_MS,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val captureFactory: (
        onError: (String) -> Unit,
        onStats: (PcmCaptureStats) -> Unit,
        onLog: (String) -> Unit
    ) -> WebRtcAudioCapture = { err, stats, log ->
        WebRtcAudioCapture(
            onError = err,
            onStats = stats,
            onLog = log,
            cleanupTimeoutMs = cleanupTimeoutMs
        )
    },
    private val peerBackendFactory: () -> WebRtcPeerBackend = ::RealWebRtcPeerBackend
) {
    constructor(
        context: Context,
        channel: CastSignalingChannel,
        onStage: (WebRtcStage) -> Unit,
        onStats: (WebRtcStats) -> Unit,
        onPcmStats: (PcmCaptureStats) -> Unit,
        onError: (String) -> Unit,
        onLog: (String) -> Unit
    ) : this(
        context = context,
        channel = channel,
        onStage = onStage,
        onStats = onStats,
        onPcmStats = onPcmStats,
        onError = onError,
        onLog = onLog,
        cleanupTimeoutMs = WEBRTC_CLEANUP_TIMEOUT_MS
    )

    private val context = context.applicationContext
    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(sessionJob + mainDispatcher)
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val offerSent = CompletableDeferred<Unit>()
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupTimedOut = AtomicBoolean(false)
    private val cleanupFailed = AtomicBoolean(false)
    private val timeoutCallbacksEmitted = AtomicBoolean(false)
    private val cleanupOutcome = CompletableDeferred<CleanupOutcome>()

    @Volatile private var peerBackend: WebRtcPeerBackend? = null
    @Volatile private var audioCapture: WebRtcAudioCapture? = null
    @Volatile private var initLatch: CountDownLatch? = null
    @Volatile private var cleanupWorkerThread: Thread? = null
    private var signaling: WebRtcSignalingState? = null
    private var connected = false
    private var previousAudioBytes = 0L
    private var statsPending = false
    private var started = false
    private var logicalStopped = false
    private var channelEnded = false
    @Volatile private var closing = false
    @Volatile private var generation = 0L

    internal val isCleanupTimedOut: Boolean
        get() = cleanupTimedOut.get()

    internal val isCleanupFailed: Boolean
        get() = cleanupFailed.get()

    internal val isCleanupWorkerAlive: Boolean
        get() = cleanupWorkerThread?.isAlive == true

    suspend fun start(projection: MediaProjection, targetUids: Set<Int> = emptySet()) {
        checkMainThread()
        check(!started && !logicalStopped) { "Create a new WebRtcAudioSession for each attempt" }
        check(channel.isConnected) { "No active custom Receiver session" }
        started = true
        val epoch = ++generation
        scope.launch {
            for (event in events) {
                if (closing || event.generation != generation) continue
                try {
                    event.action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(e.message ?: "WebRTC signaling failed")
                }
            }
        }
        try {
            onStage(WebRtcStage.CAPTURING)
            initializeNativeOffMain(projection, targetUids, epoch)
            if (closing || generation != epoch) {
                throw CancellationException("WebRTC session stopped during initialization")
            }
            signaling = WebRtcSignalingState(
                channel = channel,
                applyAnswer = { setRemoteAnswer(it, epoch) },
                applyCandidate = { candidate ->
                    check(peerBackend?.addIceCandidate(candidate) == true) {
                        "Remote ICE candidate rejected"
                    }
                },
                onLog = onLog
            )
            channel.setMessageReceiver { message ->
                enqueue(epoch) { signaling?.onMessage(message) }
            }
            scope.launch {
                delay(30_000)
                if (!connected && !closing && generation == epoch) {
                    fail("WebRTC did not connect within 30 seconds")
                }
            }
            startStatsPolling(epoch)
            enqueue(epoch) {
                onStage(WebRtcStage.NEGOTIATING)
                val offer = createOffer(epoch)
                signaling?.sendOffer(offer)
                offerSent.complete(Unit)
            }
            offerSent.await()
        } catch (e: CancellationException) {
            beginLogicalStop()
            endChannel()
            ensureNativeCleanupStarted()
            throw e
        } catch (e: Throwable) {
            val failure = when (e) {
                is WebRtcCleanupTimeoutException -> e
                is Exception -> e
                else -> IllegalStateException("WebRTC native startup failed: ${e.message}", e)
            }
            handleStartupFailure(failure)
        }
    }

    private suspend fun initializeNativeOffMain(
        projection: MediaProjection,
        targetUids: Set<Int>,
        epoch: Long
    ) {
        val latch = CountDownLatch(1)
        initLatch = latch
        val capture = captureFactory(
            { message -> reportFailure(epoch, message) },
            { stats -> enqueue(epoch) { onPcmStats(stats) } },
            { message -> if (!closing && generation == epoch) onLog(message) }
        )
        audioCapture = capture
        val backend = peerBackendFactory()
        peerBackend = backend
        withContext(workerDispatcher) {
            try {
                if (closing || generation != epoch) return@withContext
                capture.attachProjection(projection, targetUids)
                if (closing || generation != epoch) return@withContext
                backend.buildPeer(
                    context = context,
                    capture = capture,
                    onRecordError = { msg -> reportFailure(epoch, msg) },
                    onTrackError = { msg -> reportFailure(epoch, msg) },
                    onIceCandidate = { candidate ->
                        enqueue(epoch) { signaling?.onLocalCandidate(candidate) }
                    },
                    onIceConnectionChange = { state ->
                        if (state == PeerConnection.IceConnectionState.FAILED ||
                            state == PeerConnection.IceConnectionState.DISCONNECTED
                        ) {
                            reportFailure(epoch, "WebRTC ICE connection lost: $state")
                        } else {
                            enqueue(epoch) { onLog("ICE state: $state") }
                        }
                    },
                    onConnectionChange = { state ->
                        if (state == PeerConnection.PeerConnectionState.FAILED ||
                            state == PeerConnection.PeerConnectionState.DISCONNECTED
                        ) {
                            reportFailure(epoch, "WebRTC connection lost: $state")
                        } else {
                            enqueue(epoch) {
                                onLog("Peer state: $state")
                                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                                    connected = true
                                    onStage(WebRtcStage.CONNECTED)
                                }
                            }
                        }
                    },
                    onLog = { msg -> if (!closing && generation == epoch) onLog(msg) },
                    isStillActive = { !closing && generation == epoch }
                )
            } finally {
                latch.countDown()
            }
        }
    }

    private suspend fun handleStartupFailure(failure: Throwable): Nothing {
        val message = failure.message ?: "WebRTC startup failed"
        offerSent.completeExceptionally(IllegalStateException(message, failure))
        beginLogicalStop()
        endChannel()
        val outcome = ensureNativeCleanupStarted().await()
        when (outcome) {
            is CleanupOutcome.TimedOut -> {
                onStage(WebRtcStage.CLEANUP_TIMEOUT)
                onError(outcome.exception.message ?: message)
                throw outcome.exception
            }
            is CleanupOutcome.Failed -> {
                cleanupFailed.set(true)
                onStage(WebRtcStage.CLEANUP_TIMEOUT)
                onError(message)
                if (failure !== outcome.error) {
                    failure.addSuppressed(outcome.error)
                }
                throw failure
            }
            is CleanupOutcome.Completed -> {
                if (failure is WebRtcCleanupTimeoutException) {
                    onStage(WebRtcStage.CLEANUP_TIMEOUT)
                } else {
                    onStage(WebRtcStage.ERROR)
                }
                onError(message)
                throw failure
            }
        }
    }

    suspend fun stop() = withContext(NonCancellable + mainDispatcher) {
        if (!closing && !cleanupTimedOut.get()) {
            onStage(WebRtcStage.STOPPING)
        }
        // 1. Immediate logical stop: invalidate generation, mask callbacks, zero PCM, remove Cast listener.
        beginLogicalStop()
        // 2. Start tracked background native cleanup worker immediately with a single 3s budget.
        val cleanupDeferred = ensureNativeCleanupStarted()
        // 3. Send BYE (bounded 1s) and end Cast session WITHOUT waiting for native audio cleanup.
        if (!channelEnded) {
            try {
                withTimeoutOrNull(1_000) {
                    if (channel.isConnected) {
                        channel.send(JSONObject().put("type", "BYE").toString())
                    }
                } ?: onLog("BYE send timed out")
            } catch (e: Exception) {
                onLog("BYE could not be sent: ${e.message}")
            } finally {
                endChannel()
            }
        }
        // 4. Bounded wait for native cleanup result.
        when (val outcome = cleanupDeferred.await()) {
            is CleanupOutcome.Completed -> Unit
            is CleanupOutcome.Failed -> {
                cleanupFailed.set(true)
                throw outcome.error
            }
            is CleanupOutcome.TimedOut -> {
                emitTimeoutCallbacksOnMain(outcome)
                throw outcome.exception
            }
        }
    }

    /**
     * Non-blocking logical close: invalidates callbacks, closes PCM queue, ends Cast channel,
     * and triggers the tracked background cleanup worker without blocking the calling thread.
     */
    fun close() {
        checkMainThread()
        beginLogicalStop()
        endChannel()
        ensureNativeCleanupStarted()
    }

    private suspend fun createOffer(epoch: Long): String {
        val backend = peerBackend ?: error("PeerConnection is unavailable")
        return backend.createOfferAndSetLocalDescription(
            isStillActive = { generation == epoch && !closing },
            onLog = onLog
        )
    }

    private suspend fun setRemoteAnswer(sdp: String, epoch: Long) {
        val backend = peerBackend ?: error("PeerConnection is unavailable")
        backend.setRemoteAnswer(
            sdp = sdp,
            isStillActive = { generation == epoch && !closing }
        )
    }

    private fun startStatsPolling(epoch: Long) {
        scope.launch {
            while (isActive && !closing && generation == epoch) {
                delay(1_000)
                if (statsPending || closing || generation != epoch) continue
                val backend = peerBackend ?: return@launch
                statsPending = true
                backend.pollStats(
                    onStats = { stats ->
                        enqueue(epoch) {
                            statsPending = false
                            onStats(stats)
                            if (connected && stats.audioBytesSent > previousAudioBytes) {
                                onStage(WebRtcStage.SENDING)
                            }
                            previousAudioBytes = stats.audioBytesSent
                        }
                    },
                    onFailure = { e ->
                        reportFailure(epoch, "Could not query WebRTC audio statistics: ${e.message}")
                    }
                )
            }
        }
    }

    private fun enqueue(epoch: Long, action: suspend () -> Unit) {
        if (!closing && generation == epoch) {
            events.trySend(Event(epoch, action))
        }
    }

    private fun reportFailure(epoch: Long, message: String) {
        scope.launch(mainDispatcher) {
            if (!closing && generation == epoch) {
                fail(message)
            }
        }
    }

    private fun fail(message: String) {
        if (closing) return
        offerSent.completeExceptionally(IllegalStateException(message))
        beginLogicalStop()
        endChannel()
        onStage(WebRtcStage.ERROR)
        onError(message)
        ensureNativeCleanupStarted()
    }

    private fun beginLogicalStop() {
        if (logicalStopped) return
        logicalStopped = true
        closing = true
        generation++
        connected = false
        audioCapture?.deactivate()
        signaling?.close()
        signaling = null
        sessionJob.cancel()
        events.close()
        offerSent.cancel()
        runCatching { channel.removeMessageReceiver() }.onFailure {
            onLog("Receiver callback removal failed: ${it.message}")
        }
    }

    private fun ensureNativeCleanupStarted(): Deferred<CleanupOutcome> {
        if (!cleanupStarted.compareAndSet(false, true)) {
            return cleanupOutcome
        }
        val capture = audioCapture
        val backend = peerBackend
        val pendingInit = initLatch
        audioCapture = null
        peerBackend = null

        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMs)
        val workerId = CLEANUP_WORKER_ID.incrementAndGet()
        val worker = Thread(
            {
                val result = runNativeCleanup(
                    capture = capture,
                    backend = backend,
                    pendingInit = pendingInit,
                    deadlineNs = deadlineNs
                )
                when (result) {
                    is CleanupOutcome.Completed -> {
                        if (!cleanupTimedOut.get()) {
                            cleanupOutcome.complete(result)
                        }
                    }
                    is CleanupOutcome.Failed -> {
                        cleanupFailed.set(true)
                        if (!cleanupTimedOut.get()) {
                            cleanupOutcome.complete(result)
                        }
                    }
                    is CleanupOutcome.TimedOut -> {
                        reportCleanupTimeout(result)
                    }
                }
            },
            "WebRtcSession-cleanup-$workerId"
        ).apply {
            isDaemon = true
            start()
        }
        cleanupWorkerThread = worker

        watchdogScope.launch {
            delay(cleanupTimeoutMs.coerceAtLeast(1L))
            if (!cleanupOutcome.isCompleted) {
                val timeoutOutcome = CleanupOutcome.TimedOut(
                    WebRtcCleanupTimeoutException(
                        "WebRTC native audio cleanup timed out after ${cleanupTimeoutMs} ms; " +
                            "capture is isolated in this process."
                    )
                )
                reportCleanupTimeout(timeoutOutcome)
            }
        }
        return cleanupOutcome
    }

    private fun reportCleanupTimeout(outcome: CleanupOutcome.TimedOut) {
        cleanupTimedOut.set(true)
        cleanupOutcome.complete(outcome)
        watchdogScope.launch(mainDispatcher) {
            emitTimeoutCallbacksOnMain(outcome)
        }
    }

    private fun emitTimeoutCallbacksOnMain(outcome: CleanupOutcome.TimedOut) {
        if (timeoutCallbacksEmitted.compareAndSet(false, true)) {
            val msg = outcome.exception.message
                ?: "WebRTC native audio cleanup timed out after ${cleanupTimeoutMs} ms"
            onLog(msg)
            onStage(WebRtcStage.CLEANUP_TIMEOUT)
            onError(msg)
        }
    }

    private fun runNativeCleanup(
        capture: WebRtcAudioCapture?,
        backend: WebRtcPeerBackend?,
        pendingInit: CountDownLatch?,
        deadlineNs: Long
    ): CleanupOutcome {
        fun isExpired(): Boolean {
            if (cleanupTimedOut.get()) return true
            if (System.nanoTime() >= deadlineNs) {
                return true
            }
            return false
        }

        if (pendingInit != null) {
            var interrupted = false
            try {
                while (pendingInit.count > 0L) {
                    if (isExpired()) {
                        return CleanupOutcome.TimedOut(
                            WebRtcCleanupTimeoutException(
                                "WebRTC cleanup timed out waiting for native initialization after ${cleanupTimeoutMs} ms"
                            )
                        )
                    }
                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) {
                        return CleanupOutcome.TimedOut(
                            WebRtcCleanupTimeoutException(
                                "WebRTC cleanup timed out waiting for native initialization after ${cleanupTimeoutMs} ms"
                            )
                        )
                    }
                    try {
                        if (pendingInit.await(remainingNs, TimeUnit.NANOSECONDS)) break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        val steps = listOf<Pair<String, () -> Unit>>(
            "PCM release" to { capture?.releaseWithDeadline(deadlineNs, cleanupTimedOut) },
            "Audio track disable" to { backend?.disableAudioTrack() },
            "Peer close" to { backend?.closePeer() },
            "Audio track disposal" to { backend?.disposeTrack() },
            "Audio source disposal" to { backend?.disposeSource() },
            "Audio module release" to { backend?.releaseAudioModule() },
            "Peer disposal" to { backend?.disposePeer() },
            "Factory disposal" to { backend?.disposeFactory() }
        )

        for ((name, action) in steps) {
            if (isExpired()) {
                return CleanupOutcome.TimedOut(
                    WebRtcCleanupTimeoutException(
                        "WebRTC native audio cleanup timed out before $name (${cleanupTimeoutMs} ms budget)"
                    )
                )
            }
            try {
                action()
            } catch (timeout: WebRtcCleanupTimeoutException) {
                return CleanupOutcome.TimedOut(timeout)
            } catch (error: Throwable) {
                if (isExpired()) {
                    return CleanupOutcome.TimedOut(
                        WebRtcCleanupTimeoutException(
                            "WebRTC native audio cleanup timed out during $name (${cleanupTimeoutMs} ms budget)",
                            error
                        )
                    )
                }
                onLog("$name failed: ${error.message}")
                // Stop releasing objects that depend on the unclosed resource.
                return CleanupOutcome.Failed(
                    IllegalStateException("WebRTC cleanup failed at $name: ${error.message}", error)
                )
            }
            if (isExpired()) {
                return CleanupOutcome.TimedOut(
                    WebRtcCleanupTimeoutException(
                        "WebRTC native audio cleanup timed out during $name (${cleanupTimeoutMs} ms budget)"
                    )
                )
            }
        }
        return CleanupOutcome.Completed
    }

    private fun endChannel() {
        if (channelEnded) return
        channelEnded = true
        runCatching { channel.endSession() }.onFailure {
            onLog("Cast session end failed: ${it.message}")
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebRtcAudioSession requires the main thread"
        }
    }

    private data class Event(val generation: Long, val action: suspend () -> Unit)

    private sealed interface CleanupOutcome {
        data object Completed : CleanupOutcome
        data class Failed(val error: IllegalStateException) : CleanupOutcome
        data class TimedOut(val exception: WebRtcCleanupTimeoutException) : CleanupOutcome
    }

    private class RealWebRtcPeerBackend : WebRtcPeerBackend {
        @Volatile private var factory: PeerConnectionFactory? = null
        @Volatile private var peer: PeerConnection? = null
        @Volatile private var audioModule: JavaAudioDeviceModule? = null
        @Volatile private var audioSource: AudioSource? = null
        @Volatile private var audioTrack: AudioTrack? = null

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
            if (!isStillActive()) return
            synchronized(WebRtcAudioSession::class.java) {
                if (!factoryInitialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context)
                            .createInitializationOptions()
                    )
                    factoryInitialized = true
                }
            }
            if (!isStillActive()) return
            val module = JavaAudioDeviceModule.builder(context)
                .setSampleRate(WEBRTC_AUDIO_SAMPLE_RATE)
                .setUseStereoInput(WEBRTC_AUDIO_CHANNELS == 2)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioBufferCallback(capture)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                        onRecordError("WebRTC recorder initialization failed: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                        errorMessage: String
                    ) {
                        onRecordError("WebRTC recorder start failed ($errorCode): $errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String) {
                        onRecordError("WebRTC recorder failed: $errorMessage")
                    }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                        onTrackError("WebRTC audio output initialization failed: $errorMessage")
                    }

                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                        errorMessage: String
                    ) {
                        onTrackError("WebRTC audio output start failed ($errorCode): $errorMessage")
                    }

                    override fun onWebRtcAudioTrackError(errorMessage: String) {
                        onTrackError("WebRTC audio output failed: $errorMessage")
                    }
                })
                .createAudioDeviceModule()
            audioModule = module
            if (!isStillActive()) return
            val options = PeerConnectionFactory.Options().apply {
                networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK or
                    PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                    PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
            }
            val peerFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(module)
                .createPeerConnectionFactory()
            factory = peerFactory
            if (!isStillActive()) return
            val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                networkPreference = PeerConnection.AdapterType.WIFI
            }
            val connection = peerFactory.createPeerConnection(
                configuration,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                    override fun onAddStream(stream: MediaStream) {}
                    override fun onRemoveStream(stream: MediaStream) {}
                    override fun onDataChannel(channel: DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}

                    override fun onIceCandidate(candidate: IceCandidate) {
                        if (isStillActive()) onIceCandidate(candidate)
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        if (isStillActive()) onIceConnectionChange(state)
                    }

                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                        if (isStillActive()) onConnectionChange(state)
                    }
                }
            ) ?: error("Could not create audio PeerConnection")
            peer = connection
            if (!isStillActive()) return
            val constraints = MediaConstraints().apply {
                for (name in listOf(
                    "googEchoCancellation", "googAutoGainControl",
                    "googNoiseSuppression", "googHighpassFilter"
                )) {
                    mandatory.add(MediaConstraints.KeyValuePair(name, "false"))
                }
            }
            val source = peerFactory.createAudioSource(constraints)
            audioSource = source
            val track = peerFactory.createAudioTrack("system-audio", source)
            audioTrack = track
            check(connection.addTrack(track, listOf("system-audio")) != null) {
                "Could not add audio track"
            }
        }

        override suspend fun createOfferAndSetLocalDescription(
            isStillActive: () -> Boolean,
            onLog: (String) -> Unit
        ): String {
            val connection = peer ?: error("PeerConnection is unavailable")
            val created = CompletableDeferred<SessionDescription>()
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
            connection.createOffer(object : EmptySdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (isStillActive()) created.complete(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (isStillActive()) {
                        created.completeExceptionally(
                            IllegalStateException("createOffer failed: $error")
                        )
                    }
                }
            }, constraints)
            val offer = created.await()
            val lines = offer.description.lineSequence().toList()
            check(lines.any { it.startsWith("m=audio ") } && lines.none { it.startsWith("m=video ") }) {
                "Generated OFFER is not audio-only"
            }
            val applied = CompletableDeferred<Unit>()
            connection.setLocalDescription(object : EmptySdpObserver() {
                override fun onSetSuccess() {
                    if (isStillActive()) applied.complete(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (isStillActive()) {
                        applied.completeExceptionally(
                            IllegalStateException("setLocalDescription failed: $error")
                        )
                    }
                }
            }, offer)
            applied.await()
            onLog("Audio-only local OFFER applied")
            return offer.description
        }

        override suspend fun setRemoteAnswer(sdp: String, isStillActive: () -> Boolean) {
            val connection = peer ?: error("PeerConnection is unavailable")
            val applied = CompletableDeferred<Unit>()
            connection.setRemoteDescription(object : EmptySdpObserver() {
                override fun onSetSuccess() {
                    if (isStillActive()) applied.complete(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (isStillActive()) {
                        applied.completeExceptionally(
                            IllegalStateException("setRemoteDescription failed: $error")
                        )
                    }
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            applied.await()
        }

        override fun addIceCandidate(candidate: IceCandidate): Boolean {
            return peer?.addIceCandidate(candidate) == true
        }

        override fun pollStats(
            onStats: (WebRtcStats) -> Unit,
            onFailure: (Exception) -> Unit
        ) {
            val connection = peer ?: return
            try {
                connection.getStats { report ->
                    onStats(extractStats(report))
                }
            } catch (e: Exception) {
                onFailure(e)
            }
        }

        private fun extractStats(report: RTCStatsReport): WebRtcStats {
            val values = report.statsMap.values
            val audio = values.filter {
                it.type == "outbound-rtp" &&
                    (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
            }
            val bytes = audio.sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0L }
            val packets = audio.sumOf { (it.members["packetsSent"] as? Number)?.toLong() ?: 0L }
            val selectedPairId = values.firstNotNullOfOrNull {
                if (it.type == "transport") it.members["selectedCandidatePairId"] as? String else null
            }
            val pair = selectedPairId?.let { report.statsMap[it] }
            val rtt = if (pair?.type == "candidate-pair" && pair.members["state"] == "succeeded") {
                (pair.members["currentRoundTripTime"] as? Number)?.toDouble()?.times(1_000)
            } else null
            return WebRtcStats(bytes, packets, rtt)
        }

        override fun disableAudioTrack() {
            audioTrack?.setEnabled(false)
        }

        override fun closePeer() {
            peer?.close()
        }

        override fun disposeTrack() {
            val track = audioTrack ?: return
            audioTrack = null
            track.dispose()
        }

        override fun disposeSource() {
            val source = audioSource ?: return
            audioSource = null
            source.dispose()
        }

        override fun releaseAudioModule() {
            val module = audioModule ?: return
            audioModule = null
            module.release()
        }

        override fun disposePeer() {
            val connection = peer ?: return
            peer = null
            connection.dispose()
        }

        override fun disposeFactory() {
            val peerFactory = factory ?: return
            factory = null
            peerFactory.dispose()
        }
    }

    private abstract class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        @Volatile private var factoryInitialized = false
        private val CLEANUP_WORKER_ID = AtomicInteger()
    }
}
