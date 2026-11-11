package io.github.ddagunts.screencast.target

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import io.github.ddagunts.screencast.shizuku.MultiProfileCatalogSnapshot

/**
 * Abstraction over Android public multi-profile & package APIs so that target resolution
 * without Shizuku can be deterministically unit-tested across primary, work, and inaccessible spaces.
 */
interface PublicTargetEnvironment {
    data class AccessibleProfile(
        val userSerialNumber: Long,
        val userId: Int,
        val label: String,
        val profileType: ProfileType,
        val isRunning: Boolean,
        val isCurrentProcessUser: Boolean
    )

    data class PublicAppMetadata(
        val packageName: String,
        val userSerialNumber: Long,
        val appLabel: String,
        val uid: Int,
        val isSystemApp: Boolean,
        val isLaunchable: Boolean,
        /**
         * Complete list of packages sharing [uid] in this profile if verifiable via public API,
         * or `null` if public APIs cannot verify shared-UID completeness for this target/profile.
         */
        val verifiedSharedUidPackages: List<String>?,
        val appCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED
    )

    fun queryAccessibleProfiles(context: Context): List<AccessibleProfile>
    fun queryLaunchableAppsInProfile(context: Context, userSerialNumber: Long): List<PublicAppMetadata>
    fun queryLaunchableAppsInProfile(
        context: Context,
        userSerialNumber: Long,
        includeOtherApps: Boolean,
        importedTargets: Set<CaptureTarget>
    ): List<PublicAppMetadata> = queryLaunchableAppsInProfile(context, userSerialNumber)
    fun resolveTargetApp(context: Context, target: CaptureTarget): PublicAppMetadata?
}

/**
 * Resolves and validates [CaptureTarget]s using ONLY public Android APIs (`LauncherApps`,
 * `UserManager`, `PackageManager`) when Shizuku is unavailable or unauthorized.
 *
 * Strict guarantees:
 * - Never infers profile identity (`userSerialNumber` or `userId`) from list indices.
 * - Never guesses or synthesizes UIDs (`userId * 100000 + appId`).
 * - Retains inaccessible, identity-unverifiable, or shared-UID-unverifiable targets with explicit
 *   reasons instead of silently dropping them or expanding capture scope.
 * - Filters default catalog to audio/video categories plus imported/selected/preset target pool
 *   before loading labels or querying shared UIDs.
 */
object PublicApiTargetResolver {

    @Volatile
    var environment: PublicTargetEnvironment = AndroidPublicTargetEnvironment
        internal set

    internal fun resetEnvironmentForTesting() {
        AndroidPublicTargetEnvironment.resetHooksForTesting()
        environment = AndroidPublicTargetEnvironment
    }

    fun queryCatalog(
        context: Context,
        importedTargets: Set<CaptureTarget>,
        includeOtherApps: Boolean = false
    ): MultiProfileCatalogSnapshot {
        val accessibleProfiles = environment.queryAccessibleProfiles(context)
        val profileBySerial = accessibleProfiles.associateBy { it.userSerialNumber }

        val uiProfiles = accessibleProfiles.map { p ->
            UserProfileInfo(
                userSerialNumber = p.userSerialNumber,
                userId = p.userId,
                label = p.label,
                profileType = p.profileType,
                isRunning = p.isRunning,
                isAccessible = true
            )
        }.toMutableList()

        // Include any imported target's profile that is not currently accessible via public APIs
        for (target in importedTargets) {
            if (target.userSerialNumber !in profileBySerial &&
                uiProfiles.none { it.userSerialNumber == target.userSerialNumber }
            ) {
                uiProfiles.add(
                    UserProfileInfo(
                        userSerialNumber = target.userSerialNumber,
                        userId = -1,
                        label = "不可访问空间 (Serial ${target.userSerialNumber})",
                        profileType = ProfileType.OTHER,
                        isRunning = false,
                        isAccessible = false
                    )
                )
            }
        }

        val searchableApps = mutableListOf<TargetAppEntry>()
        val catalogApps = mutableListOf<TargetAppEntry>()
        val seenSearchableKeys = HashSet<String>()
        val seenCatalogKeys = HashSet<String>()

        // Default directory in fallback mode: launchable audio/video apps (plus pool targets) in accessible running profiles;
        // when includeOtherApps=true (on-demand search), includes all launchable apps in accessible running profiles.
        for (profile in accessibleProfiles) {
            if (!profile.isRunning) continue
            val launchable = environment.queryLaunchableAppsInProfile(
                context = context,
                userSerialNumber = profile.userSerialNumber,
                includeOtherApps = includeOtherApps,
                importedTargets = importedTargets
            )
            for (meta in launchable) {
                val target = CaptureTarget(meta.packageName, profile.userSerialNumber)
                if (!includeOtherApps &&
                    !isAudioOrVideoAppCategory(meta.appCategory) &&
                    target !in importedTargets
                ) {
                    continue
                }
                val entry = buildEntryFromMetadata(profile, meta)
                if (seenSearchableKeys.add(target.toStorageKey())) {
                    searchableApps.add(entry)
                }
                if (target in importedTargets && seenCatalogKeys.add(target.toStorageKey())) {
                    catalogApps.add(entry)
                }
            }
        }

        // Evaluate all imported/selected targets, retaining inaccessible or unverifiable ones with clear reasons
        for (target in importedTargets) {
            if (!seenCatalogKeys.add(target.toStorageKey())) continue
            val entry = resolveSingleImportedTargetEntry(context, target, profileBySerial)
            catalogApps.add(entry)
            if (entry.isInstalled && entry.isProfileRunning && seenSearchableKeys.add(target.toStorageKey())) {
                searchableApps.add(entry)
            }
        }

        return MultiProfileCatalogSnapshot(
            profiles = uiProfiles,
            catalogApps = catalogApps.sortedWith(compareBy({ it.profileType.ordinal }, { it.appLabel.lowercase() })),
            searchableApps = searchableApps.sortedWith(compareBy({ it.isSystemApp }, { it.profileType.ordinal }, { it.appLabel.lowercase() }))
        )
    }

