package io.github.ddagunts.screencast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import io.github.ddagunts.screencast.cast.CastDeviceModel
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenCastAudioScreen(
    devices: List<CastDeviceModel>,
    selectedDeviceId: String?,
    isScanning: Boolean,
    config: AudioStreamConfig,
    telemetry: AudioPipelineTelemetry,
    adtsHeaderHex: String,
    logs: List<String>,
    onScanClicked: () -> Unit,
    onSelectDevice: (CastDeviceModel) -> Unit,
    onAddManualSpeaker: (name: String, host: String, port: Int, model: String, ca: Int) -> Unit,
    onToggleAudioOnlyMode: (Boolean) -> Unit,
    onPreBurstChanged: (Int) -> Unit,
    onSampleRateChanged: (Int) -> Unit,
    onBitrateChanged: (Int) -> Unit,
    onStartCastClicked: () -> Unit,
    onStopCastClicked: () -> Unit
) {
    var showAddSpeakerDialog by rememberSaveable { mutableStateOf(false) }

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
                item {
                    HeroHeaderCard(
                        telemetry = telemetry,
                        config = config,
                        onStartCastClicked = onStartCastClicked,
                        onStopCastClicked = onStopCastClicked
                    )
                }

                item {
                    AudioModeCard(
                        config = config,
                        onToggleAudioOnlyMode = onToggleAudioOnlyMode
                    )
                }

                item {
                    CastDevicesSection(
                        devices = devices,
                        selectedDeviceId = selectedDeviceId,
                        isScanning = isScanning,
                        onScanClicked = onScanClicked,
                        onSelectDevice = onSelectDevice,
                        onOpenManualDialog = { showAddSpeakerDialog = true }
                    )
                }

                item {
                    StreamTuningCard(
                        config = config,
                        adtsHeaderHex = adtsHeaderHex,
                        onPreBurstChanged = onPreBurstChanged,
                        onSampleRateChanged = onSampleRateChanged,
                        onBitrateChanged = onBitrateChanged
                    )
                }

                item {
                    LiveTelemetryCard(
                        telemetry = telemetry,
                        config = config,
                        logs = logs
                    )
                }
            }
        }
    }

    if (showAddSpeakerDialog) {
        AddManualGoogleHomeDialog(
            onDismiss = { showAddSpeakerDialog = false },
            onConfirm = { name, host, port, model, ca ->
                onAddManualSpeaker(name, host, port, model, ca)
                showAddSpeakerDialog = false
            }
        )
    }
}

