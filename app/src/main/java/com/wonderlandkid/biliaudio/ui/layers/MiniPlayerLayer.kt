package com.wonderlandkid.biliaudio.ui.layers

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.ui.components.MiniPlayerBar
import dev.chrisbanes.haze.HazeState

/** 迷你播放条层：始终保留组合，保证跟手拖拽的 pointerInput 不被中途销毁。 */
@Composable
internal fun BoxScope.MiniPlayerLayer(
    visible: Boolean,
    playbackController: PlaybackController,
    playerProgress: Float,
    anchorTopPx: Float,
    onClick: () -> Unit,
    dragModifier: Modifier,
    onBarBounds: (Rect) -> Unit,
    bottomPadding: Dp,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    MiniPlayerHost(
        playbackController = playbackController,
        playerProgress = playerProgress,
        anchorTopPx = anchorTopPx,
        onClick = onClick,
        dragModifier = dragModifier,
        onBarBounds = onBarBounds,
        bottomPadding = bottomPadding,
        hazeState = hazeState,
        modifier = modifier.align(Alignment.BottomCenter),
    )
}

@Composable
private fun MiniPlayerHost(
    playbackController: PlaybackController,
    playerProgress: Float,
    anchorTopPx: Float,
    onClick: () -> Unit,
    dragModifier: Modifier,
    onBarBounds: (Rect) -> Unit,
    bottomPadding: Dp,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val state by playbackController.state.collectAsStateWithLifecycle()
    val miniAlpha = 1f - smoothStep(0f, 0.05f, playerProgress)
    MiniPlayerBar(
        state = state,
        onClick = onClick,
        onTogglePlay = { playbackController.togglePlayPause() },
        onPrevious = { playbackController.previous() },
        onNext = { playbackController.next() },
        hazeState = hazeState,
        dragModifier = dragModifier,
        onBarBounds = onBarBounds,
        modifier = modifier
            .layer(AppLayer.MiniPlayer)
            .graphicsLayer {
                alpha = miniAlpha
                translationY = -anchorTopPx * playerProgress
            }
            .padding(bottom = bottomPadding),
    )
}
