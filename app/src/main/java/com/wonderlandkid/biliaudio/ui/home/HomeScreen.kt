package com.wonderlandkid.biliaudio.ui.home

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.HomeStatsMode
import com.wonderlandkid.biliaudio.data.model.Playlist
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.BiliAlbumRow
import com.wonderlandkid.biliaudio.ui.components.BiliGroupItem
import com.wonderlandkid.biliaudio.ui.components.BiliPageHeader
import com.wonderlandkid.biliaudio.ui.components.BiliSection
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.ui.theme.entrance
import com.wonderlandkid.biliaudio.util.TimeUtils
import com.wonderlandkid.biliaudio.ui.components.BiliTextButton

/**
 * 发现页。
 *
 * 结构：标题 + 未登录提示 + 四个只露前四的听歌区块：
 * 随机专辑 / 我的歌单 / 听歌统计 / 最近播放；详情统一从区块右上角进入。
 * 搜索只保留在音乐库，发现页不再重复放搜索栏。
 */
@Composable
fun HomeScreen(
    onOpenAlbum: (Long) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenStats: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    modifier: Modifier = Modifier,
    /** 由 AppNavHost 持有：从发现页进入详情再返回时保持滚动位置。 */
    listState: LazyListState = rememberLazyListState(),
    viewModel: HomeViewModel = appViewModel { container ->
        HomeViewModel(
            albumRepository = container.albumRepository,
            authRepository = container.authRepository,
            playlistRepository = container.playlistRepository,
            statsRepository = container.playbackStatsRepository,
            settingsRepository = container.settingsRepository,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onOpenAlbum = onOpenAlbum,
        onOpenLogin = onOpenLogin,
        onOpenImport = onOpenImport,
        onOpenPlaylists = onOpenPlaylists,
        onOpenPlaylist = onOpenPlaylist,
        onOpenStats = onOpenStats,
        onOpenRecentAlbums = onOpenRecentAlbums,
        onShuffleAlbums = viewModel::shuffleRandomAlbums,
        onStatsModeChange = viewModel::setHomeStatsMode,
        listState = listState,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenAlbum: (Long) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenStats: () -> Unit,
    onOpenRecentAlbums: () -> Unit,
    onShuffleAlbums: () -> Unit,
    onStatsModeChange: (HomeStatsMode) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val miniPlayerInset = com.wonderlandkid.biliaudio.ui.navigation.LocalMiniPlayerContentInset.current
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // 迷你条可见时页面内容从其后面滚过，底部留出迷你条高度，滚到底最后一项不会被挡住。
        contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.lg + miniPlayerInset),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") { HomeHeader() }
        if (!state.isLoggedIn) {
            item(key = "loginBanner") { LoginBanner(onOpenLogin = onOpenLogin) }
        }
        when {
            state.isLoading -> item(key = "loading") {
                LoadingBox(modifier = Modifier.padding(vertical = Spacing.xxl))
            }

            state.errorMessage != null -> item(key = "error") {
                EmptyState(
                    title = "加载失败",
                    description = state.errorMessage,
                    modifier = Modifier.padding(vertical = Spacing.xl),
                )
            }

            else -> {
                item(key = "recent") {
                    Box(modifier = Modifier.entrance(order = 0)) {
                        RecentPreviewSection(
                            albums = state.recentAlbums,
                            onOpenAlbum = onOpenAlbum,
                            onOpenAll = onOpenRecentAlbums,
                        )
                    }
                }
                item(key = "stats") {
                    Box(modifier = Modifier.entrance(order = 1)) {
                        StatsPreviewSection(
                            mode = state.statsMode,
                            albums = state.statsAlbums,
                            tracks = state.statsTracks,
                            onModeChange = onStatsModeChange,
                            onOpenAlbum = onOpenAlbum,
                            onOpenAll = onOpenStats,
                        )
                    }
                }
                item(key = "playlists") {
                    Box(modifier = Modifier.entrance(order = 2)) {
                        PlaylistsPreviewSection(
                            playlists = state.playlists,
                            totalCount = state.playlistCount,
                            onOpenPlaylist = onOpenPlaylist,
                            onOpenAll = onOpenPlaylists,
                        )
                    }
                }
                item(key = "random") {
                    Box(modifier = Modifier.entrance(order = 3)) {
                        RandomAlbumsSection(
                            albums = state.randomAlbums,
                            onOpenAlbum = onOpenAlbum,
                            onShuffle = onShuffleAlbums,
                            onImport = onOpenImport,
                        )
                    }
                }
                item(key = "bottomSpace") {
                    Spacer(Modifier.height(Spacing.xxl))
                }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    BiliPageHeader(title = "发现")
}

@Composable
private fun RandomAlbumsSection(
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
    onShuffle: () -> Unit,
    onImport: () -> Unit,
) {
    BiliSection(
        title = "随机专辑",
        subtitle = if (albums.isEmpty()) "从音乐库随机挑选 4 张" else "从音乐库随机挑选 ${albums.size} 张",
        actions = {
            BiliTextButton(onClick = onShuffle, enabled = albums.isNotEmpty()) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xxs))
                Text("换一批")
            }
            BiliTextButton(onClick = onImport) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xxs))
                Text("导入")
            }
        },
    ) {
        if (albums.isEmpty()) {
            SectionEmptyRow("还没有专辑，导入一张开始随机推荐。", actionLabel = "导入专辑", onAction = onImport)
        } else {
            albums.take(4).forEach { album ->
                BiliAlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
            }
        }
    }
}