    fun resolveTargetEntries(
        context: Context,
        targets: Set<CaptureTarget>
    ): List<TargetAppEntry> {
        if (targets.isEmpty()) return emptyList()
        val accessibleProfiles = environment.queryAccessibleProfiles(context)
        val profileBySerial = accessibleProfiles.associateBy { it.userSerialNumber }
        return targets.map { target ->
            resolveSingleImportedTargetEntry(context, target, profileBySerial)
        }
    }

    private fun resolveSingleImportedTargetEntry(
        context: Context,
        target: CaptureTarget,
        profileBySerial: Map<Long, PublicTargetEnvironment.AccessibleProfile>
    ): TargetAppEntry {
        val profile = profileBySerial[target.userSerialNumber]
        if (profile == null) {
            return TargetAppEntry(
                target = target,
                appLabel = target.packageName,
                profileType = ProfileType.OTHER,
                profileLabel = "空间 #${target.userSerialNumber} (不可访问)",
                isInstalled = true,
                isProfileRunning = false,
                isSystemApp = false,
                resolvedUid = null,
                sharedUidPackages = emptyList(),
                isIdentityVerified = false,
                isSharedUidVerified = false,
                statusReason = "无 Shizuku 时无法访问或核验该空间 (Serial ${target.userSerialNumber}) 的目标身份",
                appCategory = ApplicationInfo.CATEGORY_UNDEFINED
            )
        }

        if (!profile.isRunning) {
            return TargetAppEntry(
                target = target,
                appLabel = target.packageName,
                profileType = profile.profileType,
                profileLabel = profile.label,
                isInstalled = true,
                isProfileRunning = false,
                isSystemApp = false,
                resolvedUid = null,
                sharedUidPackages = emptyList(),
                isIdentityVerified = false,
                isSharedUidVerified = false,
                statusReason = "目标所属空间 (${profile.label}) 未运行或已开启安静模式",
                appCategory = ApplicationInfo.CATEGORY_UNDEFINED
            )
        }

        val meta = environment.resolveTargetApp(context, target)
        if (meta == null) {
            return TargetAppEntry(
                target = target,
                appLabel = target.packageName,
                profileType = profile.profileType,
                profileLabel = profile.label,
                isInstalled = false,
                isProfileRunning = true,
                isSystemApp = false,
                resolvedUid = null,
                sharedUidPackages = emptyList(),
                isIdentityVerified = false,
                isSharedUidVerified = false,
                statusReason = "应用在 ${profile.label} 中未安装或公开 API 无法核验其身份",
                appCategory = ApplicationInfo.CATEGORY_UNDEFINED
            )
        }

        return buildEntryFromMetadata(profile, meta)
    }

