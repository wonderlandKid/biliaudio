package com.wonderlandkid.biliaudio.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 底部悬浮迷你播放条（v0.8.0）。
 *
 * - **Haze 毛玻璃**：backdrop blur + 半透明 tint + 细边框（API31+；低版本/软件渲染 fallback 高不透明度）；
 * - **无拖拽提示条**；点击打开正在播放页；
 * - 上滑展开由外层 [com.wonderlandkid.biliaudio.ui.navigation.AppNavHost] 的拖拽逻辑驱动（整条跟手，
 *   连续拉出播放页），本组件只负责视觉；
 * - 底部进度线为低对比度 2dp。
 *
 * @param dragModifier 外层的垂直拖拽 Modifier（驱动播放页展开进度）
 */
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    dragModifier: Modifier = Modifier,
) {
    val track = state.currentTrack
    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically(animationSpec = tween(BiliMotion.Short4)) { it / 2 } +
            fadeIn(animationSpec = tween(BiliMotion.Short4)),
        exit = slideOutVertically(animationSpec = tween(BiliMotion.Short2)) { it / 2 } +
            fadeOut(animationSpec = tween(BiliMotion.Short2)),
        modifier = modifier,
    ) {
        if (track == null) return@AnimatedVisibility
        val cover = state.queue.getOrNull(state.currentIndex)?.coverModel()
        val shape = RoundedCornerShape(24.dp)
        val containerColor = MaterialTheme.colorScheme.surfaceContainer
        val onContainer = MaterialTheme.colorScheme.onSurface
        val blurStyle = remember(containerColor) {
            val glassTint = HazeColorEffect.tint(
                color = containerColor.copy(alpha = 0.22f),
                blendMode = BlendMode.SrcOver,
            )
            val fallbackTint = HazeColorEffect.tint(
                color = containerColor.copy(alpha = 0.90f),
                blendMode = BlendMode.SrcOver,
            )
            HazeBlurStyle {
                // 更深的毛玻璃：更大的 blur radius + 更低的底色占比
                blurRadius(36.dp)
                backgroundColor(containerColor.copy(alpha = 0.16f))
                colorEffects(listOf(glassTint))
                fallbackColorEffect(fallbackTint)
                noiseFactor(0.05f)
                alpha(1f)
            }
        }
        val glassModifier =
            if (hazeState != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.hazeBlur(
                    input = HazeInput.Sources(hazeState),
                    style = blurStyle,
                    performanceMode = HazePerformanceMode.Balanced,
                )
            } else {
                Modifier.background(containerColor.copy(alpha = 0.94f))
            }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                .clip(shape)
                .then(glassModifier)
                .border(1.dp, onContainer.copy(alpha = 0.06f), shape)
                .then(dragModifier)
                .clickable(onClick = onClick),
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(
                        url = cover,
                        modifier = Modifier.size(46.dp),
                        cornerRadius = 14.dp,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.md),
                    ) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = onContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.queue.getOrNull(state.currentIndex)?.artist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "下一首",
                            tint = onContainer.copy(alpha = 0.86f),
                        )
                    }
                }
            }
        }
    }
}
