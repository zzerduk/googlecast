package com.example

import androidx.test.core.app.ApplicationProvider
import io.github.ddagunts.screencast.cast.CastOptionsProvider
import io.github.ddagunts.screencast.webrtc.WEBRTC_RECEIVER_APP_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastOptionsProviderTest {
    @Test
    fun usesTheCustomReceiverWithoutAMediaSession() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val provider = CastOptionsProvider()
        val options = provider.getCastOptions(context)

        assertEquals(WEBRTC_RECEIVER_APP_ID, options.receiverApplicationId)
        assertNull(options.castMediaOptions)
        assertNull(provider.getAdditionalSessionProviders(context))
    }
}
