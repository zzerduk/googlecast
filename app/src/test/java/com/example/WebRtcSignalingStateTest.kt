package com.example

import io.github.ddagunts.screencast.cast.CastSignalingChannel
import io.github.ddagunts.screencast.webrtc.WebRtcSignalingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.webrtc.IceCandidate

@RunWith(RobolectricTestRunner::class)
class WebRtcSignalingStateTest {
    @Test
    fun localAndRemoteIceWaitUntilAnswerIsApplied() = runTest {
        val channel = FakeChannel()
        val remote = mutableListOf<IceCandidate>()
        val applied = CompletableDeferred<Unit>()
        val state = WebRtcSignalingState(channel, { applied.await() }, { remote.add(it) }, {})
        state.onLocalCandidate(IceCandidate("audio-mid", 2, "local-candidate"))
        state.onMessage(iceMessage("remote-candidate", "remote-mid", 3))
        state.sendOffer("audio-sdp")
        val answer = launch { state.onMessage("""{"type":"ANSWER","sdp":"answer-sdp"}""") }
        testScheduler.runCurrent()
        assertEquals(listOf("OFFER"), channel.messages.map { JSONObject(it).getString("type") })
        assertTrue(remote.isEmpty())
        applied.complete(Unit)
        answer.join()
        assertEquals("remote-candidate", remote.single().sdp)
        val candidate = JSONObject(channel.messages.last()).getJSONObject("candidate")
        assertEquals("local-candidate", candidate.getString("candidate"))
        assertEquals("audio-mid", candidate.getString("sdpMid"))
        assertEquals(2, candidate.getInt("sdpMLineIndex"))
    }

    @Test
    fun offerIsSentWithoutReadyAndSerializedOnce() = runTest {
        val channel = FakeChannel()
        val state = WebRtcSignalingState(channel, {}, {}, {})
        val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=msid:\"audio\""
        state.sendOffer(sdp)
        val offer = JSONObject(channel.messages.single())
        assertEquals("OFFER", offer.getString("type"))
        assertEquals(sdp, offer.getString("sdp"))
    }

    @Test
    fun failedAnswerDoesNotReleaseCandidates() = runTest {
        val channel = FakeChannel()
        val remote = mutableListOf<IceCandidate>()
        val state = WebRtcSignalingState(channel, { error("bad answer") }, { remote.add(it) }, {})
        state.sendOffer("audio-sdp")
        state.onLocalCandidate(IceCandidate("0", 0, "local-candidate"))
        state.onMessage(iceMessage("remote-candidate", "0", 0))
        val error = runCatching { state.onMessage("""{"type":"ANSWER","sdp":"answer-sdp"}""") }
        assertTrue(error.isFailure)
        assertEquals(1, channel.messages.size)
        assertTrue(remote.isEmpty())
    }

    @Test
    fun closeDiscardsCandidatesAndIgnoresLateCallbacks() = runTest {
        val channel = FakeChannel()
        var answerApplied = false
        val remote = mutableListOf<IceCandidate>()
        val state = WebRtcSignalingState(channel, { answerApplied = true }, { remote.add(it) }, {})
        state.sendOffer("audio-sdp")
        state.onLocalCandidate(IceCandidate("0", 0, "old-local"))
        state.onMessage(iceMessage("old-remote", "0", 0))
        state.close()
        state.onMessage("""{"type":"ANSWER","sdp":"late-answer"}""")
        state.onLocalCandidate(IceCandidate("0", 0, "late-local"))
        assertFalse(answerApplied)
        assertTrue(remote.isEmpty())
        assertEquals(1, channel.messages.size)
    }

    @Test
    fun duplicateAnswerIsIgnoredAndMalformedIceIsRejected() = runTest {
        val channel = FakeChannel()
        var answers = 0
        val state = WebRtcSignalingState(channel, { answers++ }, {}, {})
        state.sendOffer("audio-sdp")
        state.onMessage("""{"type":"ANSWER","sdp":"answer"}""")
        state.onMessage("""{"type":"ANSWER","sdp":"duplicate"}""")
        assertEquals(1, answers)
        assertTrue(runCatching {
            state.onMessage("""{"type":"ICE","candidate":{"candidate":"x","sdpMid":"0","sdpMLineIndex":-1}}""")
        }.isFailure)
        assertTrue(runCatching {
            state.onMessage("""{"type":"ICE","candidate":{"candidate":"x","sdpMid":"0","sdpMLineIndex":"0"}}""")
        }.isFailure)
    }

    private fun iceMessage(candidate: String, mid: String, index: Int): String =
        JSONObject().put("type", "ICE").put(
            "candidate",
            JSONObject().put("candidate", candidate).put("sdpMid", mid).put("sdpMLineIndex", index)
        ).toString()

    private class FakeChannel : CastSignalingChannel {
        override val isConnected = true
        val messages = mutableListOf<String>()
        override fun setMessageReceiver(receiver: (String) -> Unit) {}
        override fun removeMessageReceiver() {}
        override suspend fun send(message: String) {
            messages.add(message)
        }
        override fun endSession() {}
    }
}
