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

    /**
     * 下载并缓存整张专辑音频；已存在则直接返回。
     * 下载使用解析流时携带的 UA/Referer/Cookie 请求头。
     * @param onProgress downloadedBytes / totalBytes（total 未知时为 null）
     */
    suspend fun cache(
        album: Album,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): CachedAudio

    /** 删除该专辑缓存（文件 + 元数据）。 */
    suspend fun remove(album: Album)

    /** 清空全部音频缓存（设置页/维护用）。 */
    suspend fun clearAll()
}
