package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.example.ui.theme.MyApplicationTheme
import io.github.ddagunts.screencast.ui.ScreenCastAudioScreen
import io.github.ddagunts.screencast.ui.ScreenCastViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val viewModel: ScreenCastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val devices by viewModel.devices.collectAsStateWithLifecycle()
                val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
                val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
                val config by viewModel.config.collectAsStateWithLifecycle()
                val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                val logs by viewModel.combinedLogs.collectAsStateWithLifecycle()
                val castStatus by viewModel.castStatus.collectAsStateWithLifecycle()
                val isRequestingCapture by viewModel.isRequestingCapture.collectAsStateWithLifecycle()

                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        lifecycleScope.launch {
                            try {
                                lifecycle.withResumed {
                                    viewModel.startAudioCastWithProjection(result.resultCode, result.data)
                                }
                            } catch (e: CancellationException) {
                                viewModel.captureAuthorizationFailed("Capture authorization was interrupted. Start again.")
                                throw e
                            } catch (e: Exception) {
                                viewModel.captureAuthorizationFailed(
                                    e.message ?: "Unable to start audio capture after authorization."
                                )
                            }
                        }
                    } else {
                        viewModel.captureAuthorizationFailed("Audio capture authorization was denied.")
                    }
                }

                val requestProjectionConsent = {
                    try {
                        val projectionManager =
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    } catch (e: Exception) {
                        viewModel.captureAuthorizationFailed(e.message ?: "Unable to request audio capture authorization.")
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grantResults ->
                    val recordAudioGranted = grantResults[Manifest.permission.RECORD_AUDIO] == true ||
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    if (recordAudioGranted) {
                        requestProjectionConsent()
                    } else {
                        viewModel.captureAuthorizationFailed("RECORD_AUDIO permission was denied.")
                    }
                }

                ScreenCastAudioScreen(
                    devices = devices,
                    selectedDeviceId = selectedDeviceId,
                    isScanning = isScanning,
                    config = config,
                    telemetry = telemetry,
                    adtsHeaderHex = viewModel.previewAdtsHeaderHex(),
                    logs = logs,
                    castStatus = castStatus,
                    isRequestingCapture = isRequestingCapture,
                    onModeChanged = viewModel::setCastMode,
                    castButton = {
                        if (castStatus.isAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            AndroidView(
                                factory = { context ->
                                    MediaRouteButton(context).apply {
                                        contentDescription = "Choose Cast receiver"
                                        CastButtonFactory.setUpMediaRouteButton(context, this)
                                    }
                                },
                                update = { button ->
                                    button.isEnabled = !isRequestingCapture &&
                                        !telemetry.isSessionActive &&
                                        !telemetry.isCaptureLocked
                                },
                                modifier = Modifier.size(48.dp).testTag("official_cast_button")
                            )
                        }
                    },
                    onScanClicked = { viewModel.startMdnsDiscovery() },
                    onSelectDevice = { viewModel.selectDevice(it) },
                    onAddManualSpeaker = { name, host, port, model, ca ->
                        viewModel.addManualGoogleHomeDevice(name, host, port, model, ca)
                    },
                    onToggleAudioOnlyMode = { viewModel.setAudioOnlyLowLatencyMode(it) },
                    onPreBurstChanged = { viewModel.setPreBurstBufferMs(it) },
                    onSampleRateChanged = { viewModel.setSampleRateHz(it) },
                    onBitrateChanged = { viewModel.setBitrateBps(it) },
                    onStartCastClicked = {
                        if (viewModel.prepareCaptureRequest()) {
                            val missingPermissions = buildList {
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    add(Manifest.permission.RECORD_AUDIO)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }

                            if (missingPermissions.isNotEmpty()) {
                                try {
                                    permissionLauncher.launch(missingPermissions.toTypedArray())
                                } catch (e: Exception) {
                                    viewModel.captureAuthorizationFailed(
                                        e.message ?: "Unable to request required audio permissions."
                                    )
                                }
                            } else {
                                requestProjectionConsent()
                            }
                        }
                    },
                    onStopCastClicked = { viewModel.stopAudioCast() }
                )
            }
        }
    }
}
