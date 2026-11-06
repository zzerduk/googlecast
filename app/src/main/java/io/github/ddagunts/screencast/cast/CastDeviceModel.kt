package io.github.ddagunts.screencast.cast

/**
 * Represents a Google Cast receiver discovered via `_googlecast._tcp.local` mDNS
 * or added manually via IP:Port.
 *
 * Capability bitmask (`ca` TXT record):
 * - 0x01 (1): VIDEO_OUT
 * - 0x02 (2): VIDEO_IN
 * - 0x04 (4): AUDIO_OUT
 * - 0x08 (8): AUDIO_IN
 * - 0x800 (2048): MULTIZONE_GROUP
 *
 * Note: 1st-Generation Google Home reports `md=Google Home` and `ca=2052` (0x800 | 0x04),
 * which lacks the `0x01` VIDEO_OUT bit.
 */
data class CastDeviceModel(
    val id: String,
    val friendlyName: String,
    val modelName: String,
    val hostAddress: String,
    val port: Int = DEFAULT_CAST_PORT,
    val capabilityMask: Int = CAPABILITY_AUDIO_OUT,
    val rawTxtRecords: Map<String, String> = emptyMap()
) {
    val hasVideoOut: Boolean
        get() = (capabilityMask and CAPABILITY_VIDEO_OUT) != 0

    val hasAudioOut: Boolean
        get() = (capabilityMask and CAPABILITY_AUDIO_OUT) != 0 || capabilityMask == 0

    val isAudioOnlySpeaker: Boolean
        get() = !hasVideoOut && hasAudioOut

    val isFirstGenGoogleHome: Boolean
        get() = modelName.equals("Google Home", ignoreCase = true) ||
            (modelName.contains("Google Home", ignoreCase = true) && !hasVideoOut)

    val capabilitySummary: String
        get() = buildList {
            if (hasAudioOut) add("AUDIO_OUT (0x04)")
            if (hasVideoOut) add("VIDEO_OUT (0x01)") else add("HEADLESS_NO_VIDEO")
            if ((capabilityMask and CAPABILITY_MULTIZONE) != 0) add("MULTIZONE (0x800)")
        }.joinToString(" • ")

    companion object {
        const val DEFAULT_CAST_PORT = 8009
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

        const val CAPABILITY_VIDEO_OUT = 0x01
        const val CAPABILITY_VIDEO_IN = 0x02
        const val CAPABILITY_AUDIO_OUT = 0x04
        const val CAPABILITY_AUDIO_IN = 0x08
        const val CAPABILITY_MULTIZONE = 0x800

        /**
         * Phase 1 Requirement:
         * Do NOT filter out devices lacking bit 0x01 (VIDEO_OUT).
         * Accept any Cast receiver that supports audio output (0x04) or is a known Cast speaker/display.
         */
        fun isEligibleCastReceiver(capabilityMask: Int, modelName: String): Boolean {
            if ((capabilityMask and CAPABILITY_AUDIO_OUT) != 0) return true
            if ((capabilityMask and CAPABILITY_VIDEO_OUT) != 0) return true
            if (capabilityMask == 0) return true
            val normalizedModel = modelName.lowercase()
            return normalizedModel.contains("google home") ||
                normalizedModel.contains("nest") ||
                normalizedModel.contains("chromecast") ||
                normalizedModel.contains("speaker")
        }
    }
}
