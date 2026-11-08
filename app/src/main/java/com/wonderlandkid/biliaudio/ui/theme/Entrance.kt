package com.wonderlandkid.biliaudio.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 页面/列表的入场动画（参考 SPICaMusic_Android 的 Modifier.entrance 简化版）。
 *
 * 参考项目的一级 Tab 本身没有转场，但每个页面的列表内容在出现时会做
 * 「淡入 + 上浮」的交错入场，所以切换 Tab 看起来不突兀。这里用同样的思路：
 * 整个内容块（或列表）从下方 24dp 淡入上浮；page 切换时通过 replayKey 重播。
 *
 * 动画只写 layer（不触发重组），开销很小。
 */
private const val EntranceTranslationDp = 24f
private const val EntranceStaggerMs = 35
private val EntranceEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

fun Modifier.entrance(
    order: Int = 0,
    play: Boolean = true,
    replayKey: Int = 0,
): Modifier = composed {
    // 始终从 0 开始：`play` 在页面首帧后才变 true 时仍能播放完整入场。
    // （v0.8.3 的 bug：play=false 时初始化为 1，之后 play=true 动画从 1→1 无效果，
    //   所以发现/音乐库没有列表入场，而设置页因为直接用默认 play=true 才有。）
    val progress = remember(replayKey) {
        Animatable(0f)
    }
    LaunchedEffect(play, replayKey) {
        if (play) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    delayMillis = (order.coerceAtLeast(0) * EntranceStaggerMs),
                    durationMillis = (250 + order * 45).coerceAtMost(550),
                    easing = EntranceEasing,
                ),
            )
        } else {
            progress.snapTo(1f)
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * EntranceTranslationDp.dp.toPx()
    }
}

/**
 * 页面首次进入时把 [play] 从 false 切到 true（触发入场动画）。
 * 用普通 remember：切走再切回来时组件重新进入组合，会再次播放。
 */
@Composable
fun rememberPageEntrancePlay(): Boolean {
    var play by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { play = true }
    return play
}
