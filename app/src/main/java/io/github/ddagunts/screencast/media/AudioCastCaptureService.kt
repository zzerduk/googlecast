package io.github.ddagunts.screencast.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import io.github.ddagunts.screencast.cast.CastDeviceModel
import io.github.ddagunts.screencast.cast.CastSdkSessionController
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.cast.CastV2ChannelClient
import io.github.ddagunts.screencast.webrtc.WebRtcAudioSession
import io.github.ddagunts.screencast.webrtc.WebRtcCleanupTimeoutException
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

object AudioCastSessionCoordinator {
    private val _config = MutableStateFlow(AudioStreamConfig())
    val config: StateFlow<AudioStreamConfig> = _config.asStateFlow()

    private val _telemetry = MutableStateFlow(AudioPipelineTelemetry())
    val telemetry: StateFlow<AudioPipelineTelemetry> = _telemetry.asStateFlow()

    private val _protocolLogs = MutableStateFlow<List<String>>(
        listOf(
            "Ready: WebRTC audio receiver 9098830C; AAC receiver CC1AD845 remains available.",
            "WebRTC requires Android 10+, Google Play services, and capture consent.",
            "The WebRTC audio module starts an internal microphone recorder; only overwritten playback PCM is sent."
        )
    )
    val protocolLogs: StateFlow<List<String>> = _protocolLogs.asStateFlow()

    @Volatile
    var selectedDevice: CastDeviceModel? = null

    @Volatile
    var isCaptureLocked: Boolean = false
        private set

    fun updateConfig(transform: (AudioStreamConfig) -> AudioStreamConfig) {
        if (isCaptureLocked) return
        _config.update(transform)
    }

    fun updateTelemetry(transform: (AudioPipelineTelemetry) -> AudioPipelineTelemetry) {
        _telemetry.update { current ->
            val updated = transform(current)
            if (isCaptureLocked || current.isCaptureLocked || current.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT) {
                isCaptureLocked = true
                updated.copy(
                    isStreaming = false,
                    isSessionActive = false,
                    webRtcStage = WebRtcStage.CLEANUP_TIMEOUT,
                    isCaptureLocked = true,
                    error = current.error ?: updated.error
                        ?: "Native audio cleanup timed out; capture is locked in this process."
                )
            } else {
                updated
            }
        }
    }

    fun markCleanupTimeout(message: String) {
        isCaptureLocked = true
        _telemetry.update { current ->
            current.copy(
                isStreaming = false,
                isSessionActive = false,
                webRtcStage = WebRtcStage.CLEANUP_TIMEOUT,
                isCaptureLocked = true,
                error = message
            )
        }
        appendLog("Audio cast cleanup timed out: $message")
    }

    internal fun resetForTesting() {
        isCaptureLocked = false
        selectedDevice = null
        _config.value = AudioStreamConfig()
        _telemetry.value = AudioPipelineTelemetry()
        _protocolLogs.value = listOf(
            "Ready: WebRTC audio receiver 9098830C; AAC receiver CC1AD845 remains available."
        )
    }

    fun appendLog(message: String) {
        _protocolLogs.update { current ->
            (current + message).takeLast(60)
        }
    }

