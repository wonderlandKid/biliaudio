package com.wonderlandkid.biliaudio.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.AlbumCard
import com.wonderlandkid.biliaudio.ui.components.BiliPageHeader
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.PageContentTopPadding
import com.wonderlandkid.biliaudio.ui.components.SearchField
import com.wonderlandkid.biliaudio.ui.components.SongRow
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 音乐库页（v0.6.0 改版）：
 * - 三分类 Tab：歌曲 / 专辑 / 艺术家；
 * - 每个分类按「# / A..Z」分组（中文按拼音），右侧快速索引栏支持点击/拖动定位；
 * - 页头与发现/设置统一用 [BiliPageHeader]；
 * - 搜索时三个 Tab 都按仓库搜索结果展示，并隐藏索引栏。
 *
 * 说明：本页是底部 Tab 页，系统栏 inset 由 AppNavHost 的 Scaffold `innerPadding` 统一处理。
 */
@Composable
fun LibraryScreen(
    /** 点击专辑（跳转专辑详情） */
    onOpenAlbum: (Long) -> Unit,
    /** 点击艺术家（跳转艺术家详情，由 Lead 接路由） */
    onOpenArtist: (String) -> Unit = {},
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
        onOpenArtist = onOpenArtist,
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
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PageContentTopPadding),
        ) {
            BiliPageHeader(title = "音乐库", subtitle = state.headerSubtitle)
            Spacer(Modifier.height(Spacing.lg))
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(horizontal = Spacing.lg),
                placeholder = "搜索歌曲、专辑、艺术家",
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        PrimaryTabRow(
            selectedTabIndex = state.tab.ordinal,
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
            Tab(
                selected = state.tab == LibraryTab.ARTISTS,
                onClick = { onSelectTab(LibraryTab.ARTISTS) },
                text = { Text("艺术家 ${state.artists.size}") },
            )
        }
        when {
            state.isLoading -> LoadingBox(modifier = Modifier.padding(top = Spacing.xxl))
            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                description = state.errorMessage,
                modifier = Modifier.padding(top = Spacing.xl),
            )

            state.tab == LibraryTab.SONGS -> SongsTab(
                songs = state.songs,
                isSearchActive = state.isSearchActive,
                query = state.query,
                currentTrackId = state.currentTrackId,
                isPlaying = state.isPlaying,
                onPlaySong = onPlaySong,
                onPlayAll = onPlayAll,
                onClearSearch = onClearQuery,
            )

            state.tab == LibraryTab.ALBUMS -> AlbumsTab(
                albums = state.albums,
                isSearchActive = state.isSearchActive,
                query = state.query,
                onOpenAlbum = onOpenAlbum,
                onClearSearch = onClearQuery,
            )

            else -> ArtistsTab(
                artists = state.artists,
                isSearchActive = state.isSearchActive,
                query = state.query,
                onOpenArtist = onOpenArtist,
                onClearSearch = onClearQuery,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 歌曲 Tab
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongsTab(
    songs: List<TrackWithAlbum>,
    isSearchActive: Boolean,
    query: String,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onPlaySong: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onClearSearch: () -> Unit,
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
        return
    }

    // 分组会打乱顺序，播放队列仍按 VM 的原始 songs 顺序 → 用 id 映射回原下标
    val indexById = remember(songs) {
        songs.withIndex().associate { (index, song) -> song.track.id to index }
    }
    val sections = remember(songs) { LetterIndex.sections(songs) { song -> song.track.title } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showIndexBar = !isSearchActive
    val prefixItems = 1 // 顶部「全部歌曲 / 播放全部歌曲」行
    // 索引栏点击 → 滚动到分组；remember 稳定引用，避免父级重组时反复创建
    val onSelectLetter = remember(sections, listState) {
        { label: String ->
            scope.launch { listState.animateScrollToItem(sections.scrollTargetFor(label, prefixItems)) }
            Unit
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = Spacing.lg),
        ) {
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
                        Text("播放全部歌曲")
                    }
                }
            }
            sections.forEach { section ->
                stickyHeader(
                    key = "songs_letter_${section.label}",
                    contentType = "letterHeader",
                ) {
                    LetterHeader(label = section.label, count = section.items.size)
                }
                itemsIndexed(
                    items = section.items,
                    key = { _, song -> song.track.id },
                    contentType = { _, _ -> "song" },
                ) { _, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(indexById[song.track.id] ?: 0) },
                        isCurrent = song.track.id == currentTrackId,
                        isPlaying = isPlaying,
                    )
                }
            }
        }
        if (showIndexBar) {
            AlphabetIndexBar(
                labels = LetterIndex.ALL_LABELS,
                onSelect = onSelectLetter,
                // 在索引栏内部读取首位可见下标 → 只在索引栏内部重组
                activeLabelProvider = { sections.labelAt(listState.firstVisibleItemIndex, prefixItems) },
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = Spacing.sm, bottom = Spacing.lg),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 专辑 Tab
// ---------------------------------------------------------------------------

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    isSearchActive: Boolean,
    query: String,
    onOpenAlbum: (Long) -> Unit,
    onClearSearch: () -> Unit,
) {
    if (albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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

    val sections = remember(albums) { LetterIndex.sections(albums) { album -> album.title } }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val showIndexBar = !isSearchActive
    val onSelectLetter = remember(sections, gridState) {
        { label: String ->
            scope.launch { gridState.animateScrollToItem(sections.scrollTargetFor(label)) }
            Unit
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.sm,
                top = Spacing.md,
                bottom = Spacing.lg,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            sections.forEach { section ->
                item(
                    key = "albums_letter_${section.label}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "letterHeader",
                ) {
                    LetterHeader(
                        label = section.label,
                        count = section.items.size,
                        horizontalPadding = 0.dp,
                    )
                }
                gridItems(
                    items = section.items,
                    key = { album -> album.id },
                    contentType = { "album" },
                ) { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onOpenAlbum(album.id) },
                    )
                }
            }
        }
        if (showIndexBar) {
            AlphabetIndexBar(
                labels = LetterIndex.ALL_LABELS,
                onSelect = onSelectLetter,
                activeLabelProvider = { sections.labelAt(gridState.firstVisibleItemIndex) },
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = Spacing.sm, bottom = Spacing.lg),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 艺术家 Tab
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistsTab(
    artists: List<ArtistEntry>,
    isSearchActive: Boolean,
    query: String,
    onOpenArtist: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    if (artists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (isSearchActive) {
                EmptyState(
                    title = "没有找到「$query」相关的艺术家",
                    description = "换个关键词试试，或清空搜索查看全部艺术家",
                    actionLabel = "清空搜索",
                    onAction = onClearSearch,
                )
            } else {
                EmptyState(
                    title = "还没有艺术家",
                    description = "导入专辑后，这里会按专辑艺术家自动聚合",
                )
            }
        }
        return
    }

    val sections = remember(artists) { LetterIndex.sections(artists) { artist -> artist.name } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showIndexBar = !isSearchActive
    val onSelectLetter = remember(sections, listState) {
        { label: String ->
            scope.launch { listState.animateScrollToItem(sections.scrollTargetFor(label)) }
            Unit
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = Spacing.lg),
        ) {
            sections.forEach { section ->
                stickyHeader(
                    key = "artists_letter_${section.label}",
                    contentType = "letterHeader",
                ) {
                    LetterHeader(label = section.label, count = section.items.size)
                }
                items(
                    items = section.items,
                    key = { artist -> artist.name },
                    contentType = { "artist" },
                ) { artist ->
                    ArtistRow(artist = artist, onClick = { onOpenArtist(artist.name) })
                }
            }
        }
        if (showIndexBar) {
            AlphabetIndexBar(
                labels = LetterIndex.ALL_LABELS,
                onSelect = onSelectLetter,
                activeLabelProvider = { sections.labelAt(listState.firstVisibleItemIndex) },
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = Spacing.sm, bottom = Spacing.lg),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 共用小件
// ---------------------------------------------------------------------------

/** 字母分组标题（sticky header）：主色字母 + 数量。 */
@Composable
private fun LetterHeader(
    label: String,
    count: Int,
    horizontalPadding: Dp = Spacing.lg,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 艺术家行：封面取该艺术家第一张专辑，副标题「N 张专辑 · M 首歌曲」。 */
@Composable
private fun ArtistRow(
    artist: ArtistEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CoverImage(
            url = artist.firstAlbum.coverModel(),
            modifier = Modifier.size(52.dp),
            cornerRadius = 14.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
