package io.github.ddagunts.screencast.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import io.github.ddagunts.screencast.target.PendingGlobalMuteRecovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Abstraction over system [AudioManager] `STREAM_MUSIC` operations for deterministic unit testing.
 */
interface SystemMediaAudioAdapter {
    fun getMusicVolume(context: Context): Int
    fun getMusicMaxVolume(context: Context): Int
    fun isMusicMuted(context: Context): Boolean
    fun setMusicVolume(context: Context, volume: Int)
    fun setMusicMuted(context: Context, muted: Boolean)
}

internal object AndroidSystemMediaAudioAdapter : SystemMediaAudioAdapter {
    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun getMusicVolume(context: Context): Int =
        audioManager(context).getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun getMusicMaxVolume(context: Context): Int =
        audioManager(context).getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    override fun isMusicMuted(context: Context): Boolean {
        val am = audioManager(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.isStreamMute(AudioManager.STREAM_MUSIC)
        } else {
            am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        }
    }

    override fun setMusicVolume(context: Context, volume: Int) {
        val am = audioManager(context)
        val clamped = volume.coerceIn(0, getMusicMaxVolume(context))
        am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
    }

    override fun setMusicMuted(context: Context, muted: Boolean) {
        val am = audioManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
    }
}

/**
 * Manages the `GLOBAL_MEDIA_MUTE` fallback mode using normal `MODIFY_AUDIO_SETTINGS` permission:
 * 1. Synchronously persists original `STREAM_MUSIC` volume & mute state BEFORE setting volume to 0.
 * 2. Distinguishes "mode engaged" ([inProcessSessionEngaged]) from "this service modified volume"
 *    ([inProcessModifiedAudio]); if the phone is already muted before start, does not zero volume
 *    or write a recovery record, and leaves original system state untouched on stop.
 * 3. Restores phone audio FIRST on normal stop, startup failure, disconnect, or revocation,
 *    clearing the persisted record only after confirming restoration succeeded.
 * 4. Leftover records from force-killed sessions or failed restorations are only resolved by
 *    explicit user action ("恢复原音量" vs "保留当前设置") and block new sessions until handled.
 */
object PhoneGlobalMediaMuteController {

    private const val PREFS_NAME = "screencast_global_media_mute_store"
    private const val KEY_ACTIVE = "global_mute_active"
    private const val KEY_SAVED_VOLUME = "global_mute_saved_volume"
    private const val KEY_MAX_VOLUME = "global_mute_max_volume"
    private const val KEY_WAS_MUTED = "global_mute_was_muted"
    private const val KEY_COMPATIBLE = "global_mute_capture_compatible"
    private const val KEY_DISABLED_REASON = "global_mute_disabled_reason"

    private val lock = Any()

    @Volatile
    var audioAdapter: SystemMediaAudioAdapter = AndroidSystemMediaAudioAdapter
        internal set

    @Volatile
    internal var persistCommitOverrideForTesting: ((Boolean) -> Boolean)? = null

    @Volatile
    internal var clearCommitOverrideForTesting: ((Boolean) -> Boolean)? = null

    @Volatile
    private var inProcessSessionEngaged: Boolean = false

    @Volatile
    private var inProcessModifiedAudio: Boolean = false

    private val _isGlobalMuteActive = MutableStateFlow(false)
    val isGlobalMuteActive: StateFlow<Boolean> = _isGlobalMuteActive.asStateFlow()

    private val _pendingRecovery = MutableStateFlow<PendingGlobalMuteRecovery?>(null)
    val pendingRecovery: StateFlow<PendingGlobalMuteRecovery?> = _pendingRecovery.asStateFlow()

    private val _isGlobalMediaMuteCompatible = MutableStateFlow(true)
    val isGlobalMediaMuteCompatible: StateFlow<Boolean> = _isGlobalMediaMuteCompatible.asStateFlow()

    private val _globalMediaMuteDisabledReason = MutableStateFlow<String?>(null)
    val globalMediaMuteDisabledReason: StateFlow<String?> = _globalMediaMuteDisabledReason.asStateFlow()

