package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.LyricSourceType
import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate
import com.wonderlandkid.biliaudio.data.model.StoredLyrics

/**
 * 歌词数据（在线搜索 + 本机缓存），移植自 SPICaMusic_Android 的歌词方案。
 *
 * - 在线歌词：按歌名/歌手调用歌词 API（参考项目同款接口），返回多来源候选；
 * - 本机缓存：按曲目稳定 uid（[com.wonderlandkid.biliaudio.data.model.Track.uid]）存 Room，
 *   离线可用；歌词属于设备缓存，不进入音乐库归档/用户数据备份；
 * - 原始文本解析（LRC/YRC/AMLL-TTML/纯文本）由 `data/lyrics/LyricsParser` 负责，本接口只存取原文。
 */
interface LyricsRepository {

    /**
     * 在线搜索歌词候选。
     *
     * 网络失败、接口异常或无结果时返回空列表（不抛异常，UI 显示「暂无歌词/重试」）。
     */
    suspend fun searchOnline(title: String, artist: String = ""): List<OnlineLyricCandidate>

    /** 读取该曲目的歌词快照；null = 尚未缓存/从未选择。 */
    suspend fun getStored(trackUid: String): StoredLyrics?

    /**
     * 保存歌词快照。
     *
     * 自动匹配结果（[isManual] = false）不会覆盖用户手动选择/关闭的歌词状态；
     * 手动选择会覆盖已有快照并清掉 [StoredLyrics.suppressed]。
     */
    suspend fun save(
        trackUid: String,
        rawLyrics: String,
        sourceType: LyricSourceType,
        sourceName: String,
        delayMs: Long = 0L,
        isManual: Boolean,
    ): StoredLyrics

    /** 更新歌词时间偏移（毫秒，正数 = 歌词提前）。曲目无快照时忽略。 */
    suspend fun updateDelay(trackUid: String, delayMs: Long)

    /** 关闭该曲目歌词（保留快照与偏移，UI 显示「已关闭歌词」）。 */
    suspend fun suppress(trackUid: String)

    /** 清除该曲目歌词快照（恢复自动匹配）。 */
    suspend fun clear(trackUid: String)
}