    fun resolveAndValidateTargets(
        context: Context,
        selectedTargets: Set<CaptureTarget>
    ): TargetResolutionResult {
        if (selectedTargets.isEmpty()) {
            return TargetResolutionResult(
                isValid = false,
                failureReason = "请先添加并勾选至少 1 个目标应用（空目标禁止启动投音）。"
            )
        }

        val accessibleProfiles = environment.queryAccessibleProfiles(context)
        val profileBySerial = accessibleProfiles.associateBy { it.userSerialNumber }

        val resolvedUids = LinkedHashSet<Int>()
        val targetUidMap = LinkedHashMap<CaptureTarget, Int>()
        val unavailableTargets = mutableListOf<Pair<CaptureTarget, String>>()
        val sharedUidConflicts = LinkedHashMap<Int, List<String>>()

        for (target in selectedTargets) {
            val profile = profileBySerial[target.userSerialNumber]
            if (profile == null) {
                unavailableTargets.add(
                    target to "${target.packageName} (Serial ${target.userSerialNumber}): 无 Shizuku 时公开 API 无法访问该空间或核验空间身份"
                )
                continue
            }
            if (!profile.isRunning) {
                unavailableTargets.add(
                    target to "${target.packageName} (${profile.label}): 目标所属空间未运行或已开启安静模式"
                )
                continue
            }

            val meta = environment.resolveTargetApp(context, target)
            if (meta == null || meta.uid <= 0) {
                unavailableTargets.add(
                    target to "${target.packageName} (${profile.label}): 应用未安装或无法通过公开 API 核验真实 UID"
                )
                continue
            }

            val verifiedSiblings = meta.verifiedSharedUidPackages
            if (verifiedSiblings == null || target.packageName !in verifiedSiblings) {
                unavailableTargets.add(
                    target to "${target.packageName} (${profile.label}, UID ${meta.uid}): 无 Shizuku 时公开 API 无法完整校验共享 UID 包范围，拒绝盲目放行"
                )
                continue
            }

            resolvedUids.add(meta.uid)
            targetUidMap[target] = meta.uid

            val unselectedSiblings = verifiedSiblings.filter { siblingPkg ->
                CaptureTarget(siblingPkg, target.userSerialNumber) !in selectedTargets
            }
            if (unselectedSiblings.isNotEmpty()) {
                sharedUidConflicts[meta.uid] = unselectedSiblings
            }
        }

        val isValid = unavailableTargets.isEmpty() &&
            sharedUidConflicts.isEmpty() &&
            resolvedUids.isNotEmpty()

        val failureReason = when {
            unavailableTargets.isNotEmpty() -> {
                "目标校验失败：${unavailableTargets.first().second}"
            }
            sharedUidConflicts.isNotEmpty() -> {
                val (uid, siblings) = sharedUidConflicts.entries.first()
                "共享 UID ($uid) 包含未选中的应用 [${siblings.joinToString(", ")}]，拒绝启动且不自动扩大采集范围。"
            }
            resolvedUids.isEmpty() -> "未能解析出任何有效的完整 UID。"
            else -> null
        }

        return TargetResolutionResult(
            isValid = isValid,
            resolvedUids = resolvedUids,
            targetUidMap = targetUidMap,
            sharedUidConflicts = sharedUidConflicts,
            unavailableTargets = unavailableTargets,
            failureReason = failureReason
        )
    }

    private fun buildEntryFromMetadata(
        profile: PublicTargetEnvironment.AccessibleProfile,
        meta: PublicTargetEnvironment.PublicAppMetadata
    ): TargetAppEntry {
        val target = CaptureTarget(meta.packageName, profile.userSerialNumber)
        val verifiedSiblings = meta.verifiedSharedUidPackages
        val isSharedVerified = verifiedSiblings != null && meta.packageName in verifiedSiblings
        val reason = when {
            !profile.isRunning -> "所属空间未运行或已暂停"
            meta.uid <= 0 -> "无法核验完整 UID"
            !isSharedVerified -> "无 Shizuku 时无法通过公开 API 完整校验共享 UID"
            else -> null
        }
        return TargetAppEntry(
            target = target,
            appLabel = meta.appLabel,
            profileType = profile.profileType,
            profileLabel = profile.label,
            isInstalled = true,
            isProfileRunning = profile.isRunning,
            isSystemApp = meta.isSystemApp,
            resolvedUid = meta.uid.takeIf { it > 0 },
            sharedUidPackages = verifiedSiblings.orEmpty(),
            isIdentityVerified = meta.uid > 0,
            isSharedUidVerified = isSharedVerified,
            statusReason = reason,
            appCategory = meta.appCategory
        )
    }
}

