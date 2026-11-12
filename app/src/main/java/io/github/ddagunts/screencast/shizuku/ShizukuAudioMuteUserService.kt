package io.github.ddagunts.screencast.shizuku

import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.media.VolumeShaper
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import io.github.ddagunts.screencast.media.PlaybackCaptureConfigBuilder
import io.github.ddagunts.screencast.target.CaptureTarget
import io.github.ddagunts.screencast.target.ProfileType
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Minimal Shizuku UserService running in the `shell` (UID 2000) context.
 *
 * Security & Scope Guarantees:
 * - Provides ONLY multi-profile package/UID resolution, session-level VolumeShaper mute lease,
 *   and mixer `getMutedBy()` confirmation callbacks.
 * - Does NOT expose any arbitrary shell command execution (`exec`) or use hidden-API bypass libraries.
 * - Uses dedicated VolumeShaper envelope ID ([DEDICATED_SHAPER_ID]) and removes ONLY its own
 *   envelope on stop or lease expiry, never modifying global stream volume or writing back old volume levels.
 */
@android.annotation.SuppressLint("NewApi", "SoonBlockedPrivateApi")
class ShizukuAudioMuteUserService : IShizukuAudioMuteService.Stub() {

    internal data class PlaybackSessionDescriptor(
        val piid: Int,
        val uid: Int,
        val state: Int,
        val playerType: Int,
        val usage: Int,
        val mutedBy: Int,
        val playerProxy: Any?
    )

    internal data class ShapedSessionRecord(
        val piid: Int,
        val uid: Int,
        val playerProxy: Any,
        val appliedAtElapsedNs: Long,
        @Volatile var confirmedAtElapsedNs: Long = 0L,
        @Volatile var lastMutedByMask: Int = 0,
        @Volatile var pendingRestore: Boolean = false,
        @Volatile var firstRestoreFailedAtElapsedMs: Long = 0L,
        @Volatile var restoreRetryExhausted: Boolean = false
    )

    internal data class ShellUserProfile(
        val userId: Int,
        val userSerialNumber: Long,
        val label: String,
        val profileType: ProfileType,
        val isRunning: Boolean
    )

