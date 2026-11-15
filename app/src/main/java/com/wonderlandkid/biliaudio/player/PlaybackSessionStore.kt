package com.wonderlandkid.biliaudio.player

import android.content.Context
import android.content.SharedPreferences
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「上次播放会话」的本地持久化（v0.59.0）。
 *
 * 只存恢复播放所需的最小状态：专辑/曲目（id + 起始时间 + 序号）、曲内进度、播放模式。
 * - 不存 Cookie / 账号等敏感信息；
 * - 应用私有 SharedPreferences，不进入归档/备份；
 * - 每次写入都是小 JSON 字符串，5 秒级节流，不频繁落盘。
 */
@Serializable
internal data class PlaybackSession(
    val albumId: Long,
    val trackId: Long,
    /** 曲目在专辑内的起始 ms，曲目 id 变化（重新分割）时作为兜底匹配。 */
    val trackStartMs: Long,
    /** 曲目序号（再兜底）。 */
    val trackIndex: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val savedAtEpochMs: Long = 0L,
)

internal class PlaybackSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(session: PlaybackSession) {
        runCatching {
            prefs.edit().putString(KEY_SESSION, json.encodeToString(session)).apply()
        }.onFailure { Diag.log("Play", "session save FAILED: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun load(): PlaybackSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching { json.decodeFromString<PlaybackSession>(raw) }
            .onFailure {
                Diag.log("Play", "session load FAILED (dropped): ${it.javaClass.simpleName}: ${it.message}")
                prefs.edit().remove(KEY_SESSION).apply()
            }
            .getOrNull()
    }

    fun clear() {
        runCatching { prefs.edit().remove(KEY_SESSION).apply() }
    }

    private companion object {
        const val PREFS_NAME = "biliaudio_playback_session"
        const val KEY_SESSION = "last_session_v1"
    }
}
