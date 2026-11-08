package com.example

import io.github.ddagunts.screencast.media.AudioStreamConfig
import io.github.ddagunts.screencast.webrtc.WEBRTC_AUDIO_CHANNELS
import io.github.ddagunts.screencast.webrtc.WEBRTC_AUDIO_SAMPLE_RATE
import io.github.ddagunts.screencast.webrtc.WEBRTC_NAMESPACE
import io.github.ddagunts.screencast.webrtc.WEBRTC_RECEIVER_APP_ID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    @Test
    fun defaultWebRtcConfigUses48kHzStereoAndReceiver9098830C() {
        val config = AudioStreamConfig()
        assertEquals(48000, config.sampleRateHz)
        assertEquals(2, config.channels)
        assertEquals(WEBRTC_RECEIVER_APP_ID, config.receiverAppId)
        assertEquals(48000, WEBRTC_AUDIO_SAMPLE_RATE)
        assertEquals(2, WEBRTC_AUDIO_CHANNELS)
        assertEquals("urn:x-cast:io.github.ddagunts.screencast.webrtc", WEBRTC_NAMESPACE)
    }
}
