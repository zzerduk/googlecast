package com.example

import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.ui.theme.MyApplicationTheme
import io.github.ddagunts.screencast.cast.CastSdkSessionStatus
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.PendingGlobalMuteRecovery
import io.github.ddagunts.screencast.target.PhoneOutputMode
import io.github.ddagunts.screencast.target.ProfileType
import io.github.ddagunts.screencast.target.SessionMuteTelemetry
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.TargetPreset
import io.github.ddagunts.screencast.target.UserProfileInfo
import io.github.ddagunts.screencast.ui.AppIconLoader
import io.github.ddagunts.screencast.ui.ScreenCastAudioScreen
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class WebRtcScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        AppIconLoader.clearCacheForTesting()
    }

    @Test
    fun pureWebRtcConsoleUsesCastSelectorAndUnifiedTargetCardWithoutLegacyAacOrCategoryTabs() {
        showScreen()
        compose.onNodeWithTag("screencast_main_list")
            .assertExists()
        compose.onNodeWithTag("cast_devices_card").assertDoesNotExist()
        compose.onNodeWithTag("stream_tuning_card").assertDoesNotExist()
        compose.onNodeWithTag("cast_mode_selector").assertDoesNotExist()
        compose.onNodeWithTag("category_tab_AUDIO").assertDoesNotExist()
        compose.onNodeWithTag("category_tab_VIDEO").assertDoesNotExist()
        compose.onNodeWithTag("start_cast_button").assertIsNotEnabled()
        compose.onNodeWithTag("status_four_dimensions_row").assertExists()
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("cast_selector_slot"))
        compose.onNodeWithTag("cast_selector_slot").assertExists()
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("cast_speaker_control_card"))
        compose.onNodeWithTag("cast_speaker_control_card").assertExists()
    }

    @Test
    fun visualAppPickerBottomSheetShowsProfileTabsSearchAndEnforcesAudioVideoDefaultFilterEvenWithSystemToggle() {
        val sampleAudioApp = TargetAppEntry(
            target = CaptureTarget("com.android.music", 0L),
            appLabel = "Music",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10120,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_AUDIO
        )
        val systemAudioApp = TargetAppEntry(
            target = CaptureTarget("com.android.system.fmradio", 0L),
            appLabel = "System FM Radio",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10050,
            isSystemApp = true,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_AUDIO
        )
        val systemCalculatorApp = TargetAppEntry(
            target = CaptureTarget("com.android.calculator2", 0L),
            appLabel = "System Calculator",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10051,
            isSystemApp = true,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_PRODUCTIVITY
        )
        val uncategorizedToolApp = TargetAppEntry(
            target = CaptureTarget("com.example.notes", 0L),
            appLabel = "Quick Notes",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10199,
            isSystemApp = false,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_UNDEFINED
        )
        val observedQueries = mutableListOf<String>()

        showScreen(
            searchableApps = listOf(
                sampleAudioApp,
                systemAudioApp,
                systemCalculatorApp,
                uncategorizedToolApp
            ),
            onSearchQueryChanged = { observedQueries.add(it) }
        )
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("open_import_target_dialog_button"))
        compose.onNodeWithTag("open_import_target_dialog_button").performClick()
        compose.onNodeWithTag("import_target_app_dialog").assertExists()
        compose.onNodeWithTag("import_search_input").assertExists()
        compose.onNodeWithTag("import_profile_chip_all").assertExists()

        // Default empty search shows user audio app, hides system audio app and non-audio/video apps
        compose.onNodeWithTag("searchable_app_com.android.music@0").assertExists()
        compose.onNodeWithTag("searchable_app_com.android.system.fmradio@0").assertDoesNotExist()
        compose.onNodeWithTag("searchable_app_com.android.calculator2@0").assertDoesNotExist()
        compose.onNodeWithTag("searchable_app_com.example.notes@0").assertDoesNotExist()

        // Toggling "显示系统应用" with empty search reveals system audio/video apps, but NEVER bypasses audio/video category filter
        compose.onNodeWithTag("toggle_system_apps_chip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("searchable_app_com.android.system.fmradio@0").assertExists()
        compose.onNodeWithTag("searchable_app_com.android.calculator2@0").assertDoesNotExist()
        compose.onNodeWithTag("searchable_app_com.example.notes@0").assertDoesNotExist()

        // Typing a non-empty search query notifies onSearchQueryChanged and matches non-audio/video apps
        compose.onNodeWithTag("import_search_input").performTextReplacement("Notes")
        compose.waitForIdle()
        assertTrue(observedQueries.contains("Notes"))
        compose.onNodeWithTag("searchable_app_com.example.notes@0").assertExists()

        // Clearing search immediately restores the default audio/video filter
        compose.onNodeWithTag("import_search_input").performTextReplacement("")
        compose.waitForIdle()
        compose.onNodeWithTag("searchable_app_com.example.notes@0").assertDoesNotExist()
        compose.onNodeWithTag("searchable_app_com.android.music@0").assertExists()
    }

    @Test
    fun appIconThumbnailLoadsOnBackgroundThreadInsteadOfMainCompositionThread() {
        AppIconLoader.clearCacheForTesting()
        val loadedOffMainThread = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        AppIconLoader.onLoadIconHookForTesting = { _, _, _ ->
            loadedOffMainThread.set(Looper.myLooper() != Looper.getMainLooper())
            latch.countDown()
        }

        val sampleAudioApp = TargetAppEntry(
            target = CaptureTarget("com.android.music", 0L),
            appLabel = "Music",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10120,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_AUDIO
        )
        showScreen(
            catalogApps = listOf(sampleAudioApp),
            selectedTargets = setOf(sampleAudioApp.target),
            importedTargets = setOf(sampleAudioApp.target)
        )
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("target_app_selector_card"))
        compose.waitForIdle()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue("AppIconLoader must run on a background thread, not main thread", loadedOffMainThread.get())
    }

    @Test
    fun pendingRecoveryBlocksStartCastAndPendingShaperRestoreDisplaysWarningPill() {
        val sampleAudioApp = TargetAppEntry(
            target = CaptureTarget("com.android.music", 0L),
            appLabel = "Music",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10120,
            isInstalled = true,
            isProfileRunning = true,
            appCategory = ApplicationInfo.CATEGORY_AUDIO
        )
        showScreen(
            muteTelemetry = SessionMuteTelemetry(
                pendingRestoreCount = 1,
                muteFailureReason = "仍有 1 个播放会话未能移除静音包络 (piids=[88])"
            ),
            castStatus = CastSdkSessionStatus(
                isAvailable = true,
                isConnected = true,
                deviceName = "Living Room Speaker"
            ),
            catalogApps = listOf(sampleAudioApp),
            selectedTargets = setOf(sampleAudioApp.target),
            importedTargets = setOf(sampleAudioApp.target),
            pendingGlobalMuteRecovery = PendingGlobalMuteRecovery(
                savedVolume = 10,
                maxVolume = 15,
                wasMuted = false,
                currentVolume = 0
            )
        )
        // Pending global mute recovery disables start_cast_button even when Cast is connected and target is selected
        compose.onNodeWithTag("start_cast_button").assertIsNotEnabled()
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("diagnostics_drawer_card"))
        compose.onNodeWithText("RESTORE PENDING (1)").assertExists()
    }

    @Test
    fun fallbackModeDialogAndLeftoverRecoveryDialogRenderAndRespondToActions() {
        var selectedMode: PhoneOutputMode? = null
        var restoredLeftover = false
        var showModeDialog by mutableStateOf(true)
        var pendingRecovery by mutableStateOf<PendingGlobalMuteRecovery?>(null)

        compose.setContent {
            MyApplicationTheme {
                ScreenCastAudioScreen(
                    config = AudioStreamConfig(),
                    telemetry = AudioPipelineTelemetry(),
                    logs = emptyList(),
                    castStatus = CastSdkSessionStatus(isAvailable = true),
                    isRequestingCapture = false,
                    castButton = { Text("Cast", Modifier.testTag("cast_selector_slot")) },
                    onStartCastClicked = {},
                    onStopCastClicked = {},
                    catalogApps = emptyList(),
                    searchableApps = emptyList(),
                    profiles = listOf(
                        UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true)
                    ),
                    showOutputModeDialog = showModeDialog,
                    isGlobalMediaMuteCompatible = false,
                    globalMediaMuteDisabledReason = "本机全局媒体静音会导致回采静音",
                    pendingGlobalMuteRecovery = pendingRecovery,
                    onSelectFallbackOutputMode = {
                        selectedMode = it
                        showModeDialog = false
                    },
                    onConfirmRestoreLeftoverVolume = {
                        restoredLeftover = true
                        pendingRecovery = null
                    }
                )
            }
        }

        compose.onNodeWithTag("fallback_output_mode_dialog").assertExists()
        compose.onNodeWithTag("global_mute_incompatible_reason_text").assertExists()
        compose.onNodeWithTag("select_mode_global_mute_button").assertIsNotEnabled()
        compose.onNodeWithTag("fallback_output_mode_dialog_scroll")
            .performScrollToNode(hasTestTag("select_mode_capture_only_button"))
        compose.onNodeWithTag("select_mode_capture_only_button").assertIsEnabled()
        compose.onNodeWithTag("select_mode_capture_only_button").performClick()
        compose.waitForIdle()
        assertEquals(PhoneOutputMode.CAPTURE_ONLY_UNMUTED, selectedMode)

        pendingRecovery = PendingGlobalMuteRecovery(
            savedVolume = 11,
            maxVolume = 15,
            wasMuted = false,
            currentVolume = 0
        )
        compose.waitForIdle()
        compose.onNodeWithTag("leftover_volume_recovery_dialog").assertExists()
        compose.onNodeWithTag("restore_leftover_volume_button").performClick()
        compose.waitForIdle()
        assertEquals(true, restoredLeftover)
    }

    @Test
    fun embeddedTargetPresetBarAndCastSpeakerControlCardReflectConnectionAndLockState() {
        var appliedPresetName: String? = null
        val preset = TargetPreset(
            name = "常听双应用",
            targets = setOf(CaptureTarget("com.metrolist.music", 0L))
        )

        showScreen(
            castStatus = CastSdkSessionStatus(
                isAvailable = true,
                isConnected = true,
                deviceName = "Living Room Speaker",
                volume = 0.65,
                isMuted = false,
                isVolumeControlAvailable = true
            ),
            presets = listOf(preset),
            onApplyPreset = { appliedPresetName = it }
        )

        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("cast_speaker_control_card"))
        compose.onNodeWithTag("speaker_volume_slider").assertIsEnabled()
        compose.onNodeWithTag("speaker_mute_switch").assertIsEnabled()

        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("target_presets_bar"))
        compose.onNodeWithTag("preset_chip_常听双应用").performClick()
        assertEquals("常听双应用", appliedPresetName)
    }

    @Test
    fun negotiationCanBeStoppedBeforeAudioIsSent() {
        showScreen(AudioPipelineTelemetry(isSessionActive = true, webRtcStage = WebRtcStage.NEGOTIATING))
        compose.onNodeWithTag("stop_cast_button").assertExists()
        compose.onNodeWithTag("start_cast_button").assertDoesNotExist()
        compose.onNodeWithTag("zero_audio_level_hint").assertExists()
    }

    @Test
    fun cleanupTimeoutDisablesStartButtonAndShowsTimeoutError() {
        showScreen(
            telemetry = AudioPipelineTelemetry(
                isSessionActive = false,
                webRtcStage = WebRtcStage.CLEANUP_TIMEOUT,
                isCaptureLocked = true,
                error = "WebRTC native audio cleanup timed out after 3000 ms"
            ),
            castStatus = CastSdkSessionStatus(isAvailable = true, isConnected = true, deviceName = "Living Room")
        )
        compose.onNodeWithTag("start_cast_button").assertIsNotEnabled()
        compose.onNodeWithTag("capture_error_text").assertExists()
    }

    private fun showScreen(
        telemetry: AudioPipelineTelemetry = AudioPipelineTelemetry(),
        muteTelemetry: SessionMuteTelemetry = SessionMuteTelemetry(),
        castStatus: CastSdkSessionStatus = CastSdkSessionStatus(isAvailable = true),
        catalogApps: List<TargetAppEntry> = emptyList(),
        searchableApps: List<TargetAppEntry> = emptyList(),
        selectedTargets: Set<CaptureTarget> = emptySet(),
        importedTargets: Set<CaptureTarget> = emptySet(),
        presets: List<TargetPreset> = emptyList(),
        showOutputModeDialog: Boolean = false,
        isGlobalMediaMuteCompatible: Boolean = true,
        globalMediaMuteDisabledReason: String? = null,
        pendingGlobalMuteRecovery: PendingGlobalMuteRecovery? = null,
        onSelectFallbackOutputMode: (PhoneOutputMode) -> Unit = {},
        onConfirmRestoreLeftoverVolume: () -> Unit = {},
        onApplyPreset: (String) -> Unit = {},
        onSearchQueryChanged: (String) -> Unit = {}
    ) {
        compose.setContent {
            MyApplicationTheme {
                ScreenCastAudioScreen(
                    config = AudioStreamConfig(),
                    telemetry = telemetry,
                    muteTelemetry = muteTelemetry,
                    logs = emptyList(),
                    castStatus = castStatus,
                    isRequestingCapture = false,
                    castButton = { Text("Cast selector", Modifier.testTag("cast_selector_slot")) },
                    onStartCastClicked = {},
                    onStopCastClicked = {},
                    catalogApps = catalogApps,
                    selectedTargets = selectedTargets,
                    importedTargets = importedTargets,
                    profiles = listOf(
                        UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true),
                        UserProfileInfo(10L, 10, "工作资料 (User 10)", ProfileType.WORK, true)
                    ),
                    searchableApps = searchableApps,
                    presets = presets,
                    showOutputModeDialog = showOutputModeDialog,
                    isGlobalMediaMuteCompatible = isGlobalMediaMuteCompatible,
                    globalMediaMuteDisabledReason = globalMediaMuteDisabledReason,
                    pendingGlobalMuteRecovery = pendingGlobalMuteRecovery,
                    onSelectFallbackOutputMode = onSelectFallbackOutputMode,
                    onConfirmRestoreLeftoverVolume = onConfirmRestoreLeftoverVolume,
                    onApplyPreset = onApplyPreset,
                    onSearchQueryChanged = onSearchQueryChanged
                )
            }
        }
    }
}
