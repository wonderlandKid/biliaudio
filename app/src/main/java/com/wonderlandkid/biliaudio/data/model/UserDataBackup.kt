package com.wonderlandkid.biliaudio.data.model

import kotlinx.serialization.Serializable

/**
 * 用户数据备份（歌单 + 听歌统计），与音乐库归档**完全分离**。
 *
 * 只保存用户产生的数据，不包含专辑/分曲/封面/缓存；恢复时按
 * `(source,bvid,page) + startMs` 找到当前音乐库里的歌曲，所以不依赖本机 trackUid。
 */
@Serializable
data class UserDataBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val playlists: List<BackupPlaylist> = emptyList(),
    val stats: List<BackupStat> = emptyList(),
) {
    companion object {
        const val FORMAT = "biliaudio-user-data"
        const val VERSION = 1
    }
}

@Serializable
data class BackupPlaylist(
    val name: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val entries: List<BackupPlaylistEntry> = emptyList(),
)

@Serializable
data class BackupPlaylistEntry(
    /** 专辑稳定键 */
    val source: String = "bilibili",
    val bvid: String,
    val page: Int = 1,
    /** 曲目在专辑音频流中的起点（毫秒）；恢复时用于定位分曲，比 trackUid 跨设备稳定。 */
    val startMs: Long,
    /** 仅用于诊断/展示，不参与查找 */
    val trackTitle: String? = null,
)

@Serializable
data class BackupStat(
    val source: String = "bilibili",
    val bvid: String,
    val page: Int = 1,
    val startMs: Long = 0L,
    /** 未分割专辑的整张虚拟曲目 */
    val wholeAlbum: Boolean = false,
    val playCount: Int = 0,
    val listenedMs: Long = 0L,
    val lastPlayedAt: Long? = null,
)

/** 用户数据导入结果。 */
data class UserDataImportReport(
    val playlistsCreated: Int,
    val playlistsMerged: Int,
    val entriesRestored: Int,
    val statsRestored: Int,
    val skipped: Int,
    val warnings: List<String> = emptyList(),
)
