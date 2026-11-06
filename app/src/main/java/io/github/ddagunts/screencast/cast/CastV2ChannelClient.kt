package io.github.ddagunts.screencast.cast

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Pure Kotlin Cast V2 TLS control channel over port 8009.
 *
 * Phase 4 Optimizations for 1st-Gen Google Home (`CC1AD845`):
 * 1. Sends `LOAD` with `contentId = "http://<phone-ip>:<port>/c/<token>/stream.aac"`,
 *    `streamType = "LIVE"`, and `contentType = "audio/aac"`.
 * 2. Strictly uses Default Media Receiver (`CC1AD845`) with zero custom receiver registration.
 * 3. Disables the legacy 15-second HLS `pause -> seek -> play` sync loop in Audio-Only Live
 *    Stream mode so the progressive `audio/aac` stream never stutters or resets.
 */
class CastV2ChannelClient(
    private val device: CastDeviceModel,
    private val onProtocolEvent: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIdCounter = AtomicInteger(1)

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var transportId: String? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var pendingStreamUrl: String? = null

    @Volatile
    private var loadDispatchedForCurrentSession: Boolean = false

    /**
     * Phase 4 Requirement:
     * Background HLS `pause -> seek -> play` sync loop is strictly disabled in Audio-Only mode.
     */
    val isHlsSyncLoopEnabled: Boolean = false

    private val _sessionState = MutableStateFlow(CastSessionState.IDLE)
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    enum class CastSessionState {
        IDLE,
        CONNECTING_TLS,
        LAUNCHING_CC1AD845,
        LOADING_LIVE_AAC,
        STREAMING_LIVE_AUDIO,
        ERROR
    }

    /**
     * Connects to the Google Home over TLS port 8009, launches the built-in Default Media Receiver
     * (`CC1AD845`), and dispatches the `LOAD` command for the progressive ADTS AAC stream.
     */
    suspend fun connectAndLoadLiveAudio(streamUrl: String) = withContext(Dispatchers.IO) {
        pendingStreamUrl = streamUrl
        loadDispatchedForCurrentSession = false
        _sessionState.value = CastSessionState.CONNECTING_TLS
        onProtocolEvent("Connecting TLS to ${device.friendlyName} (${device.hostAddress}:${device.port})...")

        try {
            val sslContext = createCastSslContext()
            val rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.connect(InetSocketAddress(device.hostAddress, device.port), SOCKET_CONNECT_TIMEOUT_MS)
            rawSocket.startHandshake()

            socket = rawSocket
            outputStream = DataOutputStream(rawSocket.outputStream)
            inputStream = DataInputStream(rawSocket.inputStream)

            startReaderLoop()
            startHeartbeatLoop()

            // 1. Send virtual CONNECT to receiver-0
            sendConnect(CastV2Message.RECEIVER_DESTINATION_ID)
            onProtocolEvent("Sent CONNECT to receiver-0")

            // 2. Launch Default Media Receiver (CC1AD845)
            _sessionState.value = CastSessionState.LAUNCHING_CC1AD845
            val launchReqId = requestIdCounter.getAndIncrement()
            val launchJson = JSONObject()
                .put("type", "LAUNCH")
                .put("appId", CastDeviceModel.DEFAULT_MEDIA_RECEIVER_APP_ID)
                .put("requestId", launchReqId)
                .toString()

            sendMessage(
                destinationId = CastV2Message.RECEIVER_DESTINATION_ID,
                namespace = CastV2Message.NS_RECEIVER,
                payloadUtf8 = launchJson
            )
            onProtocolEvent("Sent LAUNCH appId=${CastDeviceModel.DEFAULT_MEDIA_RECEIVER_APP_ID} (reqId=$launchReqId)")
        } catch (e: Exception) {
            Log.e(TAG, "CastV2 TLS connection error", e)
            _sessionState.value = CastSessionState.ERROR
            onProtocolEvent("CastV2 connection notice: ${e.message ?: "Speaker unreachable on local LAN"} (HTTP ADTS stream remains active)")
        }
    }

    /**
     * Constructs the Cast V2 `LOAD` command JSON payload for `urn:x-cast:com.google.cast.media`
     * adhering strictly to Phase 4 requirements (`streamType = "LIVE"`, `contentType = "audio/aac"`).
     */
    fun buildLiveAudioLoadPayload(
        streamUrl: String,
        castSessionId: String,
        requestId: Int = requestIdCounter.getAndIncrement()
    ): String {
        val metadata = JSONObject()
            .put("metadataType", 3) // MusicTrackMediaMetadata
            .put("title", "ScreenCast Ultra-Low-Latency Audio")
            .put("subtitle", "ADTS AAC Live Stream • Zero-Gap Silence Active")

        val mediaObject = JSONObject()
            .put("contentId", streamUrl)
            .put("streamType", "LIVE")
            .put("contentType", "audio/aac")
            .put("metadata", metadata)

        return JSONObject()
            .put("type", "LOAD")
            .put("requestId", requestId)
            .put("sessionId", castSessionId)
            .put("autoplay", true)
            .put("media", mediaObject)
            .toString()
    }

    private fun startReaderLoop() {
        readerJob?.cancel()
        readerJob = scope.launch {
            val input = inputStream ?: return@launch
            try {
                while (isActive) {
                    val msg = CastV2ProtoWire.readDelimitedMessage(input) ?: break
                    handleIncomingMessage(msg)
                }
            } catch (e: Exception) {
                if (isActive) {
                    onProtocolEvent("CastV2 socket closed: ${e.message}")
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    sendMessage(
                        destinationId = CastV2Message.RECEIVER_DESTINATION_ID,
                        namespace = CastV2Message.NS_HEARTBEAT,
                        payloadUtf8 = """{"type":"PING"}"""
                    )
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleIncomingMessage(msg: CastV2Message) {
        when (msg.namespace) {
            CastV2Message.NS_HEARTBEAT -> {
                if (msg.payloadUtf8.contains("PING")) {
                    sendMessage(
                        destinationId = msg.sourceId,
                        namespace = CastV2Message.NS_HEARTBEAT,
                        payloadUtf8 = """{"type":"PONG"}"""
                    )
                }
            }
            CastV2Message.NS_RECEIVER -> {
                handleReceiverNamespaceMessage(msg.payloadUtf8)
            }
            CastV2Message.NS_MEDIA -> {
                handleMediaNamespaceMessage(msg.payloadUtf8)
            }
        }
    }

    private fun handleReceiverNamespaceMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            if (json.optString("type") != "RECEIVER_STATUS") return
            val status = json.optJSONObject("status") ?: return
            val apps: JSONArray = status.optJSONArray("applications") ?: return

            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                val appId = app.optString("appId")
                if (appId == CastDeviceModel.DEFAULT_MEDIA_RECEIVER_APP_ID) {
                    val newTransportId = app.optString("transportId")
                    val newSessionId = app.optString("sessionId")
                    if (newTransportId.isNotBlank() && newSessionId.isNotBlank()) {
                        transportId = newTransportId
                        sessionId = newSessionId
                        if (!loadDispatchedForCurrentSession) {
                            loadDispatchedForCurrentSession = true
                            val url = pendingStreamUrl ?: return
                            dispatchLiveLoadToTransport(newTransportId, newSessionId, url)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse RECEIVER_STATUS", e)
        }
    }

    private fun dispatchLiveLoadToTransport(
        targetTransportId: String,
        targetSessionId: String,
        streamUrl: String
    ) {
        _sessionState.value = CastSessionState.LOADING_LIVE_AAC
        // 1. Connect virtual channel to the launched CC1AD845 transportId
        sendConnect(targetTransportId)
        onProtocolEvent("Connected to CC1AD845 transportId=$targetTransportId")

        // 2. Send LOAD with LIVE audio/aac stream
        val reqId = requestIdCounter.getAndIncrement()
        val loadPayload = buildLiveAudioLoadPayload(
            streamUrl = streamUrl,
            castSessionId = targetSessionId,
            requestId = reqId
        )
        sendMessage(
            destinationId = targetTransportId,
            namespace = CastV2Message.NS_MEDIA,
            payloadUtf8 = loadPayload
        )
        onProtocolEvent("Dispatched LOAD (LIVE audio/aac) -> $streamUrl (HLS sync loop: DISABLED)")
        _sessionState.value = CastSessionState.STREAMING_LIVE_AUDIO
    }

    private fun handleMediaNamespaceMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val type = json.optString("type")
            if (type == "MEDIA_STATUS") {
                val statuses = json.optJSONArray("status")
                if (statuses != null && statuses.length() > 0) {
                    val first = statuses.getJSONObject(0)
                    val playerState = first.optString("playerState", "PLAYING")
                    onProtocolEvent("Speaker MEDIA_STATUS: playerState=$playerState (Zero-Gap continuous stream)")
                    if (playerState == "PLAYING" || playerState == "BUFFERING") {
                        _sessionState.value = CastSessionState.STREAMING_LIVE_AUDIO
                    }
                }
            } else if (type == "LOAD_FAILED" || type == "LOAD_CANCELLED") {
                _sessionState.value = CastSessionState.ERROR
                onProtocolEvent("Speaker reported $type: $payload")
            }
        } catch (_: Exception) {
        }
    }

    private fun sendConnect(destinationId: String) {
        val connectPayload = JSONObject()
            .put("type", "CONNECT")
            .put("origin", JSONObject())
            .put("userAgent", "ScreenCastAudio/2.0 (Android; UltraLowLatency)")
            .toString()
        sendMessage(
            destinationId = destinationId,
            namespace = CastV2Message.NS_CONNECTION,
            payloadUtf8 = connectPayload
        )
    }

    private fun sendMessage(destinationId: String, namespace: String, payloadUtf8: String) {
        val out = outputStream ?: return
        val message = CastV2Message(
            sourceId = CastV2Message.DEFAULT_SOURCE_ID,
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payloadUtf8
        )
        CastV2ProtoWire.writeDelimitedMessage(out, message)
    }

    fun disconnect() {
        try {
            val currentTransport = transportId
            val currentSession = sessionId
            if (currentTransport != null && currentSession != null) {
                val stopPayload = JSONObject()
                    .put("type", "STOP")
                    .put("requestId", requestIdCounter.getAndIncrement())
                    .put("sessionId", currentSession)
                    .toString()
                sendMessage(
                    destinationId = CastV2Message.RECEIVER_DESTINATION_ID,
                    namespace = CastV2Message.NS_RECEIVER,
                    payloadUtf8 = stopPayload
                )
            }
            sendMessage(
                destinationId = CastV2Message.RECEIVER_DESTINATION_ID,
                namespace = CastV2Message.NS_CONNECTION,
                payloadUtf8 = """{"type":"CLOSE"}"""
            )
        } catch (_: Exception) {
        } finally {
            heartbeatJob?.cancel()
            readerJob?.cancel()
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            outputStream = null
            inputStream = null
            _sessionState.value = CastSessionState.IDLE
            scope.cancel()
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createCastSslContext(): SSLContext {
        // Chromecast / Google Home devices use self-signed device certificates on local LAN port 8009.
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
    }

    companion object {
        private const val TAG = "CastV2ChannelClient"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3500
        private const val HEARTBEAT_INTERVAL_MS = 5000L
    }
}
