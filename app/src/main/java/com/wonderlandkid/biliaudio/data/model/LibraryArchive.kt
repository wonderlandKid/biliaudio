package com.wonderlandkid.biliaudio.data.model

import kotlinx.serialization.Serializable

/** 归档格式标识与版本。 */
const val LIBRARY_ARCHIVE_FORMAT = "biliaudio-library"
const val LIBRARY_ARCHIVE_VERSION = 1

/**
 * 音乐库归档文件（v1）。
 *
 * 一个文件 = 可移植的音乐库备份 / 单张专辑 / 仅分割点配置，见 docs/LIBRARY_ARCHIVE.md。
 * 文件内不包含音频，只有专辑元信息与分曲时间点。
 */
@Serializable
data class LibraryArchive(
    val format: String,
    val version: Int,
    val exportedAt: String? = null,
    val app: ArchiveApp? = null,
    val settings: ArchiveSettings? = null,
    val albums: List<ArchiveAlbum> = emptyList(),
) {
    companion object {
        fun create(
            albums: List<ArchiveAlbum>,
            settings: ArchiveSettings? = null,
            exportedAt: String? = null,
            appVersion: String? = null,
        ): LibraryArchive = LibraryArchive(
            format = LIBRARY_ARCHIVE_FORMAT,
            version = LIBRARY_ARCHIVE_VERSION,
            exportedAt = exportedAt,
            app = ArchiveApp(version = appVersion),
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
    /** AudioQualityPreference.name */
    val audioQuality: String? = null,
    val darkTheme: Boolean? = null,
)

@Serializable
data class ArchiveAlbum(
    val bvid: String? = null,
    /** 也可以给视频链接（bvid 缺失时使用） */
    val url: String? = null,
    val page: Int = 1,
    val cid: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val coverUrl: String? = null,
    /** 内嵌自定义封面（JPEG base64；用于备份/分享后恢复本地封面，可选） */
    val coverBase64: String? = null,
    val durationMs: Long? = null,
    val createdAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val playCount: Int? = null,
    val segments: List<ArchiveSegment> = emptyList(),
)

/**
 * 分曲。
 * 导出时固定写 `startMs` + `title`；导入时也接受手写友好格式 `start` + `name`。
 */
@Serializable
data class ArchiveSegment(
    val startMs: Long? = null,
    /** 手写友好时间：秒数 / mm:ss / mm:ss.SSS / hh:mm:ss */
    val start: String? = null,
    val title: String? = null,
    val name: String? = null,
)

/** 导入模式。 */
enum class ArchiveImportMode {
    /** 合并：按 bvid+page 匹配，存在则更新，不存在则新建。 */
    MERGE,

    /** 仅更新已存在专辑的分曲，不新建、不改元信息。 */
    SEGMENTS_ONLY,

    /** 覆盖全库：先自动备份当前库，清空后导入。 */
    REPLACE,
}

enum class ArchiveItemAction { CREATED, UPDATED, SEGMENTS_UPDATED, SKIPPED, FAILED }

data class ArchiveItemResult(
    val bvid: String,
    val page: Int,
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
    val albumCount: Int,
    val trackCount: Int,
    val hasSettings: Boolean,
    val items: List<ArchivePreviewItem>,
    val parseErrors: List<ArchiveParseError> = emptyList(),
)

data class ArchivePreviewItem(
    val bvid: String,
    val page: Int,
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
 * 目录形态归档中的单个文件（v0.6.0）：
 * - relativePath 形如 `顶层/艺术家/专辑/album.json`，使用 `/` 分隔；
 * - 二进制内容放 [bytes]（封面等），JSON 文本按 UTF-8 写入。
 */
data class ArchiveTreeEntry(
    val relativePath: String,
    val bytes: ByteArray,
    val mimeType: String = "application/octet-stream",
)