    private val lock = Any()
    private val callbacks = CopyOnWriteArrayList<IShizukuMuteCallback>()
    private val shapedSessions = ConcurrentHashMap<Int, ShapedSessionRecord>()
    private val firstSeenStartedNs = ConcurrentHashMap<Int, Long>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ShizukuMuteLeaseWatchdog").apply { isDaemon = true }
    }

    @Volatile private var targetUids: Set<Int> = emptySet()
    @Volatile private var leaseExpiresAtElapsedMs: Long = 0L
    @Volatile private var leaseActive: Boolean = false
    @Volatile private var lastConfirmationLatencyMs: Long = -1L
    @Volatile private var maxObservedLatencyMs: Long = -1L
    @Volatile private var lastMixerMutedByMask: Int = 0
    @Volatile private var playbackCallbackToken: IBinder? = null
    private val destroyed = AtomicBoolean(false)

    // Test hooks for deterministic unit verification
    @Volatile internal var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile internal var playbackSessionsProvider: (() -> List<PlaybackSessionDescriptor>)? = null
    @Volatile internal var userProfilesProvider: (() -> List<ShellUserProfile>)? = null
    @Volatile internal var installedAppsProvider: ((Int) -> List<ApplicationInfo>)? = null
    @Volatile internal var appInfoProvider: ((String, Int) -> ApplicationInfo?)? = null
    @Volatile internal var packagesForUidProvider: ((Int) -> List<String>)? = null
    @Volatile internal var onLabelLoadedHook: ((String) -> Unit)? = null
    @Volatile internal var onSharedUidQueriedHook: ((Int) -> Unit)? = null

    init {
        scheduler.scheduleAtFixedRate(
            { checkLeaseAndEnforceSessions() },
            150L,
            150L,
            TimeUnit.MILLISECONDS
        )
        registerSystemPlaybackDispatcher()
    }

    override fun queryMultiProfileCatalog(
        importedTargets: MutableList<String>?,
        includeOtherApps: Boolean
    ): String {
        val importedSet = importedTargets.orEmpty()
            .mapNotNull { CaptureTarget.fromStorageKey(it) }
            .toSet()
        val profiles = userProfilesProvider?.invoke() ?: queryUserProfilesInternal()
        val primaryUserId = profiles.firstOrNull { it.profileType == ProfileType.PRIMARY }?.userId ?: 0
        val primaryInstalledMap: Map<String, ApplicationInfo> = (
            installedAppsProvider?.invoke(primaryUserId)
                ?: queryInstalledApplicationsForUser(primaryUserId)
            ).mapNotNull { info -> info.packageName?.let { it to info } }.toMap()

        val catalogAppsArray = JSONArray()
        val allInstalledArray = JSONArray()
        val profilesArray = JSONArray()
        val seenCatalogTargets = HashSet<String>()
        val seenSearchableTargets = HashSet<String>()

        for (profile in profiles) {
            profilesArray.put(
                JSONObject()
                    .put("userSerialNumber", profile.userSerialNumber)
                    .put("userId", profile.userId)
                    .put("label", profile.label)
                    .put("profileType", profile.profileType.name)
                    .put("isRunning", profile.isRunning)
            )
            val installedApps = if (profile.userId == primaryUserId && primaryInstalledMap.isNotEmpty()) {
                primaryInstalledMap.values.toList()
            } else {
                installedAppsProvider?.invoke(profile.userId)
                    ?: queryInstalledApplicationsForUser(profile.userId)
            }
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName ?: continue
                val target = CaptureTarget(pkg, profile.userSerialNumber)
                val category = extractAppCategory(appInfo)
                if (!includeOtherApps && !isAudioOrVideoCategory(category) && target !in importedSet) {
                    continue
                }
                val fullUid = appInfo.uid
                val sharedSiblings = queryPackagesForUidSafe(fullUid)
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val appLabel = loadAppLabelSafe(appInfo)
                val (isClonedCapturable, crossProfileReason) = verifyCrossProfileClonedFromPrimary(
                    profile = profile,
                    appInfo = appInfo,
                    primaryUserId = primaryUserId,
                    primaryInstalledMap = primaryInstalledMap
                )
                val statusReason = buildStatusReason(
                    isProfileRunning = profile.isRunning,
                    isInstalled = true,
                    crossProfileRejectReason = crossProfileReason
                )

                val itemJson = JSONObject()
                    .put("packageName", pkg)
                    .put("userSerialNumber", profile.userSerialNumber)
                    .put("appLabel", appLabel)
                    .put("profileType", profile.profileType.name)
                    .put("profileLabel", profile.label)
                    .put("isInstalled", true)
                    .put("isProfileRunning", profile.isRunning)
                    .put("isSystemApp", isSystem)
                    .put("appCategory", category)
                    .put("resolvedUid", fullUid)
                    .put("sharedUidPackages", JSONArray(sharedSiblings))
                    .put("isIdentityVerified", isClonedCapturable)
                    .put("statusReason", statusReason ?: JSONObject.NULL)

                if (seenSearchableTargets.add(target.toStorageKey())) {
                    allInstalledArray.put(itemJson)
                }
                if (target in importedSet && seenCatalogTargets.add(target.toStorageKey())) {
                    catalogAppsArray.put(itemJson)
                }
            }
        }

        // Include any user-imported targets that are currently uninstalled or belong to stopped profiles
        val profileBySerial = profiles.associateBy { it.userSerialNumber }
        for (imported in importedSet) {
            if (!seenCatalogTargets.add(imported.toStorageKey())) continue
            val profile = profileBySerial[imported.userSerialNumber]
            val appInfo = profile?.let {
                appInfoProvider?.invoke(imported.packageName, it.userId)
                    ?: queryApplicationInfoForUser(imported.packageName, it.userId)
            }
            val isInstalled = appInfo != null
            val isProfileRunning = profile?.isRunning == true
            val isSystem = appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val category = appInfo?.let { extractAppCategory(it) } ?: ApplicationInfo.CATEGORY_UNDEFINED
            val fullUid = appInfo?.uid
            val siblings = if (fullUid != null) queryPackagesForUidSafe(fullUid) else emptyList()
            val (isClonedCapturable, crossProfileReason) = if (profile != null && appInfo != null) {
                verifyCrossProfileClonedFromPrimary(
                    profile = profile,
                    appInfo = appInfo,
                    primaryUserId = primaryUserId,
                    primaryInstalledMap = primaryInstalledMap
                )
            } else {
                false to null
            }
            val reason = buildStatusReason(
                isProfileRunning = isProfileRunning,
                isInstalled = isInstalled,
                crossProfileRejectReason = crossProfileReason
            )
            val itemJson = JSONObject()
                .put("packageName", imported.packageName)
                .put("userSerialNumber", imported.userSerialNumber)
                .put("appLabel", appInfo?.let { loadAppLabelSafe(it) } ?: imported.packageName)
                .put("profileType", (profile?.profileType ?: ProfileType.OTHER).name)
                .put("profileLabel", profile?.label ?: "空间 #${imported.userSerialNumber}")
                .put("isInstalled", isInstalled)
                .put("isProfileRunning", isProfileRunning)
                .put("isSystemApp", isSystem)
                .put("appCategory", category)
                .put("resolvedUid", fullUid ?: JSONObject.NULL)
                .put("sharedUidPackages", JSONArray(siblings))
                .put("isIdentityVerified", isInstalled && isProfileRunning && isClonedCapturable)
                .put("statusReason", reason ?: JSONObject.NULL)

            catalogAppsArray.put(itemJson)
            if (isInstalled && isProfileRunning && seenSearchableTargets.add(imported.toStorageKey())) {
                allInstalledArray.put(itemJson)
            }
        }

        return JSONObject()
            .put("profiles", profilesArray)
            .put("catalogApps", catalogAppsArray)
            .put("searchableApps", allInstalledArray)
            .toString()
    }

    override fun resolveAndValidateTargets(selectedTargets: MutableList<String>?): String {
        val parsedTargets = selectedTargets.orEmpty()
            .mapNotNull { CaptureTarget.fromStorageKey(it) }
            .toSet()

        if (parsedTargets.isEmpty()) {
            return JSONObject()
                .put("isValid", false)
                .put("failureReason", "未选择任何目标应用（空目标禁止启动投音）。")
                .toString()
        }

        val profiles = userProfilesProvider?.invoke() ?: queryUserProfilesInternal()
        val primaryUserId = profiles.firstOrNull { it.profileType == ProfileType.PRIMARY }?.userId ?: 0
        val profilesBySerial = profiles.associateBy { it.userSerialNumber }
        val resolvedUids = LinkedHashSet<Int>()
        val targetUidArray = JSONArray()
        val unavailableArray = JSONArray()
        val sharedConflictsObj = JSONObject()

        for (target in parsedTargets) {
            val profile = profilesBySerial[target.userSerialNumber]
            if (profile == null || !profile.isRunning) {
                unavailableArray.put(
                    JSONObject()
                        .put("target", target.toStorageKey())
                        .put("reason", "目标所属空间 (${target.userSerialNumber}) 不存在或未运行")
                )
                continue
            }
            val appInfo = appInfoProvider?.invoke(target.packageName, profile.userId)
                ?: installedAppsProvider?.invoke(profile.userId)?.firstOrNull { it.packageName == target.packageName }
                ?: queryApplicationInfoForUser(target.packageName, profile.userId)
            if (appInfo == null) {
                unavailableArray.put(
                    JSONObject()
                        .put("target", target.toStorageKey())
                        .put("reason", "应用 ${target.packageName} 在 ${profile.label} 中未安装")
                )
                continue
            }

            val (isClonedCapturable, crossProfileReason) = verifyCrossProfileClonedFromPrimary(
                profile = profile,
                appInfo = appInfo,
                primaryUserId = primaryUserId,
                primaryInstalledMap = null
            )
            if (!isClonedCapturable) {
                unavailableArray.put(
                    JSONObject()
                        .put("target", target.toStorageKey())
                        .put(
                            "reason",
                            crossProfileReason
                                ?: "应用 ${target.packageName} (${profile.label}) 未从主空间克隆，当前采集链路无法捕获"
                        )
                )
                continue
            }

            val fullUid = appInfo.uid
            resolvedUids.add(fullUid)
            targetUidArray.put(
                JSONObject()
                    .put("target", target.toStorageKey())
                    .put("uid", fullUid)
            )

            // Verify shared UID completeness: all packages sharing `fullUid` in this profile MUST be selected
            val siblings = queryPackagesForUidSafe(fullUid)
            val unselectedSiblings = siblings.filter { siblingPkg ->
                CaptureTarget(siblingPkg, target.userSerialNumber) !in parsedTargets
            }
            if (unselectedSiblings.isNotEmpty()) {
                sharedConflictsObj.put(fullUid.toString(), JSONArray(unselectedSiblings))
            }
        }

        val isValid = unavailableArray.length() == 0 &&
            sharedConflictsObj.length() == 0 &&
            resolvedUids.isNotEmpty()

        val failureReason = when {
            unavailableArray.length() > 0 -> {
                val first = unavailableArray.getJSONObject(0)
                "目标校验失败：${first.getString("reason")}"
            }
            sharedConflictsObj.length() > 0 -> {
                val uidKey = sharedConflictsObj.keys().next()
                val unselected = sharedConflictsObj.getJSONArray(uidKey)
                val unselectedNames = (0 until unselected.length()).joinToString(", ") { unselected.getString(it) }
                "共享 UID ($uidKey) 包含未选中的应用 [$unselectedNames]，拒绝启动且不自动扩大采集/静音范围。"
            }
            resolvedUids.isEmpty() -> "未能解析出任何有效的完整 UID。"
            else -> null
        }

        return JSONObject()
            .put("isValid", isValid)
            .put("resolvedUids", JSONArray(resolvedUids.toList()))
            .put("targetUidMap", targetUidArray)
            .put("unavailableTargets", unavailableArray)
            .put("sharedUidConflicts", sharedConflictsObj)
            .put("failureReason", failureReason ?: JSONObject.NULL)
            .toString()
    }

    override fun acquireOrRenewMuteLease(targetUids: IntArray?, leaseDurationMs: Long): String {
        synchronized(lock) {
            val uidSet = targetUids?.filter { it > 0 }?.toSet().orEmpty()
            if (uidSet.isEmpty()) {
                releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                return buildLeaseResponseLocked(
                    success = false,
                    error = "目标 UID 集合为空，无法建立会话静音租约。"
                )
            }

            val nowMs = elapsedRealtimeProvider()
            if (pendingRestoreCountLocked() > 0) {
                val sessions = getActivePlaybackSessionsInternal()
                for (record in shapedSessions.values.filter { it.pendingRestore }) {
                    tryRemoveShaperOrMarkPendingLocked(record, nowMs, sessions)
                }
                val remainingPending = pendingRestoreCountLocked()
                if (remainingPending > 0) {
                    val err = "仍有 $remainingPending 个播放会话处于待恢复状态，恢复完成前禁止建立新静音租约。"
                    return buildLeaseResponseLocked(
                        success = false,
                        error = err
                    )
                }
            }

            val clampedLeaseMs = leaseDurationMs.coerceIn(500L, MAX_LEASE_DURATION_MS)
            this.targetUids = uidSet
            this.leaseExpiresAtElapsedMs = nowMs + clampedLeaseMs
            this.leaseActive = true

            val currentSessions = getActivePlaybackSessionsInternal()
            val toUnshape = shapedSessions.values.filter { it.uid !in uidSet }
            var unshapeFailed = false
            for (record in toUnshape) {
                if (!tryRemoveShaperOrMarkPendingLocked(record, nowMs, currentSessions)) {
                    unshapeFailed = true
                }
            }
            if (unshapeFailed) {
                releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                val err = "移除非目标 UID 播放器专属静音包络失败（待恢复 ${pendingRestoreCountLocked()} 个会话），已终止租约。"
                notifyFailureCallbacks(err)
                return buildLeaseResponseLocked(
                    success = false,
                    error = err
                )
            }

            val enforcementError = enforceMuteOnActiveTargetSessionsLocked(uidSet)
            if (enforcementError != null) {
                releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                notifyFailureCallbacks(enforcementError)
                return buildLeaseResponseLocked(
                    success = false,
                    error = enforcementError
                )
            }

            val snapshot = buildLeaseResponseLocked(success = true, error = null)
            notifyStateCallbacks(snapshot)
            return snapshot
        }
    }

    override fun releaseMuteLease(): String {
        synchronized(lock) {
            releaseMuteLeaseInternalLocked(resetRetryWindow = true)
            val pendingCount = pendingRestoreCountLocked()
            val ok = pendingCount == 0
            val err = if (ok) {
                null
            } else {
                "仍有 $pendingCount 个播放会话未能终止专属静音包络，已保留记录并在 3 秒窗口内重试恢复。"
            }
            val snapshot = buildLeaseResponseLocked(success = ok, error = err)
            notifyStateCallbacks(snapshot)
            return snapshot
        }
    }

    override fun queryPlaybackSnapshot(targetUids: IntArray?): String {
        val uidSet = targetUids?.filter { it > 0 }?.toSet() ?: this.targetUids
        val sessions = getActivePlaybackSessionsInternal()
        var activeStartedCount = 0
        var confirmedMutedCount = 0
        var combinedMutedBy = 0

        for (s in sessions) {
            if (s.uid in uidSet && s.state == PLAYER_STATE_STARTED && PlaybackCaptureConfigBuilder.isMatchingMediaUsage(s.usage)) {
                activeStartedCount++
                combinedMutedBy = combinedMutedBy or s.mutedBy
                if (isConfirmedMutedInMixer(s.mutedBy)) {
                    confirmedMutedCount++
                }
            }
        }

        return JSONObject()
            .put("hasActiveStartedPlayback", activeStartedCount > 0)
            .put("activeTargetPlayerCount", activeStartedCount)
            .put("confirmedMutedPlayerCount", confirmedMutedCount)
            .put("pendingRestoreCount", pendingRestoreCountLocked())
            .put("lastMixerMutedByMask", combinedMutedBy)
            .put("lastMuteConfirmationLatencyMs", lastConfirmationLatencyMs)
            .put("maxObservedNewTrackMuteLatencyMs", maxObservedLatencyMs)
            .put("leaseActive", leaseActive)
            .toString()
    }

    override fun registerCallback(callback: IShizukuMuteCallback?) {
        if (callback == null) return
        callbacks.add(callback)
        runCatching {
            callback.asBinder().linkToDeath(
                IBinder.DeathRecipient {
                    callbacks.remove(callback)
                    if (callbacks.isEmpty()) {
                        synchronized(lock) {
                            releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                        }
                    }
                },
                0
            )
        }
    }

    override fun unregisterCallback(callback: IShizukuMuteCallback?) {
        if (callback != null) {
            callbacks.remove(callback)
        }
    }

    override fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            synchronized(lock) {
                releaseMuteLeaseInternalLocked(resetRetryWindow = false)
            }
            unregisterSystemPlaybackDispatcher()
            scheduler.shutdownNow()
            exitProcess(0)
        }
    }

    internal fun triggerWatchdogTickForTesting() {
        checkLeaseAndEnforceSessions()
    }

    internal fun getTrackedSessionPiidsForTesting(): Set<Int> {
        synchronized(lock) {
            return shapedSessions.keys.toSet()
        }
    }

    internal fun getPendingRestorePiidsForTesting(): Set<Int> {
        synchronized(lock) {
            return shapedSessions.values.filter { it.pendingRestore }.map { it.piid }.toSet()
        }
    }

    internal fun isRetryExhaustedForTesting(piid: Int): Boolean {
        synchronized(lock) {
            return shapedSessions[piid]?.restoreRetryExhausted == true
        }
    }

    private fun checkLeaseAndEnforceSessions() {
        synchronized(lock) {
            val nowMs = elapsedRealtimeProvider()
            if (!leaseActive) {
                if (shapedSessions.values.any { it.pendingRestore && !it.restoreRetryExhausted }) {
                    retryPendingRestoresOnWatchdogLocked(nowMs)
                }
                return
            }
            if (nowMs >= leaseExpiresAtElapsedMs) {
                releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                val pendingCount = pendingRestoreCountLocked()
                val err = if (pendingCount == 0) {
                    "会话静音租约已到期（超过 3 秒未续期），已自动移除静音包络恢复原声。"
                } else {
                    "会话静音租约已到期，仍有 $pendingCount 个会话待恢复静音包络。"
                }
                val expiredSnapshot = buildLeaseResponseLocked(
                    success = false,
                    error = err
                )
                notifyStateCallbacks(expiredSnapshot)
                return
            }
            val currentTargets = targetUids
            if (currentTargets.isNotEmpty()) {
                val err = enforceMuteOnActiveTargetSessionsLocked(currentTargets)
                if (err != null) {
                    releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                    notifyFailureCallbacks(err)
                }
            }
        }
    }

    private fun retryPendingRestoresOnWatchdogLocked(nowMs: Long) {
        val sessions = getActivePlaybackSessionsInternal()
        var anyRecovered = false
        var anyNewlyExhausted = false

        for (record in shapedSessions.values.toList()) {
            if (!record.pendingRestore || record.restoreRetryExhausted) continue
            val elapsedSinceFirstFail = (nowMs - record.firstRestoreFailedAtElapsedMs).coerceAtLeast(0L)
            if (elapsedSinceFirstFail <= RESTORE_RETRY_WINDOW_MS) {
                val ok = tryRemoveShaperOrMarkPendingLocked(record, nowMs, sessions)
                if (ok) {
                    anyRecovered = true
                } else if (nowMs - record.firstRestoreFailedAtElapsedMs >= RESTORE_RETRY_WINDOW_MS) {
                    record.restoreRetryExhausted = true
                    anyNewlyExhausted = true
                }
            } else {
                record.restoreRetryExhausted = true
                anyNewlyExhausted = true
            }
        }

        val remainingPending = pendingRestoreCountLocked()
        if (remainingPending == 0 && anyRecovered) {
            val recoveredSnapshot = buildLeaseResponseLocked(success = true, error = null)
            notifyStateCallbacks(recoveredSnapshot)
        } else if (anyNewlyExhausted) {
            val err = "定向静音包络自动恢复重试已满 3 秒仍未完成（剩余 $remainingPending 个会话待恢复），已保留记录。"
            val failedSnapshot = buildLeaseResponseLocked(success = false, error = err)
            notifyStateCallbacks(failedSnapshot)
            notifyFailureCallbacks(err)
        }
    }

    private fun enforceMuteOnActiveTargetSessionsLocked(uidSet: Set<Int>): String? {
        val sessions = getActivePlaybackSessionsInternal()
        val activeTargetPiids = HashSet<Int>()
        val unconfirmedPiids = LinkedHashMap<Int, Long>()

        for (s in sessions) {
            val uid = s.uid
            if (uid !in uidSet) continue
            val usage = s.usage
            if (!PlaybackCaptureConfigBuilder.isMatchingMediaUsage(usage)) continue
            val state = s.state
            val piid = s.piid
            if (state != PLAYER_STATE_STARTED) {
                firstSeenStartedNs.remove(piid)
                continue
            }

            activeTargetPiids.add(piid)
            val startedEventNs = firstSeenStartedNs.computeIfAbsent(piid) {
                SystemClock.elapsedRealtimeNanos()
            }

            val playerType = s.playerType
            if (!isSupportedVolumeShaperPlayerType(playerType)) {
                return "检测到不支持 VolumeShaper 会话静音的播放器 (UID=$uid, piid=$piid, type=$playerType)，已终止投放并恢复会话。"
            }

            val playerProxy = s.playerProxy
                ?: return "无法获取目标播放器控制代理 IPlayer (UID=$uid, piid=$piid)，已终止投放并恢复会话。"

            val currentMutedBy = s.mutedBy
            val existingRecord = shapedSessions[piid]
            if (existingRecord != null && !existingRecord.pendingRestore && isConfirmedMutedInMixer(currentMutedBy)) {
                existingRecord.lastMutedByMask = currentMutedBy
                lastMixerMutedByMask = currentMutedBy
                if (existingRecord.confirmedAtElapsedNs == 0L) {
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    existingRecord.confirmedAtElapsedNs = nowNs
                    recordConfirmationLatency(startedEventNs, nowNs)
                }
                continue
            }

            val appliedOk = applyDedicatedZeroGainShaperToPlayer(
                playerProxy = playerProxy,
                replaceExisting = existingRecord != null
            )
            if (!appliedOk) {
                return "向播放器 (UID=$uid, piid=$piid) 施加零增益 VolumeShaper 失败，已终止投放并恢复会话。"
            }

            if (existingRecord == null) {
                shapedSessions[piid] = ShapedSessionRecord(
                    piid = piid,
                    uid = uid,
                    playerProxy = playerProxy,
                    appliedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
                )
            } else {
                existingRecord.pendingRestore = false
                existingRecord.firstRestoreFailedAtElapsedMs = 0L
                existingRecord.restoreRetryExhausted = false
            }
            unconfirmedPiids[piid] = startedEventNs
        }

        val nowMs = elapsedRealtimeProvider()
        val stalePiids = shapedSessions.keys.filter { it !in activeTargetPiids }
        for (stalePiid in stalePiids) {
            val record = shapedSessions[stalePiid] ?: continue
            val removedOk = tryRemoveShaperOrMarkPendingLocked(record, nowMs, sessions)
            firstSeenStartedNs.remove(stalePiid)
            if (!removedOk) {
                return "播放器会话 (UID=${record.uid}, piid=${record.piid}) 专属静音包络终止失败，已终止投放并转入后台待恢复重试。"
            }
        }

        if (unconfirmedPiids.isEmpty()) {
            return null
        }

        val pollStartMs = elapsedRealtimeProvider()
        while (elapsedRealtimeProvider() - pollStartMs <= MAX_CONFIRMATION_WAIT_MS) {
            val latestSessions = getActivePlaybackSessionsInternal()
            val sessionByPiid = latestSessions.associateBy { it.piid }
            val iterator = unconfirmedPiids.entries.iterator()
            while (iterator.hasNext()) {
                val (piid, startedNs) = iterator.next()
                val latestCfg = sessionByPiid[piid]
                if (latestCfg == null || latestCfg.state != PLAYER_STATE_STARTED) {
                    iterator.remove()
                    continue
                }
                val mutedBy = latestCfg.mutedBy
                if (isConfirmedMutedInMixer(mutedBy)) {
                    val confirmedNs = SystemClock.elapsedRealtimeNanos()
                    val latencyMs = ((confirmedNs - startedNs) / 1_000_000L).coerceAtLeast(0L)
                    shapedSessions[piid]?.let {
                        it.confirmedAtElapsedNs = confirmedNs
                        it.lastMutedByMask = mutedBy
                    }
                    lastMixerMutedByMask = mutedBy
                    recordConfirmationLatency(startedNs, confirmedNs)
                    if (latencyMs > NEW_TRACK_GATE_LIMIT_MS) {
                        return "新播放会话 (piid=$piid) 混音层静音确认耗时 ${latencyMs}ms 超过 100ms 门禁上限，已终止投放并恢复。"
                    }
                    iterator.remove()
                }
            }
            if (unconfirmedPiids.isEmpty()) {
                return null
            }
            try {
                Thread.sleep(4L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        val failedPiids = unconfirmedPiids.keys.joinToString(", ")
        return "播放器会话 (piid=$failedPiids) 未能在 100ms 内通过 getMutedBy() 混音层状态确认静音，已恢复会话并停止投放。"
    }

    private fun recordConfirmationLatency(startedNs: Long, confirmedNs: Long) {
        val latencyMs = ((confirmedNs - startedNs) / 1_000_000L).coerceAtLeast(0L)
        lastConfirmationLatencyMs = latencyMs
        if (maxObservedLatencyMs < 0L || latencyMs > maxObservedLatencyMs) {
            maxObservedLatencyMs = latencyMs
        }
    }

    private fun releaseMuteLeaseInternalLocked(resetRetryWindow: Boolean) {
        leaseActive = false
        leaseExpiresAtElapsedMs = 0L
        targetUids = emptySet()
        firstSeenStartedNs.clear()

        val nowMs = elapsedRealtimeProvider()
        val sessions = getActivePlaybackSessionsInternal()
        for (record in shapedSessions.values.toList()) {
            if (resetRetryWindow && record.pendingRestore) {
                record.firstRestoreFailedAtElapsedMs = 0L
                record.restoreRetryExhausted = false
            }
            tryRemoveShaperOrMarkPendingLocked(record, nowMs, sessions)
        }
    }

    private fun tryRemoveShaperOrMarkPendingLocked(
        record: ShapedSessionRecord,
        nowMs: Long,
        sessions: List<PlaybackSessionDescriptor>
    ): Boolean {
        val removed = removeDedicatedShaperFromPlayer(record.playerProxy)
        if (removed || isPlayerConfirmedDestroyed(record, sessions)) {
            shapedSessions.remove(record.piid)
            firstSeenStartedNs.remove(record.piid)
            return true
        }
        record.pendingRestore = true
        if (record.firstRestoreFailedAtElapsedMs == 0L) {
            record.firstRestoreFailedAtElapsedMs = nowMs
            record.restoreRetryExhausted = false
        } else if (nowMs - record.firstRestoreFailedAtElapsedMs >= RESTORE_RETRY_WINDOW_MS) {
            record.restoreRetryExhausted = true
        }
        return false
    }

    private fun isPlayerConfirmedDestroyed(
        record: ShapedSessionRecord,
        sessions: List<PlaybackSessionDescriptor>
    ): Boolean {
        val matchingSession = sessions.firstOrNull { it.piid == record.piid }
        if (matchingSession != null) {
            // PLAYER_STATE_PAUSED (3) or PLAYER_STATE_STOPPED (4) is NOT destroyed!
            // Only PLAYER_STATE_RELEASED (0) indicates the player was released/destroyed.
            if (matchingSession.state == PLAYER_STATE_RELEASED) {
                return true
            }
            return false
        }
        // Check if proxy itself reports destroyed or dead binder
        val proxy = record.playerProxy
        val explicitlyDestroyed = runCatching {
            val m = proxy.javaClass.methods.firstOrNull {
                it.name == "isPlayerDestroyed" && it.parameterTypes.isEmpty()
            }
            (m?.invoke(proxy) as? Boolean) == true
        }.getOrDefault(false)
        if (explicitlyDestroyed) return true

        val binderDead = runCatching {
            val asBinder = proxy.javaClass.methods.firstOrNull {
                it.name == "asBinder" && it.parameterTypes.isEmpty()
            }
            val binder = asBinder?.invoke(proxy) as? IBinder
            binder != null && !binder.pingBinder()
        }.getOrDefault(false)
        return binderDead
    }

    private fun pendingRestoreCountLocked(): Int {
        return shapedSessions.values.count { it.pendingRestore }
    }

    private fun buildLeaseResponseLocked(success: Boolean, error: String?): String {
        val sessions = getActivePlaybackSessionsInternal()
        var activeTargetCount = 0
        var confirmedMutedCount = 0
        var combinedMask = 0

        for (s in sessions) {
            if (s.uid in targetUids && s.state == PLAYER_STATE_STARTED && PlaybackCaptureConfigBuilder.isMatchingMediaUsage(s.usage)) {
                activeTargetCount++
                combinedMask = combinedMask or s.mutedBy
                if (isConfirmedMutedInMixer(s.mutedBy)) {
                    confirmedMutedCount++
                }
            }
        }
        if (combinedMask != 0) {
            lastMixerMutedByMask = combinedMask
        }

        val remainingLeaseMs = if (leaseActive) {
            (leaseExpiresAtElapsedMs - elapsedRealtimeProvider()).coerceAtLeast(0L)
        } else {
            0L
        }

        return JSONObject()
            .put("success", success)
            .put("leaseActive", leaseActive)
            .put("remainingLeaseMs", remainingLeaseMs)
            .put("activeTargetPlayerCount", activeTargetCount)
            .put("confirmedMutedPlayerCount", confirmedMutedCount)
            .put("pendingRestoreCount", pendingRestoreCountLocked())
            .put("lastMixerMutedByMask", lastMixerMutedByMask)
            .put("lastMuteConfirmationLatencyMs", lastConfirmationLatencyMs)
            .put("maxObservedNewTrackMuteLatencyMs", maxObservedLatencyMs)
            .put("error", error ?: JSONObject.NULL)
            .toString()
    }

    private fun notifyStateCallbacks(jsonSnapshot: String) {
        for (cb in callbacks) {
            runCatching { cb.onSessionStateUpdated(jsonSnapshot) }
        }
    }

    private fun notifyFailureCallbacks(reason: String) {
        for (cb in callbacks) {
            runCatching { cb.onMuteFailure(reason) }
        }
    }

    private fun registerSystemPlaybackDispatcher() {
        try {
            val audioService = getAudioServiceInterface() ?: return
            val registerMethod = audioService.javaClass.methods.firstOrNull {
                it.name == "registerPlaybackCallback" && it.parameterTypes.isNotEmpty()
            } ?: return

            val binderStub = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) {
                        val eventNs = SystemClock.elapsedRealtimeNanos()
                        scheduler.execute {
                            onSystemPlaybackConfigsChanged(eventNs)
                        }
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val asInterfaceMethod = Class.forName("android.media.IPlaybackConfigDispatcher\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            val dispatcherProxy = asInterfaceMethod.invoke(null, binderStub)

            when (registerMethod.parameterTypes.size) {
                1 -> registerMethod.invoke(audioService, dispatcherProxy)
                2 -> registerMethod.invoke(audioService, dispatcherProxy, true)
            }
            playbackCallbackToken = binderStub
        } catch (_: Throwable) {
        }
    }

    private fun unregisterSystemPlaybackDispatcher() {
        val token = playbackCallbackToken ?: return
        playbackCallbackToken = null
        try {
            val audioService = getAudioServiceInterface() ?: return
            val asInterfaceMethod = Class.forName("android.media.IPlaybackConfigDispatcher\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            val dispatcherProxy = asInterfaceMethod.invoke(null, token)
            val unregisterMethod = audioService.javaClass.methods.firstOrNull {
                it.name == "unregisterPlaybackCallback" && it.parameterTypes.size == 1
            }
            unregisterMethod?.invoke(audioService, dispatcherProxy)
        } catch (_: Throwable) {
        }
    }

    private fun onSystemPlaybackConfigsChanged(callbackEventNs: Long) {
        synchronized(lock) {
            if (!leaseActive || targetUids.isEmpty()) return
            val sessions = getActivePlaybackSessionsInternal()
            for (s in sessions) {
                if (s.uid in targetUids &&
                    s.state == PLAYER_STATE_STARTED &&
                    PlaybackCaptureConfigBuilder.isMatchingMediaUsage(s.usage)
                ) {
                    firstSeenStartedNs.putIfAbsent(s.piid, callbackEventNs)
                }
            }
            val err = enforceMuteOnActiveTargetSessionsLocked(targetUids)
            if (err != null) {
                releaseMuteLeaseInternalLocked(resetRetryWindow = false)
                notifyFailureCallbacks(err)
            } else {
                notifyStateCallbacks(buildLeaseResponseLocked(success = true, error = null))
            }
        }
    }

    private fun buildDedicatedZeroGainShaperConfig(): VolumeShaper.Configuration {
        val builder = VolumeShaper.Configuration.Builder()
            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
            .setCurve(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f))
            .setDuration(2L)
        runCatching {
            val setIdMethod = VolumeShaper.Configuration.Builder::class.java
                .getDeclaredMethod("setId", Int::class.javaPrimitiveType)
            setIdMethod.isAccessible = true
            setIdMethod.invoke(builder, DEDICATED_SHAPER_ID)
        }
        return builder.build()
    }

    internal fun buildDedicatedIdOnlyShaperConfig(): VolumeShaper.Configuration {
        return try {
            val ctor = VolumeShaper.Configuration::class.java
                .getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            ctor.newInstance(DEDICATED_SHAPER_ID)
        } catch (_: Throwable) {
            buildDedicatedZeroGainShaperConfig()
        }
    }

    private fun buildShaperOperation(replaceExisting: Boolean): VolumeShaper.Operation {
        if (!replaceExisting) return VolumeShaper.Operation.PLAY
        return try {
            val opBuilderClass = Class.forName("android.media.VolumeShaper\$Operation\$Builder")
            val opBuilder = opBuilderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val replaceMethod = opBuilderClass.getDeclaredMethod(
                "replace",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val playMethod = opBuilderClass.getDeclaredMethod("play").apply { isAccessible = true }
            val buildMethod = opBuilderClass.getDeclaredMethod("build").apply { isAccessible = true }
            replaceMethod.invoke(opBuilder, DEDICATED_SHAPER_ID, true)
            playMethod.invoke(opBuilder)
            buildMethod.invoke(opBuilder) as VolumeShaper.Operation
        } catch (_: Throwable) {
            VolumeShaper.Operation.PLAY
        }
    }

    internal fun buildTerminateShaperOperation(): VolumeShaper.Operation? {
        runCatching {
            val opBuilderClass = Class.forName("android.media.VolumeShaper\$Operation\$Builder")
            val opBuilder = opBuilderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val terminateMethod = opBuilderClass.getDeclaredMethod("terminate").apply { isAccessible = true }
            val buildMethod = opBuilderClass.getDeclaredMethod("build").apply { isAccessible = true }
            terminateMethod.invoke(opBuilder)
            return buildMethod.invoke(opBuilder) as VolumeShaper.Operation
        }
        runCatching {
            val ctor = VolumeShaper.Operation::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }
            return ctor.newInstance(OPERATION_FLAG_TERMINATE, DEDICATED_SHAPER_ID, Float.NaN)
        }
        return null
    }

    private fun applyDedicatedZeroGainShaperToPlayer(
        playerProxy: Any,
        replaceExisting: Boolean
    ): Boolean {
        return try {
            val config = buildDedicatedZeroGainShaperConfig()
            val operation = buildShaperOperation(replaceExisting)
            invokeApplyVolumeShaper(playerProxy, config, operation)
        } catch (_: Throwable) {
            false
        }
    }

    internal fun removeDedicatedShaperFromPlayer(playerProxy: Any): Boolean {
        return try {
            val idConfig = buildDedicatedIdOnlyShaperConfig()
            val terminateOp = buildTerminateShaperOperation() ?: return false
            invokeApplyVolumeShaper(playerProxy, idConfig, terminateOp)
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeApplyVolumeShaper(
        playerProxy: Any,
        config: VolumeShaper.Configuration,
        operation: VolumeShaper.Operation
    ): Boolean {
        val methods = playerProxy.javaClass.methods.filter { it.name == "applyVolumeShaper" }
        for (method in methods) {
            try {
                method.isAccessible = true
                if (method.parameterTypes.size == 2) {
                    val firstParam = method.parameterTypes[0]
                    val arg0 = if (firstParam.isAssignableFrom(VolumeShaper.Configuration::class.java)) {
                        config
                    } else {
                        val fromMethod = firstParam.methods.firstOrNull {
                            it.parameterTypes.size == 1 &&
                                it.parameterTypes[0].isAssignableFrom(VolumeShaper.Configuration::class.java)
                        }
                        fromMethod?.invoke(null, config) ?: continue
                    }
                    val secondParam = method.parameterTypes[1]
                    val arg1 = if (secondParam.isAssignableFrom(VolumeShaper.Operation::class.java)) {
                        operation
                    } else {
                        val fromMethod = secondParam.methods.firstOrNull {
                            it.parameterTypes.size == 1 &&
                                it.parameterTypes[0].isAssignableFrom(VolumeShaper.Operation::class.java)
                        }
                        fromMethod?.invoke(null, operation) ?: continue
                    }
                    method.invoke(playerProxy, arg0, arg1)
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun getSystemServiceBinder(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun getAudioServiceInterface(): Any? {
        return try {
            val binder = getSystemServiceBinder("audio") ?: return null
            val stubClass = Class.forName("android.media.IAudioService\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getActivePlaybackConfigurationsInternal(): List<AudioPlaybackConfiguration> {
        return try {
            val audioService = getAudioServiceInterface() ?: return emptyList()
            val method = audioService.javaClass.getMethod("getActivePlaybackConfigurations")
            (method.invoke(audioService) as? List<AudioPlaybackConfiguration>).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun getActivePlaybackSessionsInternal(): List<PlaybackSessionDescriptor> {
        playbackSessionsProvider?.let { return it.invoke() }
        return getActivePlaybackConfigurationsInternal().map { cfg ->
            PlaybackSessionDescriptor(
                piid = getConfigPiid(cfg),
                uid = getConfigClientUid(cfg),
                state = getConfigPlayerState(cfg),
                playerType = getConfigPlayerType(cfg),
                usage = getConfigUsage(cfg),
                mutedBy = getConfigMutedBy(cfg),
                playerProxy = getConfigPlayerProxy(cfg)
            )
        }
    }

    private fun getConfigClientUid(cfg: AudioPlaybackConfiguration): Int {
        return try {
            val m = cfg.javaClass.getMethod("getClientUid")
            (m.invoke(cfg) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun getConfigPiid(cfg: AudioPlaybackConfiguration): Int {
        return try {
            val m = cfg.javaClass.getMethod("getPlayerInternalId")
            (m.invoke(cfg) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun getConfigPlayerState(cfg: AudioPlaybackConfiguration): Int {
        return try {
            val m = cfg.javaClass.getMethod("getPlayerState")
            (m.invoke(cfg) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun getConfigPlayerType(cfg: AudioPlaybackConfiguration): Int {
        return try {
            val m = cfg.javaClass.getMethod("getPlayerType")
            (m.invoke(cfg) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun getConfigUsage(cfg: AudioPlaybackConfiguration): Int {
        return try {
            cfg.audioAttributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
        } catch (_: Throwable) {
            AudioAttributes.USAGE_UNKNOWN
        }
    }

    private fun getConfigMutedBy(cfg: AudioPlaybackConfiguration): Int {
        return try {
            val m = cfg.javaClass.getMethod("getMutedBy")
            (m.invoke(cfg) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun getConfigPlayerProxy(cfg: AudioPlaybackConfiguration): Any? {
        return try {
            val m = cfg.javaClass.getDeclaredMethod("getPlayerProxy")
            m.isAccessible = true
            m.invoke(cfg)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSupportedVolumeShaperPlayerType(playerType: Int): Boolean {
        return playerType == PLAYER_TYPE_JAM_AUDIOTRACK ||
            playerType == PLAYER_TYPE_JAM_MEDIAPLAYER ||
            playerType == PLAYER_TYPE_NATIVE_AUDIOTRACK ||
            playerType == PLAYER_TYPE_AAUDIO
    }

    private fun isConfirmedMutedInMixer(mutedByMask: Int): Boolean {
        return (mutedByMask and (MUTED_BY_VOLUME_SHAPER or MUTED_BY_CLIENT_VOLUME)) != 0
    }

    private fun queryUserProfilesInternal(): List<ShellUserProfile> {
        val results = mutableListOf<ShellUserProfile>()
        try {
            val binder = getSystemServiceBinder("user")
            if (binder != null) {
                val stubClass = Class.forName("android.os.IUserManager\$Stub")
                val userManager = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                val getUsersMethod = userManager.javaClass.methods.firstOrNull {
                    it.name == "getUsers" && (it.parameterTypes.size == 1 || it.parameterTypes.size == 3)
                }
                val rawUsers = when (getUsersMethod?.parameterTypes?.size) {
                    1 -> getUsersMethod.invoke(userManager, true) as? List<*>
                    3 -> getUsersMethod.invoke(userManager, true, true, true) as? List<*>
                    else -> null
                }
                if (rawUsers != null) {
                    for (userInfo in rawUsers) {
                        if (userInfo == null) continue
                        val uClass = userInfo.javaClass
                        val id = (uClass.getField("id").get(userInfo) as? Int) ?: 0
                        val serial = (uClass.getField("serialNumber").get(userInfo) as? Int)?.toLong() ?: id.toLong()
                        val name = (uClass.getField("name").get(userInfo) as? String).orEmpty()
                        val userType = runCatching {
                            uClass.getField("userType").get(userInfo) as? String
                        }.getOrNull().orEmpty()
                        val isManaged = runCatching {
                            uClass.getMethod("isManagedProfile").invoke(userInfo) as? Boolean
                        }.getOrNull() == true || userType.contains("MANAGED", ignoreCase = true)
                        val isClone = runCatching {
                            uClass.getMethod("isCloneProfile").invoke(userInfo) as? Boolean
                        }.getOrNull() == true || userType.contains("CLONE", ignoreCase = true) ||
                            name.contains("clone", ignoreCase = true)

                        val pType = when {
                            id == 0 -> ProfileType.PRIMARY
                            isClone -> ProfileType.CLONE
                            isManaged -> ProfileType.WORK
                            else -> ProfileType.OTHER
                        }
                        val label = when (pType) {
                            ProfileType.PRIMARY -> "主空间 (User 0)"
                            ProfileType.WORK -> "工作资料 (${name.ifBlank { "User $id" }})"
                            ProfileType.CLONE -> "克隆资料 (${name.ifBlank { "User $id" }})"
                            ProfileType.OTHER -> "${name.ifBlank { "子空间" }} (User $id)"
                        }
                        val running = isUserRunningInternal(userManager, id)
                        results.add(
                            ShellUserProfile(
                                userId = id,
                                userSerialNumber = serial,
                                label = label,
                                profileType = pType,
                                isRunning = running
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (results.isEmpty()) {
            results.add(
                ShellUserProfile(
                    userId = 0,
                    userSerialNumber = 0L,
                    label = "主空间 (User 0)",
                    profileType = ProfileType.PRIMARY,
                    isRunning = true
                )
            )
        }
        return results.sortedBy { it.userId }
    }

    private fun isUserRunningInternal(userManager: Any, userId: Int): Boolean {
        if (userId == 0) return true
        return try {
            val amBinder = getSystemServiceBinder("activity")
            if (amBinder != null) {
                val amStub = Class.forName("android.app.IActivityManager\$Stub")
                val am = amStub.getMethod("asInterface", IBinder::class.java).invoke(null, amBinder)
                val isRunningMethod = am.javaClass.methods.firstOrNull {
                    it.name == "isUserRunning" && it.parameterTypes.size == 2
                }
                if (isRunningMethod != null) {
                    return (isRunningMethod.invoke(am, userId, 0) as? Boolean) ?: true
                }
            }
            val umMethod = userManager.javaClass.methods.firstOrNull {
                it.name == "isUserUnlocked" && it.parameterTypes.size == 1
            }
            (umMethod?.invoke(userManager, userId) as? Boolean) ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun getPackageManagerInterface(): Any? {
        return try {
            val binder = getSystemServiceBinder("package") ?: return null
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryInstalledApplicationsForUser(userId: Int): List<ApplicationInfo> {
        return try {
            val pm = getPackageManagerInterface() ?: return emptyList()
            val method: Method = pm.javaClass.methods.firstOrNull {
                it.name == "getInstalledApplications" && it.parameterTypes.size == 2
            } ?: return emptyList()
            val flagsParamType = method.parameterTypes[0]
            val slice = if (flagsParamType == Long::class.javaPrimitiveType) {
                method.invoke(pm, 0L, userId)
            } else {
                method.invoke(pm, 0, userId)
            } ?: return emptyList()
            val getListMethod = slice.javaClass.getMethod("getList")
            (getListMethod.invoke(slice) as? List<ApplicationInfo>).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun queryApplicationInfoForUser(packageName: String, userId: Int): ApplicationInfo? {
        return try {
            val pm = getPackageManagerInterface() ?: return null
            val method = pm.javaClass.methods.firstOrNull {
                it.name == "getApplicationInfo" && it.parameterTypes.size == 3
            } ?: return null
            val flagsParamType = method.parameterTypes[1]
            val result = if (flagsParamType == Long::class.javaPrimitiveType) {
                method.invoke(pm, packageName, 0L, userId)
            } else {
                method.invoke(pm, packageName, 0, userId)
            }
            result as? ApplicationInfo
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryPackagesForUidSafe(fullUid: Int): List<String> {
        onSharedUidQueriedHook?.invoke(fullUid)
        return packagesForUidProvider?.invoke(fullUid) ?: queryPackagesForUidInternal(fullUid)
    }

    private fun queryPackagesForUidInternal(fullUid: Int): List<String> {
        return try {
            val pm = getPackageManagerInterface() ?: return emptyList()
            val method = pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
            val arr = method.invoke(pm, fullUid) as? Array<*>
            arr?.mapNotNull { it as? String }?.sorted().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun extractAppCategory(appInfo: ApplicationInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appInfo.category
        } else {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
    }

    private fun isAudioOrVideoCategory(category: Int): Boolean {
        return category == ApplicationInfo.CATEGORY_AUDIO ||
            category == ApplicationInfo.CATEGORY_VIDEO
    }

    private fun loadAppLabelSafe(appInfo: ApplicationInfo): String {
        onLabelLoadedHook?.invoke(appInfo.packageName)
        val nonLocalized = appInfo.nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }
        if (nonLocalized != null) return nonLocalized
        return appInfo.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun verifyCrossProfileClonedFromPrimary(
        profile: ShellUserProfile,
        appInfo: ApplicationInfo,
        primaryUserId: Int,
        primaryInstalledMap: Map<String, ApplicationInfo>?
    ): Pair<Boolean, String?> {
        if (profile.userId == primaryUserId || profile.profileType == ProfileType.PRIMARY) {
            return true to null
        }
        if (profile.profileType != ProfileType.WORK && profile.profileType != ProfileType.CLONE) {
            return false to "独立子用户空间 (${profile.label}) 不支持当前进程跨空间音频回采"
        }
        val pkg = appInfo.packageName ?: return false to "无效的应用包名"
        val primaryAppInfo = primaryInstalledMap?.get(pkg)
            ?: appInfoProvider?.invoke(pkg, primaryUserId)
            ?: installedAppsProvider?.invoke(primaryUserId)?.firstOrNull { it.packageName == pkg }
            ?: queryApplicationInfoForUser(pkg, primaryUserId)

        if (primaryAppInfo == null || (primaryAppInfo.uid % 100_000) != (appInfo.uid % 100_000)) {
            return false to "跨空间应用 $pkg 需从主空间克隆（主空间已安装同名应用）方可被系统回采"
        }
        return true to null
    }

    private fun buildStatusReason(
        isProfileRunning: Boolean,
        isInstalled: Boolean,
        crossProfileRejectReason: String? = null
    ): String? {
        if (!isInstalled) return "应用未安装"
        if (!isProfileRunning) return "所属空间未运行或已锁定"
        if (!crossProfileRejectReason.isNullOrBlank()) return crossProfileRejectReason
        return null
    }

    companion object {
        const val DEDICATED_SHAPER_ID: Int = 0x53434D54 // "SCMT"
        const val OPERATION_FLAG_TERMINATE: Int = 1 shl 1
        const val MAX_LEASE_DURATION_MS: Long = 3_000L
        const val RESTORE_RETRY_WINDOW_MS: Long = 3_000L
        const val MAX_CONFIRMATION_WAIT_MS: Long = 90L
        const val NEW_TRACK_GATE_LIMIT_MS: Long = 100L

        const val PLAYER_STATE_RELEASED: Int = 0
        const val PLAYER_STATE_STARTED: Int = 2
        const val PLAYER_STATE_PAUSED: Int = 3
        const val PLAYER_STATE_STOPPED: Int = 4
        const val PLAYER_TYPE_JAM_AUDIOTRACK: Int = 1
        const val PLAYER_TYPE_JAM_MEDIAPLAYER: Int = 2
        const val PLAYER_TYPE_NATIVE_AUDIOTRACK: Int = 11
        const val PLAYER_TYPE_AAUDIO: Int = 13

        const val MUTED_BY_MASTER: Int = 1 shl 0
        const val MUTED_BY_STREAM_VOLUME: Int = 1 shl 1
        const val MUTED_BY_STREAM_MUTED: Int = 1 shl 2
        const val MUTED_BY_APP_OPS: Int = 1 shl 3
        const val MUTED_BY_CLIENT_VOLUME: Int = 1 shl 4
        const val MUTED_BY_VOLUME_SHAPER: Int = 1 shl 5
    }
}
