package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import io.github.ddagunts.screencast.ui.ScreenCastAudioScreen
import io.github.ddagunts.screencast.ui.ScreenCastViewModel

class MainActivity : ComponentActivity() {

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

                // MediaProjection consent launcher (required by Android 10+ for AudioPlaybackCapture)
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        viewModel.startAudioCastWithProjection(result.resultCode, result.data)
                    } else {
                        // Even if screen capture dialog is dismissed in headless/test environments,
                        // allow starting the Ktor ADTS server + Silence Injector stream for testing.
                        viewModel.startAudioCastWithProjection(Activity.RESULT_CANCELED, null)
                    }
                }

                val requestProjectionConsent = {
                    try {
                        val projectionManager =
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    } catch (_: Exception) {
                        viewModel.startAudioCastWithProjection(Activity.RESULT_CANCELED, null)
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    requestProjectionConsent()
                }

                ScreenCastAudioScreen(
                    devices = devices,
                    selectedDeviceId = selectedDeviceId,
                    isScanning = isScanning,
                    config = config,
                    telemetry = telemetry,
                    adtsHeaderHex = viewModel.previewAdtsHeaderHex(),
                    logs = logs,
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
                        val missingPermissions = buildList {
                            if (ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }

                        if (missingPermissions.isNotEmpty()) {
                            permissionLauncher.launch(missingPermissions.toTypedArray())
                        } else {
                            requestProjectionConsent()
                        }
                    },
                    onStopCastClicked = { viewModel.stopAudioCast() }
                )
            }
        }
    }
}
