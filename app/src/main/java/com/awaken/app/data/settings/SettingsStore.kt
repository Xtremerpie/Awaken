package com.awaken.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.awaken.app.BuildConfig
import com.awaken.core.QuietHours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

enum class DndBehavior { SKIP_WHEN_DND, PLAY_ANYWAY }
enum class StartupBehavior { DASHBOARD, LIBRARY, SCHEDULES }

data class AppSettings(
    val masterEnabled: Boolean = true,
    val defaultVolume: Float = 0.8f,
    val muted: Boolean = false,
    val defaultDurationSec: Int = 0,
    val defaultSnoozeMinutes: Int = 10,
    val quietEnabled: Boolean = false,
    val quietStartMinutes: Int = 22 * 60,
    val quietEndMinutes: Int = 7 * 60,
    val dnd: DndBehavior = DndBehavior.SKIP_WHEN_DND,
    val themeId: String = "gold",
    val vibration: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val gradualVolume: Boolean = false,
    val gradualSeconds: Int = 30,
    val fadeInMs: Int = 1500,
    val fadeOutMs: Int = 2000,
    val startup: StartupBehavior = StartupBehavior.DASHBOARD,
    val showSplash: Boolean = true,
    val onboardingDone: Boolean = false,
    val seeded: Boolean = false,
    val defaultMediaId: Long = -1,
    val autoCheckUpdates: Boolean = true,
    val updateManifestUrl: String = BuildConfig.DEFAULT_UPDATE_MANIFEST_URL,
    val remoteConfigEnabled: Boolean = true,
    val remoteConfigUrl: String = BuildConfig.DEFAULT_REMOTE_CONFIG_URL,
    /** Non-zero while a mandatory update is pending. Lifted only when Android reports this version installed. */
    val requiredVersionCode: Long = 0,
    val lastUpdateCheck: Long = 0,
    val remoteConfigJson: String = "",
) {
    val quietHours: QuietHours
        get() = QuietHours(
            quietEnabled,
            LocalTime.of(quietStartMinutes / 60, quietStartMinutes % 60),
            LocalTime.of(quietEndMinutes / 60, quietEndMinutes % 60),
        )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "awaken_settings")

