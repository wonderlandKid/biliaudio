package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** B 站品牌粉，默认主题种子色。 */
val BiliPink = Color(0xFFFB7299)

/**
 * 应用主题：Material 3 动态色板（由种子色生成），扁平、无液态玻璃。
 * 参考 SPICaMusic 的 TonalSpot 风格 + 大圆角。
 */
@Composable
fun BiliAudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seedColor: Color = BiliPink,
    content: @Composable () -> Unit,
) {
    val colorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        style = PaletteStyle.TonalSpot,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        // 说明：material3 1.5.0-alpha22（BOM 2026.09.00）的 MotionScheme 在 Kotlin 层仍是 internal，
        // 无法全局注入 M3 Expressive 弹簧；v0.61.0 曾升到 1.5.0-rc01 验证，手感提升不明显已回撤。
        typography = BiliTypography,
        shapes = BiliShapes,
        content = content,
    )
}
