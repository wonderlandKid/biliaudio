package com.wonderlandkid.biliaudio.ui.album

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.SectionHeader
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 专辑详情页：大封面 + 标题/艺术家/BVID 胶囊 + 总时长/曲目数 + 操作按钮 + 分曲列表。
 *
 * 系统栏：与 AppNavHost 约定一致 —— 由外层 Scaffold 的 `innerPadding` 统一处理，
 * 页面内 TopAppBar / 列表都不重复添加系统栏 inset。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    /** 要展示的专辑 id */
    albumId: Long,
    /** 顶部返回 */
    onBack: () -> Unit,
    /** 编辑分割点（跳转分曲编辑页） */
    onOpenSplitEditor: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumDetailViewModel = appViewModel { container ->
        AlbumDetailViewModel(
            albumId = albumId,
            albumRepository = container.albumRepository,
            biliRepository = container.biliRepository,
            playbackController = container.playbackController,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 删除成功后自动返回上一页
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    // 一次性提示 → Snackbar
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AlbumDetailTopBar(
                isRefreshing = state.isRefreshing,
                menuEnabled = state.album != null,
                onBack = onBack,
                onMenuClick = { menuExpanded = true },
                menuExpanded = menuExpanded,
                onMenuDismiss = { menuExpanded = false },
                onRefresh = {
                    menuExpanded = false
                    viewModel.refreshVideoInfo()
                },
                onDelete = {
                    menuExpanded = false
                    showDeleteDialog = true
                },
            )
        },
    ) { innerPadding ->
        val album = state.album
        when {
            state.isLoading -> LoadingBox(modifier = Modifier.padding(innerPadding))
            album == null -> EmptyState(
                title = "专辑不存在",
                description = "可能已被删除，或数据加载失败",
                modifier = Modifier.padding(innerPadding),
                actionLabel = "返回",
                onAction = onBack,
            )

            else -> AlbumDetailContent(
                album = album,
                tracks = state.tracks,
                currentTrackId = state.currentTrackId,
                isPlaying = state.isPlaying,
                isThisAlbumPlaying = state.isThisAlbumPlaying,
                onPlayAll = viewModel::playAll,
                onPlayTrack = viewModel::playTrack,
                onOpenSplitEditor = { onOpenSplitEditor(album.id) },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    val albumForDialog = state.album
    if (showDeleteDialog && albumForDialog != null) {
        DeleteAlbumDialog(
            albumTitle = albumForDialog.title,
            deleting = state.isDeleting,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteAlbum()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumDetailTopBar(
    isRefreshing: Boolean,
    menuEnabled: Boolean,
    onBack: () -> Unit,
    onMenuClick: () -> Unit,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = Spacing.sm)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text("刷新视频信息") },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    enabled = menuEnabled && !isRefreshing,
                    onClick = onRefresh,
                )
                DropdownMenuItem(
                    text = { Text("删除专辑", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    enabled = menuEnabled,
                    onClick = onDelete,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        // 系统栏 inset 由外层 AppNavHost 的 Scaffold 统一处理
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun AlbumDetailContent(
    album: Album,
    tracks: List<Track>,
    currentTrackId: Long?,
    isPlaying: Boolean,
    isThisAlbumPlaying: Boolean,
    onPlayAll: () -> Unit,
    onPlayTrack: (Long) -> Unit,
    onOpenSplitEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item(key = "hero") {
            AlbumHero(album = album, trackCount = tracks.size)
        }
        item(key = "actions") {
            AlbumActionRow(
                isThisAlbumPlaying = isThisAlbumPlaying,
                onPlayAll = onPlayAll,
                onOpenSplitEditor = onOpenSplitEditor,
            )
        }
        item(key = "tracksHeader") {
            SectionHeader(
                title = "曲目",
                subtitle = if (tracks.isEmpty()) {
                    "还没有分曲"
                } else {
                    "${tracks.size} 首 · 共 ${TimeUtils.formatDuration(album.durationMs)}"
                },
            )
        }
        if (tracks.isEmpty()) {
            item(key = "tracksEmpty") {
                EmptyState(
                    title = "还没有分曲",
                    description = "把整段音频按时间点切成歌曲，就能逐首播放",
                    actionLabel = "编辑分割点",
                    onAction = onOpenSplitEditor,
                )
            }
        } else {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    title = track.title,
                    subtitle = "第 ${index + 1} 首 · 从 ${TimeUtils.formatDuration(track.startMs)} 开始",
                    durationMs = track.durationMs,
                    onClick = { onPlayTrack(track.id) },
                    index = index,
                    isCurrent = track.id == currentTrackId,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}

/** 大封面 + 专辑元信息。 */
@Composable
private fun AlbumHero(
    album: Album,
    trackCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverImage(
            url = album.coverUrl,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f),
            cornerRadius = 28.dp,
        )
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = album.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetadataChip(text = album.bvid)
            if (album.pageCount > 1) {
                MetadataChip(text = "P${album.page}/${album.pageCount}")
            }
            MetadataChip(text = "$trackCount 首 · ${TimeUtils.formatDuration(album.durationMs)}")
        }
    }
}

/** 小胶囊标签（BV 号 / 分 P / 时长曲目数）。 */
@Composable
private fun MetadataChip(text: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs + 2.dp),
            maxLines = 1,
        )
    }
}

/** 播放全部 / 编辑分割点。 */
@Composable
private fun AlbumActionRow(
    isThisAlbumPlaying: Boolean,
    onPlayAll: () -> Unit,
    onOpenSplitEditor: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Button(
            onClick = onPlayAll,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(percent = 50),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(if (isThisAlbumPlaying) "重新播放" else "播放全部")
        }
        OutlinedButton(
            onClick = onOpenSplitEditor,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(percent = 50),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text("编辑分割点")
        }
    }
}

/** 删除确认弹窗。 */
@Composable
private fun DeleteAlbumDialog(
    albumTitle: String,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除专辑") },
        text = { Text("确定要删除《$albumTitle》吗？\n分曲信息会一并删除，此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        },
    )
}
