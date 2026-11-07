package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Tab 页面进入动画（v0.6.1）。
 *
 * 目的：底部导航切页时，新页面内容不是「一瞬间出现」，而是轻微上浮 + 淡入，
 * 避免切页瞬间出现空白感。动画只影响首帧后的 200~260ms，不阻塞交互。
 *
 * 用法：包在页面内容外层（AppNavHost 里包住三个 Tab 页）。
 */
@Composable
fun PageEnterAnimation(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val startOffsetPx = with(LocalDensity.current) { 8.dp.toPx() }
    val translation by animateFloatAsState(
        targetValue = if (entered) 0f else startOffsetPx,
        animationSpec = tween(durationMillis = 260),
        label = "pageEnterTranslation",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = translation
            },
    ) {
        content()
    }
}
