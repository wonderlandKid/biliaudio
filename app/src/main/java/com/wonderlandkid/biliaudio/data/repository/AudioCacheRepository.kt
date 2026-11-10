package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track

/**
 * 专辑音频离线缓存（设备本地，不进入归档/备份）。
 *
 * 缓存粒度 = 一个「分 P」的完整 DASH 音频流（单 P 专辑即整张专辑）：
 * - 分曲只是同一个音频流的时间裁剪，所以一个分 P 缓存一次，离线时该 P 的所有分曲都能播放；
 * - 多 P 专辑按 [Track.sourceCid] 逐 P 缓存，整张专辑离线可用；
 * - 只缓存音频，不缓存歌词视频；文件放在应用私有目录，随专辑删除/用户手动删除而清理。
 */
data class CachedAudio(
    val albumId: Long,
    val bvid: String,
    val cid: Long,
    /** 分 P 序号（1-based）；0 = 旧版本缓存文件没有记录 */
    val page: Int,
    val filePath: String,
    val audioQualityId: Int,
    val audioQualityLabel: String,
    val bytes: Long,
    val cachedAtMs: Long,
)

/** 缓存管理页 / 专辑详情用的汇总：一张专辑已缓存的音频分 P。 */
data class CachedAlbumAudio(
    val albumId: Long,
    val bvid: String,
    /** 已缓存分 P 的 cid（去重；按缓存时间从早到晚） */
    val cids: List<Long> = emptyList(),
    val bytes: Long = 0L,
    val audioQualityLabel: String? = null,
    val updatedAtMs: Long = 0L,
) {
    /** 已缓存分 P 数。 */
    val cachedPageCount: Int get() = cids.size

    val hasAny: Boolean get() = cids.isNotEmpty()
}

interface AudioCacheRepository {
    /**
     * 同步查某个分 P 的缓存（只读文件元数据，UI 可安全调用）。
     *
     * 播放引擎用 `album.copy(cid = track.sourceCid, page = track.sourcePage)` 查询多 P 曲目，
     * 因此这里的 key 必须是 bvid + cid。
     */
    fun getCached(album: Album): CachedAudio?

    /**
     * 专辑详情缓存状态：按 [tracks] 的 sourceCid（0 = 专辑 cid）汇总该专辑已缓存的分 P。
     * 返回的 [CachedAlbumAudio.cids] 只包含已缓存的目标分 P，用于展示「已缓存 x/y 分P」。
     */
    fun getCachedSummary(album: Album, tracks: List<Track>): CachedAlbumAudio

    /**
     * 缓存整张专辑的音频：对 {track.sourceCid>0 ? sourceCid : album.cid} 去重后逐 P 解析并下载，
     * 已存在的分 P 直接跳过（补缓存语义）；单个分 P 失败会清理 .part 并抛错（错误信息含已下载进度）。
     *
     * 下载使用解析流时携带的 UA/Referer/Cookie 请求头；进度按所有分 P 已下载字节汇总。
     *
     * @param onProgress downloadedBytes / totalBytes（当前分 P 总长未知时为 null）
     * @return 该专辑全部分 P 的缓存条目（含原本已存在的）
     */
    suspend fun cache(
        album: Album,
        tracks: List<Track>,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): List<CachedAudio>

    /** 缓存管理页：枚举当前设备上的全部音频缓存，按专辑聚合。 */
    suspend fun listCached(): List<CachedAlbumAudio>

    /** 删除该专辑全部缓存文件（按元数据 albumId 匹配，兼容历史遗留分 P）。 */
    suspend fun remove(album: Album)

    /** 缓存管理页：删除一条汇总对应的专辑全部已缓存分 P。 */
    suspend fun removeCached(item: CachedAlbumAudio)

    /** 清空全部音频缓存（设置页/维护用）。 */
    suspend fun clearAll()
}
