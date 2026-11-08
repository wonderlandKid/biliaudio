package com.wonderlandkid.biliaudio.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track

/**
 * Room 实体（表 albums / tracks）与领域模型互转。
 * 字段语义与 [Album] / [Track] 一致，见 data/model/Models.kt。
 */

/** 专辑表：一张专辑 = 一个 B 站视频（MVP 单 P）。 */
@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["source", "bvid", "page"], unique = true),
        Index(value = ["lastPlayedAt"]),
    ],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "bilibili") val source: String = "bilibili",
    val bvid: String,
    /** 当前使用的分 P cid。 */
    val cid: Long,
    val page: Int,
    val pageCount: Int,
    val title: String,
    val artist: String,
    val coverUrl: String,
    /** 自定义封面本地文件绝对路径（旧库迁移新增列，可为空） */
    val coverLocalPath: String? = null,
    val durationMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPlayedAt: Long?,
    val playCount: Int,
)

/** 曲目表：专辑音频流中的 [startMs, endMs) 区间。 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["albumId"]), Index(value = ["uid"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "") val uid: String = "",
    val albumId: Long,
    /** 在专辑内的顺序，从 0 开始。 */
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

fun AlbumEntity.toModel(): Album = Album(
    id = id,
    source = source,
    bvid = bvid,
    cid = cid,
    page = page,
    pageCount = pageCount,
    title = title,
    artist = artist,
    coverUrl = coverUrl,
    coverLocalPath = coverLocalPath,
    durationMs = durationMs,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastPlayedAt = lastPlayedAt,
    playCount = playCount,
)

fun Album.toEntity(): AlbumEntity = AlbumEntity(
    id = id,
    source = source,
    bvid = bvid,
    cid = cid,
    page = page,
    pageCount = pageCount,
    title = title,
    artist = artist,
    coverUrl = coverUrl,
    coverLocalPath = coverLocalPath,
    durationMs = durationMs,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastPlayedAt = lastPlayedAt,
    playCount = playCount,
)

fun TrackEntity.toModel(): Track = Track(
    id = id,
    uid = uid,
    albumId = albumId,
    index = index,
    title = title,
    startMs = startMs,
    endMs = endMs,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    uid = uid.ifBlank { java.util.UUID.randomUUID().toString() },
    albumId = albumId,
    index = index,
    title = title,
    startMs = startMs,
    endMs = endMs,
)

// ---------------------------------------------------------------------------
// v4：用户歌单 + 听歌统计
// ---------------------------------------------------------------------------

/** 用户歌单。 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 歌单条目：用 [trackUid]（跨重导入稳定）而不是自增 trackId 引用歌曲，
 * 这样重新编辑分曲/重导入后歌单不会全部失效。
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "trackUid"], unique = true),
        Index(value = ["albumId"]),
    ],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val trackUid: String,
    val albumId: Long,
    val position: Int,
)

/** 专辑时长统计的聚合投影。 */
data class AlbumListeningRow(
    val albumId: Long,
    val listenedMs: Long,
)

/** 歌单列表投影（含曲目数）。 */
data class PlaylistSummaryRow(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
)

/**
 * 歌曲统计：按 trackUid 持久化，避免 tracks 重写时统计丢失。
 * albumId 外键级联：专辑删除时统计一起删除。
 */
@Entity(
    tableName = "track_stats",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["albumId"])],
)
data class TrackStatsEntity(
    @PrimaryKey val trackUid: String,
    val albumId: Long,
    val playCount: Int,
    val listenedMs: Long,
    val lastPlayedAt: Long?,
)
