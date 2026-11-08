package io.github.ddagunts.screencast.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.ddagunts.screencast.webrtc.WEBRTC_NAMESPACE
import io.github.ddagunts.screencast.webrtc.WEBRTC_RECEIVER_APP_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Abstraction over a connected [CastSession]'s device volume and mute controls.
 * All calls must execute on the main thread.
 */
interface CastSessionVolumeEndpoint {
    val isConnected: Boolean
    fun getVolume(): Double
    fun setVolume(volume: Double)
    fun isMute(): Boolean
    fun setMute(muted: Boolean)
    fun registerVolumeListener(onVolumeChanged: () -> Unit)
    fun unregisterVolumeListener()
}

private class RealCastSessionVolumeEndpoint(
    private val session: CastSession
) : CastSessionVolumeEndpoint {

    private var registeredListener: Cast.Listener? = null

    override val isConnected: Boolean
        get() = session.isConnected

    override fun getVolume(): Double = session.volume.coerceIn(0.0, 1.0)

    override fun setVolume(volume: Double) {
        session.setVolume(volume.coerceIn(0.0, 1.0))
    }

    override fun isMute(): Boolean = session.isMute

    override fun setMute(muted: Boolean) {
        session.isMute = muted
    }

    override fun registerVolumeListener(onVolumeChanged: () -> Unit) {
        unregisterVolumeListener()
        val listener = object : Cast.Listener() {
            override fun onVolumeChanged() {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    onVolumeChanged()
                } else {
                    Handler(Looper.getMainLooper()).post { onVolumeChanged() }
                }
            }
        }
        registeredListener = listener
        session.addCastListener(listener)
    }

    override fun unregisterVolumeListener() {
        val listener = registeredListener ?: return
        registeredListener = null
        runCatching { session.removeCastListener(listener) }
    }
}

object CastSdkSessionController {
    private val _status = MutableStateFlow(CastSdkSessionStatus())
    val status: StateFlow<CastSdkSessionStatus> = _status.asStateFlow()

    private var sessionManager: SessionManager? = null
    private var currentSession: CastSession? = null
    private var activeChannel: SdkChannel? = null
    private var activeVolumeEndpoint: CastSessionVolumeEndpoint? = null
    private var testSignalingChannel: CastSignalingChannel? = null
    private var initializing = false
    private var sessionGeneration = 0L
    private var log: (String) -> Unit = {}

    val channel: CastSignalingChannel?
        get() {
            requireMainThread()
            testSignalingChannel?.let { return it.takeIf { ch -> ch.isConnected } }
            return activeChannel?.takeIf { it.isConnected }
        }

    fun initialize(context: Context, onLog: (String) -> Unit) {
        requireMainThread()
        log = onLog
        if (sessionManager != null || initializing) return
        val appContext = context.applicationContext
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) {
            initializationFailed("Google Play services unavailable (code=$availability).")
            return
        }

