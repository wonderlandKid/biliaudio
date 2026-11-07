package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 底部悬浮迷你播放条（位于底部导航之上）。
 *
 * 手势（v0.6.0）：
 * - 展开态：**上滑 → 打开正在播放页**；**下滑 → 收起为细条**；
 * - 收起态：点击或上滑 → 重新展开；
 * - 点击展开态 → 打开正在播放页（保留原有交互）。
 *
 * 收起状态由 AppNavHost 持有（`rememberSaveable`），Scaffold 的 bottomBar 高度会随动画变化，
 * 让音乐库/发现等页面内容自动让出空间。
 *
 * @param collapsed 是否收起为细条
 * @param onCollapsedChange 收起/展开回调
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
) {
    val track = state.currentTrack
    // M3 Expressive motion physics：尺寸变化走官方 Standard spatial 弹簧
    val sizeSpring = BiliMotion.standardSpatialSpring<IntSize>()
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
        val thresholdPx = with(LocalDensity.current) { DRAG_THRESHOLD.toPx() }
        var dragAccumulated by remember { mutableFloatStateOf(0f) }

        val dragGesture = Modifier.pointerInput(collapsed) {
            detectVerticalDragGestures(
                onDragStart = { dragAccumulated = 0f },
                onDragCancel = { dragAccumulated = 0f },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    dragAccumulated += dragAmount
                },
                onDragEnd = {
                    val accumulated = dragAccumulated
                    dragAccumulated = 0f
                    when {
                        accumulated <= -thresholdPx && collapsed -> onCollapsedChange(false)
                        accumulated <= -thresholdPx -> onOpenPlayer()
                        accumulated >= thresholdPx && !collapsed -> onCollapsedChange(true)
                    }
                },
            )
        }

        val baseModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = if (collapsed) 2.dp else Spacing.sm)

        Surface(
            modifier = baseModifier
                .animateContentSize(animationSpec = sizeSpring)
                .then(
                    if (collapsed) {
                        Modifier
                            .clickable { onCollapsedChange(false) }
                            .then(dragGesture)
                    } else {
                        Modifier
                            .clickable(onClick = onOpenPlayer)
                            .then(dragGesture)
                    },
                ),
            shape = RoundedCornerShape(if (collapsed) 16.dp else 22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (collapsed) 1.dp else 3.dp,
            shadowElevation = if (collapsed) 2.dp else 6.dp,
        ) {
            if (collapsed) {
                CollapsedContent(trackTitle = track.title)
            } else {
                ExpandedContent(
                    state = state,
                    cover = cover,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                )
            }
        }
    }
}

/** 顶部的拖动指示条（视觉提示可上下拖）。 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(36.dp)
            .height(4.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(50),
            ),
    )
}

/** 收起态：一条细条，点击/上滑展开。 */
@Composable
private fun CollapsedContent(trackTitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DragHandle()
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = trackTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 展开态：拖动条 + 封面/标题/播放控制 + 进度线。 */
@Composable
private fun ExpandedContent(
    state: PlaybackState,
    cover: Any?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val track = state.currentTrack ?: return
    Column {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DragHandle(modifier = Modifier.padding(top = 6.dp))
        }
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.queue.getOrNull(state.currentIndex)?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
            }
        }
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 拖动触发阈值。 */
private val DRAG_THRESHOLD = 64.dp