    internal val isModeEngagedInProcess: Boolean
        get() = synchronized(lock) { inProcessSessionEngaged }

    internal val didModifyAudioInProcess: Boolean
        get() = synchronized(lock) { inProcessModifiedAudio }

    fun initialize(context: Context, isSessionCurrentlyActive: Boolean = false) {
        synchronized(lock) {
            val prefs = prefs(context)
            val compatible = prefs.getBoolean(KEY_COMPATIBLE, true)
            val reason = prefs.getString(KEY_DISABLED_REASON, null)?.takeIf { it.isNotBlank() }
            _isGlobalMediaMuteCompatible.value = compatible
            _globalMediaMuteDisabledReason.value = if (compatible) null else (reason ?: "本机整体媒体静音会导致系统内录回采静音，已禁用该备选模式。")

            val hasLeftover = prefs.getBoolean(KEY_ACTIVE, false)
            if (hasLeftover && !inProcessSessionEngaged && !inProcessModifiedAudio && !isSessionCurrentlyActive) {
                populatePendingRecoveryFromPrefsLocked(context)
            }
        }
    }

    fun hasUnhandledRecoveryRecord(context: Context): Boolean {
        synchronized(lock) {
            if (_pendingRecovery.value != null) return true
            val hasLeftover = prefs(context).getBoolean(KEY_ACTIVE, false)
            if (hasLeftover && !inProcessModifiedAudio) {
                populatePendingRecoveryFromPrefsLocked(context)
                return true
            }
            return false
        }
    }

    /**
     * Engages global media mute mode:
     * - Refuses to start if an unhandled leftover/failed recovery record exists.
     * - If the phone is already muted (`wasMuted || currentVolume == 0`), marks mode engaged
     *   without modifying volume or writing a recovery snapshot.
     * - Otherwise persists the original volume & mute state synchronously first, marks
     *   [inProcessModifiedAudio] ownership immediately, and sets `STREAM_MUSIC` volume to 0.
     *   Any failure after persistence enters [restorePhoneAudioIfNeeded].
     */
    fun engageGlobalMediaMute(context: Context): Result<Unit> {
        synchronized(lock) {
            if (!_isGlobalMediaMuteCompatible.value) {
                val reason = _globalMediaMuteDisabledReason.value
                    ?: "整体媒体静音在本机已禁用（会使系统回采失效），请改用“仅采集不静音”。"
                return Result.failure(IllegalStateException(reason))
            }

            if (hasUnhandledRecoveryRecord(context)) {
                return Result.failure(
                    IllegalStateException("存在未处理的媒体音量恢复记录，请先在恢复弹窗中选择“恢复原音量”或“保留当前设置”后再启动新投放。")
                )
            }

            return try {
                val currentVolume = audioAdapter.getMusicVolume(context)
                val maxVolume = audioAdapter.getMusicMaxVolume(context)
                val wasMuted = audioAdapter.isMusicMuted(context)

                // If phone is already muted prior to casting, mark mode ready without modifying volume or persisting a snapshot
                if (wasMuted || currentVolume == 0) {
                    inProcessSessionEngaged = true
                    inProcessModifiedAudio = false
                    _isGlobalMuteActive.value = true
                    return Result.success(Unit)
                }

                // Step 1: Synchronously persist original volume & mute state BEFORE modifying system volume
                val overrideCommit = persistCommitOverrideForTesting?.invoke(true)
                val committed = if (overrideCommit == false) {
                    false
                } else {
                    val rawCommitted = prefs(context).edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putInt(KEY_SAVED_VOLUME, currentVolume)
                        .putInt(KEY_MAX_VOLUME, maxVolume)
                        .putBoolean(KEY_WAS_MUTED, wasMuted)
                        .commit()
                    overrideCommit ?: rawCommitted
                }
                if (!committed) {
                    return Result.failure(IllegalStateException("无法持久化原媒体音量状态，拒绝执行整体静音。"))
                }

                // Step 2: Record in-process modification ownership immediately after persistence succeeds
                inProcessSessionEngaged = true
                inProcessModifiedAudio = true
                _isGlobalMuteActive.value = true

                // Step 3: Set STREAM_MUSIC volume to 0; any failure from here enters unified recovery
                try {
                    audioAdapter.setMusicVolume(context, 0)
                    val afterVolume = audioAdapter.getMusicVolume(context)
                    if (afterVolume != 0) {
                        throw IllegalStateException("无法将系统媒体音量设为 0 (当前音量=$afterVolume)")
                    }
                    Result.success(Unit)
                } catch (muteErr: Throwable) {
                    val rollbackResult = restorePhoneAudioIfNeeded(context)
                    val rollbackSuffix = rollbackResult.fold(
                        onSuccess = { "，已自动恢复原媒体音量。" },
                        onFailure = { err -> "，且自动回滚原音量失败（${err.message}），请手动处理恢复记录。" }
                    )
                    Result.failure(
                        IllegalStateException("执行整体媒体静音失败：${muteErr.message}$rollbackSuffix", muteErr)
                    )
                }
            } catch (e: Throwable) {
                if (inProcessModifiedAudio) {
                    restorePhoneAudioIfNeeded(context)
                } else {
                    inProcessSessionEngaged = false
                    _isGlobalMuteActive.value = false
                }
                Result.failure(IllegalStateException("执行整体媒体静音失败：${e.message}", e))
            }
        }
    }

