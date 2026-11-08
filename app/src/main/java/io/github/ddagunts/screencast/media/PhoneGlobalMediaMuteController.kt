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
 * 2. Restores phone audio FIRST on normal stop, startup failure, disconnect, or revocation;
 *    if the user manually raised media volume to non-zero (`> 0`) during casting, preserves the user's new setting.
 * 3. If a leftover record from a force-killed process is found on reopen, exposes [pendingRecovery]
 *    for user confirmation ("恢复原音量" vs "保留当前设置") without writing back behind the user's back.
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
    private var inProcessSessionEngaged: Boolean = false

    private val _isGlobalMuteActive = MutableStateFlow(false)
    val isGlobalMuteActive: StateFlow<Boolean> = _isGlobalMuteActive.asStateFlow()

    private val _pendingRecovery = MutableStateFlow<PendingGlobalMuteRecovery?>(null)
    val pendingRecovery: StateFlow<PendingGlobalMuteRecovery?> = _pendingRecovery.asStateFlow()

    private val _isGlobalMediaMuteCompatible = MutableStateFlow(true)
    val isGlobalMediaMuteCompatible: StateFlow<Boolean> = _isGlobalMediaMuteCompatible.asStateFlow()

    private val _globalMediaMuteDisabledReason = MutableStateFlow<String?>(null)
    val globalMediaMuteDisabledReason: StateFlow<String?> = _globalMediaMuteDisabledReason.asStateFlow()

    fun initialize(context: Context, isSessionCurrentlyActive: Boolean = false) {
        synchronized(lock) {
            val prefs = prefs(context)
            val compatible = prefs.getBoolean(KEY_COMPATIBLE, true)
            val reason = prefs.getString(KEY_DISABLED_REASON, null)?.takeIf { it.isNotBlank() }
            _isGlobalMediaMuteCompatible.value = compatible
            _globalMediaMuteDisabledReason.value = if (compatible) null else (reason ?: "本机整体媒体静音会导致系统内录回采静音，已禁用该备选模式。")

            val hasLeftover = prefs.getBoolean(KEY_ACTIVE, false)
            if (hasLeftover && !inProcessSessionEngaged && !isSessionCurrentlyActive) {
                val savedVol = prefs.getInt(KEY_SAVED_VOLUME, 0)
                val maxVol = prefs.getInt(KEY_MAX_VOLUME, audioAdapter.getMusicMaxVolume(context))
                val wasMuted = prefs.getBoolean(KEY_WAS_MUTED, false)
                val currentVol = runCatching { audioAdapter.getMusicVolume(context) }.getOrDefault(0)
                _pendingRecovery.value = PendingGlobalMuteRecovery(
                    savedVolume = savedVol,
                    maxVolume = maxVol,
                    wasMuted = wasMuted,
                    currentVolume = currentVol
                )
            }
        }
    }

    /**
     * Persists the current media volume & mute state synchronously, then sets `STREAM_MUSIC` volume to 0.
     * Rolls back immediately if setting volume to 0 fails.
     */
    fun engageGlobalMediaMute(context: Context): Result<Unit> {
        synchronized(lock) {
            if (!_isGlobalMediaMuteCompatible.value) {
                val reason = _globalMediaMuteDisabledReason.value
                    ?: "整体媒体静音在本机已禁用（会使系统回采失效），请改用“仅采集不静音”。"
                return Result.failure(IllegalStateException(reason))
            }

            return try {
                val currentVolume = audioAdapter.getMusicVolume(context)
                val maxVolume = audioAdapter.getMusicMaxVolume(context)
                val wasMuted = audioAdapter.isMusicMuted(context)

                // If there was an unacknowledged leftover recovery, clear its prompt since user explicitly started a new global mute
                _pendingRecovery.value = null

                // Step 1: Synchronously persist original volume & mute state BEFORE modifying system volume
                val committed = prefs(context).edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putInt(KEY_SAVED_VOLUME, currentVolume)
                    .putInt(KEY_MAX_VOLUME, maxVolume)
                    .putBoolean(KEY_WAS_MUTED, wasMuted)
                    .commit()
                if (!committed) {
                    return Result.failure(IllegalStateException("无法持久化原媒体音量状态，拒绝执行整体静音。"))
                }

                // Step 2: Set STREAM_MUSIC volume to 0
                audioAdapter.setMusicVolume(context, 0)
                val afterVolume = audioAdapter.getMusicVolume(context)
                if (afterVolume != 0) {
                    // Rollback immediately on failure
                    clearPersistedRecordLocked(context)
                    runCatching { audioAdapter.setMusicVolume(context, currentVolume) }
                    return Result.failure(
                        IllegalStateException("无法将系统媒体音量设为 0 (当前音量=$afterVolume)，已回滚恢复。")
                    )
                }

                inProcessSessionEngaged = true
                _isGlobalMuteActive.value = true
                Result.success(Unit)
            } catch (e: Throwable) {
                clearPersistedRecordLocked(context)
                inProcessSessionEngaged = false
                _isGlobalMuteActive.value = false
                Result.failure(IllegalStateException("执行整体媒体静音失败：${e.message}", e))
            }
        }
    }

    /**
     * Immediately restores the phone's `STREAM_MUSIC` volume if this session engaged global media mute.
     * If the user manually changed the media volume to non-zero (`> 0`) during casting, preserves
     * the user's new volume setting instead of overwriting it.
     */
    fun restorePhoneAudioIfNeeded(context: Context): String? {
        synchronized(lock) {
            val prefs = prefs(context)
            val wasActiveInPrefs = prefs.getBoolean(KEY_ACTIVE, false)
            if (!inProcessSessionEngaged && (!wasActiveInPrefs || _pendingRecovery.value != null)) {
                _isGlobalMuteActive.value = false
                return null
            }

            val savedVolume = prefs.getInt(KEY_SAVED_VOLUME, 0)
            val wasMuted = prefs.getBoolean(KEY_WAS_MUTED, false)
            inProcessSessionEngaged = false
            _isGlobalMuteActive.value = false
            clearPersistedRecordLocked(context)

            return try {
                val currentVolume = audioAdapter.getMusicVolume(context)
                if (currentVolume > 0) {
                    "Global media mute ended: preserved user's non-zero media volume ($currentVolume)."
                } else {
                    if (!wasMuted && savedVolume > 0) {
                        runCatching { audioAdapter.setMusicMuted(context, false) }
                        audioAdapter.setMusicVolume(context, savedVolume)
                        "Global media mute ended: restored media volume to $savedVolume."
                    } else if (wasMuted) {
                        audioAdapter.setMusicVolume(context, savedVolume)
                        runCatching { audioAdapter.setMusicMuted(context, true) }
                        "Global media mute ended: restored previous muted state (volume=$savedVolume)."
                    } else {
                        "Global media mute ended: original media volume was already 0."
                    }
                }
            } catch (e: Throwable) {
                "Global media mute restore encountered error: ${e.message}"
            }
        }
    }

    fun isPhoneCurrentlyMutedByGlobalMode(context: Context): Boolean {
        synchronized(lock) {
            if (!inProcessSessionEngaged) return false
            val currentVol = runCatching { audioAdapter.getMusicVolume(context) }.getOrDefault(0)
            val muted = currentVol == 0
            _isGlobalMuteActive.value = muted
            return muted
        }
    }

    /**
     * Invoked when the user clicks "恢复原音量" on the startup crash-recovery dialog.
     */
    fun confirmRestoreLeftoverVolume(context: Context) {
        synchronized(lock) {
            val pending = _pendingRecovery.value ?: return
            _pendingRecovery.value = null
            inProcessSessionEngaged = false
            _isGlobalMuteActive.value = false
            clearPersistedRecordLocked(context)
            runCatching {
                if (!pending.wasMuted && pending.savedVolume > 0) {
                    audioAdapter.setMusicMuted(context, false)
                    audioAdapter.setMusicVolume(context, pending.savedVolume)
                } else {
                    audioAdapter.setMusicVolume(context, pending.savedVolume)
                    if (pending.wasMuted) {
                        audioAdapter.setMusicMuted(context, true)
                    }
                }
            }
        }
    }

    /**
     * Invoked when the user clicks "保留当前音量" on the startup crash-recovery dialog.
     */
    fun dismissLeftoverRecoveryKeepCurrent(context: Context) {
        synchronized(lock) {
            _pendingRecovery.value = null
            inProcessSessionEngaged = false
            _isGlobalMuteActive.value = false
            clearPersistedRecordLocked(context)
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
            _isGlobalMuteActive.value = false
            _pendingRecovery.value = null
            _isGlobalMediaMuteCompatible.value = true
            _globalMediaMuteDisabledReason.value = null
            audioAdapter = AndroidSystemMediaAudioAdapter
            prefs(context).edit().clear().commit()
        }
    }

    private fun clearPersistedRecordLocked(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_SAVED_VOLUME)
            .remove(KEY_MAX_VOLUME)
            .remove(KEY_WAS_MUTED)
            .commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
