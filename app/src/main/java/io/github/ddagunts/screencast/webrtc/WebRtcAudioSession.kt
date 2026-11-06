package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Looper
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

class WebRtcAudioSession(
    context: Context,
    private val channel: CastSignalingChannel,
    private val onStage: (WebRtcStage) -> Unit,
    private val onStats: (WebRtcStats) -> Unit,
    private val onPcmStats: (PcmCaptureStats) -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val context = context.applicationContext
    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val offerSent = CompletableDeferred<Unit>()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioCapture: WebRtcAudioCapture? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var signaling: WebRtcSignalingState? = null
    private var cleanup: Deferred<Unit>? = null
    private var connected = false
    private var previousAudioBytes = 0L
    private var statsPending = false
    private var started = false
    private var closed = false
    @Volatile private var closing = false
    @Volatile private var generation = 0L

    suspend fun start(projection: MediaProjection) {
        checkMainThread()
        check(!started && !closed) { "Create a new WebRtcAudioSession for each attempt" }
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
            buildPeer(projection, epoch)
            signaling = WebRtcSignalingState(
                channel = channel,
                applyAnswer = { setRemoteAnswer(it, epoch) },
                applyCandidate = { candidate ->
                    check(peer?.addIceCandidate(candidate) == true) { "Remote ICE candidate rejected" }
                },
                onLog = onLog
            )
            channel.setMessageReceiver { message ->
                enqueue(epoch) { signaling?.onMessage(message) }
            }
            scope.launch {
                delay(30_000)
                if (!connected) fail("WebRTC did not connect within 30 seconds")
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
            if (!closing) close()
            throw e
        } catch (e: Throwable) {
            val failure = if (e is Exception) e else IllegalStateException("WebRTC native startup failed: ${e.message}", e)
            if (!closing) fail(failure.message ?: "WebRTC startup failed")
            throw failure
        }
    }

    suspend fun stop() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (closed) {
            cleanup?.await()
            return@withContext
        }
        onStage(WebRtcStage.STOPPING)
        val released = beginCleanup()
        try {
            withTimeoutOrNull(1_000) {
                if (channel.isConnected) channel.send(JSONObject().put("type", "BYE").toString())
            } ?: onLog("BYE send timed out")
        } catch (e: Exception) {
            onLog("BYE could not be sent: ${e.message}")
        } finally {
            endChannel()
            released.await()
        }
    }

    // Emergency close invalidates callbacks immediately; stop() also waits for resource release.
    fun close() {
        checkMainThread()
        if (closed) return
        beginCleanup()
        endChannel()
    }

    private fun buildPeer(projection: MediaProjection, epoch: Long) {
        synchronized(WebRtcAudioSession::class.java) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        val capture = WebRtcAudioCapture(
            onError = { message -> reportFailure(epoch, message) },
            onStats = { stats -> enqueue(epoch) { onPcmStats(stats) } },
            onLog = onLog
        )
        audioCapture = capture
        capture.attachProjection(projection)
        val module = JavaAudioDeviceModule.builder(context)
            .setSampleRate(WEBRTC_AUDIO_SAMPLE_RATE)
            .setUseStereoInput(WEBRTC_AUDIO_CHANNELS == 2)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback(capture)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    reportFailure(epoch, "WebRTC recorder initialization failed: $errorMessage")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String
                ) {
                    reportFailure(epoch, "WebRTC recorder start failed ($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    reportFailure(epoch, "WebRTC recorder failed: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                    reportFailure(epoch, "WebRTC audio output initialization failed: $errorMessage")
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    errorMessage: String
                ) {
                    reportFailure(epoch, "WebRTC audio output start failed ($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioTrackError(errorMessage: String) {
                    reportFailure(epoch, "WebRTC audio output failed: $errorMessage")
                }
            })
            .createAudioDeviceModule()
        audioModule = module
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
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            networkPreference = PeerConnection.AdapterType.WIFI
        }
        val connection = peerFactory.createPeerConnection(configuration, observer(epoch))
            ?: error("Could not create audio PeerConnection")
        peer = connection
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
        check(connection.addTrack(track, listOf("system-audio")) != null) { "Could not add audio track" }
    }

    private suspend fun createOffer(epoch: Long): String {
        val connection = peer ?: error("PeerConnection is unavailable")
        val created = CompletableDeferred<SessionDescription>()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        connection.createOffer(object : EmptySdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (generation == epoch && !closing) created.complete(description)
            }

            override fun onCreateFailure(error: String?) {
                if (generation == epoch && !closing) {
                    created.completeExceptionally(IllegalStateException("createOffer failed: $error"))
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
                if (generation == epoch && !closing) applied.complete(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (generation == epoch && !closing) {
                    applied.completeExceptionally(IllegalStateException("setLocalDescription failed: $error"))
                }
            }
        }, offer)
        applied.await()
        onLog("Audio-only local OFFER applied")
        return offer.description
    }

    private suspend fun setRemoteAnswer(sdp: String, epoch: Long) {
        val connection = peer ?: error("PeerConnection is unavailable")
        val applied = CompletableDeferred<Unit>()
        connection.setRemoteDescription(object : EmptySdpObserver() {
            override fun onSetSuccess() {
                if (generation == epoch && !closing) applied.complete(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (generation == epoch && !closing) {
                    applied.completeExceptionally(IllegalStateException("setRemoteDescription failed: $error"))
                }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        applied.await()
    }

    private fun startStatsPolling(epoch: Long) {
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (statsPending) continue
                val connection = peer ?: return@launch
                statsPending = true
                try {
                    connection.getStats { report ->
                        enqueue(epoch) {
                            statsPending = false
                            publishStats(report)
                        }
                    }
                } catch (e: Exception) {
                    fail("Could not query WebRTC audio statistics: ${e.message}")
                }
            }
        }
    }

    private fun publishStats(report: RTCStatsReport) {
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
        onStats(WebRtcStats(bytes, packets, rtt))
        if (connected && bytes > previousAudioBytes) onStage(WebRtcStage.SENDING)
        previousAudioBytes = bytes
    }

    private fun observer(epoch: Long) = object : PeerConnection.Observer {
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
            enqueue(epoch) { signaling?.onLocalCandidate(candidate) }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.DISCONNECTED
            ) {
                reportFailure(epoch, "WebRTC ICE connection lost: $state")
                return
            }
            enqueue(epoch) {
                onLog("ICE state: $state")
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.FAILED ||
                state == PeerConnection.PeerConnectionState.DISCONNECTED
            ) {
                reportFailure(epoch, "WebRTC connection lost: $state")
                return
            }
            enqueue(epoch) {
                onLog("Peer state: $state")
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    connected = true
                    onStage(WebRtcStage.CONNECTED)
                }
            }
        }
    }

    private fun enqueue(epoch: Long, action: suspend () -> Unit) {
        if (!closing && generation == epoch) events.trySend(Event(epoch, action))
    }

    private fun reportFailure(epoch: Long, message: String) {
        scope.launch(Dispatchers.Main) {
            if (!closing && generation == epoch) fail(message)
        }
    }

    private fun fail(message: String) {
        if (closing) return
        offerSent.completeExceptionally(IllegalStateException(message))
        onStage(WebRtcStage.ERROR)
        onError(message)
        close()
    }

    private fun beginCleanup(): Deferred<Unit> {
        cleanup?.let { return it }
        closing = true
        generation++
        connected = false
        signaling?.close()
        signaling = null
        sessionJob.cancel()
        events.close()
        offerSent.cancel()
        runCatching { channel.removeMessageReceiver() }.onFailure {
            onLog("Receiver callback removal failed: ${it.message}")
        }
        runCatching { audioTrack?.setEnabled(false) }
        runCatching { peer?.close() }.onFailure { onLog("Peer close failed: ${it.message}") }
        val capture = audioCapture
        val track = audioTrack
        val source = audioSource
        val module = audioModule
        val connection = peer
        val peerFactory = factory
        audioCapture = null
        audioTrack = null
        audioSource = null
        audioModule = null
        peer = null
        factory = null
        val cleanupJob = Job()
        val released = CoroutineScope(cleanupJob + Dispatchers.IO).async {
            var failure: Throwable? = null
            fun release(name: String, action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    onLog("$name failed: ${error.message}")
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
            release("PCM release") { capture?.release() }
            release("Audio track disposal") { track?.dispose() }
            release("Audio source disposal") { source?.dispose() }
            release("Audio module release") { module?.release() }
            release("Peer disposal") { connection?.dispose() }
            release("Factory disposal") { peerFactory?.dispose() }
            failure?.let { throw IllegalStateException("WebRTC cleanup failed: ${it.message}", it) }
        }
        released.invokeOnCompletion { cleanupJob.complete() }
        cleanup = released
        return released
    }

    private fun endChannel() {
        if (closed) return
        closed = true
        runCatching { channel.endSession() }.onFailure {
            onLog("Cast session end failed: ${it.message}")
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebRtcAudioSession requires the main thread" }
    }

    private data class Event(val generation: Long, val action: suspend () -> Unit)

    private abstract class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        @Volatile private var factoryInitialized = false
    }
}
