package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 底部悬浮迷你播放条（位于底部导航之上）。
 */
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack
    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically { it / 2 },
        exit = slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        if (track == null) return@AnimatedVisibility
        val cover = state.queue.getOrNull(state.currentIndex)?.coverUrl
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .clickable(onClick = onOpenPlayer),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column {
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
                    androidx.compose.material3.IconButton(onClick = onNext) {
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
    }
}
