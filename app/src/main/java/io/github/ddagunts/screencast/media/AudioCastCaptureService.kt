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
import io.github.ddagunts.screencast.cast.CastSdkSessionController
import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import io.github.ddagunts.screencast.target.CaptureTargetRepository
import io.github.ddagunts.screencast.webrtc.WebRtcAudioSession
import io.github.ddagunts.screencast.webrtc.WebRtcCleanupTimeoutException
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AudioCastSessionCoordinator {
    private val _config = MutableStateFlow(AudioStreamConfig())
    val config: StateFlow<AudioStreamConfig> = _config.asStateFlow()

    private val _telemetry = MutableStateFlow(AudioPipelineTelemetry())
    val telemetry: StateFlow<AudioPipelineTelemetry> = _telemetry.asStateFlow()

    private val _protocolLogs = MutableStateFlow<List<String>>(
        listOf(
            "Ready: WebRTC Per-App UID Whitelist + Shizuku VolumeShaper Session Mute active.",
            "WebRTC Cast Receiver: 9098830C (48 kHz / Stereo / PCM16).",
            "Add and select at least one target app instance (Primary / Work / Clone) before starting."
        )
    )
    val protocolLogs: StateFlow<List<String>> = _protocolLogs.asStateFlow()

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
        // Immediate non-blocking mute restoration even when native audio HAL cleanup times out
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
        CaptureTargetRepository.clearFrozenTargetsSnapshot()
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
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
        CaptureTargetRepository.resetForTesting()
        isCaptureLocked = false
        _config.value = AudioStreamConfig()
        _telemetry.value = AudioPipelineTelemetry()
        _protocolLogs.value = listOf(
            "Ready: WebRTC audio receiver 9098830C."
        )
    }

    fun appendLog(message: String) {
        _protocolLogs.update { current ->
            (current + message).takeLast(60)
        }
    }
}

class AudioCastCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var castStatusJob: Job? = null
    private var stopJob: Job? = null
    private var sessionRunning = false
    @Volatile private var generation = 0L
    private var stopping = false
    private var destroyed = false
    private var cleanupTimedOut = false
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var webRtcSession: WebRtcAudioSession? = null
    private var webRtcChannel: CastSignalingChannel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AUDIO_CAST -> {
                ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
                CaptureTargetRepository.clearFrozenTargetsSnapshot()
                if (cleanupTimedOut || AudioCastSessionCoordinator.isCaptureLocked) {
                    return START_NOT_STICKY
                }
                stopCastingSession()
            }
            ACTION_START_AUDIO_CAST -> {
                if (sessionRunning || stopping || cleanupTimedOut || AudioCastSessionCoordinator.isCaptureLocked) {
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startCastingPipeline(resultCode, resultData)
            }
            else -> {
                ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
                CaptureTargetRepository.clearFrozenTargetsSnapshot()
                if (!cleanupTimedOut && !AudioCastSessionCoordinator.isCaptureLocked) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    private fun startCastingPipeline(resultCode: Int, resultData: Intent?) {
        if (AudioCastSessionCoordinator.isCaptureLocked) return
        sessionRunning = true
        val token = ++generation
        // Always satisfy Android 14+ foreground service start contract before any validation can throw
        runCatching { promoteToForeground() }

        val frozenTargets = CaptureTargetRepository.frozenTargets.value
            ?: CaptureTargetRepository.selectedTargets.value

        AudioCastSessionCoordinator.updateTelemetry {
            AudioPipelineTelemetry(
                isSessionActive = true,
                activeTargets = frozenTargets,
                webRtcStage = WebRtcStage.CAPTURING
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
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "WebRTC playback capture requires Android 10 or later."
            }
            webRtcChannel = CastSdkSessionController.channel
            check(webRtcChannel?.isConnected == true) {
                "Select a Cast receiver before starting WebRTC audio."
            }

            // Re-resolve full UIDs from the frozen target snapshot immediately before starting capture
            val resolution = CaptureTargetRepository.resolveAndValidateFrozenTargets(applicationContext)
            if (!resolution.isValid || resolution.resolvedUids.isEmpty()) {
                error(resolution.failureReason ?: "目标应用完整 UID 解析与共享 UID 校验未通过。")
            }
            val targetUids = resolution.resolvedUids
            AudioCastSessionCoordinator.appendLog(
                "Resolved target UIDs for capture & session mute: ${targetUids.sorted()} " +
                    "from ${frozenTargets.map { it.toStorageKey() }}"
            )

            // Acquire 3-second clock-time session mute lease and confirm initial active sessions via getMutedBy()
            val leaseOutcome = ShizukuSessionMuteController.startSessionMuteLease(
                targetUids = targetUids,
                onFatalMuteError = { muteError ->
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        if (generation == token && !stopping) {
                            stopCastingSession(muteError)
                        }
                    }
                }
            )
            if (!leaseOutcome.success) {
                error(leaseOutcome.error ?: "初始播放器会话静音确认失败，已恢复会话并终止投放。")
            }

            AudioCastSessionCoordinator.updateTelemetry {
                it.copy(
                    activeTargets = frozenTargets,
                    activeTargetUids = targetUids,
                    isInitialMuteConfirmed = true
                )
            }
            AudioCastSessionCoordinator.appendLog(
                "Initial session mute confirmed via getMutedBy() " +
                    "(active=${leaseOutcome.activeTargetPlayerCount}, " +
                    "muted=${leaseOutcome.confirmedMutedPlayerCount}, " +
                    "mask=0x${leaseOutcome.lastMixerMutedByMask.toString(16)})"
            )

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
            startWebRtcPipeline(projection, targetUids, token)
        } catch (e: Exception) {
            stopCastingSession(e.message ?: "Unable to start audio capture.")
        }
    }

    private fun startWebRtcPipeline(
        projection: MediaProjection,
        targetUids: Set<Int>,
        token: Long
    ) {
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
                    val muteReady = AudioCastSessionCoordinator.telemetry.value.isInitialMuteConfirmed
                    AudioCastSessionCoordinator.updateTelemetry {
                        it.copy(
                            webRtcStage = stage,
                            isStreaming = muteReady && stage == WebRtcStage.SENDING,
                            isHardwareCaptureActive = stage == WebRtcStage.CAPTURING ||
                                stage == WebRtcStage.NEGOTIATING ||
                                stage == WebRtcStage.CONNECTED ||
                                stage == WebRtcStage.SENDING
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
                    ShizukuSessionMuteController.recordAudioRmsForGateComparison(stats.rmsDb)
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
                session.start(projection, targetUids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == token && !stopping) {
                    stopCastingSession(e.message ?: "WebRTC initialization failed.")
                }
            }
        }
    }

    private fun stopCastingSession(error: String? = null) {
        // CRITICAL: Immediately cancel lease renewal and restore all muted sessions BEFORE waiting on native HAL
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately(failureReason = error)
        CaptureTargetRepository.clearFrozenTargetsSnapshot()

        if (stopping || cleanupTimedOut) return
        if (!sessionRunning) {
            if (error != null) AudioCastSessionCoordinator.updateTelemetry { it.copy(error = error) }
            if (!destroyed && !AudioCastSessionCoordinator.isCaptureLocked) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
            return
        }
        stopping = true
        generation++
        var finalError = error
        val startingJob = sessionJob
        sessionJob = null
        castStatusJob?.cancel()
        castStatusJob = null

        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null

        AudioCastSessionCoordinator.updateTelemetry {
            it.copy(
                isStreaming = false,
                webRtcStage = WebRtcStage.STOPPING,
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
            } finally {
                if (timedOutCleanup) {
                    cleanupTimedOut = true
                    sessionRunning = false
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
                    sessionRunning = false
                    stopping = false
                    if (!destroyed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    AudioCastSessionCoordinator.updateTelemetry {
                        AudioPipelineTelemetry(
                            webRtcStage = if (finalError != null) WebRtcStage.ERROR else WebRtcStage.IDLE,
                            error = finalError
                        )
                    }
                    AudioCastSessionCoordinator.appendLog(
                        if (finalError == null) "Audio cast session stopped and session mute envelope removed."
                        else "Audio cast stopped with error ($finalError); session mute envelope removed."
                    )
                }
                if (destroyed) teardownScope.cancel()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()
        CaptureTargetRepository.clearFrozenTargetsSnapshot()
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
                if (isCleanupTimedOut) {
                    timeoutMessage
                        ?: "Native audio cleanup did not finish; restart the app before capturing again."
                } else {
                    "WebRTC per-UID capture + Shizuku session mute active"
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

        private const val CHANNEL_ID = "screencast_audio_low_latency"
        private const val NOTIFICATION_ID = 8009
    }
}
