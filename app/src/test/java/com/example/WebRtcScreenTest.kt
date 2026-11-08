package com.example

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.ui.theme.MyApplicationTheme
import io.github.ddagunts.screencast.cast.CastSdkSessionStatus
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.ProfileType
import io.github.ddagunts.screencast.target.TargetAppEntry
import io.github.ddagunts.screencast.target.UserProfileInfo
import io.github.ddagunts.screencast.ui.ScreenCastAudioScreen
import io.github.ddagunts.screencast.webrtc.WebRtcStage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebRtcScreenTest {
    @get:Rule
    val compose = createComposeRule()

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
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("cast_selector_slot"))
        compose.onNodeWithTag("cast_selector_slot").assertExists()
    }

    @Test
    fun visualAppPickerBottomSheetShowsProfileTabsAndSearch() {
        val sampleApp = TargetAppEntry(
            target = CaptureTarget("com.android.music", 0L),
            appLabel = "Music",
            profileLabel = "主空间 (User 0)",
            profileType = ProfileType.PRIMARY,
            resolvedUid = 10120,
            isInstalled = true,
            isProfileRunning = true
        )
        showScreen(searchableApps = listOf(sampleApp))
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("open_import_target_dialog_button"))
        compose.onNodeWithTag("open_import_target_dialog_button").performClick()
        compose.onNodeWithTag("import_target_app_dialog").assertExists()
        compose.onNodeWithTag("import_search_input").assertExists()
        compose.onNodeWithTag("import_profile_chip_all").assertExists()
        compose.onNodeWithTag("searchable_app_com.android.music@0").assertExists()
    }

    @Test
    fun negotiationCanBeStoppedBeforeAudioIsSent() {
        showScreen(AudioPipelineTelemetry(isSessionActive = true, webRtcStage = WebRtcStage.NEGOTIATING))
        compose.onNodeWithTag("stop_cast_button").assertExists()
        compose.onNodeWithTag("start_cast_button").assertDoesNotExist()
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
        castStatus: CastSdkSessionStatus = CastSdkSessionStatus(isAvailable = true),
        searchableApps: List<TargetAppEntry> = emptyList()
    ) {
        compose.setContent {
            MyApplicationTheme {
                ScreenCastAudioScreen(
                    config = AudioStreamConfig(),
                    telemetry = telemetry,
                    logs = emptyList(),
                    castStatus = castStatus,
                    isRequestingCapture = false,
                    castButton = { Text("Cast selector", Modifier.testTag("cast_selector_slot")) },
                    onStartCastClicked = {},
                    onStopCastClicked = {},
                    profiles = listOf(
                        UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true),
                        UserProfileInfo(10L, 10, "工作资料 (User 10)", ProfileType.WORK, true)
                    ),
                    searchableApps = searchableApps
                )
            }
        }
    }
}
