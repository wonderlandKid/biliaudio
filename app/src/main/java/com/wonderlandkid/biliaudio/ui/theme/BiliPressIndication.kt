package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 全局按压反馈（参考 SPICaMusic / 柠檬音乐 的 ClickHighlight 思路）：
 *
 * - 按下时在手指位置产生**水波纹**，并给整个可点击区域轻微压暗；
 * - 松开后波纹继续扩散、淡出；
 * - 只对真正可点击（enabled）的组件生效；禁用按钮不会触发 interaction；
 * - 不是页面级自动动画，所以使用快速曲线（180ms/320ms），不会拖慢界面。
 */
@Composable
fun rememberBiliPressIndication(): Indication {
    val color = MaterialTheme.colorScheme.onSurface
    return remember(color) { BiliPressIndication(color) }
}

private data class BiliPressIndication(
    private val color: Color,
    private val focusAlpha: Float = 0.08f,
    private val pressAlpha: Float = 0.07f,
    private val rippleAlpha: Float = 0.16f,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        BiliPressNode(interactionSource, color, focusAlpha, pressAlpha, rippleAlpha)
}

private class BiliPressNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val focusAlpha: Float,
    private val pressAlpha: Float,
    private val rippleAlpha: Float,
) : androidx.compose.ui.Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f)
    private var pressed = false
    private var job: Job? = null
    private var pressPosition = Offset.Unspecified
    private var hovered = false
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressed = true
                        pressPosition = interaction.pressPosition
                        animatePress()
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressed = false
                        animateRelease()
                    }
                    is HoverInteraction.Enter -> { hovered = true; invalidateDraw() }
                    is HoverInteraction.Exit -> { hovered = false; invalidateDraw() }
                    is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
                }
            }
        }
    }

    override fun onDetach() {
        job?.cancel()
        pressed = false
        hovered = false
        focused = false
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (hovered || focused) {
            drawRect(color = color.copy(alpha = focusAlpha * if (focused) 1f else 0.55f))
        }
        val value = progress.value.coerceIn(0f, 1f)
        if (value <= 0f || value >= 1f) return
        val center = if (pressPosition.isSpecified) {
            Offset(
                x = pressPosition.x.coerceIn(0f, size.width),
                y = pressPosition.y.coerceIn(0f, size.height),
            )
        } else {
            Offset(size.width / 2f, size.height / 2f)
        }
        val intensity = 1f - kotlin.math.abs(0.5f - value) * 2f
        if (pressed || intensity > 0f) {
            drawRect(color = color.copy(alpha = pressAlpha * intensity.coerceIn(0f, 1f)))
        }
        val radius = maxDistanceToCorner(center) * (0.22f + value * 0.9f)
        val ripple = (1f - value).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to color.copy(alpha = rippleAlpha * ripple),
                    0.55f to color.copy(alpha = rippleAlpha * 0.45f * ripple),
                    1f to Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }

    private fun animatePress() {
        job?.cancel()
        job = coroutineScope.launch {
            progress.snapTo(0f)
            invalidateDraw()
            progress.animateTo(
                targetValue = 0.5f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) { invalidateDraw() }
        }
    }

    private fun animateRelease() {
        job?.cancel()
        job = coroutineScope.launch {
            val duration = ((1f - progress.value) * 320f).roundToInt().coerceAtLeast(1)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            ) { invalidateDraw() }
            progress.snapTo(0f)
            invalidateDraw()
        }
    }

    private fun ContentDrawScope.maxDistanceToCorner(center: Offset): Float = maxOf(
        hypot(center.x, center.y),
        hypot(size.width - center.x, center.y),
        hypot(center.x, size.height - center.y),
        hypot(size.width - center.x, size.height - center.y),
    )
}
