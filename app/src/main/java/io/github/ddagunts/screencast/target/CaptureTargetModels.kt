package io.github.ddagunts.screencast.target

import android.content.pm.ApplicationInfo
import android.os.Build

fun isAudioOrVideoAppCategory(category: Int): Boolean {
    return category == ApplicationInfo.CATEGORY_AUDIO ||
        category == ApplicationInfo.CATEGORY_VIDEO
}

fun readApplicationCategory(appInfo: ApplicationInfo): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        appInfo.category
    } else {
        ApplicationInfo.CATEGORY_UNDEFINED
    }
}

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
 * Defines how the phone's local audio output behaves during WebRTC playback capture.
 * Frozen with the capture startup request; never switches automatically mid-session.
 */
enum class PhoneOutputMode(
    val badgeLabel: String,
    val shortDescription: String,
    val impactDescription: String
) {
    SHIZUKU_SESSION_MUTE(
        badgeLabel = "定向静音",
        shortDescription = "Shizuku 会话级零增益包络（仅静音所选应用）",
        impactDescription = "仅对目标 UID 的播放轨施加专属 VolumeShaper 零增益包络，手机上未选应用的声音不受影响。"
    ),
    GLOBAL_MEDIA_MUTE(
        badgeLabel = "整体媒体静音",
        shortDescription = "系统媒体音量归零（影响整机全部媒体声）",
        impactDescription = "无需 Shizuku，先持久化当前媒体音量与静音状态，再将系统 STREAM_MUSIC 设为 0；手机端所有媒体声均静音，停止或异常时自动恢复（若中途手动调高音量则保留新设置）。"
    ),
    CAPTURE_ONLY_UNMUTED(
        badgeLabel = "仅采集不静音",
        shortDescription = "不改手机音量（手机与音箱同时发声）",
        impactDescription = "无需 Shizuku，仅按目标 UID 白名单采集音频发送至音箱，不修改任何手机音量或会话包络，本机与远端音箱同时发声。"
    )
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

/**
 * Named combination of [CaptureTarget] instances persisted in DataStore without fixed UIDs.
 */
data class TargetPreset(
    val name: String,
    val targets: Set<CaptureTarget>
) {
    init {
        require(name.isNotBlank()) { "Preset name must not be blank." }
        require(targets.isNotEmpty()) { "Preset targets must not be empty." }
    }
}

/**
 * Represents a leftover Global Media Mute record found on app launch after an unexpected process kill.
 */
data class PendingGlobalMuteRecovery(
    val savedVolume: Int,
    val maxVolume: Int,
    val wasMuted: Boolean,
    val currentVolume: Int
)

data class UserProfileInfo(
    val userSerialNumber: Long,
    val userId: Int,
    val label: String,
    val profileType: ProfileType,
    val isRunning: Boolean = true,
    val isAccessible: Boolean = true
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
    val isIdentityVerified: Boolean = true,
    val isSharedUidVerified: Boolean = true,
    val statusReason: String? = null,
    val appCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED
) {
    val isSelectable: Boolean
        get() = isInstalled &&
            isProfileRunning &&
            isIdentityVerified &&
            isSharedUidVerified

    val isAudioOrVideoCategory: Boolean
        get() = isAudioOrVideoAppCategory(appCategory)
}

data class TargetResolutionResult(
    val isValid: Boolean,
    val resolvedUids: Set<Int> = emptySet(),
    val targetUidMap: Map<CaptureTarget, Int> = emptyMap(),
    val sharedUidConflicts: Map<Int, List<String>> = emptyMap(),
    val unavailableTargets: List<Pair<CaptureTarget, String>> = emptyList(),
    val failureReason: String? = null
)

data class SessionMuteTelemetry(
    val shizukuState: ShizukuConnectionState = ShizukuConnectionState.UNAVAILABLE,
    val resolvedTargetUids: Set<Int> = emptySet(),
    val activeTargetPlayerCount: Int = 0,
    val confirmedMutedPlayerCount: Int = 0,
    val pendingRestoreCount: Int = 0,
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
