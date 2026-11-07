package com.wonderlandkid.biliaudio.player

import androidx.media3.common.PlaybackException
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 播放层纯逻辑单元测试：队列构建、分曲 → MediaItem 规格映射、进度换算、错误文案、视频同步阈值。
 *
 * 不依赖 Android 运行时（MediaItem 转换的 Android 部分由 [PlaybackMapping.toMediaItem] 承担，
 * 这里验证其纯数据前驱 [TrackPlaySpec]，可在 JVM 上稳定运行）。
 */
class PlaybackMappingTest {

    private fun album(
        id: Long = 1L,
        durationMs: Long = 180_000L,
        title: String = "测试专辑",
    ) = Album(
        id = id,
        bvid = "BV1xx411c7mD",
        cid = 123L,
        title = title,
        artist = "测试艺术家",
        coverUrl = "https://i0.hdslb.com/test.jpg",
        durationMs = durationMs,
    )

    private fun track(
        id: Long,
        index: Int,
        startMs: Long,
        endMs: Long,
        albumId: Long = 1L,
        title: String = "曲目$index",
    ) = Track(
        id = id,
        albumId = albumId,
        index = index,
        title = title,
        startMs = startMs,
        endMs = endMs,
    )

    // ---------------------------------------------------------------- 队列构建

    @Test
    fun buildQueueEntries_fromAlbum_sortsByTrackIndexAndFillsAlbumInfo() {
        val album = album()
        val tracks = listOf(
            track(id = 2L, index = 1, startMs = 45_000L, endMs = 120_000L),
            track(id = 1L, index = 0, startMs = 0L, endMs = 45_000L),
        )
        val entries = PlaybackMapping.buildQueueEntries(album, tracks)

        assertEquals(2, entries.size)
        // 按 index 排序，而不是按传入顺序
        assertEquals(1L, entries[0].track.id)
        assertEquals(2L, entries[1].track.id)
        assertEquals(album.id, entries[0].albumId)
        assertEquals(album.title, entries[0].albumTitle)
        assertEquals(album.artist, entries[0].artist)
        assertEquals(album.coverUrl, entries[0].coverUrl)
        assertEquals(album.durationMs, entries[0].albumDurationMs)
        assertEquals(45_000L, entries[0].durationMs)
    }

    @Test
    fun buildQueueEntries_fromSongs_preservesOrderAndAlbumInfo() {
        val album1 = album(id = 1L)
        val album2 = album(id = 2L, title = "第二张专辑")
        val songs = listOf(
            TrackWithAlbum(track(id = 10L, index = 0, startMs = 0L, endMs = 60_000L, albumId = 2L), album2),
            TrackWithAlbum(track(id = 20L, index = 0, startMs = 0L, endMs = 90_000L, albumId = 1L), album1),
        )
        val entries = PlaybackMapping.buildQueueEntries(songs)

        assertEquals(2, entries.size)
        assertEquals(10L, entries[0].track.id)
        assertEquals(2L, entries[0].albumId)
        assertEquals("第二张专辑", entries[0].albumTitle)
        assertEquals(20L, entries[1].track.id)
        assertEquals(1L, entries[1].albumId)
    }

    // ------------------------------------------------------- 分曲 → MediaItem 规格

    @Test
    fun buildPlaySpecs_mapsClipRangeAndMetadata() {
        val album = album()
        val entries = PlaybackMapping.buildQueueEntries(
            album,
            listOf(track(id = 7L, index = 0, startMs = 30_000L, endMs = 95_500L)),
        )
        val specs = PlaybackMapping.buildPlaySpecs(entries, mapOf(album.id to "https://cdn.example/audio.m4s"))

        assertEquals(1, specs.size)
        val spec = specs[0]
        assertEquals("https://cdn.example/audio.m4s", spec.audioUrl)
        assertEquals("biliaudio:track:7", spec.mediaId)
        assertEquals(30_000L, spec.clipStartMs)
        assertEquals(95_500L, spec.clipEndMs)
        assertEquals(65_500L, spec.clipDurationMs)
        assertEquals("曲目0", spec.title)
        assertEquals("测试艺术家", spec.artist)
        assertEquals("测试专辑", spec.albumTitle)
        assertEquals("https://i0.hdslb.com/test.jpg", spec.coverUrl)
    }