internal object AndroidPublicTargetEnvironment : PublicTargetEnvironment {

    @Volatile
    var onLabelLoadedHookForTesting: ((String) -> Unit)? = null

    @Volatile
    var onSharedUidQueriedHookForTesting: ((String, Int) -> Unit)? = null

    internal fun resetHooksForTesting() {
        onLabelLoadedHookForTesting = null
        onSharedUidQueriedHookForTesting = null
    }

    private data class ResolvedUserHandle(
        val handle: UserHandle,
        val profile: PublicTargetEnvironment.AccessibleProfile
    )

    private fun discoverAccessibleHandles(context: Context): List<ResolvedUserHandle> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val myHandle = Process.myUserHandle()

        val rawHandles = LinkedHashSet<UserHandle>()
        rawHandles.add(myHandle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { launcherApps?.profiles?.let { rawHandles.addAll(it) } }
        }
        runCatching { userManager?.userProfiles?.let { rawHandles.addAll(it) } }

        val results = mutableListOf<ResolvedUserHandle>()
        val seenSerials = HashSet<Long>()

        for (handle in rawHandles) {
            val serial = if (userManager != null) {
                runCatching { userManager.getSerialNumberForUser(handle) }.getOrDefault(-1L)
            } else if (handle == myHandle) {
                0L
            } else {
                -1L
            }
            if (serial < 0L || !seenSerials.add(serial)) continue

            val isCurrentUser = handle == myHandle
            val isRunning = if (isCurrentUser) {
                true
            } else {
                val running = runCatching { userManager?.isUserRunning(handle) ?: false }.getOrDefault(false)
                val quiet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { userManager?.isQuietModeEnabled(handle) ?: false }.getOrDefault(false)
                } else {
                    false
                }
                running && !quiet
            }

            val realUserId = extractUserIdFromHandleOrUid(handle, isCurrentUser)
            val pType = classifyProfileType(context, handle, isCurrentUser)
            val label = when (pType) {
                ProfileType.PRIMARY -> "主空间 (Serial $serial)"
                ProfileType.WORK -> "工作资料 (Serial $serial)"
                ProfileType.CLONE -> "克隆资料 (Serial $serial)"
                ProfileType.OTHER -> "子空间 (Serial $serial)"
            }

