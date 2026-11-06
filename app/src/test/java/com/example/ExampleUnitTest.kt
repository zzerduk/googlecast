package com.example

import io.github.ddagunts.screencast.cast.CastDeviceModel
import io.github.ddagunts.screencast.cast.CastV2ChannelClient
import io.github.ddagunts.screencast.cast.CastV2Message
import io.github.ddagunts.screencast.cast.CastV2ProtoWire
import io.github.ddagunts.screencast.media.AdtsAacEncoder
import io.github.ddagunts.screencast.media.AudioStreamConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    @Test
    fun phase1_firstGenGoogleHomeWithoutVideoBitIsUnlocked() {
        // 1st-Gen Google Home reports md="Google Home" and ca=2052 (0x800 | 0x04), no 0x01 video bit
        val googleHome = CastDeviceModel(
            id = "gh-1st-gen",
            friendlyName = "Kitchen Speaker",
            modelName = "Google Home",
            hostAddress = "192.168.1.50",
            port = 8009,
            capabilityMask = 2052
        )
        assertFalse(googleHome.hasVideoOut)
        assertTrue(googleHome.hasAudioOut)
        assertTrue(googleHome.isAudioOnlySpeaker)
        assertTrue(googleHome.isFirstGenGoogleHome)
        assertTrue(CastDeviceModel.isEligibleCastReceiver(2052, "Google Home"))
    }

    @Test
    fun phase3_adtsHeaderEncodingMatchesSpec() {
        val rawLen = 250
        val packetLen = rawLen + 7
        val header = AdtsAacEncoder.createAdtsHeader(
            packetLength = packetLen,
            sampleRateIndex = 3, // 48000 Hz
            channelCount = 2
        )
        assertEquals(7, header.size)
        assertEquals(0xFF.toByte(), header[0])
        assertEquals(0xF9.toByte(), header[1])

        // Reconstruct 13-bit packetLength from bytes 3, 4, 5
        val extractedLen = ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xE0) ushr 5)
        assertEquals(packetLen, extractedLen)
    }

    @Test
    fun phase4_castV2LoadPayloadUsesLiveAudioAacAndDisablesHlsSyncLoop() {
        val device = CastDeviceModel(
            id = "gh-1",
            friendlyName = "Bedroom Speaker",
            modelName = "Google Home",
            hostAddress = "192.168.1.77",
            capabilityMask = 2052
        )
        val client = CastV2ChannelClient(device)
        assertFalse(client.isHlsSyncLoopEnabled)

        val streamUrl = "http://192.168.1.10:41922/c/a1b2c3d4e5f6/stream.aac"
        val jsonStr = client.buildLiveAudioLoadPayload(
            streamUrl = streamUrl,
            castSessionId = "session-123",
            requestId = 42
        )
        val root = JSONObject(jsonStr)
        assertEquals("LOAD", root.getString("type"))
        val media = root.getJSONObject("media")
        assertEquals(streamUrl, media.getString("contentId"))
        assertEquals("LIVE", media.getString("streamType"))
        assertEquals("audio/aac", media.getString("contentType"))
    }

    @Test
    fun phase4_castV2ProtoWireRoundTrip() {
        val original = CastV2Message(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = CastV2Message.NS_RECEIVER,
            payloadUtf8 = """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}"""
        )
        val bytes = CastV2ProtoWire.encodeToProtoBytes(original)
        val decoded = CastV2ProtoWire.decodeFromProtoBytes(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun phase5_defaultConfigIsAudioOnlyWith300msPreBurst() {
        val config = AudioStreamConfig()
        assertTrue(config.isAudioOnlyLowLatencyMode)
        assertEquals(300, config.preBurstBufferMs)
        assertEquals(48000, config.sampleRateHz)
        assertTrue(config.preBurstFrameCount >= 14)
    }
}
