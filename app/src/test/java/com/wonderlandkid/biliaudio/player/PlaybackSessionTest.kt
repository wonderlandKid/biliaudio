package com.wonderlandkid.biliaudio.player

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「上次播放会话」持久化 JSON 的兼容性测试（v0.59.0）。
 *
 * 只测纯序列化行为，不依赖 Android SharedPreferences：
 * - 写读往返字段不丢；
 * - 旧版本/多余的 key 不 crash（ignoreUnknownKeys）；
 * - 损坏内容由调用方吞掉（返回 null），不影响启动。
 */
class PlaybackSessionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `session json round trip keeps all fields`() {
        val session = PlaybackSession(
            albumId = 42L,
            trackId = 7L,
            trackStartMs = 120_000L,
            trackIndex = 2,
            positionMs = 33_000L,
            repeatMode = RepeatMode.ALL,
            shuffle = true,
            savedAtEpochMs = 1_700_000_000_000L,
        )
        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<PlaybackSession>(encoded)
        assertEquals(session, decoded)
    }

    @Test
    fun `session json ignores unknown keys from future versions`() {
        val raw = """
            {
              "albumId": 1,
              "trackId": 2,
              "trackStartMs": 0,
              "trackIndex": 0,
              "positionMs": 1234,
              "repeatMode": "OFF",
              "shuffle": false,
              "savedAtEpochMs": 0,
              "futureField": "ignored"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PlaybackSession>(raw)
        assertEquals(1L, decoded.albumId)
        assertEquals(1234L, decoded.positionMs)
    }

    @Test
    fun `invalid session json is reported as null by caller`() {
        val decoded = runCatching {
            json.decodeFromString<PlaybackSession>("not-json")
        }.getOrNull()
        assertNull(decoded)
    }
}
