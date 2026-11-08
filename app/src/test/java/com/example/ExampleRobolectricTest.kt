package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.CaptureTargetRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        AudioCastSessionCoordinator.resetForTesting()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ScreenCast Audio", appName)
    }

    @Test
    fun `opening visual app picker bottom sheet works cleanly on Android 14`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        CaptureTargetRepository.setTargetsForTesting(
            setOf(CaptureTarget("com.android.music", 0L))
        )

        composeRule.onNodeWithTag("screencast_main_list")
            .performScrollToNode(hasTestTag("open_import_target_dialog_button"))
        composeRule.onNodeWithTag("open_import_target_dialog_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("import_target_app_dialog").assertExists()

        val errorText = AudioCastSessionCoordinator.telemetry.value.error.orEmpty()
        assertFalse(
            "Expected no 16-bit requestCode exception, but got: $errorText",
            errorText.contains("lower 16 bits", ignoreCase = true)
        )
    }
}