    /**
     * Restores the phone's `STREAM_MUSIC` volume if this session modified media audio:
     * - Never auto-restores force-kill leftover records not owned by this process.
     * - If the phone was already muted before session start ([inProcessSessionEngaged] && ![inProcessModifiedAudio]),
     *   leaves system audio state untouched.
     * - If the user manually raised media volume to non-zero (`> 0`) during casting, preserves user's setting.
     * - Only clears the persisted record after confirming restoration succeeded; retains snapshot and
     *   surfaces [pendingRecovery] if restoration or record clearing fails.
     */
    fun restorePhoneAudioIfNeeded(context: Context): Result<String?> {
        synchronized(lock) {
            if (!inProcessSessionEngaged && !inProcessModifiedAudio) {
                _isGlobalMuteActive.value = false
                return Result.success(null)
            }

            if (inProcessSessionEngaged && !inProcessModifiedAudio) {
                inProcessSessionEngaged = false
                _isGlobalMuteActive.value = false
                return Result.success("Global media mute ended: phone was already muted before session; kept original system state.")
            }

            val prefs = prefs(context)
            val savedVolume = prefs.getInt(KEY_SAVED_VOLUME, 0)
            val maxVolume = prefs.getInt(KEY_MAX_VOLUME, runCatching { audioAdapter.getMusicMaxVolume(context) }.getOrDefault(15))
            val wasMuted = prefs.getBoolean(KEY_WAS_MUTED, false)

            return try {
                val currentVolume = audioAdapter.getMusicVolume(context)
                val currentlyMuted = audioAdapter.isMusicMuted(context)
                val statusMessage = if (currentVolume > 0 && !currentlyMuted) {
                    "Global media mute ended: preserved user's non-zero media volume ($currentVolume)."
                } else {
                    if (!wasMuted && savedVolume > 0) {
                        audioAdapter.setMusicMuted(context, false)
                        audioAdapter.setMusicVolume(context, savedVolume)
                        val verifiedVol = audioAdapter.getMusicVolume(context)
                        val verifiedMuted = audioAdapter.isMusicMuted(context)
                        if (verifiedVol != savedVolume || verifiedMuted) {
                            throw IllegalStateException("系统媒体音量未能恢复至 $savedVolume (当前音量=$verifiedVol, 静音=$verifiedMuted)")
                        }
                        "Global media mute ended: restored media volume to $savedVolume."
                    } else if (wasMuted) {
                        audioAdapter.setMusicVolume(context, savedVolume)
                        audioAdapter.setMusicMuted(context, true)
                        "Global media mute ended: restored previous muted state (volume=$savedVolume)."
                    } else {
                        "Global media mute ended: original media volume was already 0."
                    }
                }

                val cleared = clearPersistedRecordLocked(context)
                if (!cleared) {
                    inProcessSessionEngaged = false
                    inProcessModifiedAudio = false
                    _isGlobalMuteActive.value = false
                    populatePendingRecoveryLocked(
                        context = context,
                        savedVolume = savedVolume,
                        maxVolume = maxVolume,
                        wasMuted = wasMuted
                    )
                    return Result.failure(
                        IllegalStateException("媒体音量恢复已执行，但清除持久化恢复记录失败，请确认恢复状态。")
                    )
                }

                inProcessSessionEngaged = false
                inProcessModifiedAudio = false
                _isGlobalMuteActive.value = false
                _pendingRecovery.value = null
                Result.success(statusMessage)
            } catch (e: Throwable) {
                inProcessSessionEngaged = false
                inProcessModifiedAudio = false
                _isGlobalMuteActive.value = false
                populatePendingRecoveryLocked(
                    context = context,
                    savedVolume = savedVolume,
                    maxVolume = maxVolume,
                    wasMuted = wasMuted
                )
                Result.failure(
                    IllegalStateException("恢复手机原媒体音量失败：${e.message}", e)
                )
            }
        }
    }

