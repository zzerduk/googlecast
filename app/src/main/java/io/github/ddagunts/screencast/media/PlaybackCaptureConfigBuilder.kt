package io.github.ddagunts.screencast.media

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Shared builder for [AudioPlaybackCaptureConfiguration] used across both WebRTC and AAC
 * capture pipelines so that target full-UID whitelisting (`addMatchingUid`) and allowed media
 * usages (`USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`) are 100% consistent.
 */
object PlaybackCaptureConfigBuilder {

    val ALLOWED_CAPTURE_USAGES: IntArray = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN
    )

    fun isMatchingMediaUsage(usage: Int): Boolean {
        return usage == AudioAttributes.USAGE_MEDIA ||
            usage == AudioAttributes.USAGE_GAME ||
            usage == AudioAttributes.USAGE_UNKNOWN
    }

    /**
     * Applies the shared usage and full-UID whitelist rules to any configuration builder sink.
     * Rejects empty or non-positive target UIDs so unfiltered capture can never silently occur.
     */
    fun applyConstraints(
        targetUids: Set<Int>,
        addMatchingUsage: (Int) -> Unit,
        addMatchingUid: (Int) -> Unit
    ) {
        require(targetUids.isNotEmpty()) {
            "Cannot build AudioPlaybackCaptureConfiguration with an empty target UID whitelist."
        }
        for (usage in ALLOWED_CAPTURE_USAGES) {
            addMatchingUsage(usage)
        }
        for (uid in targetUids.sorted()) {
            require(uid > 0) { "Invalid target UID for audio capture: $uid" }
            addMatchingUid(uid)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun build(
        projection: MediaProjection,
        targetUids: Set<Int>
    ): AudioPlaybackCaptureConfiguration {
        require(targetUids.isNotEmpty()) {
            "Cannot build AudioPlaybackCaptureConfiguration with an empty target UID whitelist."
        }
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        applyConstraints(
            targetUids = targetUids,
            addMatchingUsage = { builder.addMatchingUsage(it) },
            addMatchingUid = { builder.addMatchingUid(it) }
        )
        return builder.build()
    }
}
