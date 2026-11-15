package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * 全局动效 token。新增动效先在这里加 token，再到 docs/MOTION.md 补表；
 * 曲线选型依据见 docs/MOTION.md。
 */
object BiliMotion {
    // M3 easing（页面转场/退场用；push/pop 固定 500ms，不跟手势速度联动）
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 层级页面 push/pop 固定时长（ms）。 */
    const val PageTransitionMs = 500

    /** 迷你播放条显示/隐藏（ms）。 */
    const val MiniPlayerEnterMs = 280
    const val MiniPlayerExitMs = 220

    /** Tab 列表入场：基础 320ms + 每项 50ms（封顶 650ms），交错 45ms。 */
    const val EntranceBaseMs = 320
    const val EntranceStepMs = 50
    const val EntranceMaxMs = 650
    const val EntranceStaggerMs = 45

    /** 歌词自动滚动到当前行（长缓出）；当前行着色/透明度过渡。 */
    const val LyricsScrollMs = 520
    const val LyricsActiveMs = 380
}
