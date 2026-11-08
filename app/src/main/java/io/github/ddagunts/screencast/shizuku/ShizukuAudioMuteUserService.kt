package io.github.ddagunts.screencast.shizuku

import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.media.VolumeShaper
import android.os.Binder
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
class ShizukuAudioMuteUserService : IShizukuAudioMuteService.Stub() {

    private data class ShapedSessionRecord(
        val piid: Int,
        val uid: Int,
        val playerProxy: Any,
        val appliedAtElapsedNs: Long,
        @Volatile var confirmedAtElapsedNs: Long = 0L,
        @Volatile var lastMutedByMask: Int = 0
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

    init {
        scheduler.scheduleAtFixedRate(
            { checkLeaseAndEnforceSessions() },
            150L,
            150L,
            TimeUnit.MILLISECONDS
        )
        registerSystemPlaybackDispatcher()
    }

    override fun queryMultiProfileCatalog(importedTargets: MutableList<String>?): String {
        val importedSet = importedTargets.orEmpty()
            .mapNotNull { CaptureTarget.fromStorageKey(it) }
            .toSet()
        val profiles = queryUserProfilesInternal()
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
            val installedApps = queryInstalledApplicationsForUser(profile.userId)
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName ?: continue
                val target = CaptureTarget(pkg, profile.userSerialNumber)
                val fullUid = appInfo.uid
                val sharedSiblings = queryPackagesForUidInternal(fullUid)
                val isCaptureRestricted = !isPlaybackCaptureAllowed(appInfo)
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val appLabel = loadAppLabelSafe(appInfo)
                val statusReason = buildStatusReason(
                    isProfileRunning = profile.isRunning,
                    isInstalled = true,
                    isCaptureRestricted = isCaptureRestricted
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
                    .put("resolvedUid", fullUid)
                    .put("sharedUidPackages", JSONArray(sharedSiblings))
                    .put("isCaptureRestricted", isCaptureRestricted)
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
            val appInfo = profile?.let { queryApplicationInfoForUser(imported.packageName, it.userId) }
            val isInstalled = appInfo != null
            val isProfileRunning = profile?.isRunning == true
            val isRestricted = appInfo != null && !isPlaybackCaptureAllowed(appInfo)
            val isSystem = appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val fullUid = appInfo?.uid
            val siblings = if (fullUid != null) queryPackagesForUidInternal(fullUid) else emptyList()
            val reason = buildStatusReason(
                isProfileRunning = isProfileRunning,
                isInstalled = isInstalled,
                isCaptureRestricted = isRestricted
            )
            catalogAppsArray.put(
                JSONObject()
                    .put("packageName", imported.packageName)
                    .put("userSerialNumber", imported.userSerialNumber)
                    .put("appLabel", appInfo?.let { loadAppLabelSafe(it) } ?: imported.packageName)
                    .put("profileType", (profile?.profileType ?: ProfileType.OTHER).name)
                    .put("profileLabel", profile?.label ?: "空间 #${imported.userSerialNumber}")
                    .put("isInstalled", isInstalled)
                    .put("isProfileRunning", isProfileRunning)
                    .put("isSystemApp", isSystem)
                    .put("resolvedUid", fullUid ?: JSONObject.NULL)
                    .put("sharedUidPackages", JSONArray(siblings))
                    .put("isCaptureRestricted", isRestricted)
                    .put("statusReason", reason ?: JSONObject.NULL)
            )
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

        val profilesBySerial = queryUserProfilesInternal().associateBy { it.userSerialNumber }
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
            val appInfo = queryApplicationInfoForUser(target.packageName, profile.userId)
            if (appInfo == null) {
                unavailableArray.put(
                    JSONObject()
                        .put("target", target.toStorageKey())
                        .put("reason", "应用 ${target.packageName} 在 ${profile.label} 中未安装")
                )
                continue
            }
            if (!isPlaybackCaptureAllowed(appInfo)) {
                unavailableArray.put(
                    JSONObject()
                        .put("target", target.toStorageKey())
                        .put("reason", "${target.packageName} (${profile.label}): 应用声明了 allowAudioPlaybackCapture=false 禁止系统回采")
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
            val siblings = queryPackagesForUidInternal(fullUid)
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
                releaseMuteLeaseInternalLocked()
                return buildLeaseResponseLocked(
                    success = false,
                    error = "目标 UID 集合为空，无法建立会话静音租约。"
                )
            }

            val clampedLeaseMs = leaseDurationMs.coerceIn(500L, MAX_LEASE_DURATION_MS)
            val nowMs = SystemClock.elapsedRealtime()
            this.targetUids = uidSet
            this.leaseExpiresAtElapsedMs = nowMs + clampedLeaseMs
            this.leaseActive = true

            val toUnshape = shapedSessions.values.filter { it.uid !in uidSet }
            for (record in toUnshape) {
                removeDedicatedShaperFromPlayer(record.playerProxy)
                shapedSessions.remove(record.piid)
            }

            val enforcementError = enforceMuteOnActiveTargetSessionsLocked(uidSet)
            if (enforcementError != null) {
                releaseMuteLeaseInternalLocked()
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
            releaseMuteLeaseInternalLocked()
            val snapshot = buildLeaseResponseLocked(success = true, error = null)
            notifyStateCallbacks(snapshot)
            return snapshot
        }
    }

    override fun queryPlaybackSnapshot(targetUids: IntArray?): String {
        val uidSet = targetUids?.filter { it > 0 }?.toSet() ?: this.targetUids
        val configs = getActivePlaybackConfigurationsInternal()
        var activeStartedCount = 0
        var confirmedMutedCount = 0
        var combinedMutedBy = 0

        for (cfg in configs) {
            val uid = getConfigClientUid(cfg)
            val state = getConfigPlayerState(cfg)
            val usage = getConfigUsage(cfg)
            if (uid in uidSet && state == PLAYER_STATE_STARTED && PlaybackCaptureConfigBuilder.isMatchingMediaUsage(usage)) {
                activeStartedCount++
                val mutedBy = getConfigMutedBy(cfg)
                combinedMutedBy = combinedMutedBy or mutedBy
                if (isConfirmedMutedInMixer(mutedBy)) {
                    confirmedMutedCount++
                }
            }
        }

        return JSONObject()
            .put("hasActiveStartedPlayback", activeStartedCount > 0)
            .put("activeTargetPlayerCount", activeStartedCount)
            .put("confirmedMutedPlayerCount", confirmedMutedCount)
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
                            releaseMuteLeaseInternalLocked()
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
                releaseMuteLeaseInternalLocked()
            }
            unregisterSystemPlaybackDispatcher()
            scheduler.shutdownNow()
            exitProcess(0)
        }
    }

    private fun checkLeaseAndEnforceSessions() {
        synchronized(lock) {
            if (!leaseActive) return
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs >= leaseExpiresAtElapsedMs) {
                releaseMuteLeaseInternalLocked()
                val expiredSnapshot = buildLeaseResponseLocked(
                    success = false,
                    error = "会话静音租约已到期（超过 3 秒未续期），已自动移除静音包络恢复原声。"
                )
                notifyStateCallbacks(expiredSnapshot)
                return
            }
            val currentTargets = targetUids
            if (currentTargets.isNotEmpty()) {
                val err = enforceMuteOnActiveTargetSessionsLocked(currentTargets)
                if (err != null) {
                    releaseMuteLeaseInternalLocked()
                    notifyFailureCallbacks(err)
                }
            }
        }
    }

