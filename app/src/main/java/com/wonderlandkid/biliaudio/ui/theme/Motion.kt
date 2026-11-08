package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 全局动效 token（v0.6.2 重写）。
 *
 * 依据 Material Design 3 官方规范：
 * - Easing/duration tokens（legacy 系统，规范明确仍用于「页面转场」）：
 *   https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 *   - Emphasized: (0.2, 0, 0, 1)
 *   - Emphasized decelerate: (0.05, 0.7, 0.1, 1)  → 入场
 *   - Emphasized accelerate: (0.3, 0, 0.8, 0.15)  → 退场
 *   - Legacy standard（Material transition 常用）: (0.4, 0, 0.2, 1)
 *   - duration: short2=100ms, short4=200ms, medium2=300ms, long2=500ms
 * - Transition patterns（转场模式）：
 *   https://m3.material.io/styles/motion/transitions/transition-patterns
 *   - 顶级 Tab 切换 = **Fade through**：旧页先淡出（100ms），新页再淡入（200ms）并略微放大（92%→100%），共 300ms；
 *   - 层级页面（详情/艺术家/导入等）= **Shared axis X**：旧页淡出 100ms、新页淡入 200ms（延后 100ms），
 *     两侧同时沿 X 轴平移 30dp，共 300ms；
 *   - 播放页（从迷你条向上展开）= Shared axis Y（垂直 30dp + 同样的 100/200 淡入淡出）。
 * - M3 Expressive motion physics（弹簧 token）用于可交互的尺寸/位置动画（如迷你条收起展开）：
 *   https://m3.material.io/styles/motion/overview/specs
 *   MaterialTheme.motionScheme（standard/expressive）由 Compose Material3 提供。
 */
object BiliMotion {
    // ---------------- M3 easing tokens ----------------
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    // ---------------- M3 duration tokens ----------------
    const val Short2 = 100
    const val Short4 = 200
    const val Medium2 = 300
    const val Long2 = 500

    // ---------------- Fade through（顶级 Tab） ----------------
    // 依据 M3「Emphasized decelerate 400ms 入场 / Emphasized accelerate 200ms 退场」的推荐节奏：
    // 旧页先淡出、新页随后以更长的 decelerate 淡入并 94%→100% 放大，总 400ms（略慢、更优雅）。
    const val TopLevelExitMs = 200             // 旧页 200ms 淡出
    const val TopLevelEnterDelayMs = 100       // 新页延后 100ms
    const val TopLevelEnterMs = 300            // 新页 300ms 淡入
    const val TopLevelTotalMs = 400            // 合计 400ms
    const val TopLevelEnterScale = 0.94f       // 94% → 100%

    // ---------------- 页面/局部动效时长（统一在这里调，禁止页面内散写） ----------------
    /** 层级页面 push/pop：完整宽度滑动；固定 500ms，手势快慢不会改变它。 */
    const val PageTransitionMs = 500

    // ---------------- Shared axis（层级页面） ----------------
    // v0.14.0：页面 push/pop 放慢到 500ms（M3 Emphasized 推荐「起止都在屏幕」500ms），
    // 进入用 EmphasizedDecelerate、退出用 EmphasizedAccelerate；全部固定时长，不跟手速联动。
    const val AxisExitFadeMs = 200
    const val AxisEnterDelayMs = 100
    const val AxisEnterFadeMs = 300
    const val AxisTotalMs = PageTransitionMs
    /** 迷你播放条显示/隐藏。 */
    const val MiniPlayerEnterMs = 280
    const val MiniPlayerExitMs = 220
    /** 歌词视频控制层显隐。 */
    const val VideoControlsEnterMs = 260
    const val VideoControlsExitMs = 200
    /** 歌词视频封面占位淡出。 */
    const val VideoCoverEnterMs = 300
    const val VideoCoverExitMs = 250
    /** Tab 列表入场：基础 320ms + 每项 50ms（封顶 650ms），交错 45ms。 */
    const val EntranceBaseMs = 320
    const val EntranceStepMs = 50
    const val EntranceMaxMs = 650
    const val EntranceStaggerMs = 45
    // 位移用屏幕百分比（Material 原 spec 为 30dp，视觉上偏“原地淡出”；
    // 为了让进入/返回都有明确的空间方向感，这里取 24% 屏宽，进出对称）
    const val AxisTranslationFraction = 0.24f
    const val AxisVerticalFraction = 0.14f

    // ---------------- Fade（错误/覆盖层等简单显隐） ----------------
    const val FadeInMs = 250
    const val FadeOutMs = 150

    // ---------------- M3 Expressive motion physics（弹簧 token） ----------------
    // 数值来自 androidx.compose.material3.tokens.StandardMotionTokens / ExpressiveMotionTokens
    // （material3 1.5 中 MotionScheme API 尚为 internal，这里按官方 token 数值直接使用 spring）
    // Standard：default spatial damping 0.9 / stiffness 700；effects damping 1.0 / stiffness 1600
    // Expressive：default spatial damping 0.8 / stiffness 380
    fun <T> standardSpatialSpring(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 700f)

    fun <T> expressiveSpatialSpring(): SpringSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> standardEffectsSpring(): SpringSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 1600f)
}
