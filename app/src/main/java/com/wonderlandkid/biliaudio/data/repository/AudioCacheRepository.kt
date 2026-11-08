package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.Album

/**
 * 专辑音频离线缓存（设备本地，不进入归档/备份）。
 *
 * 缓存粒度 = 一张专辑的完整 DASH 音频流：
 * - 分曲只是同一个音频流的时间裁剪，所以缓存一次即可离线播放全专辑；
 * - 只缓存音频，不缓存歌词视频（视频仍按需联网）；
 * - 文件放在应用私有目录，删除 App/专辑不会留下公开痕迹；随专辑删除/用户手动删除而清理。
 */
/** 缓存管理页用的汇总：一张专辑的音频和/或视频缓存。 */
data class CachedMediaSummary(
    val albumId: Long,
    val bvid: String,
    val cid: Long,
    val audioBytes: Long = 0L,
    val audioQualityLabel: String? = null,
    val videoBytes: Long = 0L,
    val videoHeight: Int? = null,
    val updatedAtMs: Long = 0L,
) {
    val totalBytes: Long get() = audioBytes + videoBytes
    val hasAudio: Boolean get() = audioBytes > 0L
    val hasVideo: Boolean get() = videoBytes > 0L
}

data class CachedVideo(
    val albumId: Long,
    val bvid: String,
    val cid: Long,
    val filePath: String,
    val videoHeight: Int?,
    val bytes: Long,
    val cachedAtMs: Long,
)

data class CachedAudio(
    val albumId: Long,
    val bvid: String,
    val cid: Long,
    val filePath: String,
    val audioQualityId: Int,
    val audioQualityLabel: String,
    val bytes: Long,
    val cachedAtMs: Long,
)

interface AudioCacheRepository {
    /** 同步查缓存（只读文件元数据，UI 可安全调用）。 */
    fun getCached(album: Album): CachedAudio?

    /** 同步查歌词视频缓存。 */
    fun getCachedVideo(album: Album): CachedVideo?

    /**
     * 下载并缓存整张专辑音频；已存在则直接返回。
     * 下载使用解析流时携带的 UA/Referer/Cookie 请求头。
     * @param onProgress downloadedBytes / totalBytes（total 未知时为 null）
     */
    suspend fun cache(
        album: Album,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): CachedAudio

    /** 下载并缓存整段歌词视频（文件较大，UI 层需先确认）。 */
    suspend fun cacheVideo(
        album: Album,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): CachedVideo

    /** 缓存管理页：枚举当前设备上的全部音/视频缓存。 */
    suspend fun listCached(): List<CachedMediaSummary>

    /** 删除该专辑缓存（文件 + 元数据）。 */
    suspend fun remove(album: Album)

    /** 缓存管理页：删除一条汇总对应的全部缓存（音频 + 视频）。 */
    suspend fun removeCached(item: CachedMediaSummary)

    /** 删除歌词视频缓存。 */
    suspend fun removeVideo(album: Album)

    /** 清空全部音频缓存（设置页/维护用）。 */
    suspend fun clearAll()
}