@Composable
private fun HeroHeaderCard(
    telemetry: AudioPipelineTelemetry,
    config: AudioStreamConfig,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_audio_hero),
                    contentDescription = stringResource(R.string.hero_title),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Surface(
                    color = if (telemetry.isStreaming) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
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
                            text = if (telemetry.isStreaming) {
                                "LIVE ADTS STREAM • ~${config.estimatedEndToEndLatencyMs}ms"
                            } else {
                                "RECEIVER: CC1AD845 • ZERO-COST"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.hero_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.hero_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!telemetry.isStreaming) {
                        Button(
                            onClick = onStartCastClicked,
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
                                text = stringResource(R.string.btn_start_casting),
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
}

@Composable
private fun AudioModeCard(
    config: AudioStreamConfig,
    onToggleAudioOnlyMode: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audio_mode_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = stringResource(R.string.mode_audio_only_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.mode_audio_only_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.mode_audio_only_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = config.isAudioOnlyLowLatencyMode,
                    onCheckedChange = onToggleAudioOnlyMode,
                    modifier = Modifier.testTag("audio_only_mode_switch")
                )
            }

            // Phase 5.2: Hide video-specific settings when Audio-Only mode is active;
            // only reveal disabled legacy video/HLS fields if the user toggles off Audio-Only mode.
            AnimatedVisibility(visible = !config.isAudioOnlyLowLatencyMode) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("legacy_video_settings_panel")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Legacy Video / HLS Settings (Suppressed for 1st-Gen Google Home)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "• Resolution: ${config.legacyVideoResolution}\n" +
                                "• H.264 Bitrate: Disabled (No hardware video decoder on Google Home)\n" +
                                "• HLS Segment Duration / Playlist Window: Replaced by Ktor ADTS stream.aac",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CastDevicesSection(
    devices: List<CastDeviceModel>,
    selectedDeviceId: String?,
    isScanning: Boolean,
    onScanClicked: () -> Unit,
    onSelectDevice: (CastDeviceModel) -> Unit,
    onOpenManualDialog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cast_devices_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (selectedDeviceId != null) Icons.Filled.CastConnected else Icons.Filled.Cast,
                        contentDescription = stringResource(R.string.section_devices),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.section_devices),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onScanClicked,
                        modifier = Modifier.testTag("scan_mdns_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.btn_scan_devices)
                        )
                    }
                    IconButton(
                        onClick = onOpenManualDialog,
                        modifier = Modifier.testTag("add_manual_device_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add Google Home speaker by IP"
                        )
                    }
                }
            }

            Text(
                text = "Phase 1 Active: 0x01 VIDEO_OUT filter removed. Audio-only speakers (md=Google Home, ca=2052) are unlocked.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (devices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_devices_placeholder")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isScanning) {
                                "Scanning local Wi-Fi for _googlecast._tcp.local speakers..."
                            } else {
                                "No Cast speakers discovered yet on this subnet."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tip: Tap '+' to target your 1st-Gen Google Home by LAN IP (port 8009, ca=2052) directly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onOpenManualDialog,
                            modifier = Modifier.testTag("empty_state_add_speaker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speaker,
                                contentDescription = "Add 1st-Gen Google Home IP"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Target 1st-Gen Google Home IP")
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        val isSelected = device.id == selectedDeviceId
                        val borderColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .clickable { onSelectDevice(device) }
                                .testTag("device_item_${device.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Speaker,
                                        contentDescription = device.friendlyName,
                                        tint = if (device.isFirstGenGoogleHome) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    Column {
                                        Text(
                                            text = "${device.friendlyName} (${device.modelName})",
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${device.hostAddress}:${device.port} • ca=${device.capabilityMask}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = device.capabilitySummary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected Speaker",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamTuningCard(
    config: AudioStreamConfig,
    adtsHeaderHex: String,
    onPreBurstChanged: (Int) -> Unit,
    onSampleRateChanged: (Int) -> Unit,
    onBitrateChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stream_tuning_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.section_stream_tuning),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.section_stream_tuning),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Phase 5.3: Pre-burst Buffer Size (ms) adjustable from 0ms to 1000ms (default 300ms)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.pre_burst_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${config.preBurstBufferMs} ms (${config.preBurstFrameCount} ADTS frames)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("pre_burst_value_text")
                    )
                }

                Slider(
                    value = config.preBurstBufferMs.toFloat(),
                    onValueChange = { onPreBurstChanged(it.roundToInt()) },
                    valueRange = 0f..1000f,
                    steps = 19, // 50ms increments across 0..1000ms
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Pre-burst Buffer Size in milliseconds" }
                        .testTag("pre_burst_slider")
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0, 150, 300, 500, 1000).forEach { presetMs ->
                        FilterChip(
                            selected = config.preBurstBufferMs == presetMs,
                            onClick = { onPreBurstChanged(presetMs) },
                            label = {
                                Text(
                                    text = if (presetMs == 300) "300ms (Default)" else "${presetMs}ms",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("pre_burst_chip_$presetMs")
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.pre_burst_helper),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sample Rate Selector (48000 Hz vs 44100 Hz)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.sample_rate_label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(48000 to "48000 Hz (Idx 3)", 44100 to "44100 Hz (Idx 4)").forEach { (rate, label) ->
                        FilterChip(
                            selected = config.sampleRateHz == rate,
                            onClick = { onSampleRateChanged(rate) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("sample_rate_chip_$rate")
                        )
                    }
                }
            }

            // AAC-LC CBR Bitrate Selector (192 kbps vs 128 kbps)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.bitrate_label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(192000 to "192 kbps CBR", 128000 to "128 kbps CBR").forEach { (bps, label) ->
                        FilterChip(
                            selected = config.bitrateBps == bps,
                            onClick = { onBitrateChanged(bps) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("bitrate_chip_$bps")
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "7-Byte ADTS Header Frame Prefix (AAC-LC, No CRC)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = adtsHeaderHex,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("adts_header_hex_text")
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTelemetryCard(
    telemetry: AudioPipelineTelemetry,
    config: AudioStreamConfig,
    logs: List<String>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telemetry_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.section_telemetry),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.section_telemetry),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (telemetry.streamUrl.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Ktor Progressive Chunked Endpoint (audio/aac):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = telemetry.streamUrl,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("active_stream_url_text")
                        )
                    }
                }
            }

            // Signal / Silence Injector status bar
            val normalizedLevel = ((telemetry.currentRmsDb + 60f) / 60f).coerceIn(0.05f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.silence_injector_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (telemetry.isStreaming) {
                            if (telemetry.isInjectingSilenceNow) {
                                "INJECTING ZERO-PCM SILENCE (TRACK GAP GUARD)"
                            } else {
                                "LIVE PCM CAPTURE (${telemetry.currentRmsDb.roundToInt()} dBFS)"
                            }
                        } else {
                            "STANDBY (${config.silenceInjectorTickerMs}ms TICKER)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.isInjectingSilenceNow) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
                LinearProgressIndicator(
                    progress = { if (telemetry.isStreaming) normalizedLevel else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetricItem(
                    label = "ADTS Frames",
                    value = telemetry.adtsFramesEmitted.toString()
                )
                TelemetryMetricItem(
                    label = "Silence Frames",
                    value = telemetry.silenceFramesInjected.toString()
                )
                TelemetryMetricItem(
                    label = "Video Alloc",
                    value = if (telemetry.videoPipelineAllocated) "ACTIVE" else "0 (STRIPPED)"
                )
                TelemetryMetricItem(
                    label = "Clients",
                    value = telemetry.connectedSpeakerClients.toString()
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("protocol_log_box")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Cast V2 (8009 TLS) & Ktor ADTS Log",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    logs.takeLast(8).forEach { line ->
                        Text(
                            text = "> $line",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AddManualGoogleHomeDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: Int, model: String, ca: Int) -> Unit
) {
    var friendlyName by remember { mutableStateOf("Living Room Google Home") }
    var hostAddress by remember { mutableStateOf("192.168.1.120") }
    var portText by remember { mutableStateOf("8009") }
    var modelName by remember { mutableStateOf("Google Home") }
    var capabilityText by remember { mutableStateOf("2052") } // 1st-Gen Google Home ca=2052 (no 0x01 video bit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Target 1st-Gen Google Home",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = friendlyName,
                    onValueChange = { friendlyName = it },
                    label = { Text("Speaker Friendly Name (fn)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_speaker_name_input")
                )
                OutlinedTextField(
                    value = hostAddress,
                    onValueChange = { hostAddress = it },
                    label = { Text("LAN IPv4 Address") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_speaker_ip_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("TLS Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = capabilityText,
                        onValueChange = { capabilityText = it },
                        label = { Text("TXT ca Mask") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                AssistChip(
                    onClick = {
                        modelName = "Google Home"
                        capabilityText = "2052"
                    },
                    label = { Text("Preset: 1st-Gen Google Home (md=Google Home, ca=2052)") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8009
                    val ca = capabilityText.toIntOrNull() ?: 2052
                    if (hostAddress.isNotBlank()) {
                        onConfirm(friendlyName, hostAddress, port, modelName, ca)
                    }
                },
                modifier = Modifier.testTag("dialog_confirm_speaker_button")
            ) {
                Text("Add Speaker")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
