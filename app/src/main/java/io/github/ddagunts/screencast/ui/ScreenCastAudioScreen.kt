package io.github.ddagunts.screencast.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import io.github.ddagunts.screencast.cast.CastSdkSessionStatus
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.PendingGlobalMuteRecovery
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.ShizukuConnectionState
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.TargetPreset
import io.github.ddagunts.screencast.target.UserProfileInfo
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import kotlin.math.roundToInt

@Composable
fun ScreenCastAudioScreen(
    config: AudioStreamConfig = AudioStreamConfig(),
    telemetry: AudioPipelineTelemetry = AudioPipelineTelemetry(),
    logs: List<String> = emptyList(),
    castStatus: CastSdkSessionStatus = CastSdkSessionStatus(),
    isRequestingCapture: Boolean = false,
    castButton: @Composable () -> Unit = {},
    onStartCastClicked: () -> Unit = {},
    onStopCastClicked: () -> Unit = {},
    catalogApps: List<TargetAppEntry> = emptyList(),
    searchableApps: List<TargetAppEntry> = emptyList(),
    profiles: List<UserProfileInfo> = emptyList(),
    selectedTargets: Set<CaptureTarget> = emptySet(),
    importedTargets: Set<CaptureTarget> = emptySet(),
    presets: List<TargetPreset> = emptyList(),
    muteTelemetry: SessionMuteTelemetry = SessionMuteTelemetry(),
    isTargetEditingLocked: Boolean = false,
    showOutputModeDialog: Boolean = false,
    isGlobalMediaMuteCompatible: Boolean = true,
    globalMediaMuteDisabledReason: String? = null,
    pendingGlobalMuteRecovery: PendingGlobalMuteRecovery? = null,
    onToggleTarget: (CaptureTarget) -> Unit = {},
    onClearTargets: () -> Unit = {},
    onImportTargetApp: (packageName: String, userSerialNumber: Long) -> Unit = { _, _ -> },
    onRemoveImportedTarget: (CaptureTarget) -> Unit = {},
    onSavePreset: (String) -> Result<TargetPreset> = {
        Result.failure(IllegalStateException("Not wired"))
    },
    onApplyPreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onSpeakerVolumeCommitted: (Float) -> Unit = {},
    onSpeakerMuteToggled: (Boolean) -> Unit = {},
    onSelectFallbackOutputMode: (PhoneOutputMode) -> Unit = {},
    onDismissOutputModeDialog: () -> Unit = {},
    onConfirmRestoreLeftoverVolume: () -> Unit = {},
    onKeepCurrentVolumeOnRecovery: () -> Unit = {},
    onRequestShizukuPermission: () -> Unit = {},
    onRefreshCatalog: () -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {}
) {
    var showAppPickerSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .testTag("screencast_main_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Unified WebRTC Cast Console Header Card
                item {
                    WebRtcConsoleHeaderCard(
                        config = config,
                        telemetry = telemetry,
                        castStatus = castStatus,
                        muteTelemetry = muteTelemetry,
                        selectedTargetsCount = selectedTargets.size,
                        isRequestingCapture = isRequestingCapture,
                        hasPendingGlobalMuteRecovery = pendingGlobalMuteRecovery != null,
                        castButton = castButton,
                        onRequestShizukuPermission = onRequestShizukuPermission,
                        onStartCastClicked = onStartCastClicked,
                        onStopCastClicked = onStopCastClicked
                    )
                }

                // 2. Standalone Cast Speaker Volume & Mute Control Card
                item {
                    CastSpeakerControlCard(
                        castStatus = castStatus,
                        onSpeakerVolumeCommitted = onSpeakerVolumeCommitted,
                        onSpeakerMuteToggled = onSpeakerMuteToggled
                    )
                }

                // 3. Unified Target Apps Card with Embedded TargetPreset Bar
                item {
                    TargetAppSelectorCard(
                        catalogApps = catalogApps,
                        selectedTargets = selectedTargets,
                        importedTargets = importedTargets,
                        presets = presets,
                        isEditingLocked = isTargetEditingLocked,
                        onToggleTarget = onToggleTarget,
                        onClearTargets = onClearTargets,
                        onOpenImportDialog = { showAppPickerSheet = true },
                        onRemoveImportedTarget = onRemoveImportedTarget,
                        onSavePreset = onSavePreset,
                        onApplyPreset = onApplyPreset,
                        onDeletePreset = onDeletePreset,
                        onRefreshCatalog = onRefreshCatalog
                    )
                }

                // 4. Collapsible Gate Verification & Live Diagnostics Card
                item {
                    CollapsibleDiagnosticsCard(
                        telemetry = telemetry,
                        muteTelemetry = muteTelemetry,
                        logs = logs
                    )
                }
            }
        }
    }

    if (showAppPickerSheet && !isTargetEditingLocked) {
        VisualAppPickerBottomSheet(
            profiles = profiles,
            searchableApps = searchableApps,
            importedTargets = importedTargets,
            selectedTargets = selectedTargets,
            onDismiss = {
                onSearchQueryChanged("")
                showAppPickerSheet = false
            },
            onAddTargetApp = { pkg, serial ->
                onImportTargetApp(pkg, serial)
            },
            onRemoveTargetApp = { target ->
                onRemoveImportedTarget(target)
            },
            onSearchQueryChanged = onSearchQueryChanged
        )
    }

    if (showOutputModeDialog) {
        FallbackOutputModeSelectionDialog(
            shizukuState = muteTelemetry.shizukuState,
            isGlobalMediaMuteCompatible = isGlobalMediaMuteCompatible,
            globalMediaMuteDisabledReason = globalMediaMuteDisabledReason,
            onRequestShizukuPermission = onRequestShizukuPermission,
            onSelectMode = onSelectFallbackOutputMode,
            onDismiss = onDismissOutputModeDialog
        )
    }

    if (pendingGlobalMuteRecovery != null) {
        LeftoverGlobalMuteRecoveryDialog(
            recovery = pendingGlobalMuteRecovery,
            errorReason = telemetry.error,
            onRestoreOriginal = onConfirmRestoreLeftoverVolume,
            onKeepCurrent = onKeepCurrentVolumeOnRecovery
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebRtcConsoleHeaderCard(
    config: AudioStreamConfig,
    telemetry: AudioPipelineTelemetry,
    castStatus: CastSdkSessionStatus,
    muteTelemetry: SessionMuteTelemetry,
    selectedTargetsCount: Int,
    isRequestingCapture: Boolean,
    hasPendingGlobalMuteRecovery: Boolean = false,
    castButton: @Composable () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onStartCastClicked: () -> Unit,
    onStopCastClicked: () -> Unit
) {
    val effectiveMode = if (telemetry.isSessionActive || isRequestingCapture) {
        telemetry.activeOutputMode
    } else if (muteTelemetry.shizukuState == ShizukuConnectionState.READY) {
        PhoneOutputMode.SHIZUKU_SESSION_MUTE
    } else {
        telemetry.activeOutputMode
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_header_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // Top Visual Hero Banner with Stage Pill & Mode/Receiver Badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_audio_hero),
                    contentDescription = stringResource(R.string.hero_title),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (telemetry.isStreaming) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (telemetry.isStreaming) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                            )
                            Text(
                                text = "WEBRTC • ${telemetry.webRtcStage.name}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("active_output_mode_badge")
                    ) {
                        Text(
                            text = buildString {
                                append(effectiveMode.badgeLabel)
                                if (telemetry.isSessionActive || isRequestingCapture) {
                                    append(" (已冻结)")
                                }
                                append(" • ${config.sampleRateHz / 1000}kHz")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title + Cast Receiver Selector Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("webrtc_connection_card"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (castStatus.isConnected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                            contentDescription = "Google Cast 接收端状态",
                            tint = if (castStatus.isConnected) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(26.dp)
                        )
                        Column {
                            Text(
                                text = when {
                                    castStatus.isConnected -> "已连接音箱：${castStatus.deviceName}"
                                    castStatus.isConnecting -> "正在连接 ${castStatus.deviceName}..."
                                    else -> stringResource(R.string.hero_title)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                                        "需要 Android 10 (API 29) 及以上版本支持"
                                    castStatus.error != null -> castStatus.error
                                    telemetry.webRtcStage == WebRtcStage.SENDING ->
                                        "输出模式：${telemetry.activeOutputMode.badgeLabel} • ${telemetry.activeOutputMode.shortDescription}"
                                    telemetry.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT ->
                                        "底层音频清理超时，当前进程已锁定采集，请重启应用"
                                    castStatus.isConnected ->
                                        "接收端就绪 • 已选 $selectedTargetsCount 个目标空间实例"
                                    castStatus.isAvailable ->
                                        "请点击右侧 Cast 图标选择 Google Home / Cast 接收端"
                                    else -> "正在初始化 Google Cast SDK..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (castStatus.error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        castButton()
                        val canDisconnect = castStatus.isConnected &&
                            !telemetry.isSessionActive &&
                            !isRequestingCapture &&
                            !telemetry.isCaptureLocked &&
                            telemetry.webRtcStage != WebRtcStage.CLEANUP_TIMEOUT
                        if (canDisconnect) {
                            OutlinedButton(
                                onClick = onStopCastClicked,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("disconnect_cast_button")
                            ) {
                                Text("断开", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // 4-Dimensional Independent Status Pills:
                // 1) 音箱已连接, 2) 流正在发送, 3) 已有有效音频, 4) 手机已静音
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("status_four_dimensions_row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusDimensionPill(
                        label = if (castStatus.isConnected) "音箱已连接" else "音箱未连接",
                        active = castStatus.isConnected,
                        testTag = "status_pill_speaker_connected"
                    )
                    StatusDimensionPill(
                        label = if (telemetry.isStreaming) "流正在发送" else "流未发送",
                        active = telemetry.isStreaming,
                        testTag = "status_pill_stream_sending"
                    )
                    StatusDimensionPill(
                        label = if (telemetry.hasValidAudioSignal) "已有有效音频" else "尚无有效音频",
                        active = telemetry.hasValidAudioSignal,
                        testTag = "status_pill_valid_audio"
                    )
                    StatusDimensionPill(
                        label = if (telemetry.isPhoneMuted) {
                            "手机已静音 (${telemetry.activeOutputMode.badgeLabel})"
                        } else {
                            "手机未静音"
                        },
                        active = telemetry.isPhoneMuted,
                        testTag = "status_pill_phone_muted"
                    )
                }

                // Live Audio Level Meter Bar + Non-dogmatic Zero-Level Explanation
                val normalizedLevel = ((telemetry.currentRmsDb + 60f) / 60f).coerceIn(0f, 1f)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = "实时采集电平",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "目标 UID 实时采集电平",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${telemetry.currentRmsDb.roundToInt()} dBFS • ${telemetry.audioPacketsSent} pkts",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { normalizedLevel },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (telemetry.hasValidAudioSignal) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        if (telemetry.isSessionActive && !telemetry.hasValidAudioSignal) {
                            Text(
                                text = "当前尚无有效音频信号 (-60 dBFS)：可能目标应用尚未播放、处于暂停/静音，或受应用自身回采策略影响。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("zero_audio_level_hint")
                            )
                        }
                    }
                }

                // Compact Shizuku Shell & Output Mode Status Bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when (muteTelemetry.shizukuState) {
                        ShizukuConnectionState.READY ->
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                        ShizukuConnectionState.PERMISSION_REQUIRED,
                        ShizukuConnectionState.CONNECTING ->
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else ->
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shizuku_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = muteTelemetry.shizukuState.statusLabel,
                                tint = if (muteTelemetry.shizukuState == ShizukuConnectionState.READY) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = if (muteTelemetry.shizukuState == ShizukuConnectionState.READY) {
                                        "${muteTelemetry.shizukuState.statusLabel} • 默认定向静音"
                                    } else {
                                        "${muteTelemetry.shizukuState.statusLabel} • 启动时可选备选输出模式"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (muteTelemetry.shizukuState == ShizukuConnectionState.READY) {
                                        "Shizuku 会话级零增益包络 • 3s 租约 / 1s 续期 • 专属 ID 终止恢复"
                                    } else {
                                        "无 Shizuku 时支持公开 API 可验证空间采集，启动弹窗可选整体媒体静音或仅采集不静音"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (muteTelemetry.shizukuState != ShizukuConnectionState.READY) {
                            OutlinedButton(
                                onClick = onRequestShizukuPermission,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("shizuku_action_button")
                            ) {
                                Text(
                                    text = if (muteTelemetry.shizukuState == ShizukuConnectionState.PERMISSION_REQUIRED) {
                                        "授权 Shizuku"
                                    } else {
                                        "刷新状态"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                // Error Banner (if any)
                telemetry.error?.let { error ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(10.dp)
                                .testTag("capture_error_text")
                        )
                    }
                }

                // Primary Start / Stop Action Button
                if (!telemetry.isSessionActive) {
                    val isCaptureLocked = telemetry.isCaptureLocked ||
                        telemetry.webRtcStage == WebRtcStage.CLEANUP_TIMEOUT
                    Button(
                        onClick = onStartCastClicked,
                        enabled = !isRequestingCapture &&
                            !isCaptureLocked &&
                            !hasPendingGlobalMuteRecovery &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            castStatus.isConnected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_cast_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.btn_start_casting)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRequestingCapture) {
                                "正在等待系统录音与投屏授权..."
                            } else {
                                stringResource(R.string.btn_start_casting)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onStopCastClicked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("stop_cast_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.btn_stop_casting)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.btn_stop_casting),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDimensionPill(
    label: String,
    active: Boolean,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * Standalone card for controlling the connected Cast receiver's device volume (0–100%)
 * and independent mute state via official CastSession APIs.
 * Slider commits volume on release (`onValueChangeFinished`) and never auto-unmutes the speaker.
 */
@Composable
fun CastSpeakerControlCard(
    castStatus: CastSdkSessionStatus,
    onSpeakerVolumeCommitted: (Float) -> Unit,
    onSpeakerMuteToggled: (Boolean) -> Unit
) {
    val controlsEnabled = castStatus.isConnected && castStatus.isVolumeControlAvailable
    var sliderDraft by remember { mutableFloatStateOf(castStatus.volume.toFloat().coerceIn(0f, 1f)) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(castStatus.volume, castStatus.isConnected, isDragging) {
        if (!isDragging) {
            sliderDraft = castStatus.volume.toFloat().coerceIn(0f, 1f)
        }
    }

    val displayPercent = (sliderDraft * 100f).roundToInt().coerceIn(0, 100)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cast_speaker_control_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Speaker,
                                contentDescription = "音箱音量与静音控制",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "音箱音量与静音控制",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (controlsEnabled) {
                                "${castStatus.deviceName.ifBlank { "已连接音箱" }} • 仅调节远端 Cast 设备，不改手机音量"
                            } else {
                                "连接 Cast 音箱后可调节 0–100% 音量与独立静音"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Independent Speaker Mute Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (castStatus.isMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (castStatus.isMuted) "音箱已静音" else "音箱未静音",
                        tint = if (castStatus.isMuted) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (castStatus.isMuted) "音箱静音" else "静音开关",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = castStatus.isMuted,
                        onCheckedChange = { muted -> onSpeakerMuteToggled(muted) },
                        enabled = controlsEnabled,
                        modifier = Modifier.testTag("speaker_mute_switch")
                    )
                }
            }

            // 0-100% Volume Slider (commits on release)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "$displayPercent%",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (controlsEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .width(46.dp)
                        .testTag("speaker_volume_percent_text")
                )
                Slider(
                    value = sliderDraft,
                    onValueChange = { newVal ->
                        isDragging = true
                        sliderDraft = newVal.coerceIn(0f, 1f)
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSpeakerVolumeCommitted(sliderDraft.coerceIn(0f, 1f))
                    },
                    valueRange = 0f..1f,
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("speaker_volume_slider")
                )
            }

            castStatus.volumeControlError?.let { ctrlErr ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = ctrlErr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(8.dp)
                            .testTag("speaker_volume_error_text")
                    )
                }
            }
        }
    }
}

@Composable
private fun FallbackOutputModeSelectionDialog(
    shizukuState: ShizukuConnectionState,
    isGlobalMediaMuteCompatible: Boolean,
    globalMediaMuteDisabledReason: String?,
    onRequestShizukuPermission: () -> Unit,
    onSelectMode: (PhoneOutputMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fallback_output_mode_dialog"),
        title = {
            Text(
                text = "选择手机端声音输出模式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag("fallback_output_mode_dialog_scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "当前 ${shizukuState.statusLabel}，无法启用 Shizuku 会话级定向静音。请选择本次投音的备选模式（模式将在启动时冻结，投放期间不自动切换）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Option 1: Global Media Mute
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isGlobalMediaMuteCompatible) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (isGlobalMediaMuteCompatible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = PhoneOutputMode.GLOBAL_MEDIA_MUTE.badgeLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = PhoneOutputMode.GLOBAL_MEDIA_MUTE.impactDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isGlobalMediaMuteCompatible && globalMediaMuteDisabledReason != null) {
                            Text(
                                text = "已禁用：$globalMediaMuteDisabledReason",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("global_mute_incompatible_reason_text")
                            )
                        }
                        Button(
                            onClick = { onSelectMode(PhoneOutputMode.GLOBAL_MEDIA_MUTE) },
                            enabled = isGlobalMediaMuteCompatible,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("select_mode_global_mute_button")
                        ) {
                            Text("以「整体媒体静音」启动")
                        }
                    }
                }

                // Option 2: Capture Only Unmuted
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = PhoneOutputMode.CAPTURE_ONLY_UNMUTED.badgeLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = PhoneOutputMode.CAPTURE_ONLY_UNMUTED.impactDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { onSelectMode(PhoneOutputMode.CAPTURE_ONLY_UNMUTED) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("select_mode_capture_only_button")
                        ) {
                            Text("以「仅采集不静音」启动")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onRequestShizukuPermission()
                },
                modifier = Modifier.testTag("dialog_request_shizuku_button")
            ) {
                Text("去授权 / 刷新 Shizuku")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_output_mode_dialog_button")
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun LeftoverGlobalMuteRecoveryDialog(
    recovery: PendingGlobalMuteRecovery,
    errorReason: String? = null,
    onRestoreOriginal: () -> Unit,
    onKeepCurrent: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("leftover_volume_recovery_dialog"),
        title = {
            Text(
                text = "检测到未恢复的整体媒体静音记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "上次使用「整体媒体静音」投音期间应用异常终止或恢复未完成，为避免擅自覆盖您当前的音量设置，请选择处理方式：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 投音前记录的原媒体音量：${recovery.savedVolume} / ${recovery.maxVolume}" +
                        if (recovery.wasMuted) " (原为静音)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "• 当前系统媒体音量：${recovery.currentVolume} / ${recovery.maxVolume}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                if (!errorReason.isNullOrBlank()) {
                    Text(
                        text = "恢复状态说明：$errorReason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("recovery_dialog_error_reason")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRestoreOriginal,
                modifier = Modifier.testTag("restore_leftover_volume_button")
            ) {
                Text("恢复原音量 (${recovery.savedVolume})")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onKeepCurrent,
                modifier = Modifier.testTag("keep_current_volume_button")
            ) {
                Text("保留当前设置 (${recovery.currentVolume})")
            }
        }
    )
}