    fun resolveLocalLanIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
            val fallbackInterfaces = NetworkInterface.getNetworkInterfaces()
            while (fallbackInterfaces.hasMoreElements()) {
                val intf = fallbackInterfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}

class AudioCastCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var castStatusJob: Job? = null
    private var stopJob: Job? = null
    private var activeMode: AudioCastMode? = null
    @Volatile private var generation = 0L
    private var stopping = false
    private var destroyed = false
    private var cleanupTimedOut = false
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var webRtcSession: WebRtcAudioSession? = null
    private var webRtcChannel: CastSignalingChannel? = null
    private var captureEngine: AudioPlaybackCaptureEngine? = null
    private var ktorServer: KtorAudioStreamServer? = null
    private var castClient: CastV2ChannelClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AUDIO_CAST -> {
                if (cleanupTimedOut || AudioCastSessionCoordinator.isCaptureLocked) {
                    return START_NOT_STICKY
                }
                stopCastingSession()
            }
            ACTION_START_AUDIO_CAST -> {
                if (activeMode != null || stopping || cleanupTimedOut || AudioCastSessionCoordinator.isCaptureLocked) {
                    return START_NOT_STICKY
                }
                val mode = intent.getStringExtra(EXTRA_MODE)?.let { value ->
                    AudioCastMode.entries.firstOrNull { it.name == value }
                } ?: AudioCastSessionCoordinator.config.value.mode
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startCastingPipeline(mode, resultCode, resultData)
            }
            else -> {
                if (!cleanupTimedOut && !AudioCastSessionCoordinator.isCaptureLocked) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(mode: AudioCastMode) {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (mode == AudioCastMode.WEBRTC || hasRecordAudioPermission)
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, types)
            } catch (e: SecurityException) {
                if (types != ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    throw e
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCastingPipeline(mode: AudioCastMode, resultCode: Int, resultData: Intent?) {
        if (AudioCastSessionCoordinator.isCaptureLocked) return
        activeMode = mode
        val token = ++generation
        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(
                isSessionActive = true,
                webRtcStage = if (mode == AudioCastMode.WEBRTC) WebRtcStage.CAPTURING else WebRtcStage.IDLE
            )
        }
        try {
            check(resultCode == Activity.RESULT_OK && resultData != null) {
                "Audio capture authorization was denied."
            }
            check(ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
                "RECORD_AUDIO permission is required."
            }
            if (mode == AudioCastMode.WEBRTC) {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "WebRTC playback capture requires Android 10 or later."
                }
                webRtcChannel = CastSdkSessionController.channel
                check(webRtcChannel?.isConnected == true) {
                    "Select a Cast receiver before starting WebRTC audio."
                }
            }
            promoteToForeground(mode)
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjection could not be acquired.")
            mediaProjection = projection
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (generation == token && !stopping && !cleanupTimedOut) {
                        stopCastingSession("MediaProjection authorization was revoked.")
                    }
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            if (mode == AudioCastMode.WEBRTC) {
                startWebRtcPipeline(projection, token)
            } else {
                startAacPipeline(projection, token)
            }
        } catch (e: Exception) {
            stopCastingSession(e.message ?: "Unable to start audio capture.")
        }
    }

