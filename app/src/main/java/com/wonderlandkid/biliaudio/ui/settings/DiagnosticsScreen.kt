package com.wonderlandkid.biliaudio.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志页（设置 → 诊断与维护 → 诊断日志）。
 *
 * - 顶部展示设备/版本信息与当前日志行数；
 * - 日志列表实时刷新（1.5s），并自动跟随最新一条（用户手动上滚后暂停跟随，滚回底部恢复）；
 * - 支持刷新 / 复制全部 / 清空（二次确认）/ 导出日志文件；
 * - 日志来自 [Diag] 内存环形缓冲（最多 3000 行），同时输出 Logcat tag `BiliDiag`。
 *
 * @param onBack 返回上一页
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var lines by remember { mutableStateOf(emptyList<String>()) }
    // 是否自动跟随最新日志（用户手动上滚后暂停，滚回底部恢复）
    var autoFollow by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val deviceInfo = remember { Diag.deviceInfo(context) }

    fun refresh() {
        val snapshot = Diag.snapshot()
        lines = if (snapshot.isBlank()) emptyList() else snapshot.split('\n')
    }

    // 首次进入 + 定时刷新
    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(1500)
            refresh()
        }
    }

    // 用户滚动结束后判断是否在底部：在底部则恢复自动跟随
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                autoFollow = !listState.canScrollForward
            }
        }
    }
    // 日志变化时自动滚到最新一条
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && autoFollow) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    // 导出日志文件：SAF 选择保存位置
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            isExporting = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val content = buildString {
                            appendLine("BiliAudio 诊断日志")
                            appendLine(Diag.deviceInfo(context))
                            appendLine(
                                "导出时间: " + SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    Locale.getDefault(),
                                ).format(Date()),
                            )
                            appendLine("-----------------------------")
                            append(Diag.snapshot())
                        }
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("无法打开目标文件")
                        output.use { stream ->
                            stream.write(content.toByteArray(Charsets.UTF_8))
                            stream.flush()
                        }
                    }.isSuccess
                }
                isExporting = false
                snackbarHostState.showSnackbar(
                    if (ok) "日志已导出（${lines.size} 行）" else "导出失败，请重试",
                )
            }
        }
    }

    fun copyAll() {
        val text = Diag.snapshot()
        if (text.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("暂无日志可复制") }
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("BiliAudio 诊断日志", text))
        scope.launch {
            snackbarHostState.showSnackbar("已复制全部日志（${lines.size} 行）")
        }
    }

    BiliDetailScaffold(
        onBack = onBack,
        title = "诊断日志",
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
            ) {
                // ---- 设备与版本信息 ----
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.cardCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "设备与版本",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            DiagPill("日志 ${lines.size} 行")
                        }
                        Text(
                            text = deviceInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Logcat tag：BiliDiag（adb logcat -s BiliDiag）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))

                // ---- 操作按钮 ----
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            refresh()
                            scope.launch { snackbarHostState.showSnackbar("已刷新") }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("刷新")
                    }
                    OutlinedButton(
                        onClick = { copyAll() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("复制全部")
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch(defaultDiagFileName()) },
                        modifier = Modifier.weight(1f),
                        enabled = !isExporting,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (isExporting) "导出中…" else "导出")
                    }
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("清空")
                    }
                }
                Spacer(Modifier.height(Spacing.sm))

                // ---- 说明文案 ----
                Text(
                    text = "复现问题后，点「复制全部」或「导出」把日志发给开发者帮定位。" +
                        "日志只包含播放、缓冲、网络、导入等技术链路信息，不含账号 Cookie 等敏感数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))

                // ---- 日志列表 ----
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    if (lines.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(
                                title = "暂无日志",
                                description = "操作 App（播放 / 导入 / 网络请求）后，日志会实时出现在这里。",
                                icon = {
                                    Text(
                                        text = "BiliDiag",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                },
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Spacing.md),
                        ) {
                            itemsIndexed(
                                items = lines,
                                key = { index, _ -> index },
                            ) { _, line ->
                                val isErrorLine = line.contains("failed", ignoreCase = true) ||
                                    line.contains("exception", ignoreCase = true) ||
                                    line.contains("error", ignoreCase = true)
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                    ),
                                    color = if (isErrorLine) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }

    // 清空二次确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空诊断日志？") },
            text = { Text("清空后无法恢复；如需保留日志请先「复制全部」或「导出」。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        Diag.clear()
                        refresh()
                        scope.launch { snackbarHostState.showSnackbar("已清空日志") }
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 内部组件 / 工具
// ---------------------------------------------------------------------------


@Composable
private fun DiagPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 默认导出文件名：bili-diag-yyyyMMdd-HHmm.log */
private fun defaultDiagFileName(): String =
    "bili-diag-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.log"
