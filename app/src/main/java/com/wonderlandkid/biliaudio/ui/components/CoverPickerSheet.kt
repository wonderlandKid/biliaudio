package com.wonderlandkid.biliaudio.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.CoverCandidate
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.imports.CoverPickerViewModel
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 一次封面选择的结果（由调用方负责保存到数据库 / 文件）。
 *
 * - [LocalImage]：用户从相册选的图片（已读取为 [bytes]，可直接压缩保存）；
 * - [NetEase]：网易云专辑封面候选（调用方按 [CoverCandidate.fullUrl] 下载原图后保存）；
 * - [RestoreBilibili]：恢复使用 B 站原始封面（清空自定义封面）。
 */
sealed interface CoverPick {
    data class LocalImage(val uri: Uri, val bytes: ByteArray) : CoverPick

    data class NetEase(val candidate: CoverCandidate) : CoverPick

    data object RestoreBilibili : CoverPick
}

/**
 * 共享封面选择弹层（导入页 / 专辑详情 / 分曲编辑等复用）。
 *
 * 功能：
 * - 展示当前封面预览；
 * - 「从相册选择」：PickVisualMedia 选图后读取字节，回调 [CoverPick.LocalImage]；
 * - 「网易云搜索封面」：搜索网易云专辑，3 列网格选图，回调 [CoverPick.NetEase]；
 * - [allowRestoreBilibili] 为 true 时可回调 [CoverPick.RestoreBilibili]；
 * - 取消 / 下滑关闭回调 [onDismiss]。
 *
 * 组件内不写库、不修改任何数据，选择结果通过 [onPick] 交给调用方。
 * 搜索状态由 [CoverPickerViewModel]（appViewModel 创建）维护。
 *
 * @param visible 是否展示；为 false 时直接不参与组合
 * @param title 弹层标题（如「更换专辑封面」）
 * @param defaultQuery 网易云搜索框默认关键词（通常是「专辑名 + 艺术家」）
 * @param currentCoverModel 当前封面的 Coil 数据（URL / File / Uri / null），用于预览
 * @param allowRestoreBilibili 是否展示「恢复 B 站封面」（专辑已有自定义封面时传 true）
 * @param onDismiss 取消 / 关闭
 * @param onPick 选中封面（调用方保存后自行关闭弹层）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverPickerSheet(
    visible: Boolean,
    title: String,
    defaultQuery: String,
    currentCoverModel: Any?,
    allowRestoreBilibili: Boolean,
    onDismiss: () -> Unit,
    onPick: (CoverPick) -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val viewModel: CoverPickerViewModel = appViewModel { container ->
        CoverPickerViewModel(albumCoverRepository = container.albumCoverRepository)
    }
    val searchState by viewModel.uiState.collectAsStateWithLifecycle()

    var searchOpen by remember { mutableStateOf(false) }
    var isReadingLocal by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    // 每次打开弹层重置搜索词与结果
    LaunchedEffect(Unit) { viewModel.reset(defaultQuery) }

    // 从相册选择：读取图片字节后回调 LocalImage（最多 12MB，避免 OOM）
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReadingLocal = true
        localError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readUriBytes(context, uri, MAX_LOCAL_IMAGE_BYTES) }
            }
            isReadingLocal = false
            val bytes = result.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                onPick(CoverPick.LocalImage(uri = uri, bytes = bytes))
            } else {
                localError = "读取图片失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            // ---- 标题 + 取消 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "从相册选择，或搜索网易云专辑封面",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
            Spacer(Modifier.height(Spacing.md))

            // ---- 当前封面预览 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                CoverImage(
                    url = currentCoverModel,
                    modifier = Modifier.size(84.dp),
                    cornerRadius = 18.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前封面",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (currentCoverModel == null) "暂无封面" else "选择新封面后，确认导入 / 保存才会生效",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))

            // ---- 从相册选择 ----
            Button(
                onClick = {
                    localError = null
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isReadingLocal,
                shape = RoundedCornerShape(16.dp),
            ) {
                if (isReadingLocal) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("读取图片中…")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("从相册选择")
                }
            }
            Spacer(Modifier.height(Spacing.sm))

            // ---- 网易云搜索（可折叠） ----
            OutlinedButton(
                onClick = {
                    if (searchOpen) {
                        searchOpen = false
                    } else {
                        searchOpen = true
                        viewModel.reset(defaultQuery)
                        if (defaultQuery.isNotBlank()) viewModel.search()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(if (searchOpen) "收起网易云搜索" else "网易云搜索封面")
            }

            // ---- 恢复 B 站封面 ----
            if (allowRestoreBilibili) {
                TextButton(
                    onClick = { onPick(CoverPick.RestoreBilibili) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复 B 站原始封面")
                }
            }

            // 本地图片读取错误
            val error = localError
            if (error != null) {
                Spacer(Modifier.height(Spacing.sm))
                CoverPickerError(message = error)
            }

            if (searchOpen) {
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索网易云专辑，如「专辑名 艺术家」") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.search()
                            },
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.search()
                        },
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))

                val searchError = searchState.error
                when {
                    searchState.isSearching -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.lg),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = "正在搜索网易云专辑…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    searchError != null -> CoverPickerError(
                        message = searchError,
                        onRetry = { viewModel.search() },
                    )

                    searchState.hasSearched && searchState.results.isEmpty() -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "没有找到相关封面",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "换个关键词试试，例如「专辑名 艺术家」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { viewModel.search() }) { Text("重试") }
                    }

                    searchState.results.isNotEmpty() -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentPadding = PaddingValues(vertical = Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(
                            items = searchState.results,
                            key = { candidate -> candidate.id },
                        ) { candidate ->
                            CoverCandidateCell(candidate = candidate) {
                                onPick(CoverPick.NetEase(candidate))
                            }
                        }
                    }

                    else -> Text(
                        text = "输入专辑名或艺术家，点搜索按钮查找网易云专辑封面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "封面仅在导入 / 保存时保存到本机；在线封面来自网易云音乐，版权归原平台及版权方所有。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 内部组件 / 工具
// ---------------------------------------------------------------------------

/** 网易云专辑封面候选格子（3 列网格中的一项）。 */
@Composable
private fun CoverCandidateCell(
    candidate: CoverCandidate,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
    ) {
        CoverImage(
            url = candidate.thumbnailUrl.takeIf { it.isNotBlank() },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = candidate.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = candidate.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 弹层内的错误提示（可选重试）。 */
@Composable
private fun CoverPickerError(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** 读取 content Uri 的图片字节（带上限，避免超大图 OOM）。 */
private fun readUriBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("无法打开所选图片")
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("图片过大（超过 ${maxBytes / 1024 / 1024} MB）")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

private const val MAX_LOCAL_IMAGE_BYTES = 12 * 1024 * 1024
