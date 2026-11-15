package com.wonderlandkid.biliaudio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** 归档格式标识与版本（v3：分域 bundle，不兼容 v1/v2 整库单文件）。 */
const val LIBRARY_ARCHIVE_FORMAT = "biliaudio-library"
const val LIBRARY_ARCHIVE_VERSION = 3
const val LIBRARY_ARCHIVE_MIN_READER_VERSION = 3

/** bundle 容器清单。 */
const val ARCHIVE_MANIFEST_FORMAT = "biliaudio-archive"
const val ARCHIVE_MANIFEST_VERSION = 1

/** 设置域 / 歌单域文件格式。 */
const val SETTINGS_ARCHIVE_FORMAT = "biliaudio-settings"
const val SETTINGS_ARCHIVE_VERSION = 1
const val PLAYLISTS_ARCHIVE_FORMAT = "biliaudio-playlists"
const val PLAYLISTS_ARCHIVE_VERSION = 1

/** 单分享文件用途：单专辑 / 仅分割点。 */
@Serializable
enum class ArchiveKind {
    @SerialName("library") LIBRARY,
    @SerialName("album") ALBUM,
    @SerialName("segments") SEGMENTS,
}

/** library 域文件：只放专辑 + 分曲，不放 settings/playlists。 */
@Serializable
data class LibraryArchive(
    val format: String = LIBRARY_ARCHIVE_FORMAT,
    val version: Int = LIBRARY_ARCHIVE_VERSION,
    val minReaderVersion: Int = LIBRARY_ARCHIVE_MIN_READER_VERSION,
    val kind: ArchiveKind = ArchiveKind.LIBRARY,
    val archiveId: String? = null,
    val exportedAt: String? = null,
    val app: ArchiveApp? = null,
    val albums: List<ArchiveAlbum> = emptyList(),
) {
    companion object {
        fun create(
            kind: ArchiveKind = ArchiveKind.LIBRARY,
            albums: List<ArchiveAlbum>,
            exportedAt: String? = null,
            appVersion: String? = null,
        ): LibraryArchive = LibraryArchive(
            format = LIBRARY_ARCHIVE_FORMAT,
            version = LIBRARY_ARCHIVE_VERSION,
            minReaderVersion = LIBRARY_ARCHIVE_MIN_READER_VERSION,
            kind = kind,
            archiveId = UUID.randomUUID().toString(),
            exportedAt = exportedAt,
            app = ArchiveApp(version = appVersion),
            albums = albums,
        )
    }
}

@Serializable
data class ArchiveApp(
    val name: String = "BiliAudio",
    val version: String? = null,
)

/** 封面：bundle 中给 hash 引用；单专辑分享可额外内嵌 dataBase64。 */
@Serializable
data class ArchiveCover(
    /** 原图 SHA-256（小写 hex；bundle 中同时是 covers/<hash> 文件名） */
    val hash: String,
    val mime: String,
    val byteLength: Int,
    /** 单文件分享用；bundle 中禁止写 */
    val dataBase64: String? = null,
)

@Serializable
data class ArchiveAlbum(
    /** 稳定键：source:bvid#page；导出时写入，导入时校验/推导。 */
    val albumKey: String? = null,
    val source: String = "bilibili",
    val bvid: String? = null,
    /** 手写/快捷导入可给视频链接（bvid 缺失时使用） */
    val url: String? = null,
    val page: Int = 1,
    val pageCount: Int? = null,
    /** 仅提示，不信任文件值；导入时重新解析流。 */
    val cidHint: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val updatedAt: Long? = null,
    val createdAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val playCount: Int? = null,
    val cover: ArchiveCover? = null,
    /**
     * 分曲：
     * - null（字段缺失）= 更新已有专辑时保留原分曲；
     * - [] = 清空分曲（回到未分割）；
     * - 非空 = 替换。
     */
    val segments: List<ArchiveSegment>? = null,
)

@Serializable
data class ArchiveSegment(
    val id: String? = null,
    val startMs: Long,
    val endMs: Long? = null,
    val title: String? = null,
    /** 多 P 专辑：曲目所属分 P cid / 序号 / 源内起点；旧归档为 null。 */
    val sourceCid: Long? = null,
    val sourcePage: Int? = null,
    val sourceOffsetMs: Long? = null,
)

// ---------------------------------------------------------------------------
// bundle：manifest + 分域文件
// ---------------------------------------------------------------------------

