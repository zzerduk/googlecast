package io.github.ddagunts.screencast.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.ShizukuConnectionState
import io.github.ddagunts.screencast.target.TargetAppEntry
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
    muteTelemetry: SessionMuteTelemetry = SessionMuteTelemetry(),
    isTargetEditingLocked: Boolean = false,
    onToggleTarget: (CaptureTarget) -> Unit = {},
    onClearTargets: () -> Unit = {},
    onImportTargetApp: (packageName: String, userSerialNumber: Long) -> Unit = { _, _ -> },
    onRemoveImportedTarget: (CaptureTarget) -> Unit = {},
    onRequestShizukuPermission: () -> Unit = {},
    onRefreshCatalog: () -> Unit = {}
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
                        castButton = castButton,
                        onRequestShizukuPermission = onRequestShizukuPermission,
                        onStartCastClicked = onStartCastClicked,
                        onStopCastClicked = onStopCastClicked
                    )
                }

                // 2. Unified Target Apps Card
                item {
                    TargetAppSelectorCard(
                        catalogApps = catalogApps,
                        selectedTargets = selectedTargets,
                        importedTargets = importedTargets,
                        isEditingLocked = isTargetEditingLocked,
                        onToggleTarget = onToggleTarget,
                        onClearTargets = onClearTargets,
                        onOpenImportDialog = { showAppPickerSheet = true },
                        onRemoveImportedTarget = onRemoveImportedTarget,
                        onRefreshCatalog = onRefreshCatalog
                    )
                }

                // 3. Collapsible Gate Verification & Live Diagnostics Card
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
            onDismiss = { showAppPickerSheet = false },
            onAddTargetApp = { pkg, serial ->
                onImportTargetApp(pkg, serial)
            },
            onRemoveTargetApp = { target ->
                onRemoveImportedTarget(target)
            }
        )
    }
}

@Composable
private fun WebRtcConsoleHeaderCard(
    config: AudioStreamConfig,
    telemetry: AudioPipelineTelemetry,
    castStatus: CastSdkSessionStatus,
    muteTelemetry: SessionMuteTelemetry,
    selectedTargetsCount: Int,
    isRequestingCapture: Boolean,
    castButton: @Composable () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onStartCastClicked: () -> Unit,
    onStopCastClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_header_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // Top Visual Hero Banner with Stage Pill & Receiver ID
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
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "${config.sampleRateHz / 1000}kHz PCM16 • ID ${config.receiverAppId}",
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
                                        "正在向音箱实时传输目标 App 音频并保持本地静音"
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

                // Live Audio Level Meter Bar
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
                            color = if (telemetry.isStreaming) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                // Compact Shizuku Shell Status Bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when (muteTelemetry.shizukuState) {
                        ShizukuConnectionState.READY ->
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                        ShizukuConnectionState.PERMISSION_REQUIRED,
                        ShizukuConnectionState.CONNECTING ->
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else ->
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
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
                                    text = muteTelemetry.shizukuState.statusLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Shizuku 13.1.5 会话级零增益包络 • 3s 租约 / 1s 续期 • getMutedBy() 确认",
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
