package com.wonderlandkid.biliaudio.ui

import androidx.compose.ui.geometry.Rect
import java.util.Locale

/**
 * 维测日志（Diag）在 UI 层共用的标签与格式化工具（v0.58.0 起）。
 *
 * 约定：
 * - 只记录技术状态（progress / rect / index / mode 等），**不记录 Cookie、账号等隐私数据**；
 * - 日志本身受「设置 → 诊断与维护 → 维测日志」开关控制（`Diag.enabled`）；
 * - 拖拽/动画相关日志必须去重或只在状态跃迁时记录，禁止逐帧刷屏（内存环形只有 3000 行）。
 */
internal object DiagTag {
    /** 导航壳：路由、Tab、播放器展开/收起、拖拽手势、inset、底栏、返回键。 */
    const val SHELL = "Shell"

    /** 播放器表面：出现动画、播放页内容组合阈值、共享元素测量、卡死兜底。 */
    const val SURFACE = "Surface"

    /** 播放页内部：封面/歌词分页、播放列表面板、seek、播放模式。 */
    const val NOW_PLAYING = "NowPlaying"

    /** 全局主题：封面取色种子变化。 */
    const val THEME = "Theme"
}

/** 播放进度统一格式化为 3 位小数（固定 Locale，避免逗号小数点）。 */
internal fun diagProgress(value: Float): String = String.format(Locale.US, "%.3f", value)

/** 速度/间距等数值统一格式化为 1 位小数。 */
internal fun diagNumber(value: Float): String = String.format(Locale.US, "%.1f", value)

/** 矩形统一格式化为 `L,T,R,B(WxH)`，便于日志 grep。 */
internal fun Rect.diagRect(): String =
    "L${left.toInt()},T${top.toInt()},R${right.toInt()},B${bottom.toInt()}" +
        "(${width.toInt()}x${height.toInt()})"