@Serializable
data class ArchiveManifest(
    val format: String = ARCHIVE_MANIFEST_FORMAT,
    val version: Int = ARCHIVE_MANIFEST_VERSION,
    val kind: String = "library",
    val createdAt: String? = null,
    val app: ArchiveApp? = null,
    val files: List<ArchiveManifestFile> = emptyList(),
)

@Serializable
data class ArchiveManifestFile(
    val path: String,
    /** library | settings | playlists | cover */
    val type: String,
    val version: Int? = null,
    val optional: Boolean = false,
    val sha256: String? = null,
    val bytes: Long? = null,
)

/** settings 域（只导出可移植白名单；本地设置不依赖它）。 */
@Serializable
data class ArchiveSettingsDocument(
    val format: String = SETTINGS_ARCHIVE_FORMAT,
    val version: Int = SETTINGS_ARCHIVE_VERSION,
    val minReaderVersion: Int = SETTINGS_ARCHIVE_VERSION,
    val audioQuality: String? = null,
    val darkTheme: Boolean? = null,
    val diagnosticLogsEnabled: Boolean? = null,
)

/** 歌单域（当前只定义 schema；功能后续接入）。 */
@Serializable
data class PlaylistsArchive(
    val format: String = PLAYLISTS_ARCHIVE_FORMAT,
    val version: Int = PLAYLISTS_ARCHIVE_VERSION,
    val minReaderVersion: Int = PLAYLISTS_ARCHIVE_VERSION,
    val playlists: List<ArchivePlaylist> = emptyList(),
)

@Serializable
data class ArchivePlaylist(
    val id: String,
    val name: String,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val items: List<ArchivePlaylistItem> = emptyList(),
)

@Serializable
data class ArchivePlaylistItem(
    /** 专辑稳定键：source:bvid#page */
    val albumKey: String,
    /** 曲目在专辑时间轴上的起点；与 albumKey 一起作为定位主键（跨设备重导入专辑后仍有效） */
    val startMs: Long,
    /** 曲目 UUID（可选辅助；专辑被重新导入后可能变化，不能作为主键） */
    val trackId: String? = null,
    /** 仅展示快照，不用于定位 */
    val title: String? = null,
)

// ---------------------------------------------------------------------------
// 导入/预览/报告（UI 复用）
// ---------------------------------------------------------------------------

enum class ArchiveImportMode { MERGE, SEGMENTS_ONLY, REPLACE }

enum class ArchiveItemAction { CREATED, UPDATED, SEGMENTS_UPDATED, SKIPPED, FAILED }

data class ArchiveItemResult(
    val albumKey: String,
    val title: String,
    val action: ArchiveItemAction,
    val message: String? = null,
)

data class ArchiveParseError(
    val source: String,
    val message: String,
)

data class ArchivePreview(
    val recognizedAs: String,
    val kind: ArchiveKind,
    val albumCount: Int,
    val trackCount: Int,
    val hasSettings: Boolean,
    val items: List<ArchivePreviewItem>,
    val parseErrors: List<ArchiveParseError> = emptyList(),
)

data class ArchivePreviewItem(
    val albumKey: String,
    val title: String,
    val segmentCount: Int,
    val exists: Boolean,
    val plannedAction: ArchiveItemAction,
    val note: String? = null,
)

data class ArchiveImportReport(
    val mode: ArchiveImportMode,
    val items: List<ArchiveItemResult>,
    val parseErrors: List<ArchiveParseError> = emptyList(),
    val backupFilePath: String? = null,
) {
    val createdCount: Int get() = items.count { it.action == ArchiveItemAction.CREATED }
    val updatedCount: Int get() = items.count { it.action == ArchiveItemAction.UPDATED }
    val segmentsUpdatedCount: Int get() = items.count { it.action == ArchiveItemAction.SEGMENTS_UPDATED }
    val skippedCount: Int get() = items.count { it.action == ArchiveItemAction.SKIPPED }
    val failedCount: Int get() = items.count { it.action == ArchiveItemAction.FAILED } + parseErrors.size
    val successCount: Int get() = createdCount + updatedCount + segmentsUpdatedCount
}

/** 归档解析/校验失败（message 可直接展示给用户）。 */
class ArchiveFormatException(message: String) : Exception(message)

/** bundle 中的一个文件（目录/zip 共用）。 */
data class ArchiveTreeEntry(
    val relativePath: String,
    val bytes: ByteArray,
    val mimeType: String = "application/octet-stream",
)
