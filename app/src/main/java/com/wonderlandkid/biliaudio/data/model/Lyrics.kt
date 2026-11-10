package com.wonderlandkid.biliaudio.data.model

import androidx.compose.runtime.Immutable

/**
 * 歌词模型（移植自 SPICaMusic_Android 的多来源歌词方案）。
 *
 * 数据流：
 * 1. 在线歌词按「歌名（+歌手）」调用歌词 API，返回多来源候选 [OnlineLyricCandidate]；
 * 2. 用户选择/自动匹配的原文按曲目稳定 uid 存成 [StoredLyrics]（Room，离线可用）；
 * 3. 展示前由 `data/lyrics/LyricsParser` 解析成 [ParsedLyrics]（[LyricItem] 行）。
 *
 * 注意：歌词是设备本地缓存，不进入音乐库归档/用户数据备份。
 */

/** 歌词来源类型。 */
enum class LyricSourceType { ONLINE, LOCAL_FILE, NONE }

/**
 * 在线歌词候选（来自歌词 API，多来源聚合）。
 *
 * @param durationSec 歌词源歌曲时长（秒），用于和曲目时长比对挑最匹配的候选
 */
@Immutable
data class OnlineLyricCandidate(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String = "",
    val albumArt: String = "",
    val durationSec: Int = 0,
    val rawLyrics: String,
)

/**
 * 已保存到本机的歌词快照（按曲目稳定 uid 存储）。
 *
 * @param delayMs 歌词时间偏移；正数 = 歌词提前显示
 * @param isManual 用户手动选择（手动选择不会被自动匹配覆盖）
 * @param suppressed 已关闭歌词（保留快照与偏移，便于恢复）
 */
@Immutable
data class StoredLyrics(
    val trackUid: String,
    val rawLyrics: String,
    val sourceType: LyricSourceType,
    val sourceName: String,
    val delayMs: Long = 0L,
    val isManual: Boolean = false,
    val suppressed: Boolean = false,
    val updatedAtMs: Long = 0L,
)

/**
 * 解析后的歌词。
 *
 * @param isSynced false = 歌词无时间戳（静态展示，不随进度滚动/高亮）
 * @param parseError 解析失败提示（有可用行时一般为 null）
 */
@Immutable
data class ParsedLyrics(
    val lines: List<LyricItem>,
    val isSynced: Boolean,
    val parseError: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
