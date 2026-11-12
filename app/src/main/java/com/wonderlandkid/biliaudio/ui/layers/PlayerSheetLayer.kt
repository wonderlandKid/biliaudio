package com.wonderlandkid.biliaudio.ui.layers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.ui.player.NowPlayingScreen

/**
 * 播放页 sheet 层：整块页面从迷你条位置向上滑出，底部锚定裁剪。
 * 封面/标题/频谱/进度/播放按钮都在同一个 sheet 内，所以控件跟封面一起移动。
 */
@Composable
internal fun PlayerSheetLayer(
    playerProgress: Float,
    playerPage: Int,
    onPageChanged: (Int) -> Unit,
    travelPx: Float,
    statusBarInset: Dp,
    dragModifier: Modifier,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playerProgress <= 0.001f) return
    val offsetY = travelPx.coerceAtLeast(0f) * (1f - playerProgress)
    // 页面压暗层
    Box(
        modifier = modifier
            .fillMaxSize()
            .layer(AppLayer.Player)
            .graphicsLayer { alpha = playerProgress * 0.18f }
            .background(MaterialTheme.colorScheme.scrim),
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .layer(AppLayer.Player)
            .graphicsLayer {
                translationY = offsetY
                alpha = (playerProgress / 0.03f).coerceIn(0f, 1f)
                val radius = 24.dp * (1f - playerProgress)
                shape = RoundedCornerShape(topStart = radius, topEnd = radius)
                clip = true
            }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        BackHandler(enabled = playerProgress > 0.01f) { onClose() }
        NowPlayingScreen(
            initialPage = playerPage,
            onPageChanged = onPageChanged,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset(y = statusBarInset)
                .height(112.dp)
                .then(dragModifier),
        )
    }
}
