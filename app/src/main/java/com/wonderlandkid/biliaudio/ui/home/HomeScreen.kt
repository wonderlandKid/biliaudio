package com.wonderlandkid.biliaudio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.HomeStatsMode
import com.wonderlandkid.biliaudio.data.model.Playlist
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.AlbumCard
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.ui.theme.entrance
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 发现页（v0.31.0 「杂志式」版式）。
 *
 * 用户希望发现页可以大胆一点、不要全是列表，因此在 Material 3 稳定基线之上，
 * 本页使用页面专属版式（页面内私有 Composable）：
 *
 * 1. 刊头：BILIAUDIO overline + 大号衬线「发现」+ 音乐库统计；
 * 2. 封面故事：最近播放第一张专辑作为大幅封面锚点（右侧轻微出血）；
 * 3. 最近播放：横向封面墙（LazyRow），不是列表；
 * 4. 听歌统计：横向榜单卡（大序号 + 封面 + 数据），可切「专辑时长 / 歌曲次数」；
 * 5. 我的歌单：横向卡片墙；
 * 6. 随机专辑：双列封面网格。
 *
 * 约束：只改页面内部版式；导航/转场/insets/底部导航/迷你条避让仍由 AppNavHost 统一处理，
 * 数据与全部回调保持原样。详见 docs/UI_ARCHITECTURE.md 的「页面壳自由度分级」。
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BiliLayout.pageBottom),
    ) {
        item(key = "masthead") {
            HomeMasthead(statsText = state.statsText)
        }
        if (!state.isLoggedIn) {
            item(key = "login") {
                Box(modifier = Modifier.entrance(order = 0)) {
                    LoginBanner(onOpenLogin = onOpenLogin)
                }
            }
        }
        when {
            state.isLoading -> item(key = "loading") {
                LoadingBox(modifier = Modifier.padding(vertical = Spacing.xxxl))
            }

            state.errorMessage != null -> item(key = "error") {
                EmptyState(
                    title = "加载失败",
                    description = state.errorMessage,
                    modifier = Modifier.padding(vertical = Spacing.xl),
                )
            }

            else -> {
                item(key = "hero") {
                    Box(modifier = Modifier.entrance(order = 0)) {
                        RecentHero(
                            album = state.recentAlbums.firstOrNull(),
                            onOpenAlbum = onOpenAlbum,
                            onOpenAll = onOpenRecentAlbums,
                            onImport = onOpenImport,
                        )
                    }
                }
                // 封面故事里已经展示最近播放第一张；「接着听」只放其余专辑，避免重复。
                val shelfAlbums = state.recentAlbums.drop(1)
                if (shelfAlbums.isNotEmpty()) {
                    item(key = "shelfRecent") {
                        Box(modifier = Modifier.entrance(order = 1)) {
                            AlbumCoverShelf(
                                title = "接着听",
                                subtitle = "最近播放的其它专辑",
                                albums = shelfAlbums,
                                onOpenAlbum = onOpenAlbum,
                                onOpenAll = onOpenRecentAlbums,
                            )
                        }
                    }
                }
                item(key = "stats") {
                    Box(modifier = Modifier.entrance(order = 2)) {
                        StatsRankShelf(
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
                    Box(modifier = Modifier.entrance(order = 3)) {
                        PlaylistShelf(
                            playlists = state.playlists,
                            totalCount = state.playlistCount,
                            onOpenPlaylist = onOpenPlaylist,
                            onOpenAll = onOpenPlaylists,
                        )
                    }
                }
                item(key = "random") {
                    Box(modifier = Modifier.entrance(order = 4)) {
                        RandomAlbumGrid(
                            albums = state.randomAlbums,
                            onOpenAlbum = onOpenAlbum,
                            onShuffle = onShuffleAlbums,
                            onImport = onOpenImport,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 刊头 / 登录
// ---------------------------------------------------------------------------

@Composable
private fun HomeMasthead(statsText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = BiliLayout.pageHorizontal,
                end = BiliLayout.pageHorizontal,
                top = Spacing.md,
                bottom = Spacing.sm,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "BILIAUDIO",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = statsText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "发现",
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "随身携带的专辑书架",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 未登录提示条：点击去扫码登录。 */
@Composable
private fun LoginBanner(onOpenLogin: () -> Unit) {
    Surface(
        onClick = onOpenLogin,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiliLayout.pageHorizontal, vertical = Spacing.sm),
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

// ---------------------------------------------------------------------------
// 封面故事 hero
// ---------------------------------------------------------------------------

@Composable
private fun RecentHero(
    album: Album?,
    onOpenAlbum: (Long) -> Unit,
    onOpenAll: () -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HomeSectionHeader(
            overline = "COVER STORY",
            title = "最近播放",
            trailing = { TextButton(onClick = onOpenAll) { Text("查看全部") } },
        )
        if (album == null) {
            Surface(
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiliLayout.pageHorizontal),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(Spacing.xl)) {
                    Text(text = "还没有最近播放", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "导入一张 B 站专辑，这里会变成你的封面故事。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(text = "去导入专辑", style = MaterialTheme.typography.labelLarge)
                }
            }
            return
        }
        // 大封面锚点：左侧留白、右侧轻微出血，形成与普通列表不同的视觉重心
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp)
                .aspectRatio(1f)
                .clickable { onOpenAlbum(album.id) },
        ) {
            CoverImage(
                url = album.coverModel(),
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 24.dp,
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = BiliLayout.pageHorizontal, vertical = Spacing.sm),
        ) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "打开专辑",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenAlbum(album.id) },
                )
                Spacer(Modifier.width(Spacing.md))
                Text(
                    text = "→",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 区块头 / 空态
// ---------------------------------------------------------------------------

@Composable
private fun HomeSectionHeader(
    overline: String,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = BiliLayout.pageHorizontal,
                end = BiliLayout.pageHorizontal,
                top = Spacing.xl,
                bottom = Spacing.md,
            ),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = overline,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.8.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun HomeEmptyRow(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiliLayout.pageHorizontal, vertical = Spacing.md),
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

// ---------------------------------------------------------------------------
// 最近播放：横向封面墙
// ---------------------------------------------------------------------------

@Composable
private fun AlbumCoverShelf(
    title: String,
    subtitle: String,
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column {
        HomeSectionHeader(
            overline = "RECENTLY PLAYED",
            title = title,
            trailing = { TextButton(onClick = onOpenAll) { Text("查看全部") } },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = BiliLayout.pageHorizontal),
        )
        if (albums.isEmpty()) {
            HomeEmptyRow(text = "还没有最近播放记录。")
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                contentPadding = PaddingValues(horizontal = BiliLayout.pageHorizontal, vertical = Spacing.md),
            ) {
                items(albums, key = { it.id }) { album ->
                    Column(modifier = Modifier.width(148.dp)) {
                        CoverImage(
                            url = album.coverModel(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable { onOpenAlbum(album.id) },
                            cornerRadius = 20.dp,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 听歌统计：横向榜单卡
// ---------------------------------------------------------------------------

@Composable
private fun StatsRankShelf(
    mode: HomeStatsMode,
    albums: List<HomeStatsAlbum>,
    tracks: List<HomeStatsTrack>,
    onModeChange: (HomeStatsMode) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column {
        HomeSectionHeader(
            overline = "LISTENING",
            title = "听歌统计",
            trailing = { TextButton(onClick = onOpenAll) { Text("查看全部") } },
        )
        Row(
            modifier = Modifier.padding(horizontal = BiliLayout.pageHorizontal),
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
        Spacer(Modifier.height(Spacing.sm))
        when (mode) {
            HomeStatsMode.ALBUM_LISTEN_TIME ->
                if (albums.isEmpty()) {
                    HomeEmptyRow(text = "播放一些音乐后，这里会显示 Top 专辑。")
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        contentPadding = PaddingValues(
                            horizontal = BiliLayout.pageHorizontal,
                            vertical = Spacing.sm,
                        ),
                    ) {
                        items(albums, key = { it.album.id }) { stat ->
                            StatCard(
                                rank = albums.indexOf(stat) + 1,
                                coverUrl = stat.album.coverModel(),
                                title = stat.album.title,
                                subtitle = stat.album.artist,
                                value = TimeUtils.formatHumanDuration(stat.listenedMs),
                                onClick = { onOpenAlbum(stat.album.id) },
                            )
                        }
                    }
                }

            HomeStatsMode.TRACK_PLAY_COUNT ->
                if (tracks.isEmpty()) {
                    HomeEmptyRow(text = "播放一些歌曲后，这里会显示 Top 歌曲。")
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        contentPadding = PaddingValues(
                            horizontal = BiliLayout.pageHorizontal,
                            vertical = Spacing.sm,
                        ),
                    ) {
                        items(tracks, key = { it.song.track.id }) { stat ->
                            StatCard(
                                rank = tracks.indexOf(stat) + 1,
                                coverUrl = stat.song.coverModel(),
                                title = stat.song.track.title,
                                subtitle = "${stat.song.album.artist} · ${stat.song.album.title}",
                                value = "${stat.playCount} 次",
                                onClick = { onOpenAlbum(stat.song.album.id) },
                            )
                        }
                    }
                }
        }
    }
}

/** 榜单卡：大序号 + 封面 + 标题 + 数据（横向滚动，而不是列表）。 */
@Composable
private fun StatCard(
    rank: Int,
    coverUrl: Any?,
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(238.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Black,
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            )
            Spacer(Modifier.width(Spacing.sm))
            CoverImage(
                url = coverUrl,
                modifier = Modifier.size(56.dp),
                cornerRadius = 14.dp,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 我的歌单：横向卡片墙
// ---------------------------------------------------------------------------

@Composable
private fun PlaylistShelf(
    playlists: List<Playlist>,
    totalCount: Int,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column {
        HomeSectionHeader(
            overline = "PLAYLISTS",
            title = "我的歌单",
            trailing = { TextButton(onClick = onOpenAll) { Text("查看全部") } },
        )
        Text(
            text = if (totalCount > 0) "$totalCount 个歌单" else "自己整理播放列表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = BiliLayout.pageHorizontal),
        )
        if (playlists.isEmpty()) {
            HomeEmptyRow(
                text = "还没有歌单，可以手动整理播放列表。",
                actionLabel = "查看全部",
                onAction = onOpenAll,
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                contentPadding = PaddingValues(
                    horizontal = BiliLayout.pageHorizontal,
                    vertical = Spacing.md,
                ),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Surface(
                        onClick = { onOpenPlaylist(playlist.id) },
                        modifier = Modifier.width(168.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Spacer(Modifier.height(Spacing.md))
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${playlist.trackCount} 首歌曲",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 随机专辑：双列封面网格
// ---------------------------------------------------------------------------

@Composable
private fun RandomAlbumGrid(
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
    onShuffle: () -> Unit,
    onImport: () -> Unit,
) {
    Column {
        HomeSectionHeader(
            overline = "DISCOVER",
            title = "随机专辑",
            trailing = {
                Row {
                    TextButton(onClick = onShuffle, enabled = albums.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xxs))
                        Text("换一批")
                    }
                    TextButton(onClick = onImport) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xxs))
                        Text("导入")
                    }
                }
            },
        )
        if (albums.isEmpty()) {
            HomeEmptyRow(
                text = "还没有专辑，导入一张开始随机推荐。",
                actionLabel = "导入专辑",
                onAction = onImport,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = BiliLayout.pageHorizontal),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                albums.chunked(2).forEachIndexed { index, rowAlbums ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        rowAlbums.forEach { album ->
                            AlbumCard(
                                album = album,
                                onClick = { onOpenAlbum(album.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowAlbums.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    if (index == albums.chunked(2).lastIndex) {
                        Spacer(Modifier.height(Spacing.xs))
                    }
                }
            }
        }
    }
}
