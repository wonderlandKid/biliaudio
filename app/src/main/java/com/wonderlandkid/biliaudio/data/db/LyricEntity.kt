package com.wonderlandkid.biliaudio.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.wonderlandkid.biliaudio.data.model.LyricSourceType
import com.wonderlandkid.biliaudio.data.model.StoredLyrics

/**
 * 歌词本机缓存（Room v6 新增表 `lyrics`）。
 *
 * 每个曲目（[TrackEntity.uid]）最多一条：存原始歌词文本 + 来源 + 偏移 + 手动/关闭标记。
 * 歌词属于设备本地缓存，不进入音乐库归档/用户数据备份，也没有外键
 * （曲目被删/重导入时残留行无副作用，重新导入后 uid 变化会自然失效）。
 */
@Entity(tableName = "lyrics")
data class LyricEntity(
    /** 曲目稳定 uid（[TrackEntity.uid]）。 */
    @PrimaryKey val trackUid: String,
    /** 原始歌词文本（未解析，LRC/YRC/AMLL-TTML/纯文本均可）。 */
    val rawLyrics: String,
    /** 来源类型，存 [LyricSourceType].name（ONLINE/LOCAL_FILE/NONE）。 */
    val sourceType: String,
    /** 来源名（在线候选「歌名 - 歌手」/ 本地文件名），仅用于 UI 展示。 */
    val sourceName: String,
    /** 歌词时间偏移（毫秒，正数 = 歌词提前）。 */
    @ColumnInfo(defaultValue = "0") val delayMs: Long = 0L,
    /** 用户手动选择（自动匹配不会覆盖手动选择）。 */
    @ColumnInfo(defaultValue = "0") val isManual: Boolean = false,
    /** 用户关闭了该曲目的歌词（保留快照与偏移，便于恢复）。 */
    @ColumnInfo(defaultValue = "0") val suppressed: Boolean = false,
    /** 最后更新时间（毫秒）。 */
    val updatedAtMs: Long,
)

/** Room 行 → 领域模型。 */
fun LyricEntity.toModel(): StoredLyrics = StoredLyrics(
    trackUid = trackUid,
    rawLyrics = rawLyrics,
    sourceType = LyricSourceType.entries.firstOrNull { it.name == sourceType }
        ?: LyricSourceType.NONE,
    sourceName = sourceName,
    delayMs = delayMs,
    isManual = isManual,
    suppressed = suppressed,
    updatedAtMs = updatedAtMs,
)

/** 领域模型 → Room 行。 */
fun StoredLyrics.toEntity(): LyricEntity = LyricEntity(
    trackUid = trackUid,
    rawLyrics = rawLyrics,
    sourceType = sourceType.name,
    sourceName = sourceName,
    delayMs = delayMs,
    isManual = isManual,
    suppressed = suppressed,
    updatedAtMs = updatedAtMs,
)
