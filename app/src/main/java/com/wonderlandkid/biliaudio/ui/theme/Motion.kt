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
    // Material 原时序为「旧页 100ms 淡出 → 新页 200ms 淡入（延迟 100ms）」。
    // 纯顺序执行会在两页都不可见的瞬间露出整页背景（浅色主题下如空白），
    // 因此保留 300ms 总时长与 92%→100% 放大，仅做 50ms 交叉重叠，兼顾标准节奏与不闪白。
    const val TopLevelExitMs = 150
    const val TopLevelEnterDelayMs = 50
    const val TopLevelEnterMs = 250
    const val TopLevelTotalMs = Medium2        // 合计 300ms
    const val TopLevelEnterScale = 0.92f       // 92% → 100%

    // ---------------- Shared axis（层级页面） ----------------
    const val AxisExitFadeMs = Short4          // 旧页 200ms 淡出
    const val AxisEnterDelayMs = 60            // 新页延后 60ms（与旧页淡出重叠）
    const val AxisEnterFadeMs = 240
    const val AxisTotalMs = Medium2            // 平移合计 300ms
    const val AxisTranslationDp = 30           // 沿轴向平移 30dp

    // ---------------- Fade（错误/覆盖层等简单显隐） ----------------
    const val FadeInMs = Short4
    const val FadeOutMs = Short2

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
