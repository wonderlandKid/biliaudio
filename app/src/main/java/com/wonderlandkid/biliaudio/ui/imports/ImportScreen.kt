package com.wonderlandkid.biliaudio.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverPickerSheet
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.SearchField
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 导入专辑页（三个 Tab）：
 * - BV 号导入：BV 号 / 视频链接 → 解析预览 → 编辑专辑名/艺术家 → 导入；
 * - 批量导入：粘贴文本或从文件读取（JSON / CSV / 文本块，见 docs/IMPORT_FORMATS.md）→ 结果列表；
 * - 搜索导入：关键词搜索 B 站视频 → 点击结果预览 → 导入。
 *
 * 页面不直接依赖 NavController，导航由外部通过 [onBack] / [onImported] 回调驱动。
 *
 * @param onBack 点击返回
 * @param onImported 导入成功后点击「查看专辑」时回调专辑 id（由导航层决定跳到专辑详情）
 * @param initialTab 初始 Tab；Routes.SEARCH 复用本页时传 [ImportTab.SEARCH]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onImported: (Long) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ImportTab = ImportTab.BV,
) {
    val viewModel: ImportViewModel = appViewModel { container ->
        ImportViewModel(
            albumRepository = container.albumRepository,
            biliRepository = container.biliRepository,
            libraryArchiveRepository = container.libraryArchiveRepository,
            albumCoverRepository = container.albumCoverRepository,
            appContext = container.appContext,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    // 「覆盖全库」模式的二次确认弹窗
    var showReplaceConfirm by remember { mutableStateOf(false) }
    // 封面选择弹层当前服务于哪个预览（null = 关闭）
    var coverPickerTarget by remember { mutableStateOf<CoverPickerTarget?>(null) }

    // 初始 Tab（Routes.SEARCH 复用本页时直接落在搜索 Tab）
    LaunchedEffect(initialTab) { viewModel.selectTab(initialTab) }

    // 一次性提示（Snackbar）
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // 批量导入：选择文本文件（部分文件管理器把 txt/json 报成 octet-stream，故放开类型）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.loadArchiveFile(uri)
    }

    BiliDetailScaffold(
        onBack = onBack,
        title = "导入专辑",
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            ImportTabRow(
                selected = state.selectedTab,
                onSelect = { tab ->
                    focusManager.clearFocus()
                    viewModel.selectTab(tab)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                when (state.selectedTab) {
                    ImportTab.BV -> BvImportTab(
                        state = state.bv,
                        onInputChange = viewModel::updateBvInput,
                        onParse = {
                            focusManager.clearFocus()
                            viewModel.parseBvInput()
                        },
                        onAlbumTitleChange = viewModel::updateBvAlbumTitle,
                        onArtistChange = viewModel::updateBvArtist,
                        onSplitTextChange = viewModel::updateBvSplitText,
                        onChangeCover = { coverPickerTarget = CoverPickerTarget.BV },
                        onImport = viewModel::importBv,
                        onOpenAlbum = onImported,
                        onReset = viewModel::resetBvImport,
                    )

                    ImportTab.BATCH -> ArchiveImportTab(
                        state = state.archive,
                        sampleSegmentJson = viewModel.sampleSegmentJson,
                        onModeSelect = { mode ->
                            if (mode == ArchiveImportMode.REPLACE &&
                                state.archive.mode != ArchiveImportMode.REPLACE
                            ) {
                                // 覆盖全库：先弹二次确认，确认后才真正切换模式
                                showReplaceConfirm = true
                            } else {
                                viewModel.setArchiveMode(mode)
                            }
                        },
                        onTextChange = viewModel::updateArchiveText,
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                        onPreview = {
                            focusManager.clearFocus()
                            viewModel.previewArchive()
                        },
                        onImport = {
                            focusManager.clearFocus()
                            viewModel.importArchive()
                        },
                        onClear = viewModel::clearArchive,
                    )

                    ImportTab.SEARCH -> SearchImportTab(
                        state = state,
                        onQueryChange = viewModel::updateSearchQuery,
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.search()
                        },
                        onOpenPreview = viewModel::openSearchPreview,
                    )
                }
            }
        }
    }

    // 搜索结果预览（底部弹窗）：解析后可直接编辑专辑信息并导入
    val searchPreview = state.searchPreview
    if (searchPreview != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissSearchPreview,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xxxl),
            ) {
                Text(
                    text = "视频预览",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
                when {
                    searchPreview.isParsing -> ImportLoadingCard("正在解析视频信息…")
                    searchPreview.parseError != null -> ImportErrorCard(
                        message = searchPreview.parseError,
                        title = "解析失败",
                        onRetry = viewModel::retrySearchPreview,
                    )
                    searchPreview.info != null -> VideoPreviewCard(
                        state = searchPreview,
                        onAlbumTitleChange = viewModel::updateSearchPreviewTitle,
                        onArtistChange = viewModel::updateSearchPreviewArtist,
                        onSplitTextChange = viewModel::updateSearchPreviewSplitText,
                        onChangeCover = { coverPickerTarget = CoverPickerTarget.SEARCH },
                        onImport = viewModel::importSearchPreview,
                        onOpenAlbum = { albumId ->
                            viewModel.dismissSearchPreview()
                            onImported(albumId)
                        },
                        onReset = viewModel::dismissSearchPreview,
                        resetLabel = "关闭",
                    )

                    else -> Unit
                }
            }
        }
    }

    // 封面选择弹层（共享组件 CoverPickerSheet）：BV 预览 / 搜索预览共用
    val coverTarget = coverPickerTarget
    if (coverTarget != null) {
        val videoState = when (coverTarget) {
            CoverPickerTarget.BV -> state.bv
            CoverPickerTarget.SEARCH -> state.searchPreview
        }
        if (videoState != null && videoState.info != null) {
            CoverPickerSheet(
                visible = true,
                title = "更换专辑封面",
                defaultQuery = listOf(videoState.albumTitle, videoState.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                currentCoverModel = videoState.pendingCoverModel,
                allowRestoreBilibili = false,
                onDismiss = { coverPickerTarget = null },
                onPick = { pick ->
                    when (coverTarget) {
                        CoverPickerTarget.BV -> viewModel.updateBvPendingCover(pick)
                        CoverPickerTarget.SEARCH -> viewModel.updateSearchPreviewPendingCover(pick)
                    }
                    coverPickerTarget = null
                },
            )
        }
    }

    // 「覆盖全库」二次确认：说明会先自动备份，避免误清空本地库
    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("覆盖全库？") },
            text = {
                Text(
                    "覆盖全库会先把当前音乐库自动备份到应用私有目录，" +
                        "然后清空本地库并导入此文件。\n\n" +
                        "自动备份保留最近 10 份；如需长期保留，建议先在「设置 → 备份与恢复」导出音乐库。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        viewModel.setArchiveMode(ArchiveImportMode.REPLACE)
                    },
                ) {
                    Text("覆盖全库", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部栏 / Tab
// ---------------------------------------------------------------------------


/** 居中胶囊分段 Tab（扁平、大圆角，无模糊材质）。 */
@Composable
private fun ImportTabRow(
    selected: ImportTab,
    onSelect: (ImportTab) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                ImportTab.entries.forEach { tab ->
                    val active = tab == selected
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                            )
                            .clickable { onSelect(tab) }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 内容：BV 号导入
// ---------------------------------------------------------------------------

@Composable
private fun BvImportTab(
    state: VideoImportState,
    onInputChange: (String) -> Unit,
    onParse: () -> Unit,
    onAlbumTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onSplitTextChange: (String) -> Unit,
    onChangeCover: () -> Unit,
    onImport: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ImportListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ---- 输入区 ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.cardCorner),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("BV 号 / 视频链接") },
                        placeholder = { Text("BV1GJ411x7h7 或视频链接") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = {
                            Icon(Icons.Rounded.Link, contentDescription = null)
                        },
                        trailingIcon = if (state.input.isNotEmpty()) {
                            {
                                IconButton(onClick = { onInputChange("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                                }
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onParse() }),
                    )
                    Text(
                        text = "支持 BV 号（如 BV1GJ411x7h7）或完整视频链接" +
                            "（bilibili.com/video/BV...）；短链 b23.tv 暂不支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onParse,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !state.isParsing && state.input.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (state.isParsing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text("解析中…")
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text("解析预览")
                        }
                    }
                }
            }
        }

        // ---- 解析中 / 解析失败 / 预览 ----
        if (state.isParsing) {
            item { ImportLoadingCard("正在解析视频信息…") }
        }
        val parseError = state.parseError
        if (parseError != null) {
            item {
                ImportErrorCard(
                    message = parseError,
                    title = "解析失败",
                    onRetry = onParse,
                )
            }
        }
        if (state.info != null) {
            item {
                VideoPreviewCard(
                    state = state,
                    onAlbumTitleChange = onAlbumTitleChange,
                    onArtistChange = onArtistChange,
                    onSplitTextChange = onSplitTextChange,
                    onChangeCover = onChangeCover,
                    onImport = onImport,
                    onOpenAlbum = onOpenAlbum,
                    onReset = onReset,
                    resetLabel = "再导入一个",
                )
            }
        }
        // ---- 初始空态 ----
        if (state.info == null && !state.isParsing && parseError == null) {
            item {
                EmptyState(
                    title = "输入 BV 号开始导入",
                    description = "粘贴 B 站视频的 BV 号或链接，解析后可预览封面、标题、UP 主与分 P 数，" +
                        "再导入为本地专辑。",
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 内容：归档 / 批量导入
// ---------------------------------------------------------------------------

@Composable
private fun ArchiveImportTab(
    state: ArchiveImportState,
    sampleSegmentJson: String,
    onModeSelect: (ArchiveImportMode) -> Unit,
    onTextChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = state.preview
    val report = state.report
    val lineCount = remember(state.text) {
        state.text.lines().count { it.isNotBlank() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ImportListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ---- 标题 ----
        item {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(text = "归档 / 批量导入", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "支持整库备份恢复、单张专辑分享、评论区分割点文件；" +
                        "也兼容旧的 JSON 数组 / CSV / 文本块。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 导入模式（「覆盖全库」的二次确认在页面层弹窗） ----
        item {
            ArchiveModeSelector(mode = state.mode, onSelect = onModeSelect)
        }

        // ---- 格式说明（归档 JSON 为主，旧格式折叠） ----
        item {
            ArchiveFormatGuide(sampleSegmentJson = sampleSegmentJson)
        }

        // ---- 输入区：粘贴 / 文件读取 ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.cardCorner),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = "粘贴归档内容",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp),
                        placeholder = {
                            Text("粘贴 .biliaudio.json 归档内容；也支持旧的 JSON 数组 / CSV / 文本块…")
                        },
                        minLines = 8,
                        maxLines = 14,
                        shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    // 文件读取 + 清空 + 行数
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onPickFile,
                            enabled = !state.isReadingFile,
                            shape = RoundedCornerShape(50),
                        ) {
                            if (state.isReadingFile) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text("读取中…")
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.FileOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text("从文件读取")
                            }
                        }
                        TextButton(
                            onClick = onClear,
                            enabled = state.text.isNotBlank() || preview != null || report != null,
                        ) {
                            Text("清空")
                        }
                        Spacer(Modifier.weight(1f))
                        if (lineCount > 0) {
                            Text(
                                text = "$lineCount 行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.fileName != null) {
                        Text(
                            text = "已读取：${state.fileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = onPreview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = state.canPreview,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (state.isPreviewing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text("解析中…")
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text("解析预览")
                        }
                    }
                }
            }
        }

        // ---- 解析中 / 解析预览失败 ----
        if (state.isPreviewing) {
            item { ImportLoadingCard("正在解析归档文件…") }
        }
        val previewError = state.previewError
        if (previewError != null) {
            item {
                ImportErrorCard(
                    message = previewError,
                    title = "解析预览失败",
                    onRetry = onPreview,
                )
            }
        }

        // ---- 预览：格式/统计 + 逐条计划动作 + 解析错误 + 开始导入 ----
        if (preview != null) {
            item { ArchivePreviewCard(preview) }
            if (preview.items.isNotEmpty()) {
                item { ArchiveSubHeader("计划动作（${preview.items.size} 项）") }
                itemsIndexed(
                    items = preview.items,
                    key = { index, item -> "preview_${item.albumKey}_$index" },
                ) { _, item ->
                    ArchivePreviewItemRow(item)
                }
            }
            if (preview.parseErrors.isNotEmpty()) {
                item {
                    ArchiveSubHeader(
                        text = "解析失败的条目（${preview.parseErrors.size}）",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                itemsIndexed(
                    items = preview.parseErrors,
                    key = { index, _ -> "preview_error_$index" },
                ) { _, error ->
                    ArchiveParseErrorRow(error)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (state.mode == ArchiveImportMode.REPLACE) {
                        Text(
                            text = "将先自动备份当前音乐库，再清空本地库并导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = onImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = state.canImport,
                        shape = RoundedCornerShape(16.dp),
                        colors = if (state.mode == ArchiveImportMode.REPLACE) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = if (state.mode == ArchiveImportMode.REPLACE) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text("正在导入…")
                        } else {
                            Text(
                                text = if (state.mode == ArchiveImportMode.REPLACE) {
                                    "覆盖全库并导入"
                                } else {
                                    "开始导入"
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---- 导入失败 ----
        val importError = state.importError
        if (importError != null) {
            item {
                ImportErrorCard(
                    message = importError,
                    title = "导入失败",
                    onRetry = onImport,
                )
            }
        }

        // ---- 导入结果报告 ----
        if (report != null) {
            item { ArchiveImportReportCard(report) }
            if (report.items.isNotEmpty()) {
                item { ArchiveSubHeader("逐条结果（${report.items.size} 项）") }
                itemsIndexed(
                    items = report.items,
                    key = { index, item -> "report_${item.albumKey}_$index" },
                ) { _, item ->
                    ArchiveItemResultRow(item)
                }
            }
            if (report.parseErrors.isNotEmpty()) {
                item {
                    ArchiveSubHeader(
                        text = "解析失败的条目（${report.parseErrors.size}）",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                itemsIndexed(
                    items = report.parseErrors,
                    key = { index, _ -> "report_error_$index" },
                ) { _, error ->
                    ArchiveParseErrorRow(error)
                }
            }
        } else if (preview == null && !state.isPreviewing && !state.isImporting &&
            previewError == null && importError == null
        ) {
            item {
                Text(
                    text = "步骤：选择导入模式 → 粘贴或选择归档文件 → 解析预览 → 开始导入。" +
                        "导入完成后会在这里显示逐条结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
        }
    }
}

/** Tab 内小节标题。 */
@Composable
private fun ArchiveSubHeader(
    text: String,
    color: Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color ?: MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

// ---------------------------------------------------------------------------
// Tab 内容：搜索导入
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchImportTab(
    state: ImportUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenPreview: (BiliVideoSearchItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ImportListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            SearchField(
                query = state.searchQuery,
                onQueryChange = onQueryChange,
                placeholder = "搜索 B 站视频、专辑",
                onSearch = onSearch,
            )
        }
        item {
            Text(
                text = "在 B 站搜索完整专辑视频，点击结果先预览、再导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }

        val searchError = state.searchError
        when {
            state.isSearching -> item { ImportLoadingCard("正在搜索 B 站视频…") }

            searchError != null -> item {
                ImportErrorCard(message = searchError, title = "搜索失败", onRetry = onSearch)
            }

            !state.hasSearched -> item {
                EmptyState(
                    title = "搜索 B 站视频",
                    description = "输入关键词，例如「周杰伦 专辑」「KOKIA 精选集」，先预览再导入。",
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                )
            }

            state.searchResults.isEmpty() -> item {
                // 空结果可能是关键词无匹配，也可能是风控/接口异常导致的空列表：给一个重试入口
                EmptyState(
                    title = "没有找到相关视频",
                    description = "换个关键词试试，或检查网络后重试。",
                    actionLabel = "重新搜索",
                    onAction = onSearch,
                )
            }

            else -> itemsIndexed(
                items = state.searchResults,
                key = { index, item -> "${item.bvid}_$index" },
            ) { _, item ->
                SearchResultRow(
                    item = item,
                    onClick = { onOpenPreview(item) },
                )
            }
        }
    }
}

/** 封面选择弹层当前服务于哪个预览。 */
private enum class CoverPickerTarget { BV, SEARCH }
