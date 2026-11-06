package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.media.AudioCastSessionCoordinator
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
  fun `clicking start cast on Android 14 launches permission and mediaProjection without 16-bit requestCode error`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).grantPermissions(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.POST_NOTIFICATIONS
    )

    composeRule.onNodeWithTag("cast_mode_AAC").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("start_cast_button").performClick()
    composeRule.waitForIdle()

    val errorText = AudioCastSessionCoordinator.telemetry.value.error.orEmpty()
    assertFalse(
      "Expected no 16-bit requestCode exception, but got: $errorText",
      errorText.contains("lower 16 bits", ignoreCase = true)
    )
  }
}

