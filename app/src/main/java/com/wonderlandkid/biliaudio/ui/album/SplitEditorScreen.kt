package com.wonderlandkid.biliaudio.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.impl.SplitTextParser
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.SectionHeader
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 分曲编辑页：把整段专辑音频按时间点切成歌曲。
 *
 * 功能：
 * - 已有分割点列表（时间 + 歌名），可编辑 / 删除 / 按时间排序；
 * - 新增分割点：手动输入 `mm:ss` 或点「用当前位置」取播放器专辑绝对进度（四舍五入到 0.5s）；
 * - 保存前校验严格递增 / 不超专辑时长，通过后调用 [com.wonderlandkid.biliaudio.data.repository.AlbumRepository.replaceTracks]；
 * - 提供试听预览（[com.wonderlandkid.biliaudio.player.PlaybackController.playAlbum] + `seekToAlbumPosition`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitEditorScreen(
    /** 要编辑的专辑 id */
    albumId: Long,
    /** 顶部返回 */
    onBack: () -> Unit,
    /** 保存成功后的回调（默认等同返回上一页） */
    onSaved: () -> Unit = onBack,
    modifier: Modifier = Modifier,
    viewModel: SplitEditorViewModel = appViewModel { container ->
        SplitEditorViewModel(
            albumId = albumId,
            albumRepository = container.albumRepository,
            playbackController = container.playbackController,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingKey by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }
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
            TopAppBar(
                title = { Text("编辑分割点", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::saveSplit, enabled = state.canSave) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("保存")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                // 系统栏 inset 由外层 AppNavHost 的 Scaffold 统一处理
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        val album = state.album
        when {
            state.isLoading -> LoadingBox(modifier = Modifier.padding(innerPadding))
            album == null -> EmptyState(
                title = "专辑不存在",
                description = "可能已被删除，无法编辑分割点",
                modifier = Modifier.padding(innerPadding),
                actionLabel = "返回",
                onAction = onBack,
            )

            else -> SplitEditorContent(
                album = album,
                state = state,
                onTimeInputChange = viewModel::onTimeInputChange,
                onTitleInputChange = viewModel::onTitleInputChange,
                onUseCurrentPosition = viewModel::useCurrentPlaybackPosition,
                onAddSegment = viewModel::addSegment,
                onSort = viewModel::sortByTime,
                onOpenTextImport = viewModel::openTextImport,
                onEditSegment = { key -> editingKey = key },
                onDeleteSegment = viewModel::deleteSegment,
                onPreviewSegment = viewModel::previewFrom,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    val editingDraft = state.segments.firstOrNull { draft -> draft.key == editingKey }
    if (editingDraft != null) {
        EditSegmentDialog(
            draft = editingDraft,
            albumDurationMs = state.album?.durationMs ?: 0L,
            onDismiss = { editingKey = null },
            onConfirm = { startMs, title ->
                viewModel.updateSegment(editingDraft.key, startMs, title)
                editingKey = null
            },
        )
    }

    // 「文本导入分割点」弹窗：粘贴 → 实时识别 → 替换 / 追加
    if (state.textImportVisible) {
        TextImportDialog(
            text = state.textImportText,
            result = state.textImportResult,
            existingSegmentCount = state.segments.size,
            albumDurationMs = state.album?.durationMs ?: 0L,
            onTextChange = viewModel::onTextImportInputChange,
            onDismiss = viewModel::dismissTextImport,
            onImport = { replace ->
                viewModel.importSegmentsFromText(state.textImportText, replace)
            },
        )
    }
}

@Composable
private fun SplitEditorContent(
    album: Album,
    state: SplitEditorUiState,
    onTimeInputChange: (String) -> Unit,
    onTitleInputChange: (String) -> Unit,
    onUseCurrentPosition: () -> Unit,
    onAddSegment: () -> Unit,
    onSort: () -> Unit,
    onOpenTextImport: () -> Unit,
    onEditSegment: (Long) -> Unit,
    onDeleteSegment: (Long) -> Unit,
    onPreviewSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "albumSummary") {
            AlbumSummaryCard(
                album = album,
                segmentCount = state.segments.size,
                onPreviewAlbum = { onPreviewSegment(0L) },
            )
        }
        item(key = "addPanel") {
            AddSegmentPanel(
                state = state,
                onTimeInputChange = onTimeInputChange,
                onTitleInputChange = onTitleInputChange,
                onUseCurrentPosition = onUseCurrentPosition,
                onAddSegment = onAddSegment,
                onOpenTextImport = onOpenTextImport,
            )
        }
        item(key = "segmentsHeader") {
            SectionHeader(
                title = "分割点",
                subtitle = state.summaryText,
                actionLabel = "按时间排序",
                onAction = onSort,
            )
        }
        if (state.segments.isEmpty()) {
            item(key = "segmentsEmpty") {
                EmptyState(
                    title = "尚未分割",
                    description = "可直接文本导入或手动添加，第一个时间点通常是 00:00",
                    actionLabel = "文本导入",
                    onAction = onOpenTextImport,
                )
            }
        } else {
            itemsIndexed(state.segments, key = { _, draft -> draft.key }) { index, draft ->
                val endMs = state.segments.getOrNull(index + 1)?.startMs ?: album.durationMs
                // 播放中且专辑绝对进度落在本段内 → 高亮
                val isCurrent = state.isAlbumInPlayer &&
                    state.albumPositionMs in draft.startMs until endMs
                SegmentRow(
                    index = index,
                    draft = draft,
                    endMs = endMs,
                    isCurrent = isCurrent,
                    onEdit = { onEditSegment(draft.key) },
                    onDelete = { onDeleteSegment(draft.key) },
                    onPreview = { onPreviewSegment(draft.startMs) },
                )
            }
        }
    }
}

/** 顶部专辑信息卡：封面 / 标题 / 时长 / 分割点数 / 整张试听。 */
@Composable
private fun AlbumSummaryCard(
    album: Album,
    segmentCount: Int,
    onPreviewAlbum: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CoverImage(
                url = album.coverUrl,
                modifier = Modifier.size(56.dp),
                cornerRadius = 14.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
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
                Text(
                    text = "专辑时长 ${TimeUtils.formatDuration(album.durationMs)} · $segmentCount 个分割点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onPreviewAlbum) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xxs))
                Text("试听")
            }
        }
    }
}

/** 新增分割点面板：时间 + 歌名 + 取播放位置 + 添加。 */
@Composable
private fun AddSegmentPanel(
    state: SplitEditorUiState,
    onTimeInputChange: (String) -> Unit,
    onTitleInputChange: (String) -> Unit,
    onUseCurrentPosition: () -> Unit,
    onAddSegment: () -> Unit,
    onOpenTextImport: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "新增分割点",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenTextImport,
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xxs))
                    Text("文本导入", maxLines = 1)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.timeInput,
                    onValueChange = onTimeInputChange,
                    modifier = Modifier.weight(0.45f),
                    label = { Text("时间") },
                    placeholder = { Text("mm:ss") },
                    singleLine = true,
                    isError = state.inputError != null,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = state.titleInput,
                    onValueChange = onTitleInputChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("歌名") },
                    placeholder = { Text("第 ${state.segments.size + 1} 首") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
            if (state.inputError != null) {
                Text(
                    text = state.inputError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = onUseCurrentPosition,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(percent = 50),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xxs))
                    Text("用当前位置", maxLines = 1)
                }
                Button(
                    onClick = onAddSegment,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(percent = 50),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xxs))
                    Text("添加", maxLines = 1)
                }
            }
            Text(
                text = if (state.isAlbumInPlayer) {
                    "当前播放位置 ${formatSplitTime(state.albumPositionMs)}，点「用当前位置」自动吸附到 0.5 秒"
                } else {
                    "支持 mm:ss / mm:ss.SSS；先在下方试听本专辑，就能一键取当前播放位置"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单个分割点行：序号 + 时间区间 + 歌名 + 试听/删除。 */
@Composable
private fun SegmentRow(
    index: Int,
    draft: SegmentDraft,
    endMs: Long,
    isCurrent: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatSplitTime(draft.startMs)} → ${formatSplitTime(endMs)}" +
                        "   ·   ${TimeUtils.formatDuration(endMs - draft.startMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "从此处试听",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 编辑单个分割点的弹窗（时间 + 歌名）。 */
@Composable
private fun EditSegmentDialog(
    draft: SegmentDraft,
    albumDurationMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, title: String) -> Unit,
) {
    var timeText by remember(draft.key) { mutableStateOf(formatSplitTime(draft.startMs)) }
    var titleText by remember(draft.key) { mutableStateOf(draft.title) }
    var errorText by remember(draft.key) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑分割点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = timeText,
                    onValueChange = {
                        timeText = it
                        errorText = null
                    },
                    label = { Text("时间（mm:ss）") },
                    singleLine = true,
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("歌名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorText?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = TimeUtils.parseTimeToMs(timeText)
                    when {
                        parsed == null -> errorText = "时间格式不正确，请输入 mm:ss"
                        parsed < 0L -> errorText = "时间不能为负数"
                        parsed >= albumDurationMs -> errorText = "不能超过专辑总时长"
                        else -> onConfirm(parsed, titleText)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 分割点时间文案：整秒 mm:ss，带毫秒则 mm:ss.SSS。 */
private fun formatSplitTime(ms: Long): String =
    if (ms % 1000L == 0L) TimeUtils.formatDuration(ms) else TimeUtils.formatDurationPrecise(ms)


// ---------------------------------------------------------------------------
// 文本导入分割点
// ---------------------------------------------------------------------------

/** 「文本导入分割点」弹窗：粘贴文本 → 实时识别 → 替换 / 追加。 */
@Composable
private fun TextImportDialog(
    text: String,
    result: SplitTextParser.Result?,
    existingSegmentCount: Int,
    albumDurationMs: Long,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: (replace: Boolean) -> Unit,
) {
    var showReplaceConfirm by remember { mutableStateOf(false) }
    val parsedCount = result?.segments?.size ?: 0
    val canImport = parsedCount > 0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column {
                    Text(
                        text = "文本导入分割点",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "每行一首，三种格式可混用；粘贴后自动识别。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "示例（三种格式可混用）：\n1.Breakaway00:00\n00:00 Tap Out\nOh! Darling 10:50",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 8,
                    maxLines = 10,
                    shape = RoundedCornerShape(16.dp),
                )
                TextImportSummary(result = result, albumDurationMs = albumDurationMs)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(
                        onClick = {
                            if (existingSegmentCount > 0) {
                                showReplaceConfirm = true
                            } else {
                                onImport(true)
                            }
                        },
                        enabled = canImport,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) {
                        Text(
                            text = "替换全部分割点",
                            maxLines = 1,
                            color = if (existingSegmentCount > 0 && canImport) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Button(
                        onClick = { onImport(false) },
                        enabled = canImport,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(percent = 50),
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
                    ) {
                        Text("追加到末尾", maxLines = 1)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("取消")
                }
            }
        }
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("替换全部分割点？") },
            text = {
                Text(
                    "将清除当前 $existingSegmentCount 个分割点，替换为文本识别出的 $parsedCount 个。\n" +
                        "点「保存」后才会写入专辑。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        onImport(true)
                    },
                ) {
                    Text("替换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 文本识别结果摘要：识别/跳过数量、格式提示、跳过行前 3 条、超时长提示。 */
@Composable
private fun TextImportSummary(
    result: SplitTextParser.Result?,
    albumDurationMs: Long,
) {
    if (result == null) {
        Text(
            text = "支持 `1.Breakaway00:00` / `00:00 Tap Out` / `Oh! Darling 10:50` 三种写法",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (result.segments.isEmpty()) {
        Text(
            text = "未识别到分割点：请确认每行都包含时间（mm:ss）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val skippedCount = result.skippedLines.size
    val outOfRangeCount =
        if (albumDurationMs > 0L) result.segments.count { it.startMs >= albumDurationMs } else 0
    Text(
        text = "识别 ${result.segments.size} 个分割点" +
            if (skippedCount > 0) " · 跳过 $skippedCount 行" else "",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = result.styleHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (outOfRangeCount > 0) {
        Text(
            text = "其中 $outOfRangeCount 个时间点超出专辑时长，导入时会跳过",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (result.skippedLines.isNotEmpty()) {
        Text(
            text = "跳过的行（前 3 条）：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.skippedLines.take(3).forEach { line ->
            Text(
                text = "· ${line.take(60)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (result.skippedLines.size > 3) {
            Text(
                text = "…共 ${result.skippedLines.size} 行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
