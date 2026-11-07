package com.wonderlandkid.biliaudio.player

import kotlin.math.abs

/**
 * 歌词页视频与音频的同步策略（纯逻辑，便于单元测试）。
 *
 * 依据 docs/DESIGN.md：视频用独立第二路 ExoPlayer 播放，UI 每 ~500ms 比较一次
 * “音频在专辑流中的绝对进度”与“视频当前进度”，漂移超过阈值才 seek，避免频繁打断视频缓冲。
 */
internal object VideoSync {

    /** 允许的漂移阈值：超过则校正（毫秒）。 */
    const val DRIFT_THRESHOLD_MS = 300L

    /** 校正轮询间隔（毫秒）。 */
    const val SYNC_INTERVAL_MS = 500L

    /** 视频/音频进度差是否已超出可接受范围。 */
    fun needsCorrection(
        audioAlbumPositionMs: Long,
        videoPositionMs: Long,
        thresholdMs: Long = DRIFT_THRESHOLD_MS,
    ): Boolean = abs(audioAlbumPositionMs - videoPositionMs) > thresholdMs

    /** 校正目标：直接对齐音频的专辑绝对进度。 */
    fun targetPosition(audioAlbumPositionMs: Long): Long = audioAlbumPositionMs.coerceAtLeast(0L)
}
