package io.github.ddagunts.screencast.cast

import android.content.Context
import android.os.Looper
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

object CastSdkSessionController {
    private val _status = MutableStateFlow(CastSdkSessionStatus())
    val status: StateFlow<CastSdkSessionStatus> = _status.asStateFlow()

    private var sessionManager: SessionManager? = null
    private var currentSession: CastSession? = null
    private var activeChannel: SdkChannel? = null
    private var initializing = false
    private var sessionGeneration = 0L
    private var log: (String) -> Unit = {}

    val channel: CastSignalingChannel?
        get() {
            requireMainThread()
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

    fun endSession() {
        requireMainThread()
        val session = currentSession
        activeChannel?.invalidate()
        activeChannel = null
        currentSession = null
        _status.value = CastSdkSessionStatus(
            isAvailable = sessionManager != null,
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

    private fun initializationFailed(message: String) {
        activeChannel?.invalidate()
        activeChannel = null
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
            _status.value = _status.value.copy(
                isConnecting = false,
                isConnected = true,
                deviceName = session.castDevice?.friendlyName.orEmpty(),
                error = null
            )
            return
        }
        activeChannel?.invalidate()
        currentSession = session
        activeChannel = SdkChannel(session)
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
            isConnected = true,
            deviceName = session.castDevice?.friendlyName.orEmpty(),
            sessionGeneration = ++sessionGeneration
        )
        log("Cast session connected to ${_status.value.deviceName}. Audio has not started.")
    }

    private fun sessionDisconnected(session: CastSession, error: String? = null) {
        if (currentSession !== session) return
        activeChannel?.invalidate()
        activeChannel = null
        currentSession = null
        _status.value = CastSdkSessionStatus(
            isAvailable = true,
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
