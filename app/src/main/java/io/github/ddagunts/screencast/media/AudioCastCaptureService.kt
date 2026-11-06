package io.github.ddagunts.screencast.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import io.github.ddagunts.screencast.cast.CastDeviceModel
import io.github.ddagunts.screencast.cast.CastV2ChannelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Coordinates the live audio casting state across the Foreground Service and Compose UI.
 */
object AudioCastSessionCoordinator {
    private val _config = MutableStateFlow(AudioStreamConfig())
    val config: StateFlow<AudioStreamConfig> = _config.asStateFlow()

    private val _telemetry = MutableStateFlow(AudioPipelineTelemetry())
    val telemetry: StateFlow<AudioPipelineTelemetry> = _telemetry.asStateFlow()

    private val _protocolLogs = MutableStateFlow<List<String>>(
        listOf(
            "Ready: Default Media Receiver (CC1AD845) Audio-Only Caster initialized.",
            "Discovery filter: 0x01 VIDEO_OUT requirement removed — 1st-Gen Google Home (ca=2052) unlocked.",
            "Pipeline: H.264/VirtualDisplay stripped; Ktor progressive ADTS AAC + Zero-Gap Silence Injector armed."
        )
    )
    val protocolLogs: StateFlow<List<String>> = _protocolLogs.asStateFlow()

    @Volatile
    var selectedDevice: CastDeviceModel? = null

    fun updateConfig(transform: (AudioStreamConfig) -> AudioStreamConfig) {
        _config.update(transform)
    }

    fun updateTelemetry(transform: (AudioPipelineTelemetry) -> AudioPipelineTelemetry) {
        _telemetry.update(transform)
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

/**
 * Foreground Service managing the [MediaProjection] lifecycle, [AudioPlaybackCaptureEngine],
 * [KtorAudioStreamServer], and [CastV2ChannelClient] without allocating any video `VirtualDisplay`
 * or `video/avc` encoder.
 */
class AudioCastCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var captureEngine: AudioPlaybackCaptureEngine? = null
    private var ktorServer: KtorAudioStreamServer? = null
    private var castClient: CastV2ChannelClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AUDIO_CAST -> {
                stopCastingSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_AUDIO_CAST -> {
                promoteToForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startCastingPipeline(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCastingPipeline(resultCode: Int, resultData: Intent?) {
        // 1. Obtain MediaProjection if consent data was provided (required for AudioPlaybackCapture on Android 10+)
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)?.apply {
                    registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            AudioCastSessionCoordinator.appendLog("MediaProjection revoked by system; stopping cast.")
                            stopCastingSession()
                            stopSelf()
                        }
                    }, null)
                }
                AudioCastSessionCoordinator.appendLog(
                    "MediaProjection token acquired for AudioPlaybackCapture (createVirtualDisplay: SKIPPED)"
                )
            } catch (e: Exception) {
                AudioCastSessionCoordinator.appendLog("MediaProjection notice: ${e.message}")
            }
        }

        // 2. Initialize Ktor progressive ADTS AAC server
        val server = KtorAudioStreamServer(
            configProvider = { AudioCastSessionCoordinator.config.value },
            onServerEvent = { msg -> AudioCastSessionCoordinator.appendLog(msg) },
            onTelemetryUpdate = { clients, totalBytes, preBurstFrames ->
                AudioCastSessionCoordinator.updateTelemetry {
                    it.copy(
                        connectedSpeakerClients = clients,
                        totalBytesStreamed = totalBytes,
                        preBurstFramesFlushedLastClient = preBurstFrames
                    )
                }
            }
        )
        ktorServer = server

        // 3. Initialize AudioPlaybackCapture + Zero-Gap Silence Injector + ADTS AAC Encoder
        val engine = AudioPlaybackCaptureEngine(
            configProvider = { AudioCastSessionCoordinator.config.value },
            onAdtsPacketEncoded = { adtsPacket ->
                server.emitAdtsFrame(adtsPacket)
            },
            onStatsUpdated = { adtsFrames, silenceFrames, isSilentNow, rmsDb, minBufBytes ->
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
            },
            onEngineLog = { msg -> AudioCastSessionCoordinator.appendLog(msg) }
        )
        captureEngine = engine

        serviceScope.launch {
            val port = server.startServer(preferredPort = 0)
            val localIp = AudioCastSessionCoordinator.resolveLocalLanIpAddress()
            val streamUrl = server.buildStreamUrl(localIp)

            AudioCastSessionCoordinator.updateTelemetry {
                it.copy(
                    isStreaming = true,
                    streamUrl = streamUrl,
                    activeServerPort = port,
                    videoPipelineAllocated = false
                )
            }

            engine.startAudioOnlyCapture(mediaProjection)

            // 4. Connect to selected 1st-Gen Google Home / Cast device over TLS 8009 and send LIVE LOAD
            val targetDevice = AudioCastSessionCoordinator.selectedDevice
            if (targetDevice != null) {
                val client = CastV2ChannelClient(
                    device = targetDevice,
                    onProtocolEvent = { event -> AudioCastSessionCoordinator.appendLog(event) }
                )
                castClient = client
                client.connectAndLoadLiveAudio(streamUrl)
            } else {
                AudioCastSessionCoordinator.appendLog(
                    "Live ADTS AAC stream ready at $streamUrl (Select a Google Home speaker to dispatch CastV2 LOAD)"
                )
            }
        }
    }

    private fun stopCastingSession() {
        castClient?.disconnect()
        castClient = null

        captureEngine?.release()
        captureEngine = null

        ktorServer?.stopServer()
        ktorServer = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(isStreaming = false)
        }
        AudioCastSessionCoordinator.appendLog("Audio cast session stopped cleanly.")
    }

    override fun onDestroy() {
        stopCastingSession()
        serviceScope.cancel()
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

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioCastCaptureService::class.java).apply {
            action = ACTION_STOP_AUDIO_CAST
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop_casting),
                pendingStop
            )
            .build()
    }

    companion object {
        const val ACTION_START_AUDIO_CAST = "io.github.ddagunts.screencast.action.START_AUDIO_CAST"
        const val ACTION_STOP_AUDIO_CAST = "io.github.ddagunts.screencast.action.STOP_AUDIO_CAST"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "screencast_audio_low_latency"
        private const val NOTIFICATION_ID = 8009
    }
}