@Composable
private fun PlaylistsPreviewSection(
    playlists: List<Playlist>,
    totalCount: Int,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    BiliSection(
        title = "我的歌单",
        subtitle = if (totalCount > 0) "$totalCount 个歌单" else "自己整理播放列表",
        actions = {
            BiliTextButton(onClick = onOpenAll) { Text("查看全部") }
        },
    ) {
        if (playlists.isEmpty()) {
            SectionEmptyRow("还没有歌单，点「查看全部」新建。")
        } else {
            playlists.take(4).forEach { playlist ->
                BiliGroupItem(
                    title = playlist.name,
                    subtitle = "${playlist.trackCount} 首歌曲",
                    onClick = { onOpenPlaylist(playlist.id) },
                    leading = {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatsPreviewSection(
    mode: HomeStatsMode,
    albums: List<HomeStatsAlbum>,
    tracks: List<HomeStatsTrack>,
    onModeChange: (HomeStatsMode) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    BiliSection(
        title = "听歌统计",
        subtitle = when (mode) {
            HomeStatsMode.ALBUM_LISTEN_TIME -> "按专辑累计收听时长"
            HomeStatsMode.TRACK_PLAY_COUNT -> "按歌曲播放次数"
        },
        actions = {
            BiliTextButton(onClick = onOpenAll) { Text("查看全部") }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BiliLayout.groupItemHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FilterChip(
                selected = mode == HomeStatsMode.ALBUM_LISTEN_TIME,
                onClick = { onModeChange(HomeStatsMode.ALBUM_LISTEN_TIME) },
                label = { Text("专辑时长") },
            )
            FilterChip(
                selected = mode == HomeStatsMode.TRACK_PLAY_COUNT,
                onClick = { onModeChange(HomeStatsMode.TRACK_PLAY_COUNT) },
                label = { Text("歌曲次数") },
            )
        }
        when (mode) {
            HomeStatsMode.ALBUM_LISTEN_TIME -> {
                if (albums.isEmpty()) {
                    SectionEmptyRow("播放一些音乐后，这里会显示 Top 专辑。")
                } else {
                    albums.take(4).forEach { stat ->
                        BiliAlbumRow(
                            album = stat.album,
                            onClick = { onOpenAlbum(stat.album.id) },
                            trailingText = TimeUtils.formatHumanDuration(stat.listenedMs),
                        )
                    }
                }
            }

            HomeStatsMode.TRACK_PLAY_COUNT -> {
                if (tracks.isEmpty()) {
                    SectionEmptyRow("播放一些歌曲后，这里会显示 Top 歌曲。")
                } else {
                    tracks.take(4).forEach { stat ->
                        BiliGroupItem(
                            title = stat.song.track.title,
                            subtitle = "${stat.song.album.artist} · ${stat.song.album.title}",
                            onClick = { onOpenAlbum(stat.song.album.id) },
                            leading = {
                                CoverImage(
                                    url = stat.song.coverModel(),
                                    modifier = Modifier.size(48.dp),
                                    cornerRadius = 12.dp,
                                )
                            },
                            trailing = {
                                Text(
                                    text = "${stat.playCount} 次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPreviewSection(
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    BiliSection(
        title = "最近播放",
        subtitle = "最近听过的专辑",
        actions = {
            BiliTextButton(onClick = onOpenAll) { Text("查看全部") }
        },
    ) {
        if (albums.isEmpty()) {
            SectionEmptyRow("还没有最近播放记录。")
        } else {
            albums.take(4).forEach { album ->
                BiliAlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
            }
        }
    }
}

@Composable
private fun SectionEmptyRow(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiliLayout.groupItemHorizontal, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            BiliTextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 未登录提示条：点击去扫码登录。 */
@Composable
private fun LoginBanner(onOpenLogin: () -> Unit) {
    Surface(
        onClick = onOpenLogin,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "未登录 B 站账号", style = MaterialTheme.typography.titleSmall)
                Text(text = "登录后可解锁更高音质与画质", style = MaterialTheme.typography.bodySmall)
            }
            Text(text = "去登录", style = MaterialTheme.typography.labelLarge)
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
