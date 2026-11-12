package com.wonderlandkid.biliaudio.ui.layers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.components.BiliBottomBar
import com.wonderlandkid.biliaudio.ui.components.MainTab
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion

/**
 * 根 Tab 的底部导航层。
 *
 * 固定高度、不参与 Content 的测量；显示/隐藏只做 alpha + translationY。
 * 这样返回根 Tab 时内容 viewport 从第一帧就是最终高度，LazyColumn 不会被 clamp。
 */
@Composable
internal fun BottomBarLayer(
    showChrome: Boolean,
    shownOnce: Boolean,
    onShown: () -> Unit,
    heightPx: Int,
    onHeightMeasured: (Int) -> Unit,
    playerProgress: Float,
    currentTab: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(showChrome) { if (showChrome) onShown() }
    AnimatedVisibility(
        visible = showChrome,
        modifier = modifier.layer(AppLayer.BottomBar),
        enter = if (shownOnce) {
            EnterTransition.None
        } else {
            expandVertically(
                animationSpec = tween(
                    durationMillis = BiliMotion.BottomBarEnterMs,
                    easing = BiliMotion.EmphasizedDecelerate,
                ),
                expandFrom = Alignment.Bottom,
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = BiliMotion.BottomBarEnterMs,
                    easing = BiliMotion.EmphasizedDecelerate,
                ),
            )
        },
        exit = shrinkVertically(
            animationSpec = tween(
                durationMillis = BiliMotion.BottomBarExitMs,
                easing = BiliMotion.EmphasizedAccelerate,
            ),
            shrinkTowards = Alignment.Bottom,
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = BiliMotion.BottomBarExitMs,
                easing = BiliMotion.EmphasizedAccelerate,
            ),
        ),
    ) {
        val fixedHeight = if (heightPx > 0) with(androidx.compose.ui.platform.LocalDensity.current) {
            heightPx.toDp()
        } else {
            null
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
                .onSizeChanged { measured ->
                    if (measured.height > heightPx) onHeightMeasured(measured.height)
                }
                .graphicsLayer {
                    val hide = smoothStep(0f, 0.18f, playerProgress)
                    alpha = 1f - hide
                    translationY = hide * size.height
                },
        ) {
            BiliBottomBar(current = currentTab, onSelect = onSelect)
        }
    }
}
