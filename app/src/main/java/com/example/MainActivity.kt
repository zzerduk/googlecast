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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.mediarouter.app.MediaRouteButton
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.cast.framework.CastButtonFactory
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
                val config by viewModel.config.collectAsStateWithLifecycle()
                val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                val logs by viewModel.combinedLogs.collectAsStateWithLifecycle()
                val castStatus by viewModel.castStatus.collectAsStateWithLifecycle()
                val isRequestingCapture by viewModel.isRequestingCapture.collectAsStateWithLifecycle()

                val catalogApps by viewModel.catalogApps.collectAsStateWithLifecycle()
                val searchableApps by viewModel.searchableApps.collectAsStateWithLifecycle()
                val profiles by viewModel.profiles.collectAsStateWithLifecycle()
                val selectedTargets by viewModel.selectedTargets.collectAsStateWithLifecycle()
                val importedTargets by viewModel.importedTargets.collectAsStateWithLifecycle()
                val presets by viewModel.presets.collectAsStateWithLifecycle()
                val muteTelemetry by viewModel.muteTelemetry.collectAsStateWithLifecycle()
                val isTargetEditingLocked by viewModel.isTargetEditingLocked.collectAsStateWithLifecycle()

                val showOutputModeDialog by viewModel.showOutputModeDialog.collectAsStateWithLifecycle()
                val isGlobalMediaMuteCompatible by viewModel.isGlobalMediaMuteCompatible.collectAsStateWithLifecycle()
                val globalMediaMuteDisabledReason by viewModel.globalMediaMuteDisabledReason.collectAsStateWithLifecycle()
                val pendingGlobalMuteRecovery by viewModel.pendingGlobalMuteRecovery.collectAsStateWithLifecycle()

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

                val launchPermissionsAndProjectionIfPrepared = {
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

                ScreenCastAudioScreen(
                    config = config,
                    telemetry = telemetry,
                    logs = logs,
                    castStatus = castStatus,
                    isRequestingCapture = isRequestingCapture,
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
                    onStartCastClicked = {
                        lifecycleScope.launch {
                            if (viewModel.initiateStartCastFlow()) {
                                launchPermissionsAndProjectionIfPrepared()
                            }
                        }
                    },
                    onStopCastClicked = { viewModel.stopAudioCast() },
                    catalogApps = catalogApps,
                    searchableApps = searchableApps,
                    profiles = profiles,
                    selectedTargets = selectedTargets,
                    importedTargets = importedTargets,
                    presets = presets,
                    muteTelemetry = muteTelemetry,
                    isTargetEditingLocked = isTargetEditingLocked,
                    showOutputModeDialog = showOutputModeDialog,
                    isGlobalMediaMuteCompatible = isGlobalMediaMuteCompatible,
                    globalMediaMuteDisabledReason = globalMediaMuteDisabledReason,
                    pendingGlobalMuteRecovery = pendingGlobalMuteRecovery,
                    onToggleTarget = viewModel::toggleTargetAppSelection,
                    onClearTargets = viewModel::clearTargetAppSelections,
                    onImportTargetApp = { pkg, serial -> viewModel.importTargetApp(pkg, serial) },
                    onRemoveImportedTarget = viewModel::removeImportedTarget,
                    onSavePreset = viewModel::saveCurrentSelectionAsPreset,
                    onApplyPreset = { name -> viewModel.applyTargetPreset(name) },
                    onDeletePreset = { name -> viewModel.deleteTargetPreset(name) },
                    onSpeakerVolumeCommitted = { vol -> viewModel.setSpeakerVolume(vol) },
                    onSpeakerMuteToggled = { muted -> viewModel.setSpeakerMuted(muted) },
                    onSelectFallbackOutputMode = { chosenMode ->
                        lifecycleScope.launch {
                            if (viewModel.confirmFallbackOutputModeAndPrepare(chosenMode)) {
                                launchPermissionsAndProjectionIfPrepared()
                            }
                        }
                    },
                    onDismissOutputModeDialog = viewModel::dismissOutputModeDialog,
                    onConfirmRestoreLeftoverVolume = viewModel::confirmRestoreLeftoverMediaVolume,
                    onKeepCurrentVolumeOnRecovery = viewModel::dismissLeftoverMediaVolumeRecovery,
                    onRequestShizukuPermission = viewModel::requestShizukuPermission,
                    onRefreshCatalog = viewModel::refreshTargetCatalog,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }
}
