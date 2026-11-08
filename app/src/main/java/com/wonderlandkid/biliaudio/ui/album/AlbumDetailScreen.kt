package com.wonderlandkid.biliaudio.ui.album

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.CoverPick
import com.wonderlandkid.biliaudio.ui.components.CoverPickerSheet
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.SectionHeader
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
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
            libraryArchiveRepository = container.libraryArchiveRepository,
            appContext = container.appContext,
            albumCoverRepository = container.albumCoverRepository,
            audioCacheRepository = container.audioCacheRepository,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showVideoCacheConfirm by remember { mutableStateOf(false) }
    // 「换封面」共享组件（相册 / 网易云 / 恢复 B 站）
    var showCoverPicker by remember { mutableStateOf(false) }
    // 封面大图欣赏
    var showCoverViewer by remember { mutableStateOf(false) }
    val saveCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { uri ->
        if (uri != null) viewModel.saveCoverTo(uri)
    }
    // 导出专辑文件：系统文件选择器（CreateDocument，默认文件名 <专辑名>-biliaudio.json）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportAlbumTo(uri)
    }

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

    BiliDetailScaffold(
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = {
            AlbumDetailActions(
                isRefreshing = state.isRefreshing,
                isExporting = state.isExporting,
                isCaching = state.isCaching,
                cached = state.cachedAudio != null,
                cachedQuality = state.cachedAudio?.audioQualityLabel,
                isCachingVideo = state.isCachingVideo,
                cachedVideo = state.cachedVideo != null,
                cachedVideoHeight = state.cachedVideo?.videoHeight,
                menuEnabled = state.album != null,
                onMenuClick = { menuExpanded = true },
                menuExpanded = menuExpanded,
                onMenuDismiss = { menuExpanded = false },
                onRefresh = {
                    menuExpanded = false
                    viewModel.refreshVideoInfo()
                },
                onExport = {
                    menuExpanded = false
                    state.album?.let { album -> exportLauncher.launch(buildExportFileName(album.title)) }
                },
                onToggleCache = {
                    menuExpanded = false
                    viewModel.toggleAudioCache()
                },
                onToggleVideoCache = {
                    menuExpanded = false
                    if (state.cachedVideo != null) {
                        viewModel.toggleVideoCache()
                    } else {
                        showVideoCacheConfirm = true
                    }
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
                isApplyingCover = state.isApplyingCover,
                onPlayAll = viewModel::playAll,
                onPlayTrack = viewModel::playTrack,
                onOpenSplitEditor = { onOpenSplitEditor(album.id) },
                onChangeCover = { showCoverPicker = true },
                onOpenCover = { showCoverViewer = true },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showVideoCacheConfirm && state.album != null) {
        AlertDialog(
            onDismissRequest = { showVideoCacheConfirm = false },
            title = { Text("缓存歌词视频？") },
            text = {
                Text(
                    "歌词视频是整段原始视频，文件可能较大（数百 MB 至 1 GB+）。" +
                        "建议在 Wi-Fi 下下载；下载期间请保持页面打开。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showVideoCacheConfirm = false
                        viewModel.toggleVideoCache()
                    },
                ) { Text("开始缓存") }
            },
            dismissButton = {
                TextButton(onClick = { showVideoCacheConfirm = false }) { Text("取消") }
            },
        )
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

    // 更换封面弹层（共享组件，内部不写库；选中后由 ViewModel 保存并写库）
    val pickerAlbum = state.album
    if (pickerAlbum != null) {
        CoverPickerSheet(
            visible = showCoverPicker,
            title = "更换专辑封面",
            defaultQuery = "${pickerAlbum.title} ${pickerAlbum.artist}".trim(),
            currentCoverModel = pickerAlbum.coverModel(),
            allowRestoreBilibili = pickerAlbum.coverLocalPath != null,
            onDismiss = { showCoverPicker = false },
            onPick = { pick: CoverPick ->
                showCoverPicker = false
                viewModel.applyCover(pickerAlbum.id, pick)
            },
        )
        if (showCoverViewer) {
            CoverViewerDialog(
                album = pickerAlbum,
                isApplyingCover = state.isApplyingCover,
                onDismiss = { showCoverViewer = false },
                onChangeCover = {
                    showCoverViewer = false
                    showCoverPicker = true
                },
                onSaveCover = {
                    showCoverViewer = false
                    saveCoverLauncher.launch(buildCoverFileName(pickerAlbum.title))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.AlbumDetailActions(
    isRefreshing: Boolean,
    isExporting: Boolean,
    isCaching: Boolean,
    cached: Boolean,
    cachedQuality: String?,
    isCachingVideo: Boolean,
    cachedVideo: Boolean,
    cachedVideoHeight: Int?,
    menuEnabled: Boolean,
    onMenuClick: () -> Unit,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onToggleCache: () -> Unit,
    onToggleVideoCache: () -> Unit,
    onDelete: () -> Unit,
) {
    if (isRefreshing || isExporting || isCaching || isCachingVideo) {
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
            text = {
                Text(
                    when {
                        isCaching -> "缓存中…"
                        cached -> "删除音频缓存${cachedQuality?.let { "（$it）" }.orEmpty()}"
                        else -> "缓存整张专辑音频"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
            enabled = menuEnabled && !isCaching,
            onClick = onToggleCache,
        )
        DropdownMenuItem(
            text = {
                Text(
                    when {
                        isCachingVideo -> "视频缓存中…"
                        cachedVideo -> "删除歌词视频缓存${cachedVideoHeight?.let { "（${it}P）" }.orEmpty()}"
                        else -> "缓存歌词视频"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
            enabled = menuEnabled && !isCachingVideo,
            onClick = onToggleVideoCache,
        )
        DropdownMenuItem(
            text = { Text("导出专辑文件") },
            leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
            enabled = menuEnabled && !isExporting,
            onClick = onExport,
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
}

@Composable
private fun AlbumDetailContent(
    album: Album,
    tracks: List<Track>,
    currentTrackId: Long?,
    isPlaying: Boolean,
    isThisAlbumPlaying: Boolean,
    isApplyingCover: Boolean,
    onPlayAll: () -> Unit,
    onPlayTrack: (Long) -> Unit,
    onOpenSplitEditor: () -> Unit,
    onChangeCover: () -> Unit,
    onOpenCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item(key = "hero") {
            AlbumHero(
                album = album,
                trackCount = tracks.size,
                onOpenCover = onOpenCover,
            )
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
                    "未分割 · 共 ${TimeUtils.formatDuration(album.durationMs)}"
                } else {
                    "${tracks.size} 首 · 共 ${TimeUtils.formatDuration(album.durationMs)}"
                },
            )
        }
        if (tracks.isEmpty()) {
            item(key = "tracksEmpty") {
                EmptyState(
                    title = "尚未分割",
                    description = "整张专辑会按一首歌播放；可在分曲编辑里添加时间点或粘贴文本",
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
    onOpenCover: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .clickable(onClick = onOpenCover),
        ) {
            CoverImage(
                // 支持用户自定义本地封面（album.coverModel() 优先本地 File，回退 B 站 URL）
                url = album.coverModel(),
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 28.dp,
            )
        }
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
            MetadataChip(
                text = if (trackCount == 0) {
                    "未分割 · ${TimeUtils.formatDuration(album.durationMs)}"
                } else {
                    "$trackCount 首 · ${TimeUtils.formatDuration(album.durationMs)}"
                },
            )
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

// ---------------------------------------------------------------------------
// 导出文件工具
// ---------------------------------------------------------------------------

/** SAF 默认文件名里专辑名部分的最大长度。 */
private const val MAX_EXPORT_TITLE_LENGTH = 60

/** 文件系统非法字符：`/ \ : * ? " < > |` + 不可见控制字符。 */
private val ILLEGAL_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

/** 连续空白（含换行/制表），文件名里压缩成单个空格。 */
private val FILE_NAME_WHITESPACE = Regex("""\s+""")

/**
 * 生成 SAF 默认文件名：`<安全化专辑名>-biliaudio.json`。
 *
 * 去掉文件系统非法字符、压缩空白，标题部分最长 [MAX_EXPORT_TITLE_LENGTH] 个字符；
 * 标题为空时回退为 "album"。
 */
private fun buildExportFileName(albumTitle: String): String {
    val sanitized = albumTitle
        .replace(ILLEGAL_FILE_NAME_CHARS, "")
        .replace(FILE_NAME_WHITESPACE, " ")
        .trim()
        .trim('.')
        .take(MAX_EXPORT_TITLE_LENGTH)
        .trim()
        .ifBlank { "album" }
    return "$sanitized-biliaudio.json"
}

/** 封面大图欣赏：大图 + 保存封面 / 换封面。 */
@Composable
private fun CoverViewerDialog(
    album: Album,
    isApplyingCover: Boolean,
    onDismiss: () -> Unit,
    onChangeCover: () -> Unit,
    onSaveCover: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                        )
                    }
                }
                CoverImage(
                    url = album.coverModel(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp)),
                    cornerRadius = 24.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Button(
                        onClick = onSaveCover,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(Spacing.xs))
                        Text("保存封面")
                    }
                    Button(
                        onClick = onChangeCover,
                        enabled = !isApplyingCover,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (isApplyingCover) "更新中…" else "换封面")
                    }
                }
            }
        }
    }
}

/** 保存封面时的文件名：去掉文件系统非法字符。 */
private fun buildCoverFileName(title: String): String {
    val safe = title.trim()
        .replace(Regex("""[/\\:*?"<>|]"""), "_")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(60)
        .ifBlank { "cover" }
    return "$safe-cover.jpg"
}
