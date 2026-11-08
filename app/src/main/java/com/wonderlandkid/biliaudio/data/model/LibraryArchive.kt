package com.wonderlandkid.biliaudio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** 归档格式标识与版本（v2：新设计，不兼容 v1）。 */
const val LIBRARY_ARCHIVE_FORMAT = "biliaudio-library"
const val LIBRARY_ARCHIVE_VERSION = 2
const val LIBRARY_ARCHIVE_MIN_READER_VERSION = 2

/**
 * v2 归档能力标记：读取方不认识的 feature 一律拒绝，避免静默丢数据。
 * - stable-album-key / stable-track-id：albumKey + segment.id 稳定
 * - cover-sha256：封面带 SHA-256 校验
 * - explicit-end-ms：分曲显式 endMs
 */
val LIBRARY_ARCHIVE_FEATURES: List<String> = listOf(
    "stable-album-key",
    "stable-track-id",
    "cover-sha256",
    "explicit-end-ms",
)

/** 归档用途：整库 / 单专辑 / 仅分割点 / 目录索引。 */
@Serializable
enum class ArchiveKind {
    @SerialName("library") LIBRARY,
    @SerialName("album") ALBUM,
    @SerialName("segments") SEGMENTS,
    @SerialName("index") INDEX,
}

/**
 * 音乐库归档（v2）。
 *
 * 设计见 docs/ARCHIVE_SCHEMA_V2.md：
 * - `version` + `minReaderVersion`：旧版拒绝高版本；
 * - `kind`：区分整库/单专辑/分割点/目录索引（索引禁止走“覆盖全库”）；
 * - `features`：未知数据特性直接拒绝；
 * - `albumKey` = source:bvid#page，`segments[].id` 跨设备稳定。
 */
@Serializable
data class LibraryArchive(
    val format: String = LIBRARY_ARCHIVE_FORMAT,
    val version: Int = LIBRARY_ARCHIVE_VERSION,
    val minReaderVersion: Int = LIBRARY_ARCHIVE_MIN_READER_VERSION,
    val kind: ArchiveKind = ArchiveKind.LIBRARY,
    val archiveId: String? = null,
    val exportedAt: String? = null,
    val app: ArchiveApp? = null,
    val features: List<String> = emptyList(),
    val settings: ArchiveSettings? = null,
    val albums: List<ArchiveAlbum> = emptyList(),
) {
    companion object {
        fun create(
            kind: ArchiveKind,
            albums: List<ArchiveAlbum>,
            settings: ArchiveSettings? = null,
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
            features = LIBRARY_ARCHIVE_FEATURES,
            settings = settings,
            albums = albums,
        )
    }
}

@Serializable
data class ArchiveApp(
    val name: String = "BiliAudio",
    val version: String? = null,
)

@Serializable
data class ArchiveSettings(
    /** 设置对象自版本；新增设置时改这里，不污染归档主版本。 */
    val version: Int = 1,
    /** AudioQualityPreference.name */
    val audioQuality: String? = null,
    val darkTheme: Boolean? = null,
    val diagnosticLogsEnabled: Boolean? = null,
)

@Serializable
data class ArchiveCover(
    /** image/jpeg、image/png、image/webp */
    val mime: String,
    /** 原图字节的 SHA-256（小写十六进制） */
    val sha256: String,
    /** 原图字节数（≤2MB） */
    val byteLength: Int,
    /** base64（无换行）；目录形态后续可改为独立文件，这里先保持单文件可分享 */
    val dataBase64: String,
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
    /** 用户数据；更新已有专辑时用于合并/冲突判断 */
    val updatedAt: Long? = null,
    val createdAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val playCount: Int? = null,
    val cover: ArchiveCover? = null,
    /**
     * 分曲：
     * - `null`（字段缺失） = 更新已有专辑时保留原分曲；
     * - `[]` = 清空分曲（回到未分割）；
     * - 非空 = 替换分曲。
     */
    val segments: List<ArchiveSegment>? = null,
)

/** 分曲；`id` 为跨设备稳定 UUID，手写可省略（导入时生成）。 */
@Serializable
data class ArchiveSegment(
    val id: String? = null,
    val startMs: Long,
    val endMs: Long? = null,
    val title: String? = null,
)

/** 导入模式。 */
enum class ArchiveImportMode {
    /** 合并：按 albumKey 匹配，存在则更新，不存在则新建。 */
    MERGE,

    /** 仅更新已存在专辑的分曲，不新建、不改元信息。 */
    SEGMENTS_ONLY,

    /** 覆盖全库：先自动备份当前库，清空后导入。 */
    REPLACE,
}

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

/** 预览结果（不写入数据库）。 */
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

/**
 * 目录形态归档中的单个文件（v2）：
 * - relativePath 形如 `顶层/艺术家/专辑/album.json`，使用 `/` 分隔；
 * - 二进制内容放 [bytes]（封面等），JSON 文本按 UTF-8 写入。
 */
data class ArchiveTreeEntry(
    val relativePath: String,
    val bytes: ByteArray,
    val mimeType: String = "application/octet-stream",
)
