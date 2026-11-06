package com.example

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.example.ui.theme.MyApplicationTheme
import io.github.ddagunts.screencast.cast.CastSdkSessionStatus
import io.github.ddagunts.screencast.media.AudioPipelineTelemetry
import io.github.ddagunts.screencast.media.AudioStreamConfig
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
    fun defaultModeUsesCastSelectorWithoutLegacyDeviceList() {
        showScreen()
        compose.onNodeWithTag("screencast_main_list")
            .assertExists()
        compose.onNodeWithTag("cast_devices_card").assertDoesNotExist()
        compose.onNodeWithTag("stream_tuning_card").assertDoesNotExist()
        compose.onNodeWithTag("start_cast_button").assertIsNotEnabled()
        compose.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("cast_selector_slot"))
        compose.onNodeWithTag("cast_selector_slot").assertExists()
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
        castStatus: CastSdkSessionStatus = CastSdkSessionStatus(isAvailable = true)
    ) {
        compose.setContent {
            MyApplicationTheme {
                ScreenCastAudioScreen(
                    devices = emptyList(),
                    selectedDeviceId = null,
                    isScanning = false,
                    config = AudioStreamConfig(),
                    telemetry = telemetry,
                    adtsHeaderHex = "",
                    logs = emptyList(),
                    castStatus = castStatus,
                    isRequestingCapture = false,
                    onModeChanged = {},
                    castButton = { Text("Cast selector", Modifier.testTag("cast_selector_slot")) },
                    onScanClicked = {},
                    onSelectDevice = {},
                    onAddManualSpeaker = { _, _, _, _, _ -> },
                    onToggleAudioOnlyMode = {},
                    onPreBurstChanged = {},
                    onSampleRateChanged = {},
                    onBitrateChanged = {},
                    onStartCastClicked = {},
                    onStopCastClicked = {}
                )
            }
        }
    }
}
