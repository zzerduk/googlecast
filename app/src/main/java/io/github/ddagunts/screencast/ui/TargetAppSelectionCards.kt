package io.github.ddagunts.screencast.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserManager
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.target.ProfileType
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.TargetPreset
import io.github.ddagunts.screencast.target.UserProfileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetAppSelectorCard(
    catalogApps: List<TargetAppEntry>,
    selectedTargets: Set<CaptureTarget>,
    importedTargets: Set<CaptureTarget>,
    presets: List<TargetPreset> = emptyList(),
    activeOutputMode: PhoneOutputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE,
    isEditingLocked: Boolean,
    onToggleTarget: (CaptureTarget) -> Unit,
    onClearTargets: () -> Unit,
    onOpenImportDialog: () -> Unit,
    onRemoveImportedTarget: (CaptureTarget) -> Unit,
    onSavePreset: (String) -> Result<TargetPreset> = {
        Result.failure(IllegalStateException("Not wired"))
    },
    onApplyPreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    onRefreshCatalog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("target_app_selector_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header: Title + Active Count + Refresh + Add Target App Button
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
                                imageVector = Icons.Filled.VolumeOff,
                                contentDescription = "已添加目标应用",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "已添加目标应用",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "已勾选 ${selectedTargets.size} / ${catalogApps.size} 个空间实例 • ${activeOutputMode.targetCardSummary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("target_card_output_mode_subtitle")
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onRefreshCatalog,
                        modifier = Modifier.testTag("refresh_catalog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新已添加目标状态"
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenImportDialog,
                        enabled = !isEditingLocked,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("open_import_target_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加目标应用",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加应用", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Editing Lock Indicator during Auth or Active Session
            if (isEditingLocked) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("target_editing_locked_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "目标编辑已锁定",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "授权或投音进行中：目标与快捷组合已锁定，停止投音后可增删、修改目标或切换组合。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Embedded TargetPreset Chip Bar
            TargetPresetChipBar(
                presets = presets,
                selectedTargets = selectedTargets,
                isEditingLocked = isEditingLocked,
                onSavePreset = onSavePreset,
                onApplyPreset = onApplyPreset,
                onDeletePreset = onDeletePreset
            )

            if (selectedTargets.isNotEmpty() && !isEditingLocked) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AssistChip(
                        onClick = onClearTargets,
                        label = { Text("取消全选 (${selectedTargets.size})") },
                        modifier = Modifier.testTag("clear_selected_targets_button")
                    )
                }
            }

            // Unified Target Apps List or Empty State
            if (catalogApps.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_category_placeholder")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "尚未添加需要投音与本地静音的目标应用",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "支持从主空间、工作资料、克隆资料中浏览图标添加已安装应用，同包名的不同空间实例可独立勾选。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onOpenImportDialog,
                            enabled = !isEditingLocked,
                            modifier = Modifier.testTag("empty_category_import_button")
                        ) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = "浏览并添加目标应用")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("从应用列表中选择目标 App")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .testTag("target_app_lazy_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = catalogApps,
                        key = { it.target.toStorageKey() }
                    ) { entry ->
                        TargetAppRowItem(
                            entry = entry,
                            selectedTargets = selectedTargets,
                            importedTargets = importedTargets,
                            isEditingLocked = isEditingLocked,
                            onToggleTarget = onToggleTarget,
                            onRemoveImportedTarget = onRemoveImportedTarget
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetAppRowItem(
    entry: TargetAppEntry,
    selectedTargets: Set<CaptureTarget>,
    importedTargets: Set<CaptureTarget>,
    isEditingLocked: Boolean,
    onToggleTarget: (CaptureTarget) -> Unit,
    onRemoveImportedTarget: (CaptureTarget) -> Unit
) {
    val isSelected = entry.target in selectedTargets
    val isImported = entry.target in importedTargets
    val canInteract = (entry.isSelectable || isSelected) && !isEditingLocked
    val sharedSiblingsWarning = if (entry.sharedUidPackages.size > 1) {
        val unselectedSiblings = entry.sharedUidPackages.filter { pkg ->
            CaptureTarget(pkg, entry.target.userSerialNumber) !in selectedTargets
        }
        if (isSelected && unselectedSiblings.isNotEmpty()) {
            "共享 UID 含未选包: ${unselectedSiblings.joinToString(", ")} (禁止启动)"
        } else {
            "共享 UID 共 ${entry.sharedUidPackages.size} 个包"
        }
    } else null

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            !entry.isSelectable ->
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
            isSelected ->
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else ->
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = canInteract) { onToggleTarget(entry.target) }
            .testTag("target_app_item_${entry.target.toStorageKey()}")
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
                AppIconThumbnail(
                    packageName = entry.target.packageName,
                    userSerialNumber = entry.target.userSerialNumber,
                    appLabel = entry.appLabel,
                    size = 42.dp
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = entry.appLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        ProfileBadge(
                            profileType = entry.profileType,
                            serialNumber = entry.target.userSerialNumber
                        )
                    }

                    Text(
                        text = buildString {
                            append(entry.target.packageName)
                            append(" • user#${entry.target.userSerialNumber}")
                            if (entry.resolvedUid != null) {
                                append(" • UID ${entry.resolvedUid}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    entry.statusReason?.let { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Block,
                                contentDescription = reason,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    sharedSiblingsWarning?.let { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (warning.contains("禁止启动")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isImported && !isEditingLocked) {
                    IconButton(
                        onClick = { onRemoveImportedTarget(entry.target) },
                        modifier = Modifier.testTag("remove_imported_${entry.target.toStorageKey()}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "从目标列表移除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = if (canInteract) {
                        { onToggleTarget(entry.target) }
                    } else null,
                    enabled = canInteract,
                    modifier = Modifier.testTag("checkbox_${entry.target.toStorageKey()}")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetPresetChipBar(
    presets: List<TargetPreset>,
    selectedTargets: Set<CaptureTarget>,
    isEditingLocked: Boolean,
    onSavePreset: (String) -> Result<TargetPreset>,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    var showSaveInput by rememberSaveable { mutableStateOf(false) }
    var presetNameDraft by rememberSaveable { mutableStateOf("") }
    var presetErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("target_presets_bar")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmarks,
                        contentDescription = "目标快捷组合",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "目标快捷组合 (${presets.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                AssistChip(
                    onClick = {
                        presetErrorMessage = null
                        showSaveInput = !showSaveInput
                    },
                    enabled = !isEditingLocked && selectedTargets.isNotEmpty(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdd,
                            contentDescription = "保存当前选择为组合",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (showSaveInput) "收起保存" else "+ 保存当前选择",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("open_save_preset_button")
                )
            }

            AnimatedVisibility(visible = showSaveInput && !isEditingLocked) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = presetNameDraft,
                            onValueChange = {
                                presetNameDraft = it
                                presetErrorMessage = null
                            },
                            placeholder = { Text("输入组合名称 (不重复)...") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("preset_name_input")
                        )
                        Button(
                            onClick = {
                                val res = onSavePreset(presetNameDraft)
                                res.onSuccess {
                                    presetNameDraft = ""
                                    presetErrorMessage = null
                                    showSaveInput = false
                                }.onFailure { err ->
                                    presetErrorMessage = err.message ?: "保存组合失败"
                                }
                            },
                            enabled = presetNameDraft.trim().isNotEmpty() && selectedTargets.isNotEmpty() && !isEditingLocked,
                            modifier = Modifier.testTag("confirm_save_preset_button")
                        ) {
                            Text("保存组合")
                        }
                    }
                    presetErrorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("preset_error_text")
                        )
                    }
                }
            }

            if (presets.isEmpty()) {
                Text(
                    text = if (selectedTargets.isEmpty()) {
                        "尚无保存的目标组合 • 先勾选至少 1 个应用即可保存快捷组合"
                    } else {
                        "尚无保存的目标组合 • 点击右上角「+ 保存当前选择」创建命名组合"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("empty_presets_hint")
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        val isActiveMatch = preset.targets == selectedTargets && selectedTargets.isNotEmpty()
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isActiveMatch) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(
                                    width = 1.dp,
                                    color = if (isActiveMatch) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(50)
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(enabled = !isEditingLocked) {
                                            onApplyPreset(preset.name)
                                        }
                                        .padding(vertical = 4.dp, horizontal = 4.dp)
                                        .testTag("preset_chip_${preset.name}")
                                ) {
                                    if (isActiveMatch) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "当前已选组合",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = "${preset.name} (${preset.targets.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isEditingLocked) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                                if (!isEditingLocked) {
                                    IconButton(
                                        onClick = { onDeletePreset(preset.name) },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("delete_preset_${preset.name}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "删除组合 ${preset.name}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
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
}

@Composable
fun ProfileBadge(
    profileType: ProfileType,
    serialNumber: Long
) {
    val containerColor = when (profileType) {
        ProfileType.PRIMARY -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ProfileType.WORK -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
        ProfileType.CLONE -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
        ProfileType.OTHER -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (profileType) {
        ProfileType.PRIMARY -> MaterialTheme.colorScheme.primary
        ProfileType.WORK -> MaterialTheme.colorScheme.secondary
        ProfileType.CLONE -> MaterialTheme.colorScheme.tertiary
        ProfileType.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = "${profileType.badgeLabel} (S$serialNumber)",
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

internal object AppIconLoader {
    const val DEFAULT_MAX_CACHE_BYTES: Int = 4 * 1024 * 1024 // 4 MB
    const val MAX_ICON_DIMENSION_PX: Int = 96

    private val cacheLock = Any()

    @Volatile
    private var maxCacheBytes: Int = DEFAULT_MAX_CACHE_BYTES

    private var memoryCache: LruCache<String, ImageBitmap> = createLruCache(DEFAULT_MAX_CACHE_BYTES)

    private fun createLruCache(maxBytes: Int): LruCache<String, ImageBitmap> {
        return object : LruCache<String, ImageBitmap>(maxBytes.coerceAtLeast(1024)) {
            override fun sizeOf(key: String, value: ImageBitmap): Int {
                return (value.width * value.height * 4).coerceAtLeast(1)
            }
        }
    }

    @Volatile
    var onLoadIconHookForTesting: ((packageName: String, userSerialNumber: Long, threadName: String) -> Unit)? = null

    @Volatile
    var customIconProviderForTesting: ((Context, String, Long) -> ImageBitmap?)? = null

    fun setMaxCacheBytesForTesting(maxBytes: Int) {
        synchronized(cacheLock) {
            maxCacheBytes = maxBytes.coerceAtLeast(1024)
            memoryCache = createLruCache(maxCacheBytes)
        }
    }

    fun cacheSizeBytesForTesting(): Int = synchronized(cacheLock) { memoryCache.size() }

    fun cacheEntryCountForTesting(): Int = synchronized(cacheLock) { memoryCache.snapshot().size }

    fun clearCacheForTesting() {
        synchronized(cacheLock) {
            maxCacheBytes = DEFAULT_MAX_CACHE_BYTES
            memoryCache = createLruCache(DEFAULT_MAX_CACHE_BYTES)
        }
        onLoadIconHookForTesting = null
        customIconProviderForTesting = null
    }

    fun drawableToImageBitmapForTesting(
        drawable: Drawable,
        targetPx: Int = MAX_ICON_DIMENSION_PX
    ): ImageBitmap? = drawable.toImageBitmap(targetPx)

    suspend fun loadIconAsync(
        context: Context,
        packageName: String,
        userSerialNumber: Long
    ): ImageBitmap? {
        val key = "$userSerialNumber:$packageName"
        synchronized(cacheLock) { memoryCache.get(key) }?.let { return it }
        return withContext(Dispatchers.IO) {
            onLoadIconHookForTesting?.invoke(packageName, userSerialNumber, Thread.currentThread().name)
            val loaded = customIconProviderForTesting?.invoke(context, packageName, userSerialNumber)
                ?: loadAppIconBitmap(context, packageName, userSerialNumber)
            if (loaded != null) {
                synchronized(cacheLock) {
                    memoryCache.put(key, loaded)
                }
            }
            loaded
        }
    }
}

@Composable
fun AppIconThumbnail(
    packageName: String,
    userSerialNumber: Long,
    appLabel: String,
    size: Dp = 40.dp
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val iconBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = packageName,
        key2 = userSerialNumber
    ) {
        value = AppIconLoader.loadIconAsync(appContext, packageName, userSerialNumber)
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap!!,
            contentDescription = appLabel,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        val monogram = remember(appLabel, packageName) {
            val clean = appLabel.trim().takeIf { it.isNotEmpty() } ?: packageName
            clean.first().uppercaseChar().toString()
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = monogram,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun loadAppIconBitmap(
    context: Context,
    packageName: String,
    userSerialNumber: Long
): ImageBitmap? {
    return try {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val handle = um?.getUserForSerialNumber(userSerialNumber)
        val drawable: Drawable? = if (handle != null && launcherApps != null) {
            val activityIcon = runCatching {
                launcherApps.getActivityList(packageName, handle).firstOrNull()?.getBadgedIcon(0)
            }.getOrNull()
            activityIcon ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                runCatching {
                    val appInfo = launcherApps.getApplicationInfo(packageName, 0, handle)
                    val baseIcon = context.packageManager.getApplicationIcon(appInfo)
                    context.packageManager.getUserBadgedIcon(baseIcon, handle)
                }.getOrNull()
            } else {
                null
            }
        } else {
            null
        } ?: runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull()

        drawable?.toImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

internal fun Drawable.toImageBitmap(targetPx: Int = AppIconLoader.MAX_ICON_DIMENSION_PX): ImageBitmap? {
    return try {
        val maxPx = targetPx.coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            val src = bitmap
            if (src.width <= maxPx && src.height <= maxPx) {
                return src.asImageBitmap()
            }
            val scale = minOf(
                maxPx.toFloat() / src.width.coerceAtLeast(1).toFloat(),
                maxPx.toFloat() / src.height.coerceAtLeast(1).toFloat()
            )
            val scaledW = (src.width * scale).roundToInt().coerceIn(1, maxPx)
            val scaledH = (src.height * scale).roundToInt().coerceIn(1, maxPx)
            return Bitmap.createScaledBitmap(src, scaledW, scaledH, true).asImageBitmap()
        }
        val rawW = if (intrinsicWidth > 0) intrinsicWidth else maxPx
        val rawH = if (intrinsicHeight > 0) intrinsicHeight else maxPx
        val scale = minOf(
            1f,
            maxPx.toFloat() / rawW.toFloat(),
            maxPx.toFloat() / rawH.toFloat()
        )
        val width = (rawW * scale).roundToInt().coerceIn(1, maxPx)
        val height = (rawH * scale).roundToInt().coerceIn(1, maxPx)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bmp.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VisualAppPickerBottomSheet(
    profiles: List<UserProfileInfo>,
    searchableApps: List<TargetAppEntry>,
    importedTargets: Set<CaptureTarget>,
    selectedTargets: Set<CaptureTarget>,
    onDismiss: () -> Unit,
    onAddTargetApp: (packageName: String, userSerialNumber: Long) -> Unit,
    onRemoveTargetApp: (CaptureTarget) -> Unit,
    onSearchQueryChanged: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // -1L represents "All Profiles" (全部空间)
    var selectedProfileFilter by rememberSaveable { mutableLongStateOf(-1L) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var showManualInput by rememberSaveable { mutableStateOf(false) }

    val defaultManualSerial = profiles.firstOrNull()?.userSerialNumber ?: 0L
    var manualSerial by rememberSaveable { mutableLongStateOf(defaultManualSerial) }
    var manualPackageName by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            onSearchQueryChanged("")
        }
    }

    val filteredApps = remember(
        searchableApps,
        selectedProfileFilter,
        searchQuery,
        showSystemApps,
        importedTargets,
        selectedTargets
    ) {
        val q = searchQuery.trim().lowercase()
        searchableApps.filter { entry ->
            val inPool = entry.target in importedTargets || entry.target in selectedTargets
            val matchesProfile = selectedProfileFilter == -1L ||
                entry.target.userSerialNumber == selectedProfileFilter
            // "显示系统应用" only controls system app visibility; it never bypasses audio/video category filtering when search is empty
            val matchesCategoryWhenSearchEmpty = q.isNotEmpty() ||
                entry.isAudioOrVideoCategory ||
                inPool
            val matchesSystem = showSystemApps ||
                !entry.isSystemApp ||
                inPool ||
                q.isNotEmpty()
            val matchesQuery = q.isEmpty() ||
                entry.appLabel.lowercase().contains(q) ||
                entry.target.packageName.lowercase().contains(q)
            matchesProfile && matchesCategoryWhenSearchEmpty && matchesSystem && matchesQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("import_target_app_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sheet Title + Manual Package Input Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选择目标应用 (多空间实例)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "默认展示音视频与已导入目标；搜索或手动输入包名可添加任意应用 • 已添加 ${importedTargets.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = { showManualInput = !showManualInput },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Keyboard,
                            contentDescription = "手动输入包名",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (showManualInput) "收起包名输入" else "手动输入包名",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("toggle_manual_package_input_button")
                )
            }

            // Instant Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { newQuery ->
                    searchQuery = newQuery
                    onSearchQueryChanged(newQuery)
                },
                placeholder = { Text("搜索应用名称或包名...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "搜索")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                onSearchQueryChanged("")
                            },
                            modifier = Modifier.testTag("clear_search_query_button")
                        ) {
                            Icon(imageVector = Icons.Filled.Clear, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("import_search_input")
            )

            // Profile Filter Tabs + Show System Apps Toggle
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedProfileFilter == -1L,
                    onClick = {
                        selectedProfileFilter = -1L
                        manualSerial = defaultManualSerial
                    },
                    label = { Text("全部空间", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("import_profile_chip_all")
                )
                profiles.forEach { profile ->
                    FilterChip(
                        selected = selectedProfileFilter == profile.userSerialNumber,
                        onClick = {
                            selectedProfileFilter = profile.userSerialNumber
                            manualSerial = profile.userSerialNumber
                        },
                        label = {
                            Text(
                                text = "${profile.profileType.badgeLabel} (S${profile.userSerialNumber})",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("import_profile_chip_${profile.userSerialNumber}")
                    )
                }
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text("显示系统应用", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("toggle_system_apps_chip")
                )
            }

            // Collapsible Manual Package Input Section
            AnimatedVisibility(visible = showManualInput) {
                val effectiveManualSerial = if (selectedProfileFilter != -1L) {
                    selectedProfileFilter
                } else {
                    manualSerial
                }
                val selectedManualProfile = profiles.firstOrNull {
                    it.userSerialNumber == effectiveManualSerial
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "按精确包名与所属空间直接添加",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = buildString {
                                append("导入目标所属空间：")
                                if (selectedManualProfile != null) {
                                    append("${selectedManualProfile.profileType.badgeLabel} (S${selectedManualProfile.userSerialNumber})")
                                } else {
                                    append("S$effectiveManualSerial")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("manual_import_selected_profile_text")
                        )
                        if (profiles.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_import_profile_selector"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                profiles.forEach { profile ->
                                    FilterChip(
                                        selected = effectiveManualSerial == profile.userSerialNumber,
                                        onClick = {
                                            manualSerial = profile.userSerialNumber
                                            if (selectedProfileFilter != -1L) {
                                                selectedProfileFilter = profile.userSerialNumber
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = "${profile.profileType.badgeLabel} (S${profile.userSerialNumber})",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        modifier = Modifier.testTag("manual_import_profile_chip_${profile.userSerialNumber}")
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = manualPackageName,
                                onValueChange = { manualPackageName = it },
                                label = { Text("包名 (如 com.android.music)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_package_input")
                            )
                            Button(
                                onClick = {
                                    val pkg = manualPackageName.trim()
                                    if (pkg.isNotEmpty()) {
                                        onAddTargetApp(pkg, effectiveManualSerial)
                                        manualPackageName = ""
                                    }
                                },
                                enabled = manualPackageName.trim().contains('.'),
                                modifier = Modifier.testTag("confirm_import_target_button")
                            ) {
                                Text("添加")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Scrollable Installed Apps List with Icons
            if (filteredApps.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "未找到匹配的应用",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "可尝试开启“显示系统应用”或点击右上角“手动输入包名”按包名添加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 420.dp)
                        .testTag("visual_picker_app_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { it.target.toStorageKey() }
                    ) { item ->
                        val isAdded = item.target in importedTargets || item.target in selectedTargets
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAdded) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (isAdded) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    if (isAdded) {
                                        onRemoveTargetApp(item.target)
                                    } else {
                                        onAddTargetApp(
                                            item.target.packageName,
                                            item.target.userSerialNumber
                                        )
                                    }
                                }
                                .testTag("searchable_app_${item.target.toStorageKey()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    AppIconThumbnail(
                                        packageName = item.target.packageName,
                                        userSerialNumber = item.target.userSerialNumber,
                                        appLabel = item.appLabel,
                                        size = 40.dp
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = item.appLabel,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            ProfileBadge(
                                                profileType = item.profileType,
                                                serialNumber = item.target.userSerialNumber
                                            )
                                        }
                                        Text(
                                            text = buildString {
                                                append(item.target.packageName)
                                                if (item.resolvedUid != null) {
                                                    append(" • UID ${item.resolvedUid}")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        item.statusReason?.let { reason ->
                                            Text(
                                                text = reason,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = if (isAdded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isAdded) Icons.Filled.Check else Icons.Filled.Add,
                                            contentDescription = if (isAdded) "已添加" else "添加应用",
                                            tint = if (isAdded) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                            modifier = Modifier.size(18.dp)
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
}

@Composable
fun CollapsibleDiagnosticsCard(
    telemetry: AudioPipelineTelemetry,
    muteTelemetry: SessionMuteTelemetry,
    logs: List<String>
) {
    val alertSignature = buildString {
        if (telemetry.error != null) append("err:${telemetry.error}|")
        if (muteTelemetry.muteFailureReason != null) append("mute:${muteTelemetry.muteFailureReason}|")
        if (muteTelemetry.pendingRestoreCount > 0) append("restore:${muteTelemetry.pendingRestoreCount}|")
        if (muteTelemetry.isNewTrackLatencyWithinGate == false) append("lat:fail|")
    }
    var expanded by rememberSaveable { mutableStateOf(alertSignature.isNotEmpty()) }
    var lastObservedAlertSignature by rememberSaveable { mutableStateOf(alertSignature) }

    LaunchedEffect(alertSignature) {
        if (alertSignature.isNotEmpty() && alertSignature != lastObservedAlertSignature) {
            expanded = true
        }
        lastObservedAlertSignature = alertSignature
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostics_drawer_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp)
                    .testTag("diagnostics_expand_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "真机门禁遥测与实时诊断",
                        tint = if (muteTelemetry.leaseActive && telemetry.isInitialMuteConfirmed) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Column {
                        Text(
                            text = "真机门禁遥测与 WebRTC 诊断",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "UID 白名单 • getMutedBy() 混音器确认 • ≤100ms 新轨门禁 • 协议日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GateStatusPill(muteTelemetry = muteTelemetry)
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起诊断面板" else "展开诊断面板"
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    GateVerificationTelemetryContent(
                        telemetry = telemetry,
                        muteTelemetry = muteTelemetry
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    WebRtcLiveDiagnosticsContent(
                        telemetry = telemetry,
                        logs = logs
                    )
                }
            }
        }
    }
}

@Composable
private fun GateStatusPill(muteTelemetry: SessionMuteTelemetry) {
    val gateBadgeText = when {
        muteTelemetry.pendingRestoreCount > 0 -> "RESTORE PENDING (${muteTelemetry.pendingRestoreCount})"
        muteTelemetry.muteFailureReason != null -> "GATE BLOCKED"
        muteTelemetry.isNewTrackLatencyWithinGate == false -> ">100ms FAIL"
        muteTelemetry.leaseActive && muteTelemetry.confirmedMutedPlayerCount > 0 ->
            "MUTED & CAPTURING"
        muteTelemetry.leaseActive -> "LEASE ACTIVE"
        else -> "STANDBY"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            muteTelemetry.pendingRestoreCount > 0 ||
                muteTelemetry.muteFailureReason != null ||
                muteTelemetry.isNewTrackLatencyWithinGate == false ->
                MaterialTheme.colorScheme.errorContainer
            muteTelemetry.leaseActive ->
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
            else ->
                MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = gateBadgeText,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("gate_status_badge")
        )
    }
}

@Composable
fun GateVerificationTelemetryContent(
    telemetry: AudioPipelineTelemetry,
    muteTelemetry: SessionMuteTelemetry
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gate_verification_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1: UID Whitelist & Active/Muted Session Counts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GateMetricColumn(
                label = "白名单完整 UID",
                value = if (telemetry.activeTargetUids.isNotEmpty()) {
                    telemetry.activeTargetUids.sorted().joinToString(",")
                } else if (muteTelemetry.resolvedTargetUids.isNotEmpty()) {
                    muteTelemetry.resolvedTargetUids.sorted().joinToString(",")
                } else {
                    "未激活"
                }
            )
            GateMetricColumn(
                label = "目标播放轨 (STARTED)",
                value = "${muteTelemetry.activeTargetPlayerCount} 轨"
            )
            GateMetricColumn(
                label = "getMutedBy() 确认",
                value = "${muteTelemetry.confirmedMutedPlayerCount} 轨 (0x${muteTelemetry.lastMixerMutedByMask.toString(16)})"
            )
        }

        // Row 2: 100ms New-Track Latency Gate & dBFS Comparison
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val latencyText = muteTelemetry.lastMuteConfirmationLatencyMs?.let { last ->
                val max = muteTelemetry.maxObservedNewTrackMuteLatencyMs ?: last
                "${last}ms (峰值 ${max}ms / ≤100ms)"
            } ?: "unverified (待新轨触发)"
            GateMetricColumn(
                label = "STARTED→静音确认时延",
                value = latencyText
            )

            val preDb = muteTelemetry.preMuteBaselineRmsDb?.let { "${it.roundToInt()} dBFS" } ?: "--"
            val postDb = muteTelemetry.postMuteActiveRmsDb?.let { "${it.roundToInt()} dBFS" }
                ?: if (telemetry.isSessionActive && !telemetry.isHardwareCaptureDegraded) {
                    "${telemetry.currentRmsDb.roundToInt()} dBFS"
                } else "--"
            GateMetricColumn(
                label = "静音前后采集 dBFS",
                value = "$preDb → $postDb"
            )

            val captureValidText = when {
                telemetry.isHardwareCaptureDegraded -> "DEGRADED"
                telemetry.isHardwareCaptureActive -> "HW VALID"
                else -> "IDLE"
            }
            GateMetricColumn(
                label = "采集轨有效性与租约",
                value = "$captureValidText (#${muteTelemetry.leaseRenewalCount})"
            )
        }

        muteTelemetry.muteFailureReason?.let { reason ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mute_failure_reason_box")
            ) {
                Text(
                    text = "静音门禁告警：$reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun WebRtcLiveDiagnosticsContent(
    telemetry: AudioPipelineTelemetry,
    logs: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telemetry_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GateMetricColumn(
                label = "WebRTC 阶段",
                value = telemetry.webRtcStage.name
            )
            GateMetricColumn(
                label = "RTP 已发送包 / 字节",
                value = "${telemetry.audioPacketsSent} pkts (${telemetry.totalBytesStreamed / 1024} KB)"
            )
            GateMetricColumn(
                label = "RTT 往返时延",
                value = telemetry.rttMs?.let { "${(it * 1000).roundToInt()} ms" } ?: "--"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GateMetricColumn(
                label = "PCM 读取块 / 环形深度",
                value = "${telemetry.pcmChunksRead} (${telemetry.pcmRingDepth})"
            )
            GateMetricColumn(
                label = "Underruns / Dropped",
                value = "${telemetry.pcmUnderruns} / ${telemetry.pcmDroppedChunks}"
            )
            GateMetricColumn(
                label = "AudioRecord 最小缓冲",
                value = "${telemetry.audioRecordMinBufferBytes} B"
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("protocol_log_box")
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "WebRTC & Shizuku 协议事件日志",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                logs.takeLast(8).forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun GateMetricColumn(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
