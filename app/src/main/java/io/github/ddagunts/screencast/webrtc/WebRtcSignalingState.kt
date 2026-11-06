package io.github.ddagunts.screencast.webrtc

import io.github.ddagunts.screencast.cast.CastSignalingChannel
import org.json.JSONObject
import org.webrtc.IceCandidate

internal class WebRtcSignalingState(
    private val channel: CastSignalingChannel,
    private val applyAnswer: suspend (String) -> Unit,
    private val applyCandidate: (IceCandidate) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val localCandidates = ArrayDeque<IceCandidate>()
    private val remoteCandidates = ArrayDeque<IceCandidate>()
    private var offerSent = false
    private var answerApplied = false
    private var closed = false

    suspend fun sendOffer(sdp: String) {
        check(!closed && !offerSent) { "OFFER is not valid in the current session" }
        require(sdp.isNotBlank()) { "Empty OFFER SDP" }
        offerSent = true
        channel.send(JSONObject().put("type", "OFFER").put("sdp", sdp).toString())
    }

    suspend fun onLocalCandidate(candidate: IceCandidate) {
        if (closed || candidate.sdp.isBlank()) return
        if (answerApplied) sendCandidate(candidate) else localCandidates.addLast(candidate)
    }

    suspend fun onMessage(message: String) {
        if (closed) return
        val payload = JSONObject(message)
        when (payload.optString("type")) {
            "READY" -> onLog("Receiver READY hint received")
            "ANSWER" -> {
                if (answerApplied) {
                    onLog("Ignoring duplicate ANSWER")
                    return
                }
                check(offerSent) { "ANSWER arrived before OFFER" }
                val sdp = payload.get("sdp")
                require(sdp is String && sdp.isNotBlank()) { "Invalid ANSWER SDP" }
                applyAnswer(sdp)
                if (closed) return
                answerApplied = true
                while (remoteCandidates.isNotEmpty()) applyCandidate(remoteCandidates.removeFirst())
                while (localCandidates.isNotEmpty()) sendCandidate(localCandidates.removeFirst())
                onLog("ANSWER applied; queued ICE released")
            }
            "ICE" -> {
                val candidate = parseCandidate(payload.getJSONObject("candidate")) ?: return
                if (answerApplied) applyCandidate(candidate) else remoteCandidates.addLast(candidate)
            }
            "BYE" -> error("Receiver ended the WebRTC session")
            else -> onLog("Ignoring unknown receiver message")
        }
    }

    fun close() {
        closed = true
        localCandidates.clear()
        remoteCandidates.clear()
    }

    private suspend fun sendCandidate(candidate: IceCandidate) {
        val value = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid ?: JSONObject.NULL)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
        channel.send(JSONObject().put("type", "ICE").put("candidate", value).toString())
    }

    private fun parseCandidate(value: JSONObject): IceCandidate? {
        val sdp = value.get("candidate")
        require(sdp is String) { "Invalid ICE candidate string" }
        val mid = value.get("sdpMid")
        require(mid is String || mid === JSONObject.NULL) { "Invalid ICE sdpMid" }
        val index = value.get("sdpMLineIndex")
        require(
            index is Number &&
                index.toDouble() >= 0 &&
                index.toDouble() <= Int.MAX_VALUE.toDouble() &&
                index.toDouble() == index.toInt().toDouble()
        ) { "Invalid ICE sdpMLineIndex" }
        if (sdp.isBlank()) return null
        return IceCandidate(if (mid is String) mid else null, index.toInt(), sdp)
    }
}
