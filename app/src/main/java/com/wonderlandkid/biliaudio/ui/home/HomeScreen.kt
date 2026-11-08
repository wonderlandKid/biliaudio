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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.AlbumCard
import com.wonderlandkid.biliaudio.ui.components.BiliPageHeader
import com.wonderlandkid.biliaudio.ui.components.SearchBarEntry
import com.wonderlandkid.biliaudio.ui.components.AlbumCompactRow
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.ListBottomSpacer
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.ui.theme.entrance

/** 「最近播放」卡片内最多展示的专辑条数（其余走「查看全部」）。 */
private const val RECENT_VISIBLE_COUNT = 3

/**
 * 发现页。
 *
 * 结构：大标题「发现」+ 统计行 + 搜索入口 + 未登录提示条 +「最近播放」紧凑卡片
 * +「我的专辑」两列网格 + 标题行右侧「导入专辑」入口。
 *
 * 说明：
 * - 本页是底部 Tab 页，系统栏 inset 由 AppNavHost 的 Scaffold `innerPadding` 统一处理；
 * - 不使用悬浮 FAB（会压住内容），导入入口放在「我的专辑」标题行右侧，空态里也有入口。
 */
@Composable
fun HomeScreen(
    /** 点击搜索入口（跳转搜索页 / 搜索结果页） */
    onOpenSearch: () -> Unit,
    /** 点击专辑（跳转专辑详情） */
    onOpenAlbum: (Long) -> Unit,
    /** 点击未登录提示条（跳转扫码登录） */
    onOpenLogin: () -> Unit,
    /** 点击导入入口（标题行按钮 / 空态按钮） */
    onOpenImport: () -> Unit,
    /** 点击「最近播放 · 查看全部」（跳转音乐库 Tab） */
    onOpenLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = appViewModel { container ->
        HomeViewModel(
            albumRepository = container.albumRepository,
            authRepository = container.authRepository,
            playbackController = container.playbackController,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onOpenSearch = onOpenSearch,
        onOpenAlbum = onOpenAlbum,
        onOpenLogin = onOpenLogin,
        onOpenImport = onOpenImport,
        onOpenLibrary = onOpenLibrary,
        onPlayRecentAlbum = viewModel::playRecentAlbum,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenSearch: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPlayRecentAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 两列网格：按 2 个一行切块，避免 LazyColumn 内再嵌套可滚动网格
    val albumRows = remember(state.albums) { state.albums.chunked(2) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") {
            HomeHeader(statsText = state.statsText, onOpenSearch = onOpenSearch)
        }
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
                // 无最近播放数据时整块不显示
                if (state.recentAlbums.isNotEmpty()) {
                    item(key = "recent") {
                        Box(modifier = Modifier.entrance(order = 0)) {
                            RecentPlayedCard(
                                albums = state.recentAlbums,
                                onOpenAlbum = onOpenAlbum,
                                onPlayAlbum = onPlayRecentAlbum,
                                onOpenLibrary = onOpenLibrary,
                            )
                        }
                    }
                }
                item(key = "myAlbumsHeader") {
                    Box(modifier = Modifier.entrance(order = 1)) {
                        MyAlbumsHeader(albumCount = state.albumCount, onOpenImport = onOpenImport)
                    }
                }
                if (albumRows.isEmpty()) {
                    item(key = "empty") {
                        Box(modifier = Modifier.entrance(order = 1)) {
                            EmptyState(
                                title = "还没有专辑",
                                description = "导入一张 B 站专辑，就能按歌曲播放整段音频",
                                actionLabel = "导入专辑",
                                onAction = onOpenImport,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = albumRows,
                        key = { index, row -> "albumRow_${row.firstOrNull()?.id ?: index}" },
                    ) { index, row ->
                        Box(modifier = Modifier.entrance(order = (index + 2).coerceAtMost(8))) {
                            AlbumGridRow(albums = row, onOpenAlbum = onOpenAlbum)
                        }
                    }
                }
            }
        }
        // 列表底部留白，避免最后一行贴着迷你播放条
        item(key = "bottomSpace") { ListBottomSpacer(extra = 32) }
    }
}

/** 大标题 + 统计行 + 搜索入口。 */
@Composable
private fun HomeHeader(
    statsText: String,
    onOpenSearch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 三个 Tab 页统一页头（位置/字号一致，见 ui/components/PageHeader.kt）
        BiliPageHeader(title = "发现", subtitle = statsText)
        Spacer(Modifier.height(Spacing.lg))
        Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
            SearchEntry(onClick = onOpenSearch)
        }
    }
}

/** 搜索入口：与音乐库/搜索页共用同一视觉组件（点击跳转音乐库搜索）。 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    SearchBarEntry(
        onClick = onClick,
        placeholder = "搜索歌曲、专辑、艺术家",
    )
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
                Text(
                    text = "未登录 B 站账号",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "登录后可解锁更高音质与画质",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "去登录",
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 「最近播放」卡片：大圆角卡片内的紧凑横向条目（封面 52dp + 标题/艺术家 + 序号），
 * 最多展示 3 条，卡片右上角有播放按钮（播放最近一张专辑）。
 */
@Composable
private fun RecentPlayedCard(
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val visibleAlbums = albums.take(RECENT_VISIBLE_COUNT)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "最近播放",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "${albums.size} 张专辑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (albums.size > RECENT_VISIBLE_COUNT) {
                    TextButton(onClick = onOpenLibrary) { Text("查看全部") }
                }
                FilledIconButton(
                    onClick = { onPlayAlbum(albums.first().id) },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "播放最近专辑",
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            visibleAlbums.forEachIndexed { index, album ->
                AlbumCompactRow(
                    album = album,
                    onClick = { onOpenAlbum(album.id) },
                    rank = index,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

/** 「我的专辑」标题行 + 右侧「导入专辑」入口（替代悬浮 FAB，避免压住内容）。 */
@Composable
private fun MyAlbumsHeader(
    albumCount: Int,
    onOpenImport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "我的专辑",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "$albumCount 张专辑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onOpenImport,
            shape = RoundedCornerShape(percent = 50),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xxs))
            Text("导入专辑")
        }
    }
}

/** 「我的专辑」两列网格中的一行。 */
@Composable
private fun AlbumGridRow(
    albums: List<Album>,
    onOpenAlbum: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        albums.forEach { album ->
            AlbumCard(
                album = album,
                onClick = { onOpenAlbum(album.id) },
                modifier = Modifier.weight(1f),
            )
        }
        // 单数个时补一个空位，保持卡片宽度一致
        if (albums.size < 2) {
            Spacer(Modifier.weight(1f))
        }
    }
}
