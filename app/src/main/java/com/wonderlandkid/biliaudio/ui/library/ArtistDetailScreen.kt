package com.wonderlandkid.biliaudio.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.AlbumCard
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.ListBottomSpacer
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.SectionHeader
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 艺术家详情页：头部封面（第一张专辑）+ 名称 + 统计；内容为该艺术家的专辑与歌曲。
 *
 * - 专辑点击 → [onOpenAlbum]（跳专辑详情）；
 * - 歌曲点击 → [PlaybackController.playTrack]（队列 = 该曲所属专辑分曲）。
 *
 * 说明：本页是全屏页，系统栏 inset 与外层 AppNavHost 的 Scaffold `innerPadding` 约定一致，
 * 页内 TopAppBar 不再重复加 inset。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    /** 艺术家名（来自音乐库「艺术家」Tab） */
    artistName: String,
    /** 顶部返回 */
    onBack: () -> Unit,
    /** 点击专辑（跳专辑详情） */
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistDetailViewModel = appViewModel { container ->
        ArtistDetailViewModel(
            artistName = artistName,
            albumRepository = container.albumRepository,
            playbackController = container.playbackController,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                // 系统栏 inset 由外层 AppNavHost 的 Scaffold 统一处理
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(modifier = Modifier.padding(innerPadding))
            state.albums.isEmpty() -> EmptyState(
                title = "没有找到艺术家",
                description = "该艺术家的专辑可能已被删除",
                modifier = Modifier.padding(innerPadding),
                actionLabel = "返回",
                onAction = onBack,
            )

            else -> ArtistDetailContent(
                state = state,
                onOpenAlbum = onOpenAlbum,
                onPlaySong = viewModel::playSong,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ArtistDetailContent(
    state: ArtistDetailUiState,
    onOpenAlbum: (Long) -> Unit,
    onPlaySong: (TrackWithAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albumRows = state.albums.chunked(2)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item(key = "hero") { ArtistHero(state = state) }
        item(key = "albumsHeader") {
            SectionHeader(title = "专辑", subtitle = "${state.albums.size} 张")
        }
        items(albumRows, key = { row -> "artistAlbumRow_${row.first().id}" }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                row.forEach { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onOpenAlbum(album.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < 2) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        item(key = "songsHeader") {
            SectionHeader(title = "歌曲", subtitle = "${state.songs.size} 首")
        }
        if (state.songs.isEmpty()) {
            item(key = "songsEmpty") {
                EmptyState(
                    title = "还没有歌曲",
                    description = "该艺术家的专辑尚未分割或暂无可播放曲目",
                )
            }
        } else {
            itemsIndexed(state.songs, key = { _, song -> song.track.id }) { index, song ->
                TrackRow(
                    title = song.track.title,
                    subtitle = "${song.album.title} · ${TimeUtils.formatDuration(song.track.durationMs)}",
                    durationMs = null,
                    onClick = { onPlaySong(song) },
                    index = index,
                    isCurrent = song.track.id == state.currentTrackId,
                    isPlaying = state.isPlaying,
                    leading = {
                        CoverImage(
                            url = song.album.coverModel(),
                            modifier = Modifier.size(48.dp),
                            cornerRadius = 12.dp,
                        )
                    },
                )
            }
        }
        item(key = "bottomSpace") { ListBottomSpacer(extra = 24) }
    }
}

/** 头部：第一张专辑封面 + 艺术家名 + 统计。 */
@Composable
private fun ArtistHero(state: ArtistDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverImage(
            url = state.firstAlbum?.coverModel(),
            modifier = Modifier
                .fillMaxWidth(0.38f)
                .aspectRatio(1f),
            // 圆形头像效果：圆角取宽度一半
            cornerRadius = 999.dp,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = state.artistName,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = state.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
