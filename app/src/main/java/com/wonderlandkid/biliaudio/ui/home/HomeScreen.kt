package com.wonderlandkid.biliaudio.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Playlist
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.BiliGroup
import com.wonderlandkid.biliaudio.ui.components.BiliGroupItem
import com.wonderlandkid.biliaudio.ui.components.BiliPageHeader
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.ui.theme.entrance
import com.wonderlandkid.biliaudio.util.TimeUtils

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
    viewModel: HomeViewModel = appViewModel { container ->
        HomeViewModel(
            albumRepository = container.albumRepository,
            authRepository = container.authRepository,
            playlistRepository = container.playlistRepository,
            statsRepository = container.playbackStatsRepository,
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") { HomeHeader(statsText = state.statsText) }
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
                item(key = "random") {
                    Box(modifier = Modifier.entrance(order = 0)) {
                        RandomAlbumsSection(
                            albums = state.randomAlbums,
                            onOpenAlbum = onOpenAlbum,
                            onShuffle = onShuffleAlbums,
                            onImport = onOpenImport,
                        )
                    }
                }
                item(key = "playlists") {
                    Box(modifier = Modifier.entrance(order = 1)) {
                        PlaylistsPreviewSection(
                            playlists = state.playlists,
                            totalCount = state.playlistCount,
                            onOpenPlaylist = onOpenPlaylist,
                            onOpenAll = onOpenPlaylists,
                        )
                    }
                }
                item(key = "stats") {
                    Box(modifier = Modifier.entrance(order = 2)) {
                        StatsPreviewSection(
                            albums = state.statsAlbums,
                            onOpenAlbum = onOpenAlbum,
                            onOpenAll = onOpenStats,
                        )
                    }
                }
                item(key = "recent") {
                    Box(modifier = Modifier.entrance(order = 3)) {
                        RecentPreviewSection(
                            albums = state.recentAlbums,
                            onOpenAlbum = onOpenAlbum,
                            onOpenAll = onOpenRecentAlbums,
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
private fun HomeHeader(statsText: String) {
    BiliPageHeader(title = "发现", subtitle = statsText)
}

/** 四个区块共用的卡片：标题 + 右上角操作 + 最多四条预览。 */
@Composable
private fun HomeSectionCard(
    title: String,
    subtitle: String?,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    BiliGroup(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
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
            actions()
        }
        content()
    }
}

@Composable
private fun RandomAlbumsSection(
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
    onShuffle: () -> Unit,
    onImport: () -> Unit,
) {
    HomeSectionCard(
        title = "随机专辑",
        subtitle = if (albums.isEmpty()) "从音乐库随机挑选 4 张" else "从音乐库随机挑选 ${albums.size} 张",
        actions = {
            TextButton(onClick = onShuffle, enabled = albums.isNotEmpty()) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xxs))
                Text("换一批")
            }
            TextButton(onClick = onImport) {
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
                HomeAlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
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
    HomeSectionCard(
        title = "我的歌单",
        subtitle = if (totalCount > 0) "$totalCount 个歌单" else "自己整理播放列表",
        actions = {
            TextButton(onClick = onOpenAll) { Text("查看全部") }
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
    albums: List<HomeStatsAlbum>,
    onOpenAlbum: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    HomeSectionCard(
        title = "听歌统计",
        subtitle = "按专辑累计收听时长",
        actions = {
            TextButton(onClick = onOpenAll) { Text("查看全部") }
        },
    ) {
        if (albums.isEmpty()) {
            SectionEmptyRow("播放一些音乐后，这里会显示 Top 专辑。")
        } else {
            albums.take(4).forEach { stat ->
                HomeAlbumRow(
                    album = stat.album,
                    onClick = { onOpenAlbum(stat.album.id) },
                    trailingText = TimeUtils.formatHumanDuration(stat.listenedMs),
                )
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
    HomeSectionCard(
        title = "最近播放",
        subtitle = "最近听过的专辑",
        actions = {
            TextButton(onClick = onOpenAll) { Text("查看全部") }
        },
    ) {
        if (albums.isEmpty()) {
            SectionEmptyRow("还没有最近播放记录。")
        } else {
            albums.take(4).forEach { album ->
                HomeAlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
            }
        }
    }
}

@Composable
private fun HomeAlbumRow(
    album: Album,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    BiliGroupItem(
        title = album.title,
        subtitle = album.artist,
        onClick = onClick,
        leading = {
            CoverImage(
                url = album.coverModel(),
                modifier = Modifier.size(48.dp),
                cornerRadius = 12.dp,
            )
        },
        trailing = if (trailingText != null) {
            {
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    )
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
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
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