class SettingsStore(private val context: Context) {
    private object K {
        val master = booleanPreferencesKey("master_enabled")
        val volume = floatPreferencesKey("default_volume")
        val muted = booleanPreferencesKey("muted")
        val duration = intPreferencesKey("default_duration_sec")
        val snooze = intPreferencesKey("default_snooze_min")
        val quietOn = booleanPreferencesKey("quiet_enabled")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val dnd = stringPreferencesKey("dnd")
        val theme = stringPreferencesKey("theme")
        val vibration = booleanPreferencesKey("vibration")
        val haptics = booleanPreferencesKey("haptics")
        val gradual = booleanPreferencesKey("gradual")
        val gradualSec = intPreferencesKey("gradual_sec")
        val fadeIn = intPreferencesKey("fade_in")
        val fadeOut = intPreferencesKey("fade_out")
        val startup = stringPreferencesKey("startup")
        val splash = booleanPreferencesKey("show_splash")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val seeded = booleanPreferencesKey("seeded")
        val defaultMedia = longPreferencesKey("default_media")
        val autoCheck = booleanPreferencesKey("auto_check_updates")
        val manifestUrl = stringPreferencesKey("manifest_url")
        val remoteOn = booleanPreferencesKey("remote_enabled")
        val remoteUrl = stringPreferencesKey("remote_url")
        val required = longPreferencesKey("required_version_code")
        val lastCheck = longPreferencesKey("last_update_check")
        val remoteJson = stringPreferencesKey("remote_config_json")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            masterEnabled = p[K.master] ?: d.masterEnabled,
            defaultVolume = (p[K.volume] ?: d.defaultVolume).coerceIn(0f, 1f),
            muted = p[K.muted] ?: d.muted,
            defaultDurationSec = p[K.duration] ?: d.defaultDurationSec,
            defaultSnoozeMinutes = p[K.snooze] ?: d.defaultSnoozeMinutes,
            quietEnabled = p[K.quietOn] ?: d.quietEnabled,
            quietStartMinutes = p[K.quietStart] ?: d.quietStartMinutes,
            quietEndMinutes = p[K.quietEnd] ?: d.quietEndMinutes,
            dnd = p[K.dnd]?.let { runCatching { DndBehavior.valueOf(it) }.getOrNull() } ?: d.dnd,
            themeId = p[K.theme] ?: d.themeId,
            vibration = p[K.vibration] ?: d.vibration,
            hapticsEnabled = p[K.haptics] ?: d.hapticsEnabled,
            gradualVolume = p[K.gradual] ?: d.gradualVolume,
            gradualSeconds = p[K.gradualSec] ?: d.gradualSeconds,
            fadeInMs = p[K.fadeIn] ?: d.fadeInMs,
            fadeOutMs = p[K.fadeOut] ?: d.fadeOutMs,
            startup = p[K.startup]?.let { runCatching { StartupBehavior.valueOf(it) }.getOrNull() } ?: d.startup,
            showSplash = p[K.splash] ?: d.showSplash,
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
            seeded = p[K.seeded] ?: d.seeded,
            defaultMediaId = p[K.defaultMedia] ?: d.defaultMediaId,
            autoCheckUpdates = p[K.autoCheck] ?: d.autoCheckUpdates,
            updateManifestUrl = p[K.manifestUrl] ?: d.updateManifestUrl,
            remoteConfigEnabled = p[K.remoteOn] ?: d.remoteConfigEnabled,
            remoteConfigUrl = p[K.remoteUrl] ?: d.remoteConfigUrl,
            requiredVersionCode = p[K.required] ?: d.requiredVersionCode,
            lastUpdateCheck = p[K.lastCheck] ?: d.lastUpdateCheck,
            remoteConfigJson = p[K.remoteJson] ?: d.remoteConfigJson,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setMasterEnabled(v: Boolean) = put(K.master, v)
    suspend fun setDefaultVolume(v: Float) = put(K.volume, v.coerceIn(0f, 1f))
    suspend fun setMuted(v: Boolean) = put(K.muted, v)
    suspend fun setDefaultDurationSec(v: Int) = put(K.duration, v.coerceIn(0, 6 * 3600))
    suspend fun setDefaultSnoozeMinutes(v: Int) = put(K.snooze, v.coerceIn(1, 120))
    suspend fun setQuietEnabled(v: Boolean) = put(K.quietOn, v)
    suspend fun setQuietStart(minutes: Int) = put(K.quietStart, minutes.coerceIn(0, 1439))
    suspend fun setQuietEnd(minutes: Int) = put(K.quietEnd, minutes.coerceIn(0, 1439))
    suspend fun setDnd(v: DndBehavior) = put(K.dnd, v.name)
    suspend fun setThemeId(v: String) = put(K.theme, v)
    suspend fun setVibration(v: Boolean) = put(K.vibration, v)
    suspend fun setHaptics(v: Boolean) = put(K.haptics, v)
    suspend fun setGradualVolume(v: Boolean) = put(K.gradual, v)
    suspend fun setGradualSeconds(v: Int) = put(K.gradualSec, v.coerceIn(5, 300))
    suspend fun setFadeInMs(v: Int) = put(K.fadeIn, v.coerceIn(0, 10_000))
    suspend fun setFadeOutMs(v: Int) = put(K.fadeOut, v.coerceIn(0, 10_000))
    suspend fun setStartup(v: StartupBehavior) = put(K.startup, v.name)
    suspend fun setShowSplash(v: Boolean) = put(K.splash, v)
    suspend fun setOnboardingDone(v: Boolean) = put(K.onboarding, v)
    suspend fun setSeeded(v: Boolean) = put(K.seeded, v)
    suspend fun setDefaultMediaId(v: Long) = put(K.defaultMedia, v)
    suspend fun setAutoCheckUpdates(v: Boolean) = put(K.autoCheck, v)
    suspend fun setUpdateManifestUrl(v: String) = put(K.manifestUrl, v.trim())
    suspend fun setRemoteConfigEnabled(v: Boolean) = put(K.remoteOn, v)
    suspend fun setRemoteConfigUrl(v: String) = put(K.remoteUrl, v.trim())
    suspend fun setRequiredVersionCode(v: Long) = put(K.required, v)
    suspend fun setLastUpdateCheck(v: Long) = put(K.lastCheck, v)
    suspend fun setRemoteConfigJson(v: String) = put(K.remoteJson, v)

    /** Reset-to-factory: every preference removed (the seeded flag too, so first-run seeding runs again). */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
