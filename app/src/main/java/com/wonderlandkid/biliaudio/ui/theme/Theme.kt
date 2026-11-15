package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
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
        // v0.60.1：material3 1.5.0-rc01 起 MotionScheme 公开；Expressive = M3 弹簧物理，
        // 底部抽屉/按钮/开关/勾选/导航栏/波纹/菜单等内置动画统一换成弹簧（iOS 肉感）。
        motionScheme = MotionScheme.expressive(),
        typography = BiliTypography,
        shapes = BiliShapes,
        content = content,
    )
}
