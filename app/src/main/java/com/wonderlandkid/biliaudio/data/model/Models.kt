package com.wonderlandkid.biliaudio.data.model

import androidx.compose.runtime.Immutable

/**
 * 一张“B 站专辑” = 一个 B 站视频（或视频的一个分 P）。
 * 专辑本身是一首歌的合集，靠 [Track] 的时间点切分成多首。
 */
@Immutable
data class Album(
    val id: Long = 0L,
    /** 来源标识（当前只有 bilibili；为后续多来源预留稳定键） */
    val source: String = "bilibili",
    val bvid: String,
    /** 当前专辑使用的视频分 P 对应的 cid（播放/获取流需要） */
    val cid: Long,
    val page: Int = 1,
    val pageCount: Int = 1,
    val title: String,
    val artist: String,
    /** B 站原始封面 URL（远程兜底） */
    val coverUrl: String,
    /** 用户自定义封面（本机文件绝对路径）；null 时使用 [coverUrl] */
    val coverLocalPath: String? = null,
    /** 视频总时长（即整张专辑的音频总时长） */
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
)

/** 专辑中的一首歌：在专辑音频流中的 [startMs, endMs) 区间。 */
@Immutable
data class Track(
    val id: Long = 0L,
    /** 跨设备稳定 ID（UUID；归档/未来播放历史/收藏用，不依赖自增 id） */
    val uid: String = "",
    val albumId: Long,
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

@Immutable
data class AlbumWithTracks(
    val album: Album,
    val tracks: List<Track>,
)

/** 音乐库列表用的“歌曲 + 所属专辑”组合。 */
@Immutable
data class TrackWithAlbum(
    val track: Track,
    val album: Album,
)

/** 导入方式：整张一个视频再手动分段，或按 B 站分 P 自动生成曲目。 */
enum class ImportMode { SINGLE_VIDEO, MULTI_PAGE }

/** B 站视频分 P 信息。 */
data class BiliPage(
    val page: Int,
    val cid: Long,
    val title: String,
    val durationMs: Long,
)

/** /x/web-interface/view 的解析结果。 */
data class BiliVideoInfo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val coverUrl: String,
    val durationMs: Long,
    val ownerName: String,
    val pages: List<BiliPage>,
)

/** B 站搜索出来的视频条目（用于搜索导入）。 */
data class BiliVideoSearchItem(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationText: String,
    val author: String,
    val playCount: Int,
    val pageCount: Int,
)

data class UserProfile(
    val mid: Long,
    val name: String,
    val avatarUrl: String,
)

enum class AudioQualityPreference(val label: String) {
    AUTO("自动（最高）"),
    HIGH("192K"),
    MEDIUM("132K"),
    LOW("64K"),
}

/** 已解析出的可播放流地址。 */
data class ResolvedStreams(
    val audioUrl: String,
    val audioQualityId: Int,
    val audioQualityLabel: String,
    val videoUrl: String?,
    val videoHeight: Int?,
    val expiresAtEpochMs: Long,
    /** 拉流必须的请求头（User-Agent / Referer / Cookie 等）。 */
    val headers: Map<String, String> = emptyMap(),
)

/** 编辑分割点时输入的单个片段。 */
data class SegmentSpec(
    /** 距离专辑开头的毫秒数（>= 0） */
    val startMs: Long,
    val title: String,
)

/**
 * 一次专辑导入请求。
 * [segments] 非空时使用自定义分割点（[mode] 忽略）；为空时由实现按 [mode] 自动生成。
 */
data class AlbumImportRequest(
    /** 支持 BV 号、av 号或完整 B 站链接 */
    val bvidOrUrl: String,
    val albumTitle: String? = null,
    val artist: String? = null,
    val mode: ImportMode = ImportMode.SINGLE_VIDEO,
    /** 要导入的分 P 序号（从 1 开始；归档/链接指定页时使用） */
    val page: Int = 1,
    val segments: List<SegmentSpec> = emptyList(),
)

sealed interface ImportResult {
    data class Success(
        val albumId: Long,
        val albumTitle: String,
        val trackCount: Int,
    ) : ImportResult

    data class Failure(
        /** 原始输入（BV 号或专辑名），便于批量导入时定位 */
        val input: String,
        val message: String,
    ) : ImportResult
}

data class BatchImportReport(
    val results: List<ImportResult>,
) {
    val successCount: Int get() = results.count { it is ImportResult.Success }
    val failureCount: Int get() = results.count { it is ImportResult.Failure }
}

/** B 站相关接口错误。 */
class BiliApiException(
    message: String,
    val code: Int? = null,
) : Exception(message)

/** 用户偏好设置。 */
data class AppSettings(
    val audioQuality: AudioQualityPreference = AudioQualityPreference.AUTO,
    /** null = 跟随系统 */
    val darkTheme: Boolean? = null,
    /** 维测日志开关（关闭后 Diag 不再记录/打印） */
    val diagnosticLogsEnabled: Boolean = false,
)

/**
 * 在线封面候选项（当前来源：网易云音乐专辑）。
 * 只用于 UI 展示与下载，不落库。
 */
data class CoverCandidate(
    val source: String = "netease",
    val id: String,
    val title: String,
    val artist: String,
    /** 列表缩略图（带 param=300y300） */
    val thumbnailUrl: String,
    /** 原图（用于下载保存） */
    val fullUrl: String,
) {
    val displaySubtitle: String get() = if (artist.isBlank()) title else "$artist · $title"
}
