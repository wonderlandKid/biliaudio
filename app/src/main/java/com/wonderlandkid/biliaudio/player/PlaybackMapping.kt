package com.wonderlandkid.biliaudio.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum

/**
 * 一首曲目在 ExoPlayer 播放队列中的“纯逻辑规格”。
 *
 * 与 Media3 的 [MediaItem] 一一对应，但**不依赖 Android 运行时**，可在 JVM 单元测试中验证
 * 队列构建 / 分曲 → 媒体项映射 / 进度换算是否正确。
 */
internal data class TrackPlaySpec(
    val albumId: Long,
    val trackId: Long,
    val mediaId: String,
    val audioUrl: String,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val coverUrl: String,
    /** 在专辑音频流中的裁剪起点（毫秒） */
    val clipStartMs: Long,
    /** 在专辑音频流中的裁剪终点（毫秒，开区间） */
    val clipEndMs: Long,
) {
    /** 当前曲目时长 = 裁剪区间长度。 */
    val clipDurationMs: Long get() = clipEndMs - clipStartMs
}

/**
 * 播放层纯逻辑映射：队列构建、MediaItem 规格、曲内/专辑绝对进度换算。
 *
 * 设计依据 docs/DESIGN.md「播放架构（关键决策）」：
 * - 队列元素 = 一首歌 → MediaItem(audioUrl) + ClippingConfiguration(track.startMs, track.endMs)；
 * - 曲内进度 = player.currentPosition（裁剪后从 0 开始）；
 * - 专辑绝对进度 = track.startMs + 曲内进度。
 */
internal object PlaybackMapping {

    /** MediaItem.mediaId 前缀，便于在日志 / 通知排障时识别。 */
    const val MEDIA_ID_PREFIX = "biliaudio:track:"

    /** 单张专辑 → 队列（按 [Track.index] 排序，保证播放顺序与专辑内顺序一致）。 */
    fun buildQueueEntries(album: Album, tracks: List<Track>): List<QueueEntry> =
        tracks.sortedBy { it.index }.map { track -> entryOf(album, track) }

    /** 歌曲 + 所属专辑列表（音乐库 / 搜索结果）→ 队列（保持传入顺序）。 */
    fun buildQueueEntries(songs: List<TrackWithAlbum>): List<QueueEntry> =
        songs.map { song -> entryOf(song.album, song.track) }

    /** 队列 + 专辑音频流地址 → MediaItem 规格列表（顺序、数量与队列严格一致）。 */
    fun buildPlaySpecs(
        entries: List<QueueEntry>,
        audioUrlByAlbumId: Map<Long, String>,
    ): List<TrackPlaySpec> = entries.map { entry ->
        val audioUrl = audioUrlByAlbumId[entry.albumId]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("音频流获取失败，请重试")
        val (clipStart, clipEnd) = sanitizeClipRange(entry.track.startMs, entry.track.endMs)
        TrackPlaySpec(
            albumId = entry.albumId,
            trackId = entry.track.id,
            mediaId = MEDIA_ID_PREFIX + entry.track.id,
            audioUrl = audioUrl,
            title = entry.track.title,
            artist = entry.artist,
            albumTitle = entry.albumTitle,
            coverUrl = entry.coverUrl,
            clipStartMs = clipStart,
            clipEndMs = clipEnd,
        )
    }

    /** 规格 → Media3 MediaItem（含裁剪配置与通知栏元数据）。 */
    fun toMediaItem(spec: TrackPlaySpec, artworkData: ByteArray?): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(spec.title)
            .setArtist(spec.artist)
            .setAlbumTitle(spec.albumTitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        when {
            // 优先用预取的封面字节（B 站图床需要 Referer，预取可绕开通知栏加载器的请求头问题）
            artworkData != null && artworkData.isNotEmpty() ->
                metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            spec.coverUrl.isNotBlank() -> metadataBuilder.setArtworkUri(Uri.parse(spec.coverUrl))
        }
        return MediaItem.Builder()
            .setMediaId(spec.mediaId)
            .setUri(spec.audioUrl)
            .setMediaMetadata(metadataBuilder.build())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(spec.clipStartMs)
                    .setEndPositionMs(spec.clipEndMs)
                    // B 站 fMP4 实测可 seek；这里仍允许不可 seek 的流裁剪到非 0 起点，避免直接创建失败
                    .setAllowUnseekableMedia(true)
                    .build(),
            )
            .build()
    }

    /**
     * 规范化裁剪区间：
     * - 起点不小于 0；
     * - 终点至少比起点大 1ms（ClippingMediaSource 不允许空区间）。
     */
    fun sanitizeClipRange(startMs: Long, endMs: Long): Pair<Long, Long> {
        val start = startMs.coerceAtLeast(0L)
        val end = endMs.coerceAtLeast(start + 1L)
        return start to end
    }

    /** 曲内进度 → 专辑音频流的绝对进度。 */
    fun trackPositionToAlbumPosition(track: Track, positionMs: Long): Long =
        (track.startMs + positionMs.coerceAtLeast(0L))
            .coerceAtMost(maxOf(track.startMs, track.endMs))

    /** 专辑绝对进度 → 曲内进度（越界时收敛到 [0, 曲长]）。 */
    fun albumPositionToTrackPosition(track: Track, albumPositionMs: Long): Long =
        (albumPositionMs - track.startMs).coerceIn(0L, track.durationMs)

    /**
     * 在同专辑队列中查找包含 [albumPositionMs] 的曲目下标；找不到返回 -1。
     *
     * 用于歌词页 / 视频页按“专辑绝对进度”定位：目标可能落在别的曲目上。
     */
    fun findQueueIndexAtAlbumPosition(
        entries: List<QueueEntry>,
        albumId: Long,
        albumPositionMs: Long,
    ): Int = entries.indexOfFirst { entry ->
        entry.albumId == albumId &&
            albumPositionMs >= entry.track.startMs &&
            albumPositionMs < entry.track.endMs
    }

    /** 把专辑绝对进度收敛到 [0, albumDurationMs - 1]，避免落在最后一首的 endMs 边界之外。 */
    fun clampAlbumPosition(albumPositionMs: Long, albumDurationMs: Long): Long {
        val duration = albumDurationMs.coerceAtLeast(1L)
        return albumPositionMs.coerceIn(0L, duration - 1L)
    }

    private fun entryOf(album: Album, track: Track): QueueEntry = QueueEntry(
        track = track,
        albumId = album.id,
        albumTitle = album.title,
        artist = album.artist,
        coverUrl = album.coverUrl,
        albumDurationMs = album.durationMs,
    )
}
