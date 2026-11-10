package com.wonderlandkid.biliaudio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.BiliGroup
import com.wonderlandkid.biliaudio.ui.components.BiliGroupItem
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 缓存管理页（设置 → 存储）。
 *
 * 展示设备上已缓存的专辑音频（多 P 专辑按专辑聚合：分 P 数 / 总大小 / 档位 / 更新时间），
 * 并支持逐条删除或清空全部。
 */
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CacheManagementViewModel = appViewModel { container ->
        CacheManagementViewModel(
            cacheRepository = container.audioCacheRepository,
            albumRepository = container.albumRepository,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<CachedItemUi?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    BiliDetailScaffold(
        onBack = onBack,
        title = "缓存管理",
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            state.items.isEmpty() -> EmptyState(
                title = "还没有缓存",
                description = "在专辑详情 → 更多菜单里缓存整张专辑音频，离线也能听。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                actionLabel = "返回",
                onAction = onBack,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = BiliLayout.pageHorizontal,
                    end = BiliLayout.pageHorizontal,
                    top = Spacing.sm,
                    bottom = BiliLayout.pageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "summary") {
                    CacheSummaryCard(
                        count = state.items.size,
                        totalBytes = state.totalBytes,
                        onClearAll = { showClearConfirm = true },
                    )
                }
                items(
                    items = state.items,
                    key = { item -> item.key },
                ) { item ->
                    CacheAlbumCard(
                        item = item,
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这张专辑的缓存？") },
            text = {
                Text(
                    buildString {
                        append(item.album?.title ?: item.summary.bvid)
                        append("\n")
                        append("已缓存 ${item.summary.cachedPageCount} 个分P 音频（${formatBytes(context, item.summary.bytes)}）")
                        append(" 将一起删除，无法恢复。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(item)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部缓存？") },
            text = { Text("会删除所有已缓存的专辑音频（含多 P 全部分 P），无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearAll()
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CacheSummaryCard(
    count: Int,
    totalBytes: Long,
    onClearAll: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BiliGroup(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "已缓存 $count 张专辑", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "占用 ${formatBytes(context, totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClearAll) { Text("清空全部") }
        }
    }
}

@Composable
private fun CacheAlbumCard(
    item: CachedItemUi,
    onDelete: () -> Unit,
) {
    val summary = item.summary
    val context = LocalContext.current
    BiliGroup(contentPadding = PaddingValues(0.dp)) {
        BiliGroupItem(
            title = item.album?.title ?: "已删除的专辑",
            subtitle = item.album?.artist ?: summary.bvid,
            leading = {
                CoverImage(
                    url = item.album?.coverModel(),
                    modifier = Modifier.size(56.dp),
                    cornerRadius = 14.dp,
                )
            },
            trailing = {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "删除缓存",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            CacheChip(
                buildString {
                    append("音频")
                    summary.audioQualityLabel?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                    append(" · ${formatBytes(context = context, bytes = summary.bytes)}")
                },
            )
            CacheChip("${summary.cachedPageCount} 个分P")
            Text(
                text = "更新于 ${formatDate(summary.updatedAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }

}

@Composable
private fun CacheChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

private fun formatBytes(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes)

private fun formatDate(epochMs: Long): String =
    if (epochMs <= 0L) "-" else SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochMs))
