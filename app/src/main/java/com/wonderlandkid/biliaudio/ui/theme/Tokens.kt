package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BiliShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val BiliTypography = Typography().let { base ->
    base.copy(
        displayLarge = TextStyle(
            fontWeight = FontWeight.Black,
            fontSize = 44.sp,
            lineHeight = 50.sp,
            letterSpacing = (-1).sp,
        ),
        displayMedium = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            letterSpacing = (-0.5).sp,
        ),
        headlineLarge = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 36.sp,
        ),
        headlineMedium = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
        ),
        titleLarge = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object Dimens {
    val cardCorner = 22.dp
}

/**
 * 页面/组件统一布局 token（v0.24.0）。
 *
 * 页面里禁止再按心情写 padding 数字；横向页面留白统一 [pageHorizontal]，
 * 区块间距 [sectionGap]，列表封面/图标尺寸 [listCoverSize]/[leadingIconSize]。
 */
object BiliLayout {
    val pageHorizontal = Spacing.lg
    val pageBottom = Spacing.xxl
    val sectionGap = Spacing.md
    val sectionHeaderVertical = Spacing.md
    val groupItemHorizontal = Spacing.md
    val groupItemVertical = Spacing.sm + 2.dp
    val leadingIconSize = 22.dp
    val listCoverSize = 48.dp
    val listCoverCorner = 12.dp
    val contentBottomWithMiniPlayer = 96.dp
    /** 迷你条实际占用的内容高度（悬浮层，不含底部导航/系统栏）。 */
    val miniPlayerContentInset = 72.dp
}
