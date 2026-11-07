package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 播放列表面板：当前队列（通常是当前专辑的全部分曲，也支持跨专辑搜索队列）。
 *
 * - 当前曲高亮 + 正在播放标识；
 * - 点击任意曲目跳转（[PlaybackController.jumpToQueueIndex]）；
 * - 由「正在播放」页用 ModalBottomSheet 承载。
 */
@Composable
fun PlaylistPanel(
    state: PlaybackState,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentEntry = state.queue.getOrNull(state.currentIndex)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "播放列表",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(state.queue.size)
                        append(" 首")
                        currentEntry?.albumTitle?.takeIf { it.isNotBlank() }?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        if (state.queue.isEmpty()) {
            Text(
                text = "播放列表为空",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(
                    items = state.queue,
                    key = { _, entry -> entry.track.id },
                ) { index, entry ->
                    TrackRow(
                        title = entry.track.title,
                        subtitle = buildString {
                            if (entry.artist.isNotBlank()) append(entry.artist)
                            if (entry.albumTitle.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(entry.albumTitle)
                            }
                        },
                        durationMs = entry.durationMs,
                        onClick = { onSelectIndex(index) },
                        index = index,
                        isCurrent = index == state.currentIndex,
                        isPlaying = state.isPlaying,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
    }
}
