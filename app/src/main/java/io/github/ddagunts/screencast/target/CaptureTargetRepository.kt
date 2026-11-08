package io.github.ddagunts.screencast.target

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ddagunts.screencast.shizuku.ShizukuSessionMuteController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.captureTargetsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "capture_targets_store"
)

/**
 * Persists user-added (`importedTargets`) and active-checked (`selectedTargets`)
 * [CaptureTarget]s (`packageName@userSerialNumber`) locally via DataStore without ever persisting UIDs.
 *
 * Also manages freezing the target snapshot at authorization time (`frozenTargets`)
 * and re-resolving full UIDs immediately before service capture starts.
 */
object CaptureTargetRepository {

    private val KEY_SELECTED_TARGETS = stringSetPreferencesKey("selected_capture_targets")
    private val KEY_IMPORTED_TARGETS = stringSetPreferencesKey("imported_capture_targets")

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _selectedTargets = MutableStateFlow<Set<CaptureTarget>>(emptySet())
    val selectedTargets: StateFlow<Set<CaptureTarget>> = _selectedTargets.asStateFlow()

    private val _importedTargets = MutableStateFlow<Set<CaptureTarget>>(emptySet())
    val importedTargets: StateFlow<Set<CaptureTarget>> = _importedTargets.asStateFlow()

    private val _profiles = MutableStateFlow<List<UserProfileInfo>>(
        listOf(UserProfileInfo(0L, 0, "主空间 (User 0)", ProfileType.PRIMARY, true))
    )
    val profiles: StateFlow<List<UserProfileInfo>> = _profiles.asStateFlow()

    private val _catalogApps = MutableStateFlow<List<TargetAppEntry>>(emptyList())
    val catalogApps: StateFlow<List<TargetAppEntry>> = _catalogApps.asStateFlow()

    private val _searchableApps = MutableStateFlow<List<TargetAppEntry>>(emptyList())
    val searchableApps: StateFlow<List<TargetAppEntry>> = _searchableApps.asStateFlow()

    private val _frozenTargets = MutableStateFlow<Set<CaptureTarget>?>(null)
    val frozenTargets: StateFlow<Set<CaptureTarget>?> = _frozenTargets.asStateFlow()

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (!initialized) {
            initialized = true
            repoScope.launch {
                runCatching {
                    val prefs = appContext.captureTargetsDataStore.data.first()
                    val loadedSelected = prefs[KEY_SELECTED_TARGETS].orEmpty()
                        .mapNotNull { CaptureTarget.fromStorageKey(it) }
                        .toSet()
                    val loadedImported = prefs[KEY_IMPORTED_TARGETS].orEmpty()
                        .mapNotNull { CaptureTarget.fromStorageKey(it) }
                        .toSet()
                    val combinedImported = loadedImported + loadedSelected
                    if (_selectedTargets.value.isEmpty() && loadedSelected.isNotEmpty()) {
                        _selectedTargets.value = loadedSelected
                    }
                    if (_importedTargets.value.isEmpty() && combinedImported.isNotEmpty()) {
                        _importedTargets.value = combinedImported
                    }
                }
                refreshCatalog(appContext)
            }
        } else {
            repoScope.launch {
                refreshCatalog(appContext)
            }
        }
    }

    fun refreshCatalog(context: Context) {
        val allTargetsInPool = _importedTargets.value + _selectedTargets.value
        val snapshot = ShizukuSessionMuteController.queryCatalog(
            context = context.applicationContext,
            importedTargets = allTargetsInPool
        )
        _profiles.value = snapshot.profiles

        val deduplicatedCatalog = LinkedHashMap<CaptureTarget, TargetAppEntry>()
        for (entry in snapshot.catalogApps) {
            if (entry.target in allTargetsInPool) {
                deduplicatedCatalog.putIfAbsent(entry.target, entry)
            }
        }
        _catalogApps.value = deduplicatedCatalog.values.toList()
        _searchableApps.value = snapshot.searchableApps
    }

    /**
     * Toggles selection of a [CaptureTarget] on the main console list.
     * Also ensures the target is in `_importedTargets`.
     * Ignored when target editing is locked (during capture authorization or an active cast session).
     */
    fun toggleTargetSelection(
        context: Context,
        target: CaptureTarget,
        isEditingLocked: Boolean
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        _importedTargets.update { it + target }
        _selectedTargets.update { current ->
            if (target in current) current - target else current + target
        }
        persistSelectionsAsync(context.applicationContext)
        refreshCatalog(context.applicationContext)
        return true
    }

    fun clearSelectedTargets(context: Context, isEditingLocked: Boolean): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        _selectedTargets.value = emptySet()
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
        _importedTargets.update { it + target }
        if (selectImmediately) {
            _selectedTargets.update { it + target }
        }
        persistSelectionsAsync(context.applicationContext)
        refreshCatalog(context.applicationContext)
        return Result.success(target)
    }

    fun removeImportedTarget(
        context: Context,
        target: CaptureTarget,
        isEditingLocked: Boolean
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        _importedTargets.update { it - target }
        _selectedTargets.update { it - target }
        persistSelectionsAsync(context.applicationContext)
        refreshCatalog(context.applicationContext)
        return true
    }

    /**
     * Freezes the current selected targets snapshot when capture authorization begins.
     */
    fun freezeSelectedTargetsSnapshot(): Set<CaptureTarget> {
        val snapshot = _selectedTargets.value.toSet()
        _frozenTargets.value = snapshot
        return snapshot
    }

    fun clearFrozenTargetsSnapshot() {
        _frozenTargets.value = null
    }

    /**
     * Re-resolves the frozen (or currently selected) [CaptureTarget] snapshot to full UIDs
     * immediately before capture starts, verifying package installation, profile running status,
     * and shared-UID completeness.
     */
    fun resolveAndValidateFrozenTargets(context: Context): TargetResolutionResult {
        val snapshot = _frozenTargets.value ?: _selectedTargets.value
        return ShizukuSessionMuteController.resolveAndValidateTargets(
            context = context.applicationContext,
            selectedTargets = snapshot
        )
    }

    internal fun setTargetsForTesting(
        selected: Set<CaptureTarget>,
        imported: Set<CaptureTarget> = selected,
        frozen: Set<CaptureTarget>? = null
    ) {
        _selectedTargets.value = selected
        _importedTargets.value = imported + selected
        _frozenTargets.value = frozen
    }

    internal fun resetForTesting() {
        _selectedTargets.value = emptySet()
        _importedTargets.value = emptySet()
        _frozenTargets.value = null
        _catalogApps.value = emptyList()
        _searchableApps.value = emptyList()
    }

    private fun persistSelectionsAsync(appContext: Context) {
        val selectedSnapshot = _selectedTargets.value.map { it.toStorageKey() }.toSet()
        val importedSnapshot = _importedTargets.value.map { it.toStorageKey() }.toSet()
        repoScope.launch {
            runCatching {
                appContext.captureTargetsDataStore.edit { prefs ->
                    prefs[KEY_SELECTED_TARGETS] = selectedSnapshot
                    prefs[KEY_IMPORTED_TARGETS] = importedSnapshot
                }
            }
        }
    }
}
