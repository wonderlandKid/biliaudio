package com.wonderlandkid.biliaudio.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.AlbumCard
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.ListBottomSpacer
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.SearchField
import com.wonderlandkid.biliaudio.ui.components.SongRow
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 音乐库页：大标题 + 搜索框 +「歌曲 / 专辑」Tab。
 *
 * - 歌曲列表点击 → [com.wonderlandkid.biliaudio.player.PlaybackController.playSongs]；
 * - 专辑网格点击 → 专辑详情；
 * - 搜索防抖走仓库接口；空状态/加载态/错误态完整。
 *
 * 说明：本页是底部 Tab 页，系统栏 inset 由 AppNavHost 的 Scaffold `innerPadding` 统一处理。
 */
@Composable
fun LibraryScreen(
    /** 点击专辑（跳转专辑详情） */
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = appViewModel { container ->
        LibraryViewModel(container.albumRepository, container.playbackController)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onSelectTab = viewModel::selectTab,
        onPlaySong = viewModel::playSong,
        onPlayAll = viewModel::playAllSongs,
        onOpenAlbum = onOpenAlbum,
        modifier = modifier,
    )
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSelectTab: (LibraryTab) -> Unit,
    onPlaySong: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        LibraryHeader(
            state = state,
            onQueryChange = onQueryChange,
        )
        TabRow(
            selectedTabIndex = if (state.tab == LibraryTab.SONGS) 0 else 1,
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
        ) {
            Tab(
                selected = state.tab == LibraryTab.SONGS,
                onClick = { onSelectTab(LibraryTab.SONGS) },
                text = { Text("歌曲 ${state.songs.size}") },
            )
            Tab(
                selected = state.tab == LibraryTab.ALBUMS,
                onClick = { onSelectTab(LibraryTab.ALBUMS) },
                text = { Text("专辑 ${state.albums.size}") },
            )
        }
        when {
            state.isLoading -> LoadingBox(modifier = Modifier.padding(top = Spacing.xxl))
            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                description = state.errorMessage,
                modifier = Modifier.padding(top = Spacing.xl),
            )

            state.tab == LibraryTab.SONGS -> SongsList(
                songs = state.songs,
                isSearchActive = state.isSearchActive,
                query = state.query,
                currentTrackId = state.currentTrackId,
                isPlaying = state.isPlaying,
                onPlaySong = onPlaySong,
                onPlayAll = onPlayAll,
                onClearSearch = onClearQuery,
            )

            else -> AlbumsGrid(
                albums = state.albums,
                isSearchActive = state.isSearchActive,
                query = state.query,
                onOpenAlbum = onOpenAlbum,
                onClearSearch = onClearQuery,
            )
        }
    }
}

/** 大标题 + 统计行 + 搜索框。 */
@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
    ) {
        Text(
            text = "音乐库",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = if (state.isLoading) "正在加载音乐库…" else "${state.songsCountText} · ${state.albumsCountText}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            placeholder = "搜索歌曲、专辑、艺术家",
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

/** 歌曲 Tab：列表 + 播放全部。 */
@Composable
private fun SongsList(
    songs: List<TrackWithAlbum>,
    isSearchActive: Boolean,
    query: String,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onPlaySong: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onClearSearch: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.lg),
    ) {
        if (songs.isNotEmpty()) {
            item(key = "songsHeader") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isSearchActive) "搜索结果 ${songs.size} 首" else "全部歌曲 ${songs.size} 首",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPlayAll) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("播放全部")
                    }
                }
            }
        }
        itemsIndexed(songs, key = { _, song -> song.track.id }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlaySong(index) },
                isCurrent = song.track.id == currentTrackId,
                isPlaying = isPlaying,
            )
        }
        if (songs.isEmpty()) {
            item(key = "songsEmpty") {
                if (isSearchActive) {
                    EmptyState(
                        title = "没有找到「$query」相关的歌曲",
                        description = "换个关键词试试，或清空搜索查看全部歌曲",
                        actionLabel = "清空搜索",
                        onAction = onClearSearch,
                    )
                } else {
                    EmptyState(
                        title = "音乐库还没有歌曲",
                        description = "去发现页导入一张专辑，就能按歌曲收听整段音频",
                    )
                }
            }
        }
        item(key = "songsBottomSpace") { ListBottomSpacer(extra = 32) }
    }
}

/** 专辑 Tab：两列网格。 */
@Composable
private fun AlbumsGrid(
    albums: List<Album>,
    isSearchActive: Boolean,
    query: String,
    onOpenAlbum: (Long) -> Unit,
    onClearSearch: () -> Unit,
) {
    if (albums.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (isSearchActive) {
                EmptyState(
                    title = "没有找到「$query」相关的专辑",
                    description = "换个关键词试试，或清空搜索查看全部专辑",
                    actionLabel = "清空搜索",
                    onAction = onClearSearch,
                )
            } else {
                EmptyState(
                    title = "音乐库还没有专辑",
                    description = "去发现页导入一张 B 站专辑吧",
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.md,
            bottom = Spacing.lg,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        items(albums, key = { album -> album.id }) { album ->
            AlbumCard(
                album = album,
                onClick = { onOpenAlbum(album.id) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ListBottomSpacer(extra = 24)
        }
    }
}
