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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
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
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.SearchField
import com.wonderlandkid.biliaudio.ui.theme.Dimens
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
            appContext = container.appContext,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

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
        if (uri != null) viewModel.loadBatchFile(uri)
    }

    Scaffold(
        modifier = modifier,
        // 系统 insets 已由外层 NavHost 的 Scaffold 处理，这里不再重复 padding
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            ImportTopBar(title = "导入专辑", onBack = onBack)
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
                        onImport = viewModel::importBv,
                        onOpenAlbum = onImported,
                        onReset = viewModel::resetBvImport,
                    )

                    ImportTab.BATCH -> BatchImportTab(
                        state = state,
                        onTextChange = viewModel::updateBatchText,
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                        onClear = viewModel::clearBatch,
                        onImport = {
                            focusManager.clearFocus()
                            viewModel.runBatchImport()
                        },
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
}

// ---------------------------------------------------------------------------
// 顶部栏 / Tab
// ---------------------------------------------------------------------------

@Composable
private fun ImportTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
// Tab 内容：批量导入
// ---------------------------------------------------------------------------

@Composable
private fun BatchImportTab(
    state: ImportUiState,
    onTextChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onClear: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = state.batchReport
    val lineCount = remember(state.batchText) {
        state.batchText.lines().count { it.isNotBlank() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ImportListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ---- 格式说明与示例 ----
        item { BatchFormatGuide() }

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
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = "粘贴导入内容",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = state.batchText,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp),
                        placeholder = { Text("在这里粘贴 JSON 数组 / CSV / 文本块…") },
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
                            enabled = state.batchText.isNotBlank() || report != null,
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
                    if (state.batchFileName != null) {
                        Text(
                            text = "已读取：${state.batchFileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = onImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !state.isBatchImporting && state.batchText.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (state.isBatchImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text("正在导入…（条目较多时需要一段时间）")
                        } else {
                            Text("开始批量导入")
                        }
                    }
                }
            }
        }

        // ---- 错误 ----
        val batchError = state.batchError
        if (batchError != null) {
            item { ImportErrorCard(message = batchError, title = "批量导入失败") }
        }

        // ---- 结果 ----
        if (report != null) {
            item {
                Text(
                    text = "导入结果",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
            item { BatchResultSection(report = report) }
        } else if (!state.isBatchImporting) {
            item {
                Text(
                    text = "导入结果会显示在这里：绿色为成功（专辑名 · 曲目数），红色为失败（原始输入 + 原因）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
        }
    }
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
