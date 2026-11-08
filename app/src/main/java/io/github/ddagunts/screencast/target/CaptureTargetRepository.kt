package io.github.ddagunts.screencast.target

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

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

    private val KEY_SELECTED_TARGETS = stringSetPreferencesKey("selected_capture_targets")
    private val KEY_IMPORTED_TARGETS = stringSetPreferencesKey("imported_capture_targets")
    private val KEY_TARGET_PRESETS_JSON = stringPreferencesKey("saved_target_presets_json")

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private val _frozenTargets = MutableStateFlow<Set<CaptureTarget>?>(null)
    val frozenTargets: StateFlow<Set<CaptureTarget>?> = _frozenTargets.asStateFlow()

    private val _frozenOutputMode = MutableStateFlow<PhoneOutputMode?>(null)
    val frozenOutputMode: StateFlow<PhoneOutputMode?> = _frozenOutputMode.asStateFlow()

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (!initialized) {
            initialized = true
            repoScope.launch {
                loadFromDataStoreInternal(appContext)
                refreshCatalog(appContext)
            }
        } else {
            repoScope.launch {
                refreshCatalog(appContext)
            }
        }
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

    fun refreshCatalog(context: Context) {
        val allTargetsInPool = buildSet {
            addAll(_importedTargets.value)
            addAll(_selectedTargets.value)
            _presets.value.forEach { addAll(it.targets) }
        }
        val snapshot = ShizukuSessionMuteController.queryCatalog(
            context = context.applicationContext,
            importedTargets = allTargetsInPool
        )
        _profiles.value = snapshot.profiles

        val activeDisplayTargets = _importedTargets.value + _selectedTargets.value
        val deduplicatedCatalog = LinkedHashMap<CaptureTarget, TargetAppEntry>()
        for (entry in snapshot.catalogApps) {
            if (entry.target in activeDisplayTargets) {
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

        val newPreset = TargetPreset(name = cleanName, targets = currentSelection)
        _presets.update { it + newPreset }
        persistSelectionsAsync(context.applicationContext)
        return Result.success(newPreset)
    }

    /**
     * Applies a saved [TargetPreset] by replacing the current `selectedTargets` with the preset's
     * targets and ensuring all preset targets remain visible in `importedTargets` (including any
     * currently unavailable/inaccessible targets so their status reasons are displayed).
     */
    fun applyPreset(
        context: Context,
        presetName: String,
        isEditingLocked: Boolean = false
    ): Boolean {
        if (isEditingLocked || _frozenTargets.value != null) return false
        val preset = _presets.value.firstOrNull { it.name == presetName } ?: return false
        _importedTargets.update { it + preset.targets }
        _selectedTargets.value = preset.targets.toSet()
        persistSelectionsAsync(context.applicationContext)
        refreshCatalog(context.applicationContext)
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
     * immediately before capture starts, verifying package installation, profile running status,
     * and shared-UID completeness according to the frozen [PhoneOutputMode].
     */
    fun resolveAndValidateFrozenTargets(
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

    internal fun setTargetsForTesting(
        selected: Set<CaptureTarget>,
        imported: Set<CaptureTarget> = selected,
        frozen: Set<CaptureTarget>? = null,
        frozenMode: PhoneOutputMode? = null,
        presets: List<TargetPreset> = _presets.value
    ) {
        _selectedTargets.value = selected
        _importedTargets.value = imported + selected
        _frozenTargets.value = frozen
        _frozenOutputMode.value = frozenMode
        _presets.value = presets
    }

    internal fun flushAndReloadForTesting(context: Context) {
        val appContext = context.applicationContext
        val selectedSnapshot = _selectedTargets.value.map { it.toStorageKey() }.toSet()
        val importedSnapshot = _importedTargets.value.map { it.toStorageKey() }.toSet()
        val presetsJson = encodePresetsJson(_presets.value)
        runBlocking {
            appContext.captureTargetsDataStore.edit { prefs ->
                prefs[KEY_SELECTED_TARGETS] = selectedSnapshot
                prefs[KEY_IMPORTED_TARGETS] = importedSnapshot
                prefs[KEY_TARGET_PRESETS_JSON] = presetsJson
            }
            _selectedTargets.value = emptySet()
            _importedTargets.value = emptySet()
            _presets.value = emptyList()
            loadFromDataStoreInternal(appContext)
        }
    }

    internal fun resetForTesting() {
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

    private fun persistSelectionsAsync(appContext: Context) {
        val selectedSnapshot = _selectedTargets.value.map { it.toStorageKey() }.toSet()
        val importedSnapshot = _importedTargets.value.map { it.toStorageKey() }.toSet()
        val presetsJson = encodePresetsJson(_presets.value)
        repoScope.launch {
            runCatching {
                appContext.captureTargetsDataStore.edit { prefs ->
                    prefs[KEY_SELECTED_TARGETS] = selectedSnapshot
                    prefs[KEY_IMPORTED_TARGETS] = importedSnapshot
                    prefs[KEY_TARGET_PRESETS_JSON] = presetsJson
                }
            }
        }
    }
}
