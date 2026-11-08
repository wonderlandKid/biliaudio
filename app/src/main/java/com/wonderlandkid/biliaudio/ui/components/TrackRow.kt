package com.wonderlandkid.biliaudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 歌曲行。用于专辑详情、音乐库列表、搜索结果。
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String?,
    durationMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor =
        if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when {
            leading != null -> leading()
            isCurrent -> PlayingIndicator(isPlaying = isPlaying)
            index != null -> Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            else -> Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (durationMs != null) {
            Text(
                text = TimeUtils.formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 正在播放的均衡器指示器：
 * - 播放中：三根柱子错峰起伏；
 * - 暂停：保留静态柱，避免暂停后当前曲目突然消失成序号（列表里“看不出在播哪首”）。
 */
@Composable
private fun PlayingIndicator(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    if (!isPlaying) {
        EqualizerBars(
            color = color.copy(alpha = 0.7f),
            heights = listOf(0.42f, 0.65f, 0.5f),
            modifier = modifier,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "playingIndicator")
    val h1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar1",
    )
    val h2 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar2",
    )
    val h3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar3",
    )
    EqualizerBars(color = color, heights = listOf(h1, h2, h3), modifier = modifier)
}

@Composable
private fun EqualizerBars(
    color: Color,
    heights: List<Float>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .size(20.dp)
            .semantics { contentDescription = "正在播放" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction.coerceIn(0.15f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** 音乐库中的歌曲行：显示所属专辑 + 时长。 */
@Composable
fun SongRow(
    song: TrackWithAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    TrackRow(
        title = song.track.title,
        subtitle = song.album.artist + " · " + song.album.title,
        durationMs = song.track.durationMs,
        onClick = onClick,
        modifier = modifier,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        leading = {
            CoverImage(
                url = song.coverModel(),
                modifier = Modifier.size(48.dp),
                cornerRadius = 12.dp,
            )
        },
    )
}

/** 正在播放的小徽标（专辑详情等页面用）。 */
@Composable
fun NowPlayingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "正在播放",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 列表顶部/底部的留白，避免被 mini player / 导航栏遮挡。 */
@Composable
fun ListBottomSpacer(extra: Int = 96, color: Color = Color.Transparent) {
    Box(modifier = Modifier.height(extra.dp).fillMaxWidth())
}
