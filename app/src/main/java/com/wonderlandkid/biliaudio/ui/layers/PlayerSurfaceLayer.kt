package com.wonderlandkid.biliaudio.ui.layers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.ui.components.MiniPlayerBar
import com.wonderlandkid.biliaudio.ui.player.NowPlayingScreen
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 播放器唯一表面（v0.48）。
 *
 * 迷你条和播放页不再是两个互相遮挡的层，而是**同一个 PlayerSurface**：
 * - p=0：surface 边界等于迷你条 rect；
 * - p=1：surface 边界铺满屏幕；
 * - 中间：surface 的 top/bottom/圆角连续插值，播放页内容在 surface 内被裁剪；
 * - 迷你条内容只在前 5% 淡出，surface 本身始终是一个圆角卡片。
 *
 * 这样收起时不会出现「sheet 背景 + 页面背景 + 迷你条背景」三张画面叠加的一帧。
 */
@Composable
internal fun BoxScope.PlayerSurfaceLayer(
    visible: Boolean,
    playbackController: PlaybackController,
    playerProgress: Float,
    playerPage: Int,
    onPageChanged: (Int) -> Unit,
    miniRect: Rect?,
    miniBottomPadding: Dp,
    screenHeight: Dp,
    statusBarInset: Dp,
    dragModifier: Modifier,
    onClick: () -> Unit,
    onBarBounds: (Rect) -> Unit,
    hazeState: HazeState?,
    onClose: () -> Unit,
) {
    if (!visible) return
    val state by playbackController.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { screenHeight.toPx() }
    val rect = miniRect
    if (rect == null) {
        // 第一帧还没测到迷你条位置时只画迷你条，避免画错位置的 surface 闪一下。
        MiniPlayerBar(
            state = state,
            onClick = onClick,
            onTogglePlay = { playbackController.togglePlayPause() },
            onPrevious = { playbackController.previous() },
            onNext = { playbackController.next() },
            hazeState = hazeState,
            surfaceOpaque = playerProgress > 0.001f,
            dragModifier = dragModifier,
            onBarBounds = onBarBounds,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = miniBottomPadding),
        )
        return
    }

    // 迷你条实际 rect（窗口坐标）→ 当前 surface 边界；p=1 时顶到 0，底到屏幕底。
    val topPx = rect.top * (1f - playerProgress)
    val bottomPx = rect.bottom + (screenHeightPx - rect.bottom) * playerProgress
    val heightPx = (bottomPx - topPx).coerceAtLeast(1f)
    val shape = RoundedCornerShape(24.dp * (1f - playerProgress))

    // 底层页面压暗（仅播放页展开后）
    if (playerProgress > 0.001f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layer(AppLayer.Player - 1f)
                .graphicsLayer { alpha = playerProgress * 0.18f }
                .background(MaterialTheme.colorScheme.scrim),
        )
    }

    // 唯一 surface：迷你条时就是那张卡片，展开后就是全屏播放页。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, topPx.roundToInt()) }
            .height(with(density) { heightPx.toDp() })
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surface)
            // 展开后 surface 在迷你条/底栏之上，避免被底栏盖住；
            // 收起最后 6% 切回迷你条在上，让迷你条覆盖缩小的 surface，而不是露出播放页残影。
            .layer(if (playerProgress >= 0.06f) AppLayer.Player else AppLayer.MiniPlayer),
    ) {
        // 最后 6% 不再绘制播放页内容：surface 已缩到迷你条附近，content 交给迷你条，
        // 避免收起末尾还能看到一条“播放页专辑图”从迷你条下面露出来。
        if (playerProgress >= 0.06f) {
            BackHandler(enabled = playerProgress > 0.01f) { onClose() }
            NowPlayingScreen(
                initialPage = playerPage,
                onPageChanged = onPageChanged,
                // 播放页按完整屏幕高度布局，再由 surface 裁剪；不能跟随 surface 高度重排。
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(screenHeight),
            )
            // 顶部下拉关闭热区（相对 surface，p 越高越接近屏幕顶部）。
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

    // 迷你条内容：它只在 p<5% 可见；始终保留组合以维持拖拽 pointerInput。
    val miniAlpha = 1f - smoothStep(0f, 0.05f, playerProgress)
    MiniPlayerBar(
        state = state,
        onClick = onClick,
        onTogglePlay = { playbackController.togglePlayPause() },
        onPrevious = { playbackController.previous() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        surfaceOpaque = playerProgress > 0.001f,
        dragModifier = dragModifier,
        onBarBounds = onBarBounds,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .layer(if (playerProgress >= 0.06f) AppLayer.MiniPlayer else AppLayer.MiniPlayer + 1f)
            .graphicsLayer { alpha = miniAlpha }
            .padding(bottom = miniBottomPadding),
    )
}
