package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.ui.graphics.graphicsLayer

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.ui.DiagTag
import com.wonderlandkid.biliaudio.util.Diag
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import com.wonderlandkid.biliaudio.ui.theme.biliClickable

/**
 * 底部悬浮迷你播放条（v0.8.0）。
 *
 * - **Haze 毛玻璃**：backdrop blur + 半透明 tint + 细边框（API31+；低版本/软件渲染 fallback 高不透明度）；
 *   展开/收起过渡中保持同一材质（v0.59.3 起不再中途切成不透明底）；
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
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    /** 只让内部文字/按钮淡出，卡片背景（Haze/边框）保留到被 PlayerSurface 覆盖。 */
    contentAlpha: Float = 1f,
    dragModifier: Modifier = Modifier,
    /** 供 AppNavHost 做共享元素（连续展开）定位用：迷你条/封面/标题/艺术家在窗口中的位置。 */
    onBarBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onArtistBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onPlayBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val track = state.currentTrack
    AnimatedVisibility(
        visible = track != null,
        // v0.60.0：出现/消失在 iOS 式弹簧上做（出现带一点点落位回弹，消失保持加速离场）。
        enter = slideInVertically(
            animationSpec = BiliMotion.springSnappy(),
        ) { it / 2 } + fadeIn(
            animationSpec = BiliMotion.springSnappy(),
        ),
        exit = slideOutVertically(
            animationSpec = tween(
                durationMillis = BiliMotion.MiniPlayerExitMs,
                easing = BiliMotion.EmphasizedAccelerate,
            ),
        ) { it / 2 } + fadeOut(
            animationSpec = tween(
                durationMillis = BiliMotion.MiniPlayerExitMs,
                easing = BiliMotion.EmphasizedAccelerate,
            ),
        ),
        modifier = modifier,
    ) {
        if (track == null) return@AnimatedVisibility
        val cover = state.queue.getOrNull(state.currentIndex)?.coverModel()
        // v0.38 起迷你条只作为「连续展开 morph 层」的起点，不再做自身的拉伸；
        // 展开过程由 AppLayerHost 的 PlayerSheetLayer 完成。
        val coverSize = 46.dp
        val contentVerticalPadding = Spacing.sm + 2.dp
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
        // v0.59.3：材质统一为毛玻璃（展开/收起过渡中不再切成不透明底），
        // 否则从播放页收回到迷你条时会在最后一刻出现「不透明 → 模糊」的突变。
        // 过渡中迷你条始终位于播放页表面之下或被同形状的表面背景覆盖，毛玻璃不会透出残影。
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

        // 维测：迷你条毛玻璃/降级模式（每次内容组合记一次，排查“模糊失效/变成半透明”）。
        LaunchedEffect(Unit) {
            val mode = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "translucent(api<31)"
                hazeState == null -> "translucent(no haze source)"
                else -> "blur"
            }
            Diag.log(DiagTag.SURFACE, "miniBar glass=$mode api=${Build.VERSION.SDK_INT}")
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                .clip(shape)
                .then(glassModifier)
                .border(1.dp, onContainer.copy(alpha = 0.06f), shape)
                .then(dragModifier)
                .biliClickable(onClick = onClick, pressedScale = 0.985f)
                .onGloballyPositioned { onBarBounds(it.boundsInWindow()) },
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = contentVerticalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(
                        url = cover,
                        modifier = Modifier
                            .size(coverSize)
                            .onGloballyPositioned { onCoverBounds(it.boundsInWindow()) },
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
                            modifier = Modifier.onGloballyPositioned { onTitleBounds(it.boundsInWindow()) },
                        )
                        Text(
                            text = state.queue.getOrNull(state.currentIndex)?.artist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned { onArtistBounds(it.boundsInWindow()) },
                        )
                    }
                    BiliIconButton(onClick = onPrevious) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "上一首",
                            tint = onContainer.copy(alpha = 0.86f),
                        )
                    }
                    BiliFilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(40.dp)
                            .onGloballyPositioned { onPlayBounds(it.boundsInWindow()) },
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
                    BiliIconButton(onClick = onNext) {
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