    fun isPhoneCurrentlyMutedByGlobalMode(context: Context): Boolean {
        synchronized(lock) {
            if (!inProcessSessionEngaged) return false
            val currentVol = runCatching { audioAdapter.getMusicVolume(context) }.getOrDefault(0)
            val muted = currentVol == 0 || runCatching { audioAdapter.isMusicMuted(context) }.getOrDefault(false)
            _isGlobalMuteActive.value = muted
            return muted
        }
    }

    /**
     * Invoked when the user clicks "恢复原音量" on the recovery dialog.
     * Returns [Result.failure] and keeps [pendingRecovery] if restoration or record clearing fails.
     */
    fun confirmRestoreLeftoverVolume(context: Context): Result<Unit> {
        synchronized(lock) {
            val pending = _pendingRecovery.value ?: run {
                if (prefs(context).getBoolean(KEY_ACTIVE, false)) {
                    populatePendingRecoveryFromPrefsLocked(context)
                }
                _pendingRecovery.value
            } ?: return Result.success(Unit)

            return try {
                if (!pending.wasMuted && pending.savedVolume > 0) {
                    audioAdapter.setMusicMuted(context, false)
                    audioAdapter.setMusicVolume(context, pending.savedVolume)
                    val verifiedVol = audioAdapter.getMusicVolume(context)
                    val verifiedMuted = audioAdapter.isMusicMuted(context)
                    if (verifiedVol != pending.savedVolume || verifiedMuted) {
                        throw IllegalStateException("系统媒体音量未能恢复至 ${pending.savedVolume} (当前=$verifiedVol)")
                    }
                } else {
                    audioAdapter.setMusicVolume(context, pending.savedVolume)
                    if (pending.wasMuted) {
                        audioAdapter.setMusicMuted(context, true)
                    }
                }

                val cleared = clearPersistedRecordLocked(context)
                if (!cleared) {
                    populatePendingRecoveryLocked(
                        context = context,
                        savedVolume = pending.savedVolume,
                        maxVolume = pending.maxVolume,
                        wasMuted = pending.wasMuted
                    )
                    return Result.failure(IllegalStateException("已写回原媒体音量，但清除持久化恢复记录失败。"))
                }

                _pendingRecovery.value = null
                inProcessSessionEngaged = false
                inProcessModifiedAudio = false
                _isGlobalMuteActive.value = false
                Result.success(Unit)
            } catch (e: Throwable) {
                populatePendingRecoveryLocked(
                    context = context,
                    savedVolume = pending.savedVolume,
                    maxVolume = pending.maxVolume,
                    wasMuted = pending.wasMuted
                )
                Result.failure(IllegalStateException("恢复原媒体音量失败：${e.message}", e))
            }
        }
    }

