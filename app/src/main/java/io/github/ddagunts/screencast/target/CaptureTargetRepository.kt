package io.github.ddagunts.screencast.target

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ddagunts.screencast.shizuku.MultiProfileCatalogSnapshot
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val Context.captureTargetsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "capture_targets_store"
)

/**
 * Persists user-added (`importedTargets`), active-checked (`selectedTargets`), and named
 * [TargetPreset] combinations (`presets`) locally via DataStore without ever persisting UIDs.
 *
 * Also manages freezing both the target snapshot (`frozenTargets`) and output mode
 * (`frozenOutputMode`) at authorization time and re-resolving full UIDs immediately before
 * service capture starts.
 */
object CaptureTargetRepository {

    internal data class CatalogScanRequest(
        val appContext: Context,
        val version: Long,
        val targetPoolSnapshot: Set<CaptureTarget>,
        val activeDisplaySnapshot: Set<CaptureTarget>
    )

    private val KEY_SELECTED_TARGETS = stringSetPreferencesKey("selected_capture_targets")
    private val KEY_IMPORTED_TARGETS = stringSetPreferencesKey("imported_capture_targets")
    private val KEY_TARGET_PRESETS_JSON = stringPreferencesKey("saved_target_presets_json")

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanConsumerMutex = Mutex()
    private val catalogRequestChannel = Channel<CatalogScanRequest>(Channel.CONFLATED)

    private val latestRequestVersion = AtomicLong(0L)
    private val lastCompletedRequestVersion = AtomicLong(0L)

    internal val catalogScanCountForTesting = AtomicInteger(0)
    internal val extendedCatalogScanCountForTesting = AtomicInteger(0)
    internal val targetEntryResolveCountForTesting = AtomicInteger(0)

    @Volatile
    internal var catalogScanInterceptorForTesting: (suspend (CatalogScanRequest) -> MultiProfileCatalogSnapshot?)? = null

    private val entryCache = ConcurrentHashMap<CaptureTarget, TargetAppEntry>()

    @Volatile
    private var defaultSearchableCache: List<TargetAppEntry> = emptyList()

    @Volatile
    private var extendedSearchableCache: List<TargetAppEntry>? = null

    @Volatile
    private var extendedCatalogLoadJob: Job? = null

    @Volatile
    private var activeConsumerJob: Job? = null

    @Volatile
    private var presetResolutionJob: Job? = null

    private val _selectedTargets = MutableStateFlow<Set<CaptureTarget>>(emptySet())
    val selectedTargets: StateFlow<Set<CaptureTarget>> = _selectedTargets.asStateFlow()

    private val _importedTargets = MutableStateFlow<Set<CaptureTarget>>(emptySet())
    val importedTargets: StateFlow<Set<CaptureTarget>> = _importedTargets.asStateFlow()

    private val _presets = MutableStateFlow<List<TargetPreset>>(emptyList())
    val presets: StateFlow<List<TargetPreset>> = _presets.asStateFlow()