    @Test
    fun buildPlaySpecs_sanitizesNegativeStartAndEmptyClip() {
        val album = album()
        val entries = PlaybackMapping.buildQueueEntries(
            album,
            listOf(
                track(id = 1L, index = 0, startMs = -500L, endMs = 10_000L),
                track(id = 2L, index = 1, startMs = 10_000L, endMs = 10_000L),
            ),
        )
        val specs = PlaybackMapping.buildPlaySpecs(entries, mapOf(album.id to "https://cdn.example/audio.m4s"))

        // 负起点收敛到 0
        assertEquals(0L, specs[0].clipStartMs)
        assertEquals(10_000L, specs[0].clipEndMs)
        // 空区间至少保留 1ms，避免 ClippingMediaSource 创建失败
        assertEquals(10_000L, specs[1].clipStartMs)
        assertEquals(10_001L, specs[1].clipEndMs)
    }

    @Test
    fun buildPlaySpecs_missingAudioUrl_throwsReadableError() {
        val entries = PlaybackMapping.buildQueueEntries(
            album(),
            listOf(track(id = 1L, index = 0, startMs = 0L, endMs = 10_000L)),
        )
        try {
            PlaybackMapping.buildPlaySpecs(entries, emptyMap())
            fail("缺少音频流地址时应抛出异常")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("音频流获取失败"))
        }
    }

    @Test
    fun buildPlaySpecs_keepsSameSizeAsQueue() {
        val album = album()
        val entries = PlaybackMapping.buildQueueEntries(
            album,
            (0..4).map { index ->
                track(id = index.toLong() + 1, index = index, startMs = index * 10_000L, endMs = (index + 1) * 10_000L)
            },
        )
        val specs = PlaybackMapping.buildPlaySpecs(entries, mapOf(album.id to "https://cdn.example/audio.m4s"))
        assertEquals(entries.size, specs.size)
        specs.forEachIndexed { index, spec ->
            assertEquals(entries[index].track.id, spec.trackId)
            assertEquals(entries[index].track.startMs, spec.clipStartMs)
            assertEquals(entries[index].track.endMs, spec.clipEndMs)
        }
    }

    // ---------------------------------------------------------------- 进度换算

    @Test
    fun trackPositionToAlbumPosition_addsTrackStart() {
        val track = track(id = 1L, index = 2, startMs = 90_000L, endMs = 150_000L)
        assertEquals(90_000L, PlaybackMapping.trackPositionToAlbumPosition(track, 0L))
        assertEquals(120_000L, PlaybackMapping.trackPositionToAlbumPosition(track, 30_000L))
        // 超出曲长收敛到曲尾
        assertEquals(150_000L, PlaybackMapping.trackPositionToAlbumPosition(track, 999_999L))
        // 负进度收敛到曲首
        assertEquals(90_000L, PlaybackMapping.trackPositionToAlbumPosition(track, -5_000L))
    }

    @Test
    fun albumPositionToTrackPosition_subtractsTrackStartAndClamps() {
        val track = track(id = 1L, index = 2, startMs = 90_000L, endMs = 150_000L)
        assertEquals(0L, PlaybackMapping.albumPositionToTrackPosition(track, 90_000L))
        assertEquals(30_000L, PlaybackMapping.albumPositionToTrackPosition(track, 120_000L))
        assertEquals(0L, PlaybackMapping.albumPositionToTrackPosition(track, 0L))
        assertEquals(60_000L, PlaybackMapping.albumPositionToTrackPosition(track, 180_000L))
    }

    @Test
    fun findQueueIndexAtAlbumPosition_findsTrackContainingPosition() {
        val album = album()
        val entries = PlaybackMapping.buildQueueEntries(
            album,
            listOf(
                track(id = 1L, index = 0, startMs = 0L, endMs = 60_000L),
                track(id = 2L, index = 1, startMs = 60_000L, endMs = 120_000L),
                track(id = 3L, index = 2, startMs = 120_000L, endMs = 180_000L),
            ),
        )
        assertEquals(0, PlaybackMapping.findQueueIndexAtAlbumPosition(entries, album.id, 0L))
        assertEquals(0, PlaybackMapping.findQueueIndexAtAlbumPosition(entries, album.id, 59_999L))
        assertEquals(1, PlaybackMapping.findQueueIndexAtAlbumPosition(entries, album.id, 60_000L))
        assertEquals(2, PlaybackMapping.findQueueIndexAtAlbumPosition(entries, album.id, 179_999L))
        // 越界 / 别的专辑 → -1
        assertEquals(-1, PlaybackMapping.findQueueIndexAtAlbumPosition(entries, album.id, 180_000L))
        assertEquals(-1, PlaybackMapping.findQueueIndexAtAlbumPosition(entries, albumId = 99L, albumPositionMs = 10_000L))
    }

    @Test
    fun clampAlbumPosition_keepsInsideAlbumDuration() {
        assertEquals(0L, PlaybackMapping.clampAlbumPosition(-100L, 180_000L))
        assertEquals(42_000L, PlaybackMapping.clampAlbumPosition(42_000L, 180_000L))
        // 收缩到 duration - 1，保证能落在最后一首区间内
        assertEquals(179_999L, PlaybackMapping.clampAlbumPosition(180_000L, 180_000L))
        assertEquals(0L, PlaybackMapping.clampAlbumPosition(10_000L, 0L))
    }

    // ---------------------------------------------------------------- 错误文案

    @Test
    fun playbackErrorMessages_mapCommonErrorCodes() {
        assertEquals(
            PlayerErrorMessages.NETWORK,
            PlayerErrorMessages.forPlaybackErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            PlayerErrorMessages.NETWORK,
            PlayerErrorMessages.forPlaybackErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
        assertTrue(
            PlayerErrorMessages.forPlaybackErrorCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                .contains("音频流获取失败"),
        )
        assertEquals(
            PlayerErrorMessages.STREAM_PARSE,
            PlayerErrorMessages.forPlaybackErrorCode(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertTrue(PlayerErrorMessages.forPlaybackErrorCode(Int.MAX_VALUE).isNotBlank())
    }

    @Test
    fun playbackErrorMessages_preferReadableExceptionMessage() {
        assertEquals(
            "该专辑还没有分曲，请先编辑分曲时间点",
            PlayerErrorMessages.forException(IllegalStateException("该专辑还没有分曲，请先编辑分曲时间点")),
        )
        assertEquals(
            PlayerErrorMessages.AUDIO_STREAM_UNAVAILABLE,
            PlayerErrorMessages.forException(RuntimeException("boom")),
        )
    }

    // ---------------------------------------------------------------- 视频同步

    @Test
    fun videoSync_correctsOnlyWhenDriftExceedsThreshold() {
        assertFalse(VideoSync.needsCorrection(audioAlbumPositionMs = 10_000L, videoPositionMs = 10_000L))
        assertFalse(VideoSync.needsCorrection(audioAlbumPositionMs = 10_300L, videoPositionMs = 10_000L))
        assertFalse(VideoSync.needsCorrection(audioAlbumPositionMs = 9_700L, videoPositionMs = 10_000L))
        assertTrue(VideoSync.needsCorrection(audioAlbumPositionMs = 10_301L, videoPositionMs = 10_000L))
        assertTrue(VideoSync.needsCorrection(audioAlbumPositionMs = 9_699L, videoPositionMs = 10_000L))
        // 自定义阈值
        assertTrue(VideoSync.needsCorrection(2_000L, 0L, thresholdMs = 1_000L))
        assertFalse(VideoSync.needsCorrection(2_000L, 0L, thresholdMs = 2_000L))
    }

    @Test
    fun videoSync_targetPositionNeverNegative() {
        assertEquals(0L, VideoSync.targetPosition(-1L))
        assertEquals(12_345L, VideoSync.targetPosition(12_345L))
    }
}
