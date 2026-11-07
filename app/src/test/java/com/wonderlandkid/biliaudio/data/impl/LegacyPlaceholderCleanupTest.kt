package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧版「整张专辑占位曲」识别（用于启动时自动清理历史数据）。 */
class LegacyPlaceholderCleanupTest {

    private fun album(title: String, durationMs: Long) = Album(
        id = 1L,
        bvid = "BV1GJ411x7h7",
        cid = 1L,
        title = title,
        artist = "UP主",
        coverUrl = "",
        durationMs = durationMs,
    )

    private fun track(
        title: String,
        startMs: Long = 0L,
        endMs: Long = 213_000L,
        index: Int = 0,
    ) = Track(id = 1L, albumId = 1L, index = index, title = title, startMs = startMs, endMs = endMs)

    @Test
    fun `单个整张曲目且标题等于专辑名时判定为占位曲`() {
        assertTrue(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                listOf(track(title = "专辑A")),
            ),
        )
    }

    @Test
    fun `标题带空白也能匹配`() {
        assertTrue(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                listOf(track(title = " 专辑A ")),
            ),
        )
    }

    @Test
    fun `多个分曲不是占位曲`() {
        assertFalse(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                listOf(track(title = "专辑A"), track(title = "曲目2", startMs = 100_000L)),
            ),
        )
    }

    @Test
    fun `标题与专辑名不同不是占位曲`() {
        assertFalse(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                listOf(track(title = "第一首")),
            ),
        )
    }

    @Test
    fun `没有覆盖整张专辑不是占位曲`() {
        assertFalse(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                listOf(track(title = "专辑A", startMs = 0L, endMs = 100_000L)),
            ),
        )
        assertFalse(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                listOf(track(title = "专辑A", startMs = 10_000L, endMs = 213_000L)),
            ),
        )
    }

    @Test
    fun `没有曲目不是占位曲`() {
        assertFalse(
            isLegacyPlaceholder(
                album(title = "专辑A", durationMs = 213_000L),
                emptyList(),
            ),
        )
    }
}