    private val _profiles = MutableStateFlow<List<UserProfileInfo>>(
        listOf(UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true))
    )
    val profiles: StateFlow<List<UserProfileInfo>> = _profiles.asStateFlow()

    private val _catalogApps = MutableStateFlow<List<TargetAppEntry>>(emptyList())
    val catalogApps: StateFlow<List<TargetAppEntry>> = _catalogApps.asStateFlow()

    private val _searchableApps = MutableStateFlow<List<TargetAppEntry>>(emptyList())
    val searchableApps: StateFlow<List<TargetAppEntry>> = _searchableApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isExtendedSearchLoading = MutableStateFlow(false)
    val isExtendedSearchLoading: StateFlow<Boolean> = _isExtendedSearchLoading.asStateFlow()

    private val _frozenTargets = MutableStateFlow<Set<CaptureTarget>?>(null)
    val frozenTargets: StateFlow<Set<CaptureTarget>?> = _frozenTargets.asStateFlow()

    private val _frozenOutputMode = MutableStateFlow<PhoneOutputMode?>(null)
    val frozenOutputMode: StateFlow<PhoneOutputMode?> = _frozenOutputMode.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var initialLoadInProgress = false

    @Volatile
    private var initialScanCompleted = false

    @Volatile
    private var hasExplicitInMemoryMutation = false

    init {
        startCatalogScanConsumer()
    }

    private fun startCatalogScanConsumer() {
        repoScope.launch {
            for (request in catalogRequestChannel) {
                processCatalogScanRequest(request)
            }
        }
    }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (!initialized) {
                initialized = true
                initialLoadInProgress = true
                activeConsumerJob = repoScope.launch {
                    try {
                        loadFromDataStoreInternal(appContext)
                    } finally {
                        initialLoadInProgress = false
                    }
                    enqueueCatalogScan(appContext)
                }
                return
            }
            if (initialLoadInProgress) {
                return
            }
        }
        enqueueCatalogScan(appContext)
    }

    /**
     * Called from lifecycle `onResume`. Coalesces with cold-start initialization if initial load
     * or initial scan is already in progress, otherwise triggers a background catalog refresh.
     */
    fun onAppResumed(context: Context) {
        val appContext = context.applicationContext
        if (!initialized) {
            initialize(appContext)
            return
        }
        if (initialLoadInProgress || (!initialScanCompleted && latestRequestVersion.get() > lastCompletedRequestVersion.get())) {
            return
        }
        enqueueCatalogScan(appContext)
    }

    private suspend fun loadFromDataStoreInternal(appContext: Context) {
        runCatching {
            val prefs = appContext.captureTargetsDataStore.data.first()
            val loadedSelected = prefs[KEY_SELECTED_TARGETS].orEmpty()
                .mapNotNull { CaptureTarget.fromStorageKey(it) }
                .toSet()
            val loadedImported = prefs[KEY_IMPORTED_TARGETS].orEmpty()
                .mapNotNull { CaptureTarget.fromStorageKey(it) }
                .toSet()
            val loadedPresets = decodePresetsJson(prefs[KEY_TARGET_PRESETS_JSON])
            val combinedImported = loadedImported + loadedSelected
            if (!hasExplicitInMemoryMutation) {
                if (_selectedTargets.value.isEmpty() && loadedSelected.isNotEmpty()) {
                    _selectedTargets.value = loadedSelected
                }
                if (_importedTargets.value.isEmpty() && combinedImported.isNotEmpty()) {
                    _importedTargets.value = combinedImported
                }
                if (_presets.value.isEmpty() && loadedPresets.isNotEmpty()) {
                    _presets.value = loadedPresets
                }
            }
        }
    }

    /**
     * Enqueues a background catalog scan on [repoScope] via a conflated channel.
     * Never blocks the calling thread.
     */
    fun refreshCatalog(context: Context) {
        val appContext = context.applicationContext
        if (initialLoadInProgress) return
        enqueueCatalogScan(appContext)
    }

    private fun enqueueCatalogScan(appContext: Context): Long {
        val version = latestRequestVersion.incrementAndGet()
        val poolSnapshot = buildAllTargetsInPoolSnapshot()
        val displaySnapshot = _importedTargets.value + _selectedTargets.value
        catalogRequestChannel.trySend(
            CatalogScanRequest(
                appContext = appContext,
                version = version,
                targetPoolSnapshot = poolSnapshot,
                activeDisplaySnapshot = displaySnapshot
            )
        )
        return version
    }

    private suspend fun processCatalogScanRequest(request: CatalogScanRequest) {
        scanConsumerMutex.withLock {
            if (request.version < latestRequestVersion.get()) {
                return
            }
            catalogScanCountForTesting.incrementAndGet()
            val snapshot = catalogScanInterceptorForTesting?.invoke(request)
                ?: ShizukuSessionMuteController.queryCatalog(
                    context = request.appContext,
                    importedTargets = request.targetPoolSnapshot,
                    includeOtherApps = false
                )

            // Version gate: discard stale scan results if a newer request was enqueued while scanning
            if (request.version < latestRequestVersion.get()) {
                return
            }

            applyCatalogSnapshotLocked(snapshot)
            initialScanCompleted = true
            lastCompletedRequestVersion.set(request.version)
        }
    }

    private fun applyCatalogSnapshotLocked(snapshot: MultiProfileCatalogSnapshot) {
        _profiles.value = snapshot.profiles

        for (entry in snapshot.catalogApps) {
            entryCache[entry.target] = entry
        }
        for (entry in snapshot.searchableApps) {
            entryCache[entry.target] = entry
        }

        val currentPool = buildAllTargetsInPoolSnapshot()
        defaultSearchableCache = snapshot.searchableApps.filter { entry ->
            entry.isAudioOrVideoCategory || entry.target in currentPool
        }
        // Invalidate extended cache so subsequent searches reflect latest profile/install state
        extendedSearchableCache = null

        rebuildCatalogAppsFromCache()

        val activeQuery = _searchQuery.value.trim()
        if (activeQuery.isEmpty()) {
            _searchableApps.value = defaultSearchableCache
        } else {
            _searchableApps.value = filterAppsByQuery(defaultSearchableCache, activeQuery)
        }
    }

    /**
     * Handles search query updates in the visual app picker:
     * - First non-empty search triggers an asynchronous extended catalog load (`includeOtherApps = true`).
     * - Subsequent non-empty queries filter in memory by app label and package name.
     * - Clearing search immediately restores the default audio/video + imported catalog from cache.
     */
    fun onSearchQueryChanged(context: Context, rawQuery: String) {
        _searchQuery.value = rawQuery
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            extendedCatalogLoadJob?.cancel()
            extendedCatalogLoadJob = null
            _isExtendedSearchLoading.value = false
            _searchableApps.value = defaultSearchableCache
            return
        }

        val cachedExtended = extendedSearchableCache
        if (cachedExtended != null) {
            _searchableApps.value = filterAppsByQuery(cachedExtended, query)
            return
        }

        // Immediately filter default cache for instant responsiveness while loading extended apps
        _searchableApps.value = filterAppsByQuery(defaultSearchableCache, query)

        val currentJob = extendedCatalogLoadJob
        if (currentJob != null && currentJob.isActive) {
            return
        }

        val appContext = context.applicationContext
        val requestPool = buildAllTargetsInPoolSnapshot()
        _isExtendedSearchLoading.value = true
        extendedCatalogLoadJob = repoScope.launch {
            try {
                extendedCatalogScanCountForTesting.incrementAndGet()
                val extendedSnapshot = ShizukuSessionMuteController.queryCatalog(
                    context = appContext,
                    importedTargets = requestPool,
                    includeOtherApps = true
                )
                for (entry in extendedSnapshot.searchableApps) {
                    entryCache[entry.target] = entry
                }
                for (entry in extendedSnapshot.catalogApps) {
                    entryCache[entry.target] = entry
                }
                extendedSearchableCache = extendedSnapshot.searchableApps
                val latestQuery = _searchQuery.value.trim()
                if (latestQuery.isNotEmpty()) {
                    _searchableApps.value = filterAppsByQuery(extendedSnapshot.searchableApps, latestQuery)
                }
            } finally {
                _isExtendedSearchLoading.value = false
            }
        }
    }

    private fun filterAppsByQuery(
        source: List<TargetAppEntry>,
        query: String
    ): List<TargetAppEntry> {
        if (query.isBlank()) return source
        return source.filter { entry ->
            entry.appLabel.contains(query, ignoreCase = true) ||
                entry.target.packageName.contains(query, ignoreCase = true)
        }
    }

    /**
     * Toggles selection of a [CaptureTarget] on the main console list.
     * Updates in-memory state immediately without re-scanning the full catalog.
     */
    fun toggleTargetSelection(
        context: Context,
        target: CaptureTarget,
        isEditingLocked: Boolean
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        hasExplicitInMemoryMutation = true
        _importedTargets.update { it + target }
        _selectedTargets.update { current ->
            if (target in current) current - target else current + target
        }
        ensureTargetInDefaultSearchableCache(target)
        rebuildCatalogAppsFromCache()
        persistSelectionsAsync(context.applicationContext)
        return true
    }

    fun clearSelectedTargets(context: Context, isEditingLocked: Boolean): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        hasExplicitInMemoryMutation = true
        _selectedTargets.value = emptySet()
        rebuildCatalogAppsFromCache()
        persistSelectionsAsync(context.applicationContext)
        return true
    }

    /**
     * Adds or imports a `(packageName, userSerialNumber)` target into the unified target list
     * and selects it for casting. Deduplicates automatically.
     */
    fun importTargetApp(
        context: Context,
        packageName: String,
        userSerialNumber: Long,
        selectImmediately: Boolean = true,
        isEditingLocked: Boolean = false
    ): Result<CaptureTarget> {
        if (isEditingLocked || _frozenTargets.value != null) {
            return Result.failure(IllegalStateException("授权或投音进行中，已锁定目标应用修改。"))
        }
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || !cleanPkg.contains('.')) {
            return Result.failure(IllegalArgumentException("请输入合法的应用包名（例如 com.android.music）。"))
        }
        val target = CaptureTarget(cleanPkg, userSerialNumber)
        hasExplicitInMemoryMutation = true
        _importedTargets.update { it + target }
        if (selectImmediately) {
            _selectedTargets.update { it + target }
        }
        rebuildCatalogAppsFromCache()
        persistSelectionsAsync(context.applicationContext)
        resolveMissingTargetsInBackground(context.applicationContext, setOf(target), forceResolve = true)
        return Result.success(target)
    }

    fun removeImportedTarget(
        context: Context,
        target: CaptureTarget,
        isEditingLocked: Boolean
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        hasExplicitInMemoryMutation = true
        _importedTargets.update { it - target }
        _selectedTargets.update { it - target }
        rebuildCatalogAppsFromCache()
        val remainingPool = buildAllTargetsInPoolSnapshot()
        defaultSearchableCache = defaultSearchableCache.filter { entry ->
            entry.isAudioOrVideoCategory || entry.target in remainingPool
        }
        if (_searchQuery.value.isBlank()) {
            _searchableApps.value = defaultSearchableCache
        }
        persistSelectionsAsync(context.applicationContext)
        return true
    }

    /**
     * Saves the current non-empty `selectedTargets` as a named [TargetPreset].
     * Enforces non-blank unique name, non-empty selection, and editing lock.
     */
    fun saveCurrentSelectionAsPreset(
        context: Context,
        rawName: String,
        isEditingLocked: Boolean = false
    ): Result<TargetPreset> {
        if (isEditingLocked || _frozenTargets.value != null) {
            return Result.failure(IllegalStateException("授权或投音进行中，禁止保存或修改目标组合。"))
        }
        val cleanName = rawName.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("组合名称不能为空。"))
        }
        val currentSelection = _selectedTargets.value.toSet()
        if (currentSelection.isEmpty()) {
            return Result.failure(IllegalArgumentException("当前未勾选任何目标应用，空组合不能保存。"))
        }
        val existingPresets = _presets.value
        if (existingPresets.any { it.name == cleanName }) {
            return Result.failure(IllegalArgumentException("已存在同名目标组合「$cleanName」，请使用不重复的名称。"))
        }

        hasExplicitInMemoryMutation = true
        val newPreset = TargetPreset(name = cleanName, targets = currentSelection)
        _presets.update { it + newPreset }
        persistSelectionsAsync(context.applicationContext)
        return Result.success(newPreset)
    }

    /**
     * Applies a saved [TargetPreset] by immediately replacing `selectedTargets` in memory and
     * only requesting background resolution for targets missing from [entryCache].
     */
    fun applyPreset(
        context: Context,
        presetName: String,
        isEditingLocked: Boolean = false
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        val preset = _presets.value.firstOrNull { it.name == presetName } ?: return false
        hasExplicitInMemoryMutation = true
        _importedTargets.update { it + preset.targets }
        _selectedTargets.value = preset.targets.toSet()
        preset.targets.forEach { ensureTargetInDefaultSearchableCache(it) }
        rebuildCatalogAppsFromCache()
        persistSelectionsAsync(context.applicationContext)

        val missingTargets = preset.targets.filterNot { entryCache.containsKey(it) }.toSet()
        if (missingTargets.isNotEmpty()) {
            resolveMissingTargetsInBackground(context.applicationContext, missingTargets, forceResolve = false)
        }
        return true
    }

    /**
     * Deletes a saved [TargetPreset] by name without deleting any imported apps.
     */
    fun deletePreset(
        context: Context,
        presetName: String,
        isEditingLocked: Boolean = false
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        val current = _presets.value
        val updated = current.filterNot { it.name == presetName }
        if (updated.size == current.size) return false
        _presets.value = updated
        persistSelectionsAsync(context.applicationContext)
        return true
    }

    /**
     * Freezes the current selected targets snapshot and output mode when capture authorization begins.
     */
    fun freezeSelectedTargetsSnapshot(
        outputMode: PhoneOutputMode = PhoneOutputMode.SHIZUKU_SESSION_MUTE
    ): Set<CaptureTarget> {
        val snapshot = _selectedTargets.value.toSet()
        _frozenTargets.value = snapshot
        _frozenOutputMode.value = outputMode
        return snapshot
    }

    fun clearFrozenTargetsSnapshot() {
        _frozenTargets.value = null
        _frozenOutputMode.value = null
    }

    /**
     * Re-resolves the frozen (or currently selected) [CaptureTarget] snapshot to full UIDs
     * on [Dispatchers.IO] immediately before capture starts, verifying package installation,
     * profile running status, and shared-UID completeness according to the frozen [PhoneOutputMode].
     */
    suspend fun resolveAndValidateFrozenTargets(
        context: Context,
        outputModeOverride: PhoneOutputMode? = null
    ): TargetResolutionResult = withContext(Dispatchers.IO) {
        resolveAndValidateFrozenTargetsSync(context, outputModeOverride)
    }

    internal fun resolveAndValidateFrozenTargetsSync(
        context: Context,
        outputModeOverride: PhoneOutputMode? = null
    ): TargetResolutionResult {
        val snapshot = _frozenTargets.value ?: _selectedTargets.value
        val mode = outputModeOverride ?: _frozenOutputMode.value ?: PhoneOutputMode.SHIZUKU_SESSION_MUTE
        return ShizukuSessionMuteController.resolveAndValidateTargets(
            context = context.applicationContext,
            selectedTargets = snapshot,
            outputMode = mode
        )
    }

    private fun resolveMissingTargetsInBackground(
        appContext: Context,
        targets: Set<CaptureTarget>,
        forceResolve: Boolean
    ) {
        val toResolve = if (forceResolve) {
            targets
        } else {
            targets.filterNot { entryCache.containsKey(it) }.toSet()
        }
        if (toResolve.isEmpty()) return

        presetResolutionJob = repoScope.launch {
            targetEntryResolveCountForTesting.incrementAndGet()
            val resolvedEntries = ShizukuSessionMuteController.resolveTargetEntries(appContext, toResolve)
            for (entry in resolvedEntries) {
                entryCache[entry.target] = entry
                ensureTargetInDefaultSearchableCache(entry.target)
            }
            rebuildCatalogAppsFromCache()
            if (_searchQuery.value.isBlank()) {
                _searchableApps.value = defaultSearchableCache
            }
        }
    }

    private fun ensureTargetInDefaultSearchableCache(target: CaptureTarget) {
        val cached = entryCache[target] ?: return
        if (defaultSearchableCache.none { it.target == target }) {
            defaultSearchableCache = (defaultSearchableCache + cached).sortedWith(
                compareBy({ it.isSystemApp }, { it.profileType.ordinal }, { it.appLabel.lowercase() })
            )
            if (_searchQuery.value.isBlank()) {
                _searchableApps.value = defaultSearchableCache
            }
        }
    }

    private fun rebuildCatalogAppsFromCache() {
        val activeDisplayTargets = _importedTargets.value + _selectedTargets.value
        if (activeDisplayTargets.isEmpty()) {
            _catalogApps.value = emptyList()
            return
        }
        val profileMap = _profiles.value.associateBy { it.userSerialNumber }
        val deduplicated = LinkedHashMap<CaptureTarget, TargetAppEntry>()
        for (target in activeDisplayTargets) {
            val cached = entryCache[target] ?: buildPlaceholderTargetEntry(target, profileMap)
            deduplicated.putIfAbsent(target, cached)
        }
        _catalogApps.value = deduplicated.values.sortedWith(
            compareBy({ it.profileType.ordinal }, { it.appLabel.lowercase() }, { it.target.packageName })
        )
    }

    private fun buildPlaceholderTargetEntry(
        target: CaptureTarget,
        profileMap: Map<Long, UserProfileInfo>
    ): TargetAppEntry {
        val profile = profileMap[target.userSerialNumber]
        val pType = profile?.profileType
            ?: if (target.userSerialNumber == 0L) ProfileType.PRIMARY else ProfileType.WORK
        val pLabel = profile?.label ?: pType.badgeLabel
        val isRunning = profile?.isRunning ?: (target.userSerialNumber == 0L)
        return TargetAppEntry(
            target = target,
            appLabel = target.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            profileType = pType,
            profileLabel = pLabel,
            isInstalled = true,
            isProfileRunning = isRunning,
            isSystemApp = false,
            appCategory = ApplicationInfo.CATEGORY_UNDEFINED,
            resolvedUid = null,
            sharedUidPackages = emptyList(),
            statusReason = if (!isRunning) "所属空间未运行或已锁定" else null
        )
    }

    private fun buildAllTargetsInPoolSnapshot(): Set<CaptureTarget> {
        return buildSet {
            addAll(_importedTargets.value)
            addAll(_selectedTargets.value)
            _presets.value.forEach { addAll(it.targets) }
        }
    }

    internal fun setTargetsForTesting(
        selected: Set<CaptureTarget>,
        imported: Set<CaptureTarget> = selected,
        frozen: Set<CaptureTarget>? = null,
        frozenMode: PhoneOutputMode? = null,
        presets: List<TargetPreset> = _presets.value
    ) {
        hasExplicitInMemoryMutation = true
        initialLoadInProgress = false
        _selectedTargets.value = selected
        _importedTargets.value = imported + selected
        _frozenTargets.value = frozen
        _frozenOutputMode.value = frozenMode
        _presets.value = presets
        rebuildCatalogAppsFromCache()
    }

    internal fun refreshCatalogNowForTesting(context: Context) {
        runBlocking {
            val appContext = context.applicationContext
            val version = latestRequestVersion.incrementAndGet()
            val poolSnapshot = buildAllTargetsInPoolSnapshot()
            val displaySnapshot = _importedTargets.value + _selectedTargets.value
            processCatalogScanRequest(
                CatalogScanRequest(
                    appContext = appContext,
                    version = version,
                    targetPoolSnapshot = poolSnapshot,
                    activeDisplaySnapshot = displaySnapshot
                )
            )
        }
    }

    internal suspend fun awaitIdleForTesting() {
        activeConsumerJob?.join()
        extendedCatalogLoadJob?.join()
        presetResolutionJob?.join()
        persistenceJob?.join()
        var attempts = 0
        while (
            (lastCompletedRequestVersion.get() < latestRequestVersion.get() ||
                lastPersistedVersion.get() < latestPersistenceVersion.get()) &&
            attempts < 100
        ) {
            kotlinx.coroutines.delay(10L)
            attempts++
        }
        scanConsumerMutex.withLock { }
        persistenceMutex.withLock { }
    }

    internal fun flushAndReloadForTesting(context: Context) {
        val appContext = context.applicationContext
        val selectedSnapshot = _selectedTargets.value.map { it.toStorageKey() }.toSet()
        val importedSnapshot = _importedTargets.value.map { it.toStorageKey() }.toSet()
        val presetsJson = encodePresetsJson(_presets.value)
        runBlocking {
            persistenceJob?.join()
            persistenceMutex.withLock {
                val ver = latestPersistenceVersion.incrementAndGet()
                persistenceWriteInterceptorForTesting?.invoke(ver, selectedSnapshot, presetsJson)
                appContext.captureTargetsDataStore.edit { prefs ->
                    prefs[KEY_SELECTED_TARGETS] = selectedSnapshot
                    prefs[KEY_IMPORTED_TARGETS] = importedSnapshot
                    prefs[KEY_TARGET_PRESETS_JSON] = presetsJson
                }
                lastPersistedVersion.set(ver)
            }
            hasExplicitInMemoryMutation = false
            _selectedTargets.value = emptySet()
            _importedTargets.value = emptySet()
            _presets.value = emptyList()
            loadFromDataStoreInternal(appContext)
        }
    }

    internal fun resetForTesting() {
        extendedCatalogLoadJob?.cancel()
        extendedCatalogLoadJob = null
        presetResolutionJob?.cancel()
        presetResolutionJob = null
        activeConsumerJob?.cancel()
        activeConsumerJob = null
        persistenceJob?.cancel()
        persistenceJob = null
        catalogScanInterceptorForTesting = null
        persistenceWriteInterceptorForTesting = null
        initialized = false
        initialLoadInProgress = false
        initialScanCompleted = false
        hasExplicitInMemoryMutation = false
        val currentVer = latestRequestVersion.incrementAndGet()
        lastCompletedRequestVersion.set(currentVer)
        val persistVer = latestPersistenceVersion.incrementAndGet()
        lastPersistedVersion.set(persistVer)
        while (catalogRequestChannel.tryReceive().isSuccess) {
            // Drain any pending requests
        }
        entryCache.clear()
        defaultSearchableCache = emptyList()
        extendedSearchableCache = null
        catalogScanCountForTesting.set(0)
        extendedCatalogScanCountForTesting.set(0)
        targetEntryResolveCountForTesting.set(0)
        _searchQuery.value = ""
        _isExtendedSearchLoading.value = false
        _selectedTargets.value = emptySet()
        _importedTargets.value = emptySet()
        _presets.value = emptyList()
        _frozenTargets.value = null
        _frozenOutputMode.value = null
        _catalogApps.value = emptyList()
        _searchableApps.value = emptyList()
    }

    internal fun encodePresetsJson(presets: List<TargetPreset>): String {
        val arr = JSONArray()
        for (preset in presets) {
            val targetsArr = JSONArray(preset.targets.map { it.toStorageKey() })
            arr.put(
                JSONObject()
                    .put("name", preset.name)
                    .put("targets", targetsArr)
            )
        }
        return arr.toString()
    }

    internal fun decodePresetsJson(rawJson: String?): List<TargetPreset> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(rawJson)
            val seenNames = HashSet<String>()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isEmpty() || !seenNames.add(name)) continue
                    val tArr = obj.optJSONArray("targets") ?: continue
                    val targets = buildSet {
                        for (j in 0 until tArr.length()) {
                            val t = CaptureTarget.fromStorageKey(tArr.optString(j))
                            if (t != null) add(t)
                        }
                    }
                    if (targets.isNotEmpty()) {
                        add(TargetPreset(name = name, targets = targets))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private val persistenceMutex = Mutex()
    private val latestPersistenceVersion = AtomicLong(0L)
    private val lastPersistedVersion = AtomicLong(0L)

    @Volatile
    private var persistenceJob: Job? = null

    @Volatile
    internal var persistenceWriteInterceptorForTesting: (suspend (Long, Set<String>, String) -> Unit)? = null

    private fun persistSelectionsAsync(appContext: Context) {
        latestPersistenceVersion.incrementAndGet()
        persistenceJob = repoScope.launch {
            persistenceMutex.withLock {
                while (lastPersistedVersion.get() < latestPersistenceVersion.get()) {
                    val targetVersion = latestPersistenceVersion.get()
                    val selectedSnapshot = _selectedTargets.value.map { it.toStorageKey() }.toSet()
                    val importedSnapshot = _importedTargets.value.map { it.toStorageKey() }.toSet()
                    val presetsJson = encodePresetsJson(_presets.value)
                    runCatching {
                        persistenceWriteInterceptorForTesting?.invoke(
                            targetVersion,
                            selectedSnapshot,
                            presetsJson
                        )
                        // If a newer mutation arrived while interceptor was suspended, write the latest state instead
                        val finalVersion = latestPersistenceVersion.get()
                        val finalSelected = _selectedTargets.value.map { it.toStorageKey() }.toSet()
                        val finalImported = _importedTargets.value.map { it.toStorageKey() }.toSet()
                        val finalPresetsJson = encodePresetsJson(_presets.value)
                        appContext.captureTargetsDataStore.edit { prefs ->
                            prefs[KEY_SELECTED_TARGETS] = finalSelected
                            prefs[KEY_IMPORTED_TARGETS] = finalImported
                            prefs[KEY_TARGET_PRESETS_JSON] = finalPresetsJson
                        }
                        lastPersistedVersion.set(finalVersion)
                    }.onFailure {
                        lastPersistedVersion.set(targetVersion)
                    }
                }
            }
        }
    }
}