    private fun enforceMuteOnActiveTargetSessionsLocked(uidSet: Set<Int>): String? {
        val configs = getActivePlaybackConfigurationsInternal()
        val activeTargetPiids = HashSet<Int>()
        val unconfirmedPiids = LinkedHashMap<Int, Long>()

        for (cfg in configs) {
            val uid = getConfigClientUid(cfg)
            if (uid !in uidSet) continue
            val usage = getConfigUsage(cfg)
            if (!PlaybackCaptureConfigBuilder.isMatchingMediaUsage(usage)) continue
            val state = getConfigPlayerState(cfg)
            val piid = getConfigPiid(cfg)
            if (state != PLAYER_STATE_STARTED) {
                firstSeenStartedNs.remove(piid)
                continue
            }

            activeTargetPiids.add(piid)
            val startedEventNs = firstSeenStartedNs.computeIfAbsent(piid) {
                SystemClock.elapsedRealtimeNanos()
            }

            val playerType = getConfigPlayerType(cfg)
            if (!isSupportedVolumeShaperPlayerType(playerType)) {
                return "检测到不支持 VolumeShaper 会话静音的播放器 (UID=$uid, piid=$piid, type=$playerType)，已终止投放并恢复会话。"
            }

            val playerProxy = getConfigPlayerProxy(cfg)
                ?: return "无法获取目标播放器控制代理 IPlayer (UID=$uid, piid=$piid)，已终止投放并恢复会话。"

            val currentMutedBy = getConfigMutedBy(cfg)
            val existingRecord = shapedSessions[piid]
            if (existingRecord != null && isConfirmedMutedInMixer(currentMutedBy)) {
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
            }
            unconfirmedPiids[piid] = startedEventNs
        }

        val stalePiids = shapedSessions.keys.filter { it !in activeTargetPiids }
        for (stalePiid in stalePiids) {
            val removed = shapedSessions.remove(stalePiid)
            if (removed != null) {
                removeDedicatedShaperFromPlayer(removed.playerProxy)
            }
            firstSeenStartedNs.remove(stalePiid)
        }

        if (unconfirmedPiids.isEmpty()) {
            return null
        }

        val pollStartMs = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - pollStartMs <= MAX_CONFIRMATION_WAIT_MS) {
            val latestConfigs = getActivePlaybackConfigurationsInternal()
            val configByPiid = latestConfigs.associateBy { getConfigPiid(it) }
            val iterator = unconfirmedPiids.entries.iterator()
            while (iterator.hasNext()) {
                val (piid, startedNs) = iterator.next()
                val latestCfg = configByPiid[piid]
                if (latestCfg == null || getConfigPlayerState(latestCfg) != PLAYER_STATE_STARTED) {
                    iterator.remove()
                    continue
                }
                val mutedBy = getConfigMutedBy(latestCfg)
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

    private fun releaseMuteLeaseInternalLocked() {
        leaseActive = false
        leaseExpiresAtElapsedMs = 0L
        targetUids = emptySet()
        for ((_, record) in shapedSessions) {
            removeDedicatedShaperFromPlayer(record.playerProxy)
        }
        shapedSessions.clear()
        firstSeenStartedNs.clear()
    }

    private fun buildLeaseResponseLocked(success: Boolean, error: String?): String {
        val configs = getActivePlaybackConfigurationsInternal()
        var activeTargetCount = 0
        var confirmedMutedCount = 0
        var combinedMask = 0

        for (cfg in configs) {
            val uid = getConfigClientUid(cfg)
            val state = getConfigPlayerState(cfg)
            val usage = getConfigUsage(cfg)
            if (uid in targetUids && state == PLAYER_STATE_STARTED && PlaybackCaptureConfigBuilder.isMatchingMediaUsage(usage)) {
                activeTargetCount++
                val mask = getConfigMutedBy(cfg)
                combinedMask = combinedMask or mask
                if (isConfirmedMutedInMixer(mask)) {
                    confirmedMutedCount++
                }
            }
        }
        if (combinedMask != 0) {
            lastMixerMutedByMask = combinedMask
        }

        val remainingLeaseMs = if (leaseActive) {
            (leaseExpiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            0L
        }

        return JSONObject()
            .put("success", success)
            .put("leaseActive", leaseActive)
            .put("remainingLeaseMs", remainingLeaseMs)
            .put("activeTargetPlayerCount", activeTargetCount)
            .put("confirmedMutedPlayerCount", confirmedMutedCount)
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
            val configs = getActivePlaybackConfigurationsInternal()
            for (cfg in configs) {
                val uid = getConfigClientUid(cfg)
                if (uid in targetUids &&
                    getConfigPlayerState(cfg) == PLAYER_STATE_STARTED &&
                    PlaybackCaptureConfigBuilder.isMatchingMediaUsage(getConfigUsage(cfg))
                ) {
                    val piid = getConfigPiid(cfg)
                    firstSeenStartedNs.putIfAbsent(piid, callbackEventNs)
                }
            }
            val err = enforceMuteOnActiveTargetSessionsLocked(targetUids)
            if (err != null) {
                releaseMuteLeaseInternalLocked()
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
        return (mutedByMask and (MUTED_BY_VOLUME_SHAPER or MUTED_BY_CLIENT_VOLUME)) != 0 ||
            mutedByMask != 0
    }

    private data class ShellUserProfile(
        val userId: Int,
        val userSerialNumber: Long,
        val label: String,
        val profileType: ProfileType,
        val isRunning: Boolean
    )

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

    private fun isPlaybackCaptureAllowed(appInfo: ApplicationInfo): Boolean {
        return try {
            val m = ApplicationInfo::class.java.getMethod("isAudioPlaybackCaptureAllowed")
            (m.invoke(appInfo) as? Boolean) ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun loadAppLabelSafe(appInfo: ApplicationInfo): String {
        val nonLocalized = appInfo.nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }
        if (nonLocalized != null) return nonLocalized
        return appInfo.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun buildStatusReason(
        isProfileRunning: Boolean,
        isInstalled: Boolean,
        isCaptureRestricted: Boolean
    ): String? {
        if (!isInstalled) return "应用未安装"
        if (!isProfileRunning) return "所属空间未运行或已锁定"
        if (isCaptureRestricted) return "系统禁采 (allowAudioPlaybackCapture=false)"
        return null
    }

    companion object {
        const val DEDICATED_SHAPER_ID: Int = 0x53434D54 // "SCMT"
        const val OPERATION_FLAG_TERMINATE: Int = 1 shl 1
        const val MAX_LEASE_DURATION_MS: Long = 3_000L
        const val MAX_CONFIRMATION_WAIT_MS: Long = 90L
        const val NEW_TRACK_GATE_LIMIT_MS: Long = 100L

        const val PLAYER_STATE_STARTED: Int = 2
        const val PLAYER_TYPE_JAM_AUDIOTRACK: Int = 1
        const val PLAYER_TYPE_JAM_MEDIAPLAYER: Int = 2
        const val PLAYER_TYPE_NATIVE_AUDIOTRACK: Int = 11
        const val PLAYER_TYPE_AAUDIO: Int = 13

        const val MUTED_BY_CLIENT_VOLUME: Int = 1 shl 2
        const val MUTED_BY_VOLUME_SHAPER: Int = 1 shl 3
    }
}
