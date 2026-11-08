package com.wonderlandkid.biliaudio.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.launch

/**
 * 底部悬浮迷你播放条（v0.7.0）。
 *
 * - **毛玻璃质感**：Haze backdrop blur + 半透明 surface 色 + 细边框高光（API 31+；低版本回退为半透明底）；
 * - **无拖拽提示条**：不再显示顶部 handle，直接拖动整条；
 * - **跟手拖拽**：向上拖动时整条随手指连续上移（无级跟随），松手后：
 *   - 上移超过阈值/向上快速滑动 → 打开正在播放页；
 *   - 向下拖 → 收起为细条；收起态向上/点击 → 展开；
 *   - 未达阈值 → 弹簧回位；
 * - 底部进度线改为低对比度（不再是一条亮线）。
 *
 * @param collapsed 是否收起为细条
 * @param hazeState Haze 背景采样状态（null 时退化为普通半透明底）
 */
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val track = state.currentTrack
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sizeSpring = BiliMotion.standardSpatialSpring<IntSize>()
    val maxUpPx = with(density) { 180.dp.toPx() }
    val maxDownPx = with(density) { 44.dp.toPx() }
    val openThresholdPx = with(density) { 72.dp.toPx() }
    val collapseThresholdPx = with(density) { 40.dp.toPx() }
    val velocityThresholdPx = with(density) { 700.dp.toPx() }

    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun settleToZero() {
        settleJob?.cancel()
        settleJob = scope.launch {
            Animatable(dragOffsetPx).animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
            ) { dragOffsetPx = value }
        }
    }

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
        val shape = RoundedCornerShape(if (collapsed) 18.dp else 24.dp)
        val containerColor = MaterialTheme.colorScheme.surfaceContainer
        val onContainer = MaterialTheme.colorScheme.onSurface
        val blurStyle = remember(containerColor) {
            val glassTint = HazeColorEffect.tint(
                color = containerColor.copy(alpha = 0.30f),
                blendMode = BlendMode.SrcOver,
            )
            // 不支持 RenderEffect 的设备（或软件渲染）回退时用更高不透明度保证可读性
            val fallbackTint = HazeColorEffect.tint(
                color = containerColor.copy(alpha = 0.80f),
                blendMode = BlendMode.SrcOver,
            )
            HazeBlurStyle {
                blurRadius(22.dp)
                backgroundColor(containerColor.copy(alpha = 0.28f))
                colorEffects(listOf(glassTint))
                fallbackColorEffect(fallbackTint)
                noiseFactor(0.03f)
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
                // 低版本/无 Haze：半透明磨砂底，保证可读性
                Modifier.background(containerColor.copy(alpha = 0.94f))
            }

        val draggableState = rememberDraggableState { delta ->
            dragOffsetPx = (dragOffsetPx + delta).coerceIn(-maxUpPx, maxDownPx)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = if (collapsed) 2.dp else Spacing.xs)
                .graphicsLayer {
                    translationY = dragOffsetPx
                    val progress = (-dragOffsetPx / maxUpPx).coerceIn(0f, 1f)
                    scaleX = 1f + 0.02f * progress
                    scaleY = 1f + 0.02f * progress
                }
                .animateContentSize(animationSpec = sizeSpring)
                .clip(shape)
                .then(glassModifier)
                .border(1.dp, onContainer.copy(alpha = 0.10f), shape)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = {
                        settleJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        val expandFromCollapsed =
                            dragOffsetPx <= -collapseThresholdPx || velocity <= -velocityThresholdPx
                        val openFromExpanded =
                            dragOffsetPx <= -openThresholdPx || velocity <= -velocityThresholdPx
                        val collapseFromExpanded =
                            dragOffsetPx >= collapseThresholdPx || velocity >= velocityThresholdPx
                        when {
                            collapsed && expandFromCollapsed -> {
                                // 收起态：上滑/向上快速滑动 → 展开
                                onCollapsedChange(false)
                                dragOffsetPx = 0f
                            }
                            !collapsed && openFromExpanded -> {
                                dragOffsetPx = 0f
                                onOpenPlayer()
                            }
                            !collapsed && collapseFromExpanded -> {
                                dragOffsetPx = 0f
                                onCollapsedChange(true)
                            }
                            else -> settleToZero()
                        }
                    },
                )
                .clickable {
                    if (collapsed) {
                        onCollapsedChange(false)
                    } else {
                        onOpenPlayer()
                    }
                },
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            if (collapsed) {
                CollapsedContent(trackTitle = track.title, contentColor = onContainer)
            } else {
                ExpandedContent(
                    state = state,
                    cover = cover,
                    contentColor = onContainer,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                )
            }
        }
    }
}

/** 收起态：细条（无拖拽提示条），点击/上滑展开。 */
@Composable
private fun CollapsedContent(trackTitle: String, contentColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = trackTitle,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 展开态：封面/标题/播放控制 + 低对比度进度线（无 handle）。 */
@Composable
private fun ExpandedContent(
    state: PlaybackState,
    cover: Any?,
    contentColor: Color,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val track = state.currentTrack ?: return
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
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.queue.getOrNull(state.currentIndex)?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.66f),
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
                    tint = contentColor.copy(alpha = 0.86f),
                )
            }
        }
        // 低对比度进度线（不再是一条亮线）
        val progress =
            if (state.trackDurationMs > 0) {
                (state.positionMs.toFloat() / state.trackDurationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(contentColor.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(contentColor.copy(alpha = 0.42f)),
            )
        }
    }
}
