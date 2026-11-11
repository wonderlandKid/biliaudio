package com.wonderlandkid.biliaudio.ui.player

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.delay

/**
 * 屏幕最右侧的音量微调细条（v0.38：完美隐藏 / 点按显形 / 拖拽调整 / 液态玻璃模糊）。
 *
 * - 平时完全隐身（只有透明触控区）；点一下在屏幕最右侧浮现；
 * - 显形后上下拖动调整 7 档（-3..+3，每档=系统音量 1/4，20dp 一档）；
 * - 视觉条紧贴屏幕右边缘（不占封面空间），材质为 Haze 背景模糊（API31+；低版本半透明兜底）；
 * - 拖动/点按后 2s 自动隐藏，保证不打扰封面与歌词。
 */
@Composable
internal fun VolumeFineTune(
    offsetSteps: Int,
    onOffsetChange: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacingPx = remember(density) { with(density) { 20.dp.toPx() } }

    val currentOffset by rememberUpdatedState(offsetSteps)
    val currentOnChange by rememberUpdatedState(onOffsetChange)

    var revealed by remember { mutableStateOf(false) }
    var revealedTick by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var dragBaseOffset by remember { mutableIntStateOf(offsetSteps) }

    LaunchedEffect(revealedTick) {
        if (!revealed) return@LaunchedEffect
        delay(2_000)
        revealed = false
    }
    val revealAlpha by animateFloatAsState(
        targetValue = if (revealed || dragging) 1f else 0f,
        animationSpec = tween(BiliMotion.Short4, easing = BiliMotion.Emphasized),
        label = "volumeFineTuneReveal",
    )

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val tintColor = MaterialTheme.colorScheme.surface
    val fallbackColor = containerColor.copy(alpha = 0.94f)
    val blurStyle = remember(containerColor, tintColor) {
        val glassTint = HazeColorEffect.tint(
            color = tintColor.copy(alpha = 0.22f),
            blendMode = BlendMode.SrcOver,
        )
        val fallbackTint = HazeColorEffect.tint(
            color = fallbackColor,
            blendMode = BlendMode.SrcOver,
        )
        HazeBlurStyle {
            blurRadius(28.dp)
            backgroundColor(containerColor.copy(alpha = 0.14f))
            colorEffects(listOf(glassTint))
            fallbackColorEffect(fallbackTint)
            noiseFactor(0.04f)
        }
    }
    val glassModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = blurStyle,
            performanceMode = HazePerformanceMode.Balanced,
        )
    } else {
        Modifier.background(fallbackColor)
    }

    Box(
        modifier = modifier
            .width(48.dp)
            .height(240.dp)
            .semantics { contentDescription = "音量微调：点按后在屏幕右缘显形，上下拖动" }
            .pointerInput(Unit) {
                detectTapGestures {
                    revealed = true
                    revealedTick = SystemClock.elapsedRealtime()
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        revealed = true
                        revealedTick = SystemClock.elapsedRealtime()
                        dragging = true
                        dragBaseOffset = currentOffset
                        dragAccumulated = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulated -= dragAmount
                        val steps = (dragAccumulated / spacingPx).toInt()
                        if (steps != 0) {
                            dragAccumulated -= steps * spacingPx
                            val next = (dragBaseOffset + steps).coerceIn(-3, 3)
                            if (next != dragBaseOffset) {
                                dragBaseOffset = next
                                currentOnChange(next)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            revealedTick = SystemClock.elapsedRealtime()
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        revealedTick = SystemClock.elapsedRealtime()
                    },
                    onDragCancel = {
                        dragging = false
                        revealedTick = SystemClock.elapsedRealtime()
                    },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = revealAlpha }
                .width(24.dp)
                .height(128.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(glassModifier)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 3.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                repeat(7) { index ->
                    val dotActive = index == dragBaseOffset + 3
                    Box(
                        modifier = Modifier
                            .width(if (dotActive) 12.dp else 6.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (dotActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                            ),
                    )
                }
            }
            if (dragBaseOffset != 0) {
                Text(
                    text = if (dragBaseOffset > 0) "+$dragBaseOffset" else "$dragBaseOffset",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

