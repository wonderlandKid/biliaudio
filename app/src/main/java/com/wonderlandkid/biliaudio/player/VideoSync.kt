package com.wonderlandkid.biliaudio.player

import kotlin.math.abs

/**
 * 歌词页视频与音频的同步策略（纯逻辑，便于单元测试）。
 *
 * 依据 docs/DESIGN.md：视频用独立第二路 ExoPlayer 播放，UI 每 ~500ms 比较一次
 * “音频在专辑流中的绝对进度”与“视频当前进度”，漂移超过阈值才 seek，避免频繁打断视频缓冲。
 */
internal object VideoSync {

    /**
     * 允许的漂移阈值：超过则校正（毫秒）。
     * 800ms 是为了避免硬件解码正常的小幅抖动触发 seek → BUFFERING → 加载提示再次出现；
     * 人眼对歌词页音画偏差在 1s 内通常不敏感，而频繁 seek 的代价更大。
     */
    const val DRIFT_THRESHOLD_MS = 800L

    /** 校正轮询间隔（毫秒）。 */
    const val SYNC_INTERVAL_MS = 500L

    /** 两次漂移校正的最小间隔（毫秒）：避免 seek → 缓冲 → 再 seek 的循环。 */
    const val MIN_CORRECTION_INTERVAL_MS = 3_000L

    /** 视频/音频进度差是否已超出可接受范围。 */
    fun needsCorrection(
        audioAlbumPositionMs: Long,
        videoPositionMs: Long,
        thresholdMs: Long = DRIFT_THRESHOLD_MS,
    ): Boolean = abs(audioAlbumPositionMs - videoPositionMs) > thresholdMs

    /** 校正目标：直接对齐音频的专辑绝对进度。 */
    fun targetPosition(audioAlbumPositionMs: Long): Long = audioAlbumPositionMs.coerceAtLeast(0L)
}