        initializing = true
        _status.value = CastSdkSessionStatus(sessionGeneration = sessionGeneration)
        val executor = Executors.newSingleThreadExecutor()
        try {
            CastContext.getSharedInstance(appContext, executor)
                .addOnSuccessListener { castContext ->
                    try {
                        val manager = castContext.sessionManager
                        sessionManager = manager
                        manager.addSessionManagerListener(listener, CastSession::class.java)
                        initializing = false
                        _status.value = CastSdkSessionStatus(
                            isAvailable = true,
                            sessionGeneration = sessionGeneration
                        )
                        manager.currentCastSession?.let { session ->
                            if (session.isConnected) sessionConnected(session)
                        }
                        log("Official Cast SDK ready for receiver $WEBRTC_RECEIVER_APP_ID.")
                    } catch (error: Exception) {
                        initializationFailed("Cast SDK initialization failed: ${error.message}")
                    }
                }
                .addOnFailureListener { error ->
                    initializationFailed("Cast SDK initialization failed: ${error.message}")
                }
                .addOnCompleteListener { executor.shutdown() }
        } catch (error: Exception) {
            executor.shutdown()
            initializationFailed("Cast SDK initialization failed: ${error.message}")
        }
    }

    /**
     * Adjusts the connected Cast speaker's hardware/device volume (0.0..1.0) on the main thread.
     * Does not unmute the speaker automatically, does not modify phone media volume,
     * and does not terminate active WebRTC casting if a single adjustment fails.
     */
    fun setSpeakerVolume(volumeFraction: Double): Boolean {
        requireMainThread()
        val endpoint = activeVolumeEndpoint
        if (endpoint == null || !endpoint.isConnected || !_status.value.isConnected) {
            _status.value = _status.value.copy(
                isVolumeControlAvailable = false,
                volumeControlError = "音箱未连接，无法调节音箱音量。"
            )
            return false
        }
        val clamped = volumeFraction.coerceIn(0.0, 1.0)
        return try {
            endpoint.setVolume(clamped)
            val actualVol = runCatching { endpoint.getVolume().coerceIn(0.0, 1.0) }.getOrDefault(clamped)
            val actualMute = runCatching { endpoint.isMute() }.getOrDefault(_status.value.isMuted)
            _status.value = _status.value.copy(
                volume = actualVol,
                isMuted = actualMute,
                isVolumeControlAvailable = true,
                volumeControlError = null
            )
            log("Cast speaker volume set to ${(actualVol * 100).toInt()}% (muted=$actualMute).")
            true
        } catch (error: Exception) {
            val errMsg = "音箱音量调节失败：${error.message ?: "未知错误"}"
            _status.value = _status.value.copy(volumeControlError = errMsg)
            log(errMsg)
            false
        }
    }

    /**
     * Toggles the connected Cast speaker's independent mute state on the main thread.
     * Does not modify phone media volume and does not terminate active casting on failure.
     */
    fun setSpeakerMuted(muted: Boolean): Boolean {
        requireMainThread()
        val endpoint = activeVolumeEndpoint
        if (endpoint == null || !endpoint.isConnected || !_status.value.isConnected) {
            _status.value = _status.value.copy(
                isVolumeControlAvailable = false,
                volumeControlError = "音箱未连接，无法切换音箱静音状态。"
            )
            return false
        }
        return try {
            endpoint.setMute(muted)
            val actualMute = runCatching { endpoint.isMute() }.getOrDefault(muted)
            val actualVol = runCatching { endpoint.getVolume().coerceIn(0.0, 1.0) }.getOrDefault(_status.value.volume)
            _status.value = _status.value.copy(
                volume = actualVol,
                isMuted = actualMute,
                isVolumeControlAvailable = true,
                volumeControlError = null
            )
            log("Cast speaker mute set to $actualMute (volume=${(actualVol * 100).toInt()}%).")
            true
        } catch (error: Exception) {
            val errMsg = "音箱静音控制失败：${error.message ?: "未知错误"}"
            _status.value = _status.value.copy(volumeControlError = errMsg)
            log(errMsg)
            false
        }
    }

    fun endSession() {
        requireMainThread()
        val session = currentSession
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        activeChannel = null
        testSignalingChannel = null
        currentSession = null
        _status.value = CastSdkSessionStatus(
            isAvailable = sessionManager != null || _status.value.isAvailable,
            sessionGeneration = sessionGeneration
        )
        if (session != null && sessionManager?.currentCastSession === session) {
            try {
                sessionManager?.endCurrentSession(true)
            } catch (error: Exception) {
                _status.value = _status.value.copy(error = "Ending Cast session failed: ${error.message}")
                log(_status.value.error.orEmpty())
            }
        }
    }

    private fun detachVolumeEndpoint() {
        val endpoint = activeVolumeEndpoint ?: return
        activeVolumeEndpoint = null
        runCatching { endpoint.unregisterVolumeListener() }
    }

    private fun attachVolumeEndpoint(endpoint: CastSessionVolumeEndpoint, expectedGeneration: Long) {
        detachVolumeEndpoint()
        activeVolumeEndpoint = endpoint
        endpoint.registerVolumeListener {
            requireMainThread()
            // Ignore callbacks from stale or disconnected sessions
            if (activeVolumeEndpoint !== endpoint ||
                _status.value.sessionGeneration != expectedGeneration ||
                !_status.value.isConnected ||
                !endpoint.isConnected
            ) {
                return@registerVolumeListener
            }
            val latestVol = runCatching { endpoint.getVolume().coerceIn(0.0, 1.0) }.getOrNull() ?: return@registerVolumeListener
            val latestMute = runCatching { endpoint.isMute() }.getOrNull() ?: return@registerVolumeListener
            _status.value = _status.value.copy(
                volume = latestVol,
                isMuted = latestMute,
                isVolumeControlAvailable = true
            )
        }
    }

    private fun initializationFailed(message: String) {
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        activeChannel = null
        testSignalingChannel = null
        currentSession = null
        sessionManager?.let { manager ->
            runCatching {
                manager.removeSessionManagerListener(listener, CastSession::class.java)
            }.onFailure { log("Removing Cast session listener failed: ${it.message}") }
        }
        sessionManager = null
        initializing = false
        _status.value = CastSdkSessionStatus(
            error = message,
            sessionGeneration = sessionGeneration
        )
        log(message)
    }

    private fun sessionConnecting(session: CastSession) {
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        activeChannel = null
        currentSession = session
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
            isConnecting = true,
            deviceName = session.castDevice?.friendlyName.orEmpty(),
            sessionGeneration = sessionGeneration
        )
    }

    private fun sessionConnected(session: CastSession) {
        if (sessionManager?.currentCastSession !== session) return
        if (!session.isConnected ||
            session.applicationMetadata?.applicationId != WEBRTC_RECEIVER_APP_ID
        ) {
            currentSession = session
            sessionFailed(session, "The expected WebRTC receiver is not running. Reconnect the speaker.")
            return
        }
        if (currentSession === session && activeChannel?.isConnected == true) {
            val vol = runCatching { session.volume.coerceIn(0.0, 1.0) }.getOrDefault(_status.value.volume)
            val muted = runCatching { session.isMute }.getOrDefault(_status.value.isMuted)
            _status.value = _status.value.copy(
                isConnecting = false,
                isConnected = true,
                deviceName = session.castDevice?.friendlyName.orEmpty(),
                volume = vol,
                isMuted = muted,
                isVolumeControlAvailable = true,
                error = null
            )
            return
        }
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        currentSession = session
        activeChannel = SdkChannel(session)
        val gen = ++sessionGeneration
        val endpoint = RealCastSessionVolumeEndpoint(session)
        val initialVolume = runCatching { endpoint.getVolume() }.getOrDefault(0.0)
        val initialMuted = runCatching { endpoint.isMute() }.getOrDefault(false)
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
            isConnected = true,
            deviceName = session.castDevice?.friendlyName.orEmpty(),
            volume = initialVolume,
            isMuted = initialMuted,
            isVolumeControlAvailable = true,
            volumeControlError = null,
            sessionGeneration = gen
        )
        attachVolumeEndpoint(endpoint, gen)
        log("Cast session connected to ${_status.value.deviceName}. Audio has not started.")
    }

    private fun sessionDisconnected(session: CastSession, error: String? = null) {
        if (currentSession !== session) return
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        activeChannel = null
        currentSession = null
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
            isVolumeControlAvailable = false,
            volumeControlError = null,
            error = error ?: _status.value.error,
            sessionGeneration = sessionGeneration
        )
        if (error != null) log(error)
    }

    private fun sessionFailed(session: CastSession, message: String) {
        if (currentSession !== session) return
        sessionDisconnected(session, message)
        if (sessionManager?.currentCastSession === session) {
            try {
                sessionManager?.endCurrentSession(true)
            } catch (error: Exception) {
                log("Ending failed Cast session: ${error.message}")
            }
        }
    }

    internal fun installConnectedEndpointForTesting(
        deviceName: String,
        endpoint: CastSessionVolumeEndpoint,
        signalingChannel: CastSignalingChannel? = null
    ): Long {
        requireMainThread()
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        activeChannel = null
        currentSession = null
        testSignalingChannel = signalingChannel
        val gen = ++sessionGeneration
        val initialVol = runCatching { endpoint.getVolume().coerceIn(0.0, 1.0) }.getOrDefault(0.0)
        val initialMute = runCatching { endpoint.isMute() }.getOrDefault(false)
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
            isConnected = endpoint.isConnected,
            deviceName = deviceName,
            volume = initialVol,
            isMuted = initialMute,
            isVolumeControlAvailable = endpoint.isConnected,
            volumeControlError = null,
            sessionGeneration = gen
        )
        if (endpoint.isConnected) {
            attachVolumeEndpoint(endpoint, gen)
        }
        return gen
    }

    internal fun disconnectEndpointForTesting(error: String? = null) {
        requireMainThread()
        detachVolumeEndpoint()
        activeChannel?.invalidate()
        activeChannel = null
        testSignalingChannel = null
        currentSession = null
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
            isConnected = false,
            isVolumeControlAvailable = false,
            volumeControlError = null,
            error = error,
            sessionGeneration = sessionGeneration
        )
    }

    internal fun resetForTesting() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detachVolumeEndpoint()
        } else {
            activeVolumeEndpoint = null
        }
        activeChannel?.invalidate()
        activeChannel = null
        testSignalingChannel = null
        currentSession = null
        sessionGeneration = 0L
        _status.value = CastSdkSessionStatus()
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            sessionConnecting(session)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            sessionConnected(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            sessionFailed(session, "Cast session start failed (code=$error).")
        }

        override fun onSessionEnding(session: CastSession) {
            sessionDisconnected(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            sessionDisconnected(
                session,
                if (error == 0) null else "Cast session ended (code=$error)."
            )
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            sessionConnecting(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            sessionConnected(session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            sessionFailed(session, "Cast session resume failed (code=$error).")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            sessionFailed(session, "Cast session suspended (code=$reason). Reconnect to retry.")
        }
    }

    private class SdkChannel(private val session: CastSession) : CastSignalingChannel {
        private var valid = true
        private var receiverGeneration = 0L
        private var receiverRegistered = false

        override val isConnected: Boolean
            get() = valid && activeChannel === this && currentSession === session &&
                sessionManager?.currentCastSession === session && session.isConnected

        override fun setMessageReceiver(receiver: (String) -> Unit) {
            requireMainThread()
            check(isConnected) { "No connected WebRTC Cast session." }
            val generation = ++receiverGeneration
            session.setMessageReceivedCallbacks(WEBRTC_NAMESPACE) { _, namespace, message ->
                if (generation == receiverGeneration && isConnected && namespace == WEBRTC_NAMESPACE) {
                    receiver(message)
                }
            }
            receiverRegistered = true
        }

        override fun removeMessageReceiver() {
            requireMainThread()
            receiverGeneration++
            if (!receiverRegistered) return
            receiverRegistered = false
            try {
                session.removeMessageReceivedCallbacks(WEBRTC_NAMESPACE)
            } catch (error: Exception) {
                log("Removing Cast message callback failed: ${error.message}")
            }
        }

        override suspend fun send(message: String) = withContext(Dispatchers.Main.immediate) {
            try {
                withTimeout(10_000L) {
                    check(isConnected) { "No connected WebRTC Cast session." }
                    val result = session.sendMessage(WEBRTC_NAMESPACE, message)
                    suspendCancellableCoroutine<Unit> { continuation ->
                        result.setResultCallback { status ->
                            if (!continuation.isActive) return@setResultCallback
                            if (!isConnected) {
                                continuation.resumeWithException(IOException("Cast session changed during send."))
                            } else if (status.isSuccess) {
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(
                                    IOException("Cast message send failed (code=${status.statusCode}): ${status.statusMessage.orEmpty()}")
                                )
                            }
                        }
                        continuation.invokeOnCancellation { result.cancel() }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("Cast message send timed out after 10 seconds.", error)
            }
        }

        override fun endSession() {
            requireMainThread()
            if (activeChannel === this && currentSession === session) {
                CastSdkSessionController.endSession()
            }
        }

        fun invalidate() {
            valid = false
            removeMessageReceiver()
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Cast SDK calls require the main thread." }
    }
}
