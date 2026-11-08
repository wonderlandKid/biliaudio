package com.wonderlandkid.biliaudio.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/** 听歌统计（发现 → 听歌统计）。 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: StatsViewModel = appViewModel { container ->
        StatsViewModel(
            albumRepository = container.albumRepository,
            statsRepository = container.playbackStatsRepository,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

    BiliDetailScaffold(
        onBack = onBack,
        title = "听歌统计",
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StatsSummaryCard(
                totalListenedMs = state.totalListenedMs,
                totalPlayCount = state.totalPlayCount,
            )
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("专辑时长") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("歌曲次数") },
                )
            }
            when {
                state.isLoading -> LoadingBox(modifier = Modifier.fillMaxSize())
                selectedTab == 0 -> AlbumStatsList(state.albumStats)
                else -> TrackStatsList(state.trackStats)
            }
        }
    }
}

@Composable
private fun StatsSummaryCard(totalListenedMs: Long, totalPlayCount: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        shape = RoundedCornerShape(Dimens.cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "累计收听",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = TimeUtils.formatHumanDuration(totalListenedMs),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column {
                Text(
                    text = "累计播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$totalPlayCount 次",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun AlbumStatsList(stats: List<AlbumListeningUi>) {
    if (stats.isEmpty()) {
        EmptyState(
            title = "还没有听歌数据",
            description = "播放一些专辑后，这里会按累计收听时长排行。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val maxListened = stats.maxOf { it.listenedMs }.coerceAtLeast(1L)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        itemsIndexed(stats, key = { _, item -> item.album.id }) { index, item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.cardCorner),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp),
                    )
                    CoverImage(
                        url = item.album.coverModel(),
                        modifier = Modifier.size(52.dp),
                        cornerRadius = 12.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.album.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        LinearProgressIndicator(
                            progress = { (item.listenedMs.toFloat() / maxListened).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                    }
                    Text(
                        text = TimeUtils.formatHumanDuration(item.listenedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackStatsList(stats: List<TrackPlayUi>) {
    if (stats.isEmpty()) {
        EmptyState(
            title = "还没有听歌数据",
            description = "播放一些歌曲后，这里会按播放次数排行。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        itemsIndexed(stats, key = { _, item -> item.song.track.uid }) { index, item ->
            TrackRow(
                title = item.song.track.title,
                subtitle = "${item.song.album.artist} · ${item.song.album.title}",
                durationMs = item.song.track.durationMs,
                onClick = {},
                index = index,
                leading = {
                    CoverImage(
                        url = item.song.coverModel(),
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 12.dp,
                    )
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${item.playCount} 次",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = TimeUtils.formatHumanDuration(item.listenedMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}