    private fun startWebRtcPipeline(projection: MediaProjection, token: Long) {
        val channel = webRtcChannel?.takeIf { it.isConnected }
            ?: error("The Cast session ended before capture started.")
        val session = WebRtcAudioSession(
            context = applicationContext,
            channel = channel,
            onStage = { stage ->
                if (stage == WebRtcStage.CLEANUP_TIMEOUT) {
                    AudioCastSessionCoordinator.markCleanupTimeout(
                        AudioCastSessionCoordinator.telemetry.value.error
                            ?: "WebRTC native audio cleanup timed out; capture is locked in this process."
                    )
                } else if (generation == token && !stopping) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            webRtcStage = stage,
                            isStreaming = stage == WebRtcStage.SENDING,
                            videoPipelineAllocated = false
                        )
                    }
                }
            },
            onStats = { stats ->
                if (generation == token && !stopping) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            totalBytesStreamed = stats.audioBytesSent,
                            audioPacketsSent = stats.audioPacketsSent,
                            rttMs = stats.rttMs
                        )
                    }
                }
            },
            onPcmStats = { stats ->
                if (generation == token && !stopping) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            pcmChunksRead = stats.chunksRead,
                            pcmUnderruns = stats.underruns,
                            pcmDroppedChunks = stats.droppedChunks,
                            pcmRingDepth = stats.ringDepth,
                            audioRecordMinBufferBytes = stats.minBufferBytes,
                            currentRmsDb = stats.rmsDb
                        )
                    }
                }
            },
            onError = { message ->
                if (generation == token && !stopping) {
                    AudioCastSessionCoordinator.updateTelemetry { it.copy(error = message) }
                }
                serviceScope.launch(Dispatchers.Main) {
                    if (generation == token && !stopping) stopCastingSession(message)
                }
            },
            onLog = { message ->
                if (generation == token || stopping) AudioCastSessionCoordinator.appendLog(message)
            }
        )
        webRtcSession = session
        castStatusJob = serviceScope.launch {
            CastSdkSessionController.status.collect { status ->
                if ((!status.isConnected || CastSdkSessionController.channel !== channel) &&
                    generation == token && !stopping
                ) {
                    stopCastingSession(
                        AudioCastSessionCoordinator.telemetry.value.error
                            ?: status.error ?: "The Cast session ended or was replaced."
                    )
                }
            }
        }
        sessionJob = serviceScope.launch {
            try {
                session.start(projection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == token && !stopping) {
                    stopCastingSession(e.message ?: "WebRTC initialization failed.")
                }
            }
        }
    }

    private fun startAacPipeline(projection: MediaProjection, token: Long) {
        val server = KtorAudioStreamServer(
            configProvider = { AudioCastSessionCoordinator.config.value },
            onServerEvent = { msg -> AudioCastSessionCoordinator.appendLog(msg) },
            onTelemetryUpdate = { clients, totalBytes, preBurstFrames ->
                if (generation == token) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            connectedSpeakerClients = clients,
                            totalBytesStreamed = totalBytes,
                            preBurstFramesFlushedLastClient = preBurstFrames
                        )
                    }
                }
            }
        )
        ktorServer = server

        val engine = AudioPlaybackCaptureEngine(
            configProvider = { AudioCastSessionCoordinator.config.value },
            onAdtsPacketEncoded = { adtsPacket ->
                server.emitAdtsFrame(adtsPacket)
            },
            onStatsUpdated = { adtsFrames, silenceFrames, isSilentNow, rmsDb, minBufBytes ->
                if (generation == token) {
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            adtsFramesEmitted = adtsFrames,
                            silenceFramesInjected = silenceFrames,
                            isInjectingSilenceNow = isSilentNow,
                            currentRmsDb = rmsDb,
                            audioRecordMinBufferBytes = minBufBytes,
                            videoPipelineAllocated = false
                        )
                    }
                }
            },
            onEngineLog = { msg -> AudioCastSessionCoordinator.appendLog(msg) },
            context = applicationContext
        )
        captureEngine = engine

        sessionJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val port = server.startServer(preferredPort = 0)
                ensureActive()
                val streamUrl = server.buildStreamUrl(AudioCastSessionCoordinator.resolveLocalLanIpAddress())
                engine.startAudioOnlyCapture(projection)
                ensureActive()
                if (generation != token) return@launch
                AudioCastSessionCoordinator.updateTelemetry {
                    it.copy(isStreaming = true, streamUrl = streamUrl, activeServerPort = port)
                }
                val targetDevice = AudioCastSessionCoordinator.selectedDevice
                if (targetDevice != null) {
                    val client = CastV2ChannelClient(
                        device = targetDevice,
                        onProtocolEvent = { event -> AudioCastSessionCoordinator.appendLog(event) }
                    )
                    castClient = client
                    ensureActive()
                    client.connectAndLoadLiveAudio(streamUrl)
                } else {
                    AudioCastSessionCoordinator.appendLog("AAC stream ready at $streamUrl; no receiver selected.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                serviceScope.launch(Dispatchers.Main) {
                    if (generation == token) stopCastingSession(e.message ?: "AAC initialization failed.")
                }
            }
        }
    }

    private fun stopCastingSession(error: String? = null) {
        if (stopping || cleanupTimedOut) return
        if (activeMode == null) {
            if (error != null) AudioCastSessionCoordinator.updateTelemetry { it.copy(error = error) }
            if (!destroyed && !AudioCastSessionCoordinator.isCaptureLocked) stopSelf()
            return
        }
        stopping = true
        generation++
        val mode = activeMode
        var finalError = error
        val startingJob = sessionJob
        sessionJob = null
        if (mode != AudioCastMode.WEBRTC) startingJob?.cancel()
        castStatusJob?.cancel()
        castStatusJob = null

        // Unregister MediaProjection callback immediately during logical stop so late callbacks are ignored.
        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null

        AudioCastSessionCoordinator.updateTelemetry {
            it.copy(
                isStreaming = false,
                webRtcStage = if (mode == AudioCastMode.WEBRTC) WebRtcStage.STOPPING else it.webRtcStage,
                error = error ?: it.error
            )
        }
        if (error != null) AudioCastSessionCoordinator.appendLog("Audio cast failed: $error")
        stopJob = teardownScope.launch {
            var timedOutCleanup = false
            var nativeCleanupFailed = false
            val session = webRtcSession
            webRtcSession = null
            try {
                try {
                    session?.stop()
                } catch (e: WebRtcCleanupTimeoutException) {
                    timedOutCleanup = true
                    finalError = e.message ?: "WebRTC native audio cleanup timed out."
                } catch (e: Exception) {
                    if (session?.isCleanupTimedOut == true) {
                        timedOutCleanup = true
                        finalError = e.message ?: "WebRTC native audio cleanup timed out."
                    } else {
                        nativeCleanupFailed = true
                        AudioCastSessionCoordinator.appendLog("WebRTC cleanup: ${e.message}")
                        finalError = finalError ?: "WebRTC cleanup failed: ${e.message}"
                        session?.close()
                    }
                }
                if (session?.isCleanupTimedOut == true) {
                    timedOutCleanup = true
                }
                startingJob?.cancel()
                if (!timedOutCleanup) {
                    startingJob?.join()
                }
                webRtcChannel?.endSession()
                webRtcChannel = null
                if (!timedOutCleanup) {
                    withContext(Dispatchers.IO) {
                        runCatching { castClient?.disconnect() }
                        castClient = null
                        runCatching { captureEngine?.release() }.onFailure {
                            nativeCleanupFailed = true
                            finalError = finalError ?: "AAC cleanup failed: ${it.message}"
                        }
                        captureEngine = null
                        runCatching { ktorServer?.stopServer() }.onFailure {
                            finalError = finalError ?: "AAC server cleanup failed: ${it.message}"
                        }
                        ktorServer = null
                    }
                }
            } finally {
                if (timedOutCleanup) {
                    cleanupTimedOut = true
                    activeMode = null
                    stopping = false
                    val timeoutMessage = finalError
                        ?: "WebRTC native audio cleanup timed out; capture is locked in this process."
                    AudioCastSessionCoordinator.markCleanupTimeout(timeoutMessage)
                    if (!destroyed) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(
                            NOTIFICATION_ID,
                            buildForegroundNotification(
                                isCleanupTimedOut = true,
                                timeoutMessage = timeoutMessage
                            )
                        )
                    }
                } else {
                    val projection = mediaProjection
                    mediaProjection = null
                    if (!nativeCleanupFailed && projection != null) {
                        withContext(Dispatchers.IO) {
                            runCatching { projection.stop() }.onFailure {
                                finalError = finalError ?: "MediaProjection cleanup failed: ${it.message}"
                            }
                        }
                    }
                    activeMode = null
                    stopping = false
                    if (!destroyed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    AudioCastSessionCoordinator.updateTelemetry {
                        AudioPipelineTelemetry(
                            webRtcStage = if (finalError != null && mode == AudioCastMode.WEBRTC) {
                                WebRtcStage.ERROR
                            } else {
                                WebRtcStage.IDLE
                            },
                            error = finalError
                        )
                    }
                    AudioCastSessionCoordinator.appendLog(
                        if (finalError == null) "Audio cast session stopped."
                        else "Audio cast stopped with error: $finalError"
                    )
                }
                if (destroyed) teardownScope.cancel()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        stopCastingSession()
        serviceScope.cancel()
        if (stopJob?.isActive != true) teardownScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(
        isCleanupTimedOut: Boolean = false,
        timeoutMessage: String? = null
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(
                if (isCleanupTimedOut) "Audio Cleanup Timed Out (Capture Locked)"
                else getString(R.string.notification_title)
            )
            .setContentText(
                when {
                    isCleanupTimedOut -> timeoutMessage
                        ?: "Native audio cleanup did not finish; restart the app before capturing again."
                    activeMode == AudioCastMode.WEBRTC ->
                        "WebRTC playback capture active; internal microphone recorder in use"
                    else -> getString(R.string.notification_text)
                }
            )
            .setContentIntent(pendingOpen)
            .setOngoing(true)

        if (!isCleanupTimedOut) {
            val stopIntent = Intent(this, AudioCastCaptureService::class.java).apply {
                action = ACTION_STOP_AUDIO_CAST
            }
            val pendingStop = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop_casting),
                pendingStop
            )
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START_AUDIO_CAST = "io.github.ddagunts.screencast.action.START_AUDIO_CAST"
        const val ACTION_STOP_AUDIO_CAST = "io.github.ddagunts.screencast.action.STOP_AUDIO_CAST"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MODE = "extra_mode"

        private const val CHANNEL_ID = "screencast_audio_low_latency"
        private const val NOTIFICATION_ID = 8009
    }
}
