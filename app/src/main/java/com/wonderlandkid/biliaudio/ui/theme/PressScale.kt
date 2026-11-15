package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * iOS 式按压肉感（v0.60.0）。
 *
 * - 按下：快速（90ms）缩到 [pressedScale]，像手指压住一块有弹性的材质；
 * - 松开：用 springBouncy（ζ=0.75）弹回 1，有轻微过冲；
 * - 只用 transform（graphicsLayer），不参与布局，不影响点击热区；
 * - 需要与 clickable 共用同一个 [InteractionSource]，否则收不到按压状态。
 */
fun Modifier.biliPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.92f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) {
            tween(durationMillis = 120, easing = FastOutSlowInEasing)
        } else {
            BiliMotion.springBouncy()
        },
        label = "biliPressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 带 iOS 按压肉感的 clickable：内部创建 InteractionSource，同时驱动缩放和按压状态层。
 *
 * 保留全局 [LocalIndication]（项目自定义的按压缩暗/水波纹），只额外叠加缩放；
 * 行类元素（整行列表）不建议缩放，会显得“整行在动”，只给卡片/按钮/封面用。
 */
@Composable
fun Modifier.biliClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.92f,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    return this
        .biliPressScale(interactionSource, pressedScale)
        .clickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
