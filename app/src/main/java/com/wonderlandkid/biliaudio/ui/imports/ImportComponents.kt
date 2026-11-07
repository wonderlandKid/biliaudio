package com.wonderlandkid.biliaudio.ui.imports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wonderlandkid.biliaudio.data.model.BatchImportReport
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils
import java.util.Locale

// ---------------------------------------------------------------------------
// 通用小部件
// ---------------------------------------------------------------------------

/** 圆角信息胶囊（时长 / 分 P 数 / BV 号）。 */
@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 加载态卡片（解析/搜索/导入中）。 */
@Composable
fun ImportLoadingCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 内联错误卡片，可选「重试」按钮。 */
@Composable
fun ImportErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "操作失败",
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text("重试")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 视频预览卡片（BV Tab 与搜索预览共用）
// ---------------------------------------------------------------------------

/**
 * 视频解析结果预览 + 专辑元信息编辑 + 导入按钮 / 导入成功卡片。
 *
 * @param resetLabel 成功卡片上「再导入一个 / 关闭」按钮文案
 * @param onReset    成功卡片上重置按钮回调
 * @param onOpenAlbum 成功卡片上「查看专辑」回调（参数为专辑 id）
 */
@Composable
fun VideoPreviewCard(
    state: VideoImportState,
    onAlbumTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onImport: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    resetLabel: String = "再导入一个",
) {
    val info = state.info ?: return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // ---- 视频基本信息 ----
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                CoverImage(
                    url = info.coverUrl,
                    modifier = Modifier.size(92.dp),
                    cornerRadius = 16.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = info.ownerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        InfoPill(TimeUtils.formatDuration(info.durationMs))
                        InfoPill("${info.pages.size} 个分 P")
                        InfoPill(info.bvid)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // ---- 可编辑的专辑元信息 ----
            Text(
                text = "专辑信息（可修改）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.albumTitle,
                onValueChange = onAlbumTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("专辑名") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                ),
            )
            OutlinedTextField(
                value = state.artist,
                onValueChange = onArtistChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("艺术家") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                ),
            )

            // ---- 多 P 视频说明（当前版本仅支持整段导入） ----
            if (info.pages.size > 1) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = "该视频包含 ${info.pages.size} 个分 P，当前版本按整段视频导入为一张专辑；" +
                            "导入后可在专辑详情里手动添加分割点分曲。",
                        modifier = Modifier.padding(Spacing.md),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // ---- 导入按钮 / 成功卡片 ----
            if (state.importedAlbumId != null) {
                ImportSuccessPanel(
                    state = state,
                    onOpenAlbum = onOpenAlbum,
                    onReset = onReset,
                    resetLabel = resetLabel,
                )
            } else {
                Button(
                    onClick = onImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = state.canImport,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text("正在导入…")
                    } else {
                        Text("导入到我的专辑")
                    }
                }
                if (state.importError != null) {
                    Text(
                        text = state.importError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 导入成功提示卡片。 */
@Composable
private fun ImportSuccessPanel(
    state: VideoImportState,
    onOpenAlbum: (Long) -> Unit,
    onReset: () -> Unit,
    resetLabel: String,
) {
    val albumId = state.importedAlbumId ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "导入成功",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "《${state.importedTitle ?: state.albumTitle}》已加入音乐库，" +
                    "共 ${state.importedTrackCount} 首曲目。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = { onOpenAlbum(albumId) },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("查看专辑")
                }
                TextButton(
                    onClick = onReset,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(resetLabel)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 批量导入：格式说明 + 结果列表
// ---------------------------------------------------------------------------

private enum class BatchFormatExample(val label: String) {
    JSON("JSON 数组"),
    CSV("CSV"),
    TEXT("文本块"),
}

/** 批量导入格式说明卡片（文案与 docs/IMPORT_FORMATS.md 保持一致）。 */
@Composable
fun BatchFormatGuide(modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(BatchFormatExample.JSON) }
    Card(
        modifier = modifier.fillMaxWidth(),
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
                text = "支持的格式",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "自动识别 JSON 数组 / CSV / 文本块，按 BV 号解析视频；" +
                    "已存在同一 BV 的专辑会覆盖更新（保留播放次数）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BatchFormatExample.entries.forEach { format ->
                    FilterChip(
                        selected = format == selected,
                        onClick = { selected = format },
                        label = { Text(format.label) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
            // 示例代码块：小号等宽字体 + 横向滚动，避免中文/字段被软换行拆碎
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = when (selected) {
                            BatchFormatExample.JSON -> JSON_EXAMPLE
                            BatchFormatExample.CSV -> CSV_EXAMPLE
                            BatchFormatExample.TEXT -> TEXT_EXAMPLE
                        },
                        modifier = Modifier.padding(Spacing.md),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = when (selected) {
                    BatchFormatExample.JSON ->
                        "JSON：每个数组项一张专辑；title / artist 可省略（默认取视频标题与 UP 主）；" +
                            "segments 可省略（整段视频作为一首歌）；多 P 自动分曲即将支持（当前版本按整段视频导入）；" +
                            "也支持用 \"url\" 代替 \"bvid\"。"

                    BatchFormatExample.CSV ->
                        "CSV：每行一首歌，固定 5 列「BV号,专辑名,艺术家,时间点,歌名」；" +
                            "专辑名 / 艺术家可留空，同一 BV 的多行会自动合并成一张专辑；" +
                            "首行是表头（含 bvid / BV）时自动跳过。"

                    BatchFormatExample.TEXT ->
                        "文本块：以 BV 号 / av 号 / 链接开头且不缩进的行开始一张新专辑，行尾可跟专辑名；" +
                            "其后缩进行为分曲「时间点 歌名」；专辑之间用空行分隔。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "时间点支持：秒数（83）、mm:ss（01:23）、mm:ss.SSS（01:23.500）、hh:mm:ss（1:02:03）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private const val JSON_EXAMPLE = """[
  {
    "bvid": "BV1GJ411x7h7",
    "title": "专辑名（可选）",
    "artist": "艺术家（可选）",
    "segments": [
      { "start": "00:00",
        "name": "曲目一" },
      { "start": "03:21",
        "name": "曲目二" },
      { "start": "07:45.500",
        "name": "曲目三" }
    ]
  }
]"""

private const val CSV_EXAMPLE = """BV1GJ411x7h7,专辑名,艺术家,00:00,曲目一
BV1GJ411x7h7,专辑名,艺术家,03:21,曲目二
BV1uv411q7Mv,另一张专辑,,00:00,第一首"""

private const val TEXT_EXAMPLE = """BV1GJ411x7h7 专辑名
  00:00 曲目一
  03:21 曲目二

BV1uv411q7Mv 另一张专辑
  00:00 第一首"""

/** 批量导入结果列表：顶部成功/失败统计卡片 + 每条结果（成功专辑名·曲目数 / 失败输入+原因）。 */
@Composable
fun BatchResultSection(
    report: BatchImportReport,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ---- 统计 ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BatchStat(label = "成功", value = report.successCount, isError = false)
                Spacer(Modifier.width(Spacing.xl))
                BatchStat(label = "失败", value = report.failureCount, isError = true)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "共 ${report.results.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // ---- 明细 ----
        report.results.forEach { result ->
            when (result) {
                is ImportResult.Success -> BatchResultRow(
                    title = result.albumTitle,
                    subtitle = "已导入 · ${result.trackCount} 首",
                    isError = false,
                )

                is ImportResult.Failure -> BatchResultRow(
                    title = result.input.ifBlank { "（未识别条目）" },
                    subtitle = result.message,
                    isError = true,
                )
            }
        }
    }
}

@Composable
private fun BatchStat(label: String, value: Int, isError: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatchResultRow(
    title: String,
    subtitle: String,
    isError: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 搜索导入
// ---------------------------------------------------------------------------

/** 搜索结果行：封面 / 标题 / UP 主 · 播放量 · 时长。 */
@Composable
fun SearchResultRow(
    item: BiliVideoSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                CoverImage(
                    url = item.coverUrl.ifBlank { null },
                    modifier = Modifier
                        .width(116.dp)
                        .height(66.dp),
                    cornerRadius = 12.dp,
                )
                // 时长角标
                if (item.durationText.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                    ) {
                        Text(
                            text = item.durationText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = "${item.author} · ${formatPlayCount(item.playCount)} 播放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.pageCount > 1) {
                    Spacer(Modifier.height(Spacing.xxs))
                    InfoPill("${item.pageCount} 个分 P")
                }
            }
        }
    }
}

/** 播放量：12345 → 1.2 万。 */
fun formatPlayCount(count: Int): String = when {
    count >= 100_000_000 -> "${trimTrailingZero(count / 100_000_000.0)} 亿"
    count >= 10_000 -> "${trimTrailingZero(count / 10_000.0)} 万"
    count < 0 -> "0"
    else -> count.toString()
}

private fun trimTrailingZero(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

// 供 LazyColumn 使用的小工具（避免 import 顺序问题，集中导出 padding）。
internal val ImportListPadding = PaddingValues(
    start = Spacing.lg,
    end = Spacing.lg,
    top = Spacing.lg,
    bottom = Spacing.xxl,
)
