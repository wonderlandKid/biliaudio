package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import android.content.SharedPreferences
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.HomeStatsMode
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置仓库：SharedPreferences 持久化，StateFlow 暴露给 UI/播放层订阅。
 *
 * 装配：`SettingsRepositoryImpl(context)`。
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override suspend fun setAudioQuality(preference: AudioQualityPreference) {
        prefs.edit().putString(KEY_AUDIO_QUALITY, preference.name).apply()
        _settings.update { it.copy(audioQuality = preference) }
    }

    override suspend fun setDiagnosticLogsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIAGNOSTIC_LOGS, enabled).apply()
        _settings.update { it.copy(diagnosticLogsEnabled = enabled) }
    }

    override suspend fun setHomeStatsMode(mode: HomeStatsMode) {
        prefs.edit().putString(KEY_HOME_STATS_MODE, mode.name).apply()
        _settings.update { it.copy(homeStatsMode = mode) }
    }

    override suspend fun setDarkTheme(dark: Boolean?) {
        val editor = prefs.edit()
        when (dark) {
            null -> editor.remove(KEY_DARK_THEME)
            true -> editor.putBoolean(KEY_DARK_THEME, true)
            false -> editor.putBoolean(KEY_DARK_THEME, false)
        }
        editor.apply()
        _settings.update { it.copy(darkTheme = dark) }
    }

    private fun loadSettings(): AppSettings {
        val quality = prefs.getString(KEY_AUDIO_QUALITY, null)
            ?.let { name -> runCatching { AudioQualityPreference.valueOf(name) }.getOrNull() }
            ?: AudioQualityPreference.AUTO
        // 未设置过 → null（跟随系统）
        val dark = if (prefs.contains(KEY_DARK_THEME)) prefs.getBoolean(KEY_DARK_THEME, false) else null
        val diagnosticLogs = prefs.getBoolean(KEY_DIAGNOSTIC_LOGS, false)
        val homeStatsMode = prefs.getString(KEY_HOME_STATS_MODE, null)
            ?.let { name -> runCatching { HomeStatsMode.valueOf(name) }.getOrNull() }
            ?: HomeStatsMode.ALBUM_LISTEN_TIME
        return AppSettings(
            audioQuality = quality,
            darkTheme = dark,
            diagnosticLogsEnabled = diagnosticLogs,
            homeStatsMode = homeStatsMode,
        )
    }

    private companion object {
        const val PREFS_NAME = "biliaudio_settings"
        const val KEY_AUDIO_QUALITY = "audio_quality"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_DIAGNOSTIC_LOGS = "diagnostic_logs_enabled"
        const val KEY_HOME_STATS_MODE = "home_stats_mode"
    }
}
