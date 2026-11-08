package io.github.ddagunts.screencast.target

enum class ProfileType(val badgeLabel: String) {
    PRIMARY("主空间"),
    WORK("工作资料"),
    CLONE("克隆资料"),
    OTHER("子空间")
}

enum class ShizukuConnectionState(val statusLabel: String) {
    UNAVAILABLE("Shizuku 未运行"),
    PERMISSION_REQUIRED("待授予 Shizuku 权限"),
    CONNECTING("正在连接 Shell 服务..."),
    READY("Shizuku Shell 就绪"),
    ERROR("Shizuku 服务异常")
}

/**
 * Identifies a capture & session-mute target by package name and user profile serial number.
 * Full UIDs are never persisted; they are dynamically resolved before session startup.
 */
data class CaptureTarget(
    val packageName: String,
    val userSerialNumber: Long
) {
    fun toStorageKey(): String = "${packageName.trim()}@$userSerialNumber"

    companion object {
        fun fromStorageKey(raw: String): CaptureTarget? {
            val idx = raw.lastIndexOf('@')
            if (idx <= 0 || idx >= raw.length - 1) return null
            val pkg = raw.substring(0, idx).trim()
            val serial = raw.substring(idx + 1).trim().toLongOrNull() ?: return null
            if (pkg.isEmpty()) return null
            return CaptureTarget(pkg, serial)
        }
    }
}

data class UserProfileInfo(
    val userSerialNumber: Long,
    val userId: Int,
    val label: String,
    val profileType: ProfileType,
    val isRunning: Boolean = true
)

data class TargetAppEntry(
    val target: CaptureTarget,
    val appLabel: String,
    val profileType: ProfileType,
    val profileLabel: String,
    val isInstalled: Boolean = true,
    val isProfileRunning: Boolean = true,
    val isSystemApp: Boolean = false,
    val resolvedUid: Int? = null,
    val sharedUidPackages: List<String> = emptyList(),
    val isCaptureRestrictedByManifest: Boolean = false,
    val statusReason: String? = null
) {
    val isSelectable: Boolean
        get() = isInstalled && isProfileRunning && !isCaptureRestrictedByManifest
}

data class TargetResolutionResult(
    val isValid: Boolean,
    val resolvedUids: Set<Int> = emptySet(),
    val targetUidMap: Map<CaptureTarget, Int> = emptyMap(),
    val sharedUidConflicts: Map<Int, List<String>> = emptyMap(),
    val unavailableTargets: List<Pair<CaptureTarget, String>> = emptyList(),
    val failureReason: String?
        = null
)

data class SessionMuteTelemetry(
    val shizukuState: ShizukuConnectionState = ShizukuConnectionState.UNAVAILABLE,
    val resolvedTargetUids: Set<Int> = emptySet(),
    val activeTargetPlayerCount: Int = 0,
    val confirmedMutedPlayerCount: Int = 0,
    val lastMixerMutedByMask: Int = 0,
    val lastMuteConfirmationLatencyMs: Long? = null,
    val maxObservedNewTrackMuteLatencyMs: Long? = null,
    val isNewTrackLatencyWithinGate: Boolean? = null,
    val leaseActive: Boolean = false,
    val leaseDurationMs: Long = 3_000L,
    val leaseRenewalCount: Long = 0L,
    val preMuteBaselineRmsDb: Float? = null,
    val postMuteActiveRmsDb: Float? = null,
    val muteFailureReason: String? = null
)