            results.add(
                ResolvedUserHandle(
                    handle = handle,
                    profile = PublicTargetEnvironment.AccessibleProfile(
                        userSerialNumber = serial,
                        userId = realUserId,
                        label = label,
                        profileType = pType,
                        isRunning = isRunning,
                        isCurrentProcessUser = isCurrentUser
                    )
                )
            )
        }
        return results
    }

    override fun queryAccessibleProfiles(context: Context): List<PublicTargetEnvironment.AccessibleProfile> {
        return discoverAccessibleHandles(context).map { it.profile }
    }

    override fun queryLaunchableAppsInProfile(
        context: Context,
        userSerialNumber: Long
    ): List<PublicTargetEnvironment.PublicAppMetadata> {
        return queryLaunchableAppsInProfile(
            context = context,
            userSerialNumber = userSerialNumber,
            includeOtherApps = true,
            importedTargets = emptySet()
        )
    }

    override fun queryLaunchableAppsInProfile(
        context: Context,
        userSerialNumber: Long,
        includeOtherApps: Boolean,
        importedTargets: Set<CaptureTarget>
    ): List<PublicTargetEnvironment.PublicAppMetadata> {
        val resolved = discoverAccessibleHandles(context)
            .firstOrNull { it.profile.userSerialNumber == userSerialNumber }
            ?: return emptyList()
        if (!resolved.profile.isRunning) return emptyList()

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val activities = runCatching {
            launcherApps.getActivityList(null, resolved.handle)
        }.getOrDefault(emptyList())

        val seenPackages = HashSet<String>()
        val results = mutableListOf<PublicTargetEnvironment.PublicAppMetadata>()
        for (activityInfo in activities) {
            val appInfo = activityInfo.applicationInfo ?: continue
            val pkg = appInfo.packageName ?: continue
            if (!seenPackages.add(pkg)) continue
            val target = CaptureTarget(pkg, userSerialNumber)
            val category = readApplicationCategory(appInfo)
            if (!includeOtherApps && !isAudioOrVideoAppCategory(category) && target !in importedTargets) {
                continue
            }
            onLabelLoadedHookForTesting?.invoke(pkg)
            val label = runCatching { activityInfo.label?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: loadAppLabel(context.packageManager, appInfo, pkg)
            val sharedPackages = resolveVerifiedSharedUidPackages(
                context = context,
                pkg = pkg,
                fullUid = appInfo.uid,
                isCurrentProcessUser = resolved.profile.isCurrentProcessUser
            )
            results.add(
                PublicTargetEnvironment.PublicAppMetadata(
                    packageName = pkg,
                    userSerialNumber = userSerialNumber,
                    appLabel = label,
                    uid = appInfo.uid,
                    isSystemApp = isSystemApp(appInfo),
                    isLaunchable = true,
                    verifiedSharedUidPackages = sharedPackages,
                    appCategory = category
                )
            )
        }
        return results
    }

    override fun resolveTargetApp(
        context: Context,
        target: CaptureTarget
    ): PublicTargetEnvironment.PublicAppMetadata? {
        val resolved = discoverAccessibleHandles(context)
            .firstOrNull { it.profile.userSerialNumber == target.userSerialNumber }
            ?: return null
        if (!resolved.profile.isRunning) return null

        val pm = context.packageManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

        val appInfo: ApplicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                launcherApps?.getApplicationInfo(target.packageName, 0, resolved.handle)
            }.getOrNull()
        } else {
            null
        } ?: if (resolved.profile.isCurrentProcessUser) {
            runCatching { pm.getApplicationInfo(target.packageName, 0) }.getOrNull()
        } else {
            null
        } ?: return null

        val category = readApplicationCategory(appInfo)
        onLabelLoadedHookForTesting?.invoke(target.packageName)
        val label = loadAppLabel(pm, appInfo, target.packageName)
        val sharedPackages = resolveVerifiedSharedUidPackages(
            context = context,
            pkg = target.packageName,
            fullUid = appInfo.uid,
            isCurrentProcessUser = resolved.profile.isCurrentProcessUser
        )
        return PublicTargetEnvironment.PublicAppMetadata(
            packageName = target.packageName,
            userSerialNumber = target.userSerialNumber,
            appLabel = label,
            uid = appInfo.uid,
            isSystemApp = isSystemApp(appInfo),
            isLaunchable = true,
            verifiedSharedUidPackages = sharedPackages,
            appCategory = category
        )
    }

    private fun resolveVerifiedSharedUidPackages(
        context: Context,
        pkg: String,
        fullUid: Int,
        isCurrentProcessUser: Boolean
    ): List<String>? {
        onSharedUidQueriedHookForTesting?.invoke(pkg, fullUid)
        if (fullUid <= 0) return null
        val pm = context.packageManager
        val fromGetPackages = runCatching {
            pm.getPackagesForUid(fullUid)?.filter { it.isNotBlank() }?.sorted()
        }.getOrNull().orEmpty()

        if (pkg in fromGetPackages) {
            return fromGetPackages
        }

        // In the current process user, if getPackagesForUid did not populate (e.g. unit test shadow),
        // verify via PackageInfo.sharedUserId whether this package declares a shared UID.
        if (isCurrentProcessUser) {
            val pkgInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            if (pkgInfo != null && pkgInfo.sharedUserId.isNullOrBlank()) {
                return listOf(pkg)
            }
        }

        // For cross-profile targets where public getPackagesForUid cannot enumerate all packages
        // sharing the cross-user UID without Shizuku, return null so we never guess or silently widen scope.
        return null
    }

    private fun classifyProfileType(
        context: Context,
        handle: UserHandle,
        isCurrentUser: Boolean
    ): ProfileType {
        if (isCurrentUser) return ProfileType.PRIMARY
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && userManager != null) {
            // Check via LauncherApps or UserHandle description without relying on list index
            val handleStr = handle.toString()
            if (handleStr.contains("Clone", ignoreCase = true)) {
                return ProfileType.CLONE
            }
        }
        return ProfileType.WORK
    }

    private fun extractUserIdFromHandleOrUid(handle: UserHandle, isCurrentUser: Boolean): Int {
        if (isCurrentUser) {
            return Process.myUid() / 100_000
        }
        return runCatching {
            val method = UserHandle::class.java.getDeclaredMethod("getIdentifier")
            method.isAccessible = true
            (method.invoke(handle) as? Int) ?: -1
        }.getOrDefault(-1)
    }

    private fun loadAppLabel(pm: PackageManager, appInfo: ApplicationInfo, fallbackPkg: String): String {
        return runCatching {
            pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
            ?: appInfo.nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: fallbackPkg
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }
}
