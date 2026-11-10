package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.CachedAlbumAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频缓存纯逻辑单测（不依赖 Android Context）：
 * key 生成、多 P 分 P 去重、按专辑聚合、删除匹配、专辑详情汇总。
 */
class AudioCacheLogicTest {

    private fun track(
        sourceCid: Long = 0L,
        sourcePage: Int = 0,
        index: Int = 0,
    ) = Track(
        albumId = 7L,
        index = index,
        title = "曲目 $index",
        startMs = index * 1_000L,
        endMs = (index + 1) * 1_000L,
        sourceCid = sourceCid,
        sourcePage = sourcePage,
    )

    private fun meta(
        cid: Long,
        albumId: Long = 7L,
        bvid: String = "BV1xx411c7mD",
        bytes: Long = 100L,
        quality: String = "192K",
        cachedAtMs: Long = 1_000L,
        page: Int = 1,
    ) = CachedAudioMeta(
        key = AudioCacheLogic.keyOf(bvid, cid),
        albumId = albumId,
        bvid = bvid,
        cid = cid,
        page = page,
        audioQualityId = 30280,
        audioQualityLabel = quality,
        bytes = bytes,
        cachedAtMs = cachedAtMs,
    )

    // ---------------------------------------------------------------- key

    @Test
    fun `key 由 bvid 和 cid 组成`() {
        assertEquals("BV1xx411c7mD_123456", AudioCacheLogic.keyOf("BV1xx411c7mD", 123_456L))
    }

    // ---------------------------------------------------------------- 目标分 P

    @Test
    fun `多 P 曲目按 sourceCid 去重且保持曲目顺序`() {
        val pages = AudioCacheLogic.targetPages(
            albumCid = 100L,
            albumPage = 1,
            tracks = listOf(
                track(sourceCid = 0L, sourcePage = 0, index = 0), // 沿用专辑 cid
                track(sourceCid = 200L, sourcePage = 2, index = 1),
                track(sourceCid = 200L, sourcePage = 2, index = 2), // 同 P 的第二首，不应重复缓存
                track(sourceCid = 300L, sourcePage = 3, index = 3),
            ),
        )
        assertEquals(
            listOf(
                CachePage(cid = 100L, page = 1),
                CachePage(cid = 200L, page = 2),
                CachePage(cid = 300L, page = 3),
            ),
            pages,
        )
    }

    @Test
    fun `单视频曲目全部沿用专辑 cid 只缓存一份`() {
        val pages = AudioCacheLogic.targetPages(
            albumCid = 100L,
            albumPage = 4,
            tracks = listOf(track(index = 0), track(index = 1), track(index = 2)),
        )
        assertEquals(listOf(CachePage(cid = 100L, page = 4)), pages)
    }

    @Test
    fun `没有曲目时回退为专辑本身的分 P`() {
        assertEquals(
            listOf(CachePage(cid = 100L, page = 2)),
            AudioCacheLogic.targetPages(albumCid = 100L, albumPage = 2, tracks = emptyList()),
        )
        // 非法 cid 不产生缓存目标
        assertTrue(AudioCacheLogic.targetPages(albumCid = 0L, albumPage = 1, tracks = emptyList()).isEmpty())
    }

    @Test
    fun `多 P 曲目 sourcePage 缺失时默认 P1`() {
        val pages = AudioCacheLogic.targetPages(
            albumCid = 100L,
            albumPage = 1,
            tracks = listOf(track(sourceCid = 200L, sourcePage = 0)),
        )
        assertEquals(listOf(CachePage(cid = 200L, page = 1)), pages)
    }

    // ---------------------------------------------------------------- 按专辑聚合

    @Test
    fun `缓存管理按 albumId 聚合多个分 P`() {
        val grouped = AudioCacheLogic.groupByAlbum(
            listOf(
                meta(cid = 100L, bytes = 100L, cachedAtMs = 1_000L, quality = "192K"),
                meta(cid = 200L, bytes = 200L, cachedAtMs = 2_000L, quality = "132K"),
                meta(cid = 100L, bytes = 100L, cachedAtMs = 1_000L), // 不会出现，但去重 cid
            ),
        )
        assertEquals(1, grouped.size)
        val album = grouped.single()
        assertEquals(7L, album.albumId)
        assertEquals("BV1xx411c7mD", album.bvid)
        assertEquals(listOf(100L, 200L), album.cids)
        assertEquals(400L, album.bytes)
        assertEquals(2, album.cachedPageCount)
        // 档位取最近一次缓存的分 P
        assertEquals("132K", album.audioQualityLabel)
        assertEquals(2_000L, album.updatedAtMs)
    }