    /**
     * Invoked when the user clicks "保留当前音量" on the recovery dialog.
     * Returns [Result.failure] and retains [pendingRecovery] if clearing the persisted record fails.
     */
    fun dismissLeftoverRecoveryKeepCurrent(context: Context): Result<Unit> {
        synchronized(lock) {
            val cleared = clearPersistedRecordLocked(context)
            if (!cleared) {
                return Result.failure(IllegalStateException("清除未处理媒体静音恢复记录失败，请重试。"))
            }
            _pendingRecovery.value = null
            inProcessSessionEngaged = false
            inProcessModifiedAudio = false
            _isGlobalMuteActive.value = false
            return Result.success(Unit)
        }
    }

    /**
     * Enables or disables `GLOBAL_MEDIA_MUTE` option if device verification shows setting
     * `STREAM_MUSIC` to 0 silences internal playback capture.
     */
    fun setGlobalMediaMuteCompatibility(
        context: Context,
        compatible: Boolean,
        disabledReason: String? = null
    ) {
        synchronized(lock) {
            val cleanReason = if (compatible) {
                null
            } else {
                disabledReason?.takeIf { it.isNotBlank() }
                    ?: "本机系统媒体音量归零会导致 AudioPlaybackCapture 回采静音，已禁用整体媒体静音，请使用“仅采集不静音”或启用 Shizuku。"
            }
            prefs(context).edit()
                .putBoolean(KEY_COMPATIBLE, compatible)
                .putString(KEY_DISABLED_REASON, cleanReason)
                .commit()
            _isGlobalMediaMuteCompatible.value = compatible
            _globalMediaMuteDisabledReason.value = cleanReason
        }
    }

    internal fun simulateLeftoverCrashRecordForTesting(
        context: Context,
        savedVolume: Int,
        maxVolume: Int = 15,
        wasMuted: Boolean = false
    ) {
        synchronized(lock) {
            inProcessSessionEngaged = false
            inProcessModifiedAudio = false
            _isGlobalMuteActive.value = false
            prefs(context).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putInt(KEY_SAVED_VOLUME, savedVolume)
                .putInt(KEY_MAX_VOLUME, maxVolume)
                .putBoolean(KEY_WAS_MUTED, wasMuted)
                .commit()
        }
    }

    internal fun resetForTesting(context: Context) {
        synchronized(lock) {
            inProcessSessionEngaged = false
            inProcessModifiedAudio = false
            persistCommitOverrideForTesting = null
            clearCommitOverrideForTesting = null
            _isGlobalMuteActive.value = false
            _pendingRecovery.value = null
            _isGlobalMediaMuteCompatible.value = true
            _globalMediaMuteDisabledReason.value = null
            audioAdapter = AndroidSystemMediaAudioAdapter
            prefs(context).edit().clear().commit()
        }
    }

    private fun populatePendingRecoveryFromPrefsLocked(context: Context) {
        val prefs = prefs(context)
        val savedVol = prefs.getInt(KEY_SAVED_VOLUME, 0)
        val maxVol = prefs.getInt(
            KEY_MAX_VOLUME,
            runCatching { audioAdapter.getMusicMaxVolume(context) }.getOrDefault(15)
        )
        val wasMuted = prefs.getBoolean(KEY_WAS_MUTED, false)
        populatePendingRecoveryLocked(context, savedVol, maxVol, wasMuted)
    }

    private fun populatePendingRecoveryLocked(
        context: Context,
        savedVolume: Int,
        maxVolume: Int,
        wasMuted: Boolean
    ) {
        val currentVol = runCatching { audioAdapter.getMusicVolume(context) }.getOrDefault(0)
        _pendingRecovery.value = PendingGlobalMuteRecovery(
            savedVolume = savedVolume,
            maxVolume = maxVolume,
            wasMuted = wasMuted,
            currentVolume = currentVol
        )
    }

    private fun clearPersistedRecordLocked(context: Context): Boolean {
        val overrideClear = clearCommitOverrideForTesting?.invoke(true)
        if (overrideClear == false) {
            return false
        }
        val rawCommitted = prefs(context).edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_SAVED_VOLUME)
            .remove(KEY_MAX_VOLUME)
            .remove(KEY_WAS_MUTED)
            .commit()
        return overrideClear ?: rawCommitted
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
