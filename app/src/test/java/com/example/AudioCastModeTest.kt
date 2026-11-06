package com.example

import io.github.ddagunts.screencast.media.AudioCastMode
import io.github.ddagunts.screencast.media.AudioStreamConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCastModeTest {
    @Test
    fun webRtcIsDefaultAndAacSettingsRemainAvailable() {
        val config = AudioStreamConfig()
        assertEquals(AudioCastMode.WEBRTC, config.mode)
        val aac = config.copy(mode = AudioCastMode.AAC)
        assertEquals(48000, aac.sampleRateHz)
        assertEquals(192000, aac.bitrateBps)
        assertEquals(300, aac.preBurstBufferMs)
        assertEquals(config.preBurstFrameCount, aac.preBurstFrameCount)
    }
}