    @Test
    fun `元数据缺失 albumId 的历史缓存按 bvid 分组`() {
        val grouped = AudioCacheLogic.groupByAlbum(
            listOf(
                meta(cid = 100L, albumId = 0L, bvid = "BV1aa", cachedAtMs = 1_000L),
                meta(cid = 200L, albumId = 0L, bvid = "BV1bb", cachedAtMs = 3_000L),
            ),
        )
        assertEquals(2, grouped.size)
        // 更新时间新的排前面
        assertEquals("BV1bb", grouped.first().bvid)
        assertEquals("BV1aa", grouped.last().bvid)
        assertEquals(0L, grouped.last().albumId)
    }

    // ---------------------------------------------------------------- 删除匹配

    @Test
    fun `remove 按 albumId 命中该专辑所有分 P 并兼容 cid 回退`() {
        val keys = AudioCacheLogic.keysToRemove(
            albumId = 7L,
            bvid = "BV1xx411c7mD",
            cid = 100L,
            entries = listOf(
                meta(cid = 100L),
                meta(cid = 200L),
                meta(cid = 300L, albumId = 8L), // 别的专辑不能删
            ),
        )
        assertEquals(
            setOf(
                AudioCacheLogic.keyOf("BV1xx411c7mD", 100L),
                AudioCacheLogic.keyOf("BV1xx411c7mD", 200L),
            ),
            keys,
        )
    }

    @Test
    fun `removeCached 按 albumId 或 bvid 命中整张专辑`() {
        val byAlbumId = AudioCacheLogic.keysToRemoveForGroup(
            item = CachedAlbumAudio(albumId = 7L, bvid = "BV1xx411c7mD", cids = listOf(100L)),
            entries = listOf(meta(cid = 100L), meta(cid = 200L), meta(cid = 300L, albumId = 8L)),
        )
        assertEquals(
            setOf(
                AudioCacheLogic.keyOf("BV1xx411c7mD", 100L),
                AudioCacheLogic.keyOf("BV1xx411c7mD", 200L),
            ),
            byAlbumId,
        )

        val byBvid = AudioCacheLogic.keysToRemoveForGroup(
            item = CachedAlbumAudio(albumId = 0L, bvid = "BV1aa", cids = emptyList()),
            entries = listOf(
                meta(cid = 100L, albumId = 0L, bvid = "BV1aa"),
                meta(cid = 200L, albumId = 0L, bvid = "BV1bb"),
            ),
        )
        assertEquals(setOf(AudioCacheLogic.keyOf("BV1aa", 100L)), byBvid)
    }

    // ---------------------------------------------------------------- 专辑详情汇总

    @Test
    fun `专辑详情汇总只统计目标分 P 的已缓存数量与大小`() {
        val targets = listOf(
            CachePage(cid = 100L, page = 1),
            CachePage(cid = 200L, page = 2),
            CachePage(cid = 300L, page = 3),
        )
        val summary = AudioCacheLogic.summarize(
            albumId = 7L,
            bvid = "BV1xx411c7mD",
            targets = targets,
            cached = listOf(
                meta(cid = 100L, bytes = 100L, cachedAtMs = 1_000L, quality = "192K"),
                meta(cid = 300L, bytes = 300L, cachedAtMs = 2_000L, quality = "132K"),
                // 不属于本专辑目标分 P 的缓存不应计入
                meta(cid = 999L, bytes = 999L, cachedAtMs = 9_000L),
            ),
        )
        assertEquals(listOf(100L, 300L), summary.cids)
        assertEquals(2, summary.cachedPageCount)
        assertEquals(400L, summary.bytes)
        assertEquals("132K", summary.audioQualityLabel)
        assertEquals(2_000L, summary.updatedAtMs)
    }

    @Test
    fun `完全未缓存时汇总为空`() {
        val summary = AudioCacheLogic.summarize(
            albumId = 7L,
            bvid = "BV1xx411c7mD",
            targets = listOf(CachePage(cid = 100L, page = 1)),
            cached = emptyList(),
        )
        assertTrue(!summary.hasAny)
        assertEquals(0, summary.cachedPageCount)
        assertEquals(0L, summary.bytes)
        assertNull(summary.audioQualityLabel)
    }
}
