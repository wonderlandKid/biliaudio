package com.wonderlandkid.biliaudio.ui.imports

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchiveItemAction
import com.wonderlandkid.biliaudio.data.model.ArchiveItemResult
import com.wonderlandkid.biliaudio.data.model.ArchiveParseError
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchivePreviewItem
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils
import java.util.Locale
import com.wonderlandkid.biliaudio.ui.components.BiliTextButton
import com.wonderlandkid.biliaudio.ui.theme.biliClickable
import com.wonderlandkid.biliaudio.ui.components.BiliButton

// ---------------------------------------------------------------------------
// 通用小部件
// ---------------------------------------------------------------------------

/** 圆角信息胶囊（时长 / 分 P 数 / BV 号 / 统计）。 */
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

/** 加载态卡片（解析/预览/导入中）。 */
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
                BiliTextButton(
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

/** 示例代码块：小号等宽字体 + 横向滚动，避免中文/字段被软换行拆碎。 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = text,
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
    onSplitTextChange: (String) -> Unit,
    splitTimeFormat: com.wonderlandkid.biliaudio.data.impl.SplitTextParser.TimeFormat,
    onSplitTimeFormatChange: (com.wonderlandkid.biliaudio.data.impl.SplitTextParser.TimeFormat) -> Unit,
    onImportModeChange: (ImportMode) -> Unit,
    onChangeCover: () -> Unit,
    onImport: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    resetLabel: String = "再导入一个",
) {
    val info = state.info ?: return
    val context = LocalContext.current
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
                // 封面可点击更换（相册 / 网易云搜索）；有 pendingCover 时预览选中的封面
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .biliClickable(onClick = onChangeCover, pressedScale = 0.96f),
                ) {
                    CoverImage(
                        url = state.pendingCoverModel,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 16.dp,
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                    ) {
                        Text(
                            text = "换封面",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
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
                    BiliTextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.bilibili.com/video/${info.bvid}"),
                                    ),
                                )
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("在 B 站打开（看分段/时间点）", style = MaterialTheme.typography.labelMedium)
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

            // ---- 多 P 导入方式 ----
            if (info.pages.size > 1) {
                Text(
                    text = "该视频包含 ${info.pages.size} 个分 P，选择导入方式：",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = state.importMode == ImportMode.SINGLE_VIDEO,
                        onClick = { onImportModeChange(ImportMode.SINGLE_VIDEO) },
                        label = { Text("整段视频") },
                    )
                    FilterChip(
                        selected = state.importMode == ImportMode.MULTI_PAGE,
                        onClick = { onImportModeChange(ImportMode.MULTI_PAGE) },
                        label = { Text("按分 P 自动分曲") },
                    )
                }
                Text(
                    text = if (state.importMode == ImportMode.MULTI_PAGE) {
                        "每个分 P 自动成为一首曲目；播放时所有分 P 预解析入队，自动无缝衔接。"
                    } else {
                        "整段视频作为一张专辑导入，可配合下方分曲文本手动分曲。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            if (!state.isMultiPageMode) {
            // ---- 可选：分曲文本（评论区「歌名 + 时间点」） ----
            Text(
                text = "分曲文本（可选）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "粘贴带时间点的歌名列表可自动生成分曲；不填则先导入为未分割专辑" +
                    "（整张按一首播放），之后可在分曲编辑里补时间点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.splitText,
                onValueChange = onSplitTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "1.Breakaway00:00\n00:00 Tap Out\n Come Together00:00",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                minLines = 6,
                maxLines = 12,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                ),
            )
            com.wonderlandkid.biliaudio.ui.components.ImportTimeFormatSelector(
                selected = splitTimeFormat,
                onSelect = onSplitTimeFormatChange,
            )
            if (state.splitText.isNotBlank()) {
                val parsed = state.splitParse
                val segments = state.splitSegments
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "已识别 ${segments.size} 个分割点 · 跳过 ${parsed?.skippedLines?.size ?: 0} 行",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (segments.isEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.weight(1f),
                    )
                    BiliTextButton(onClick = { onSplitTextChange("") }) { Text("清空") }
                }
                if (segments.isEmpty()) {
                    Text(
                        text = "未识别到有效时间点：请检查格式（示例：00:42 歌名 / 歌名00:42 / " +
                            "1.歌名00:42），或清空分曲文本后以未分割专辑导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    val styleHint = parsed?.styleHint
                    if (!styleHint.isNullOrBlank()) {
                        Text(
                            text = styleHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 前几条识别结果，便于用户快速确认
                    segments.take(4).forEach { spec ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            InfoPill(TimeUtils.formatDuration(spec.startMs))
                            Text(
                                text = spec.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (segments.size > 4) {
                        Text(
                            text = "… 等共 ${segments.size} 个分割点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    val skipped = parsed?.skippedLines.orEmpty()
                    if (skipped.isNotEmpty()) {
                        Text(
                            text = "跳过的行：${skipped.take(2).joinToString("  /  ")}" +
                                if (skipped.size > 2) " 等 ${skipped.size} 行" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
                BiliButton(
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
                        // 未填分曲文本时明确提示会导入为未分割专辑
                        Text(
                            text = when {
                                state.isMultiPageMode -> "导入 ${info.pages.size} 个分 P 为曲目"
                                state.splitText.isBlank() -> "导入为未分割专辑"
                                else -> "导入到音乐库"
                            },
                        )
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
                text = if (state.importedTrackCount == 0) {
                    "《${state.importedTitle ?: state.albumTitle}》已导入（未分割，整张专辑按一首播放），" +
                        "之后可在分曲编辑里补时间点。"
                } else {
                    "《${state.importedTitle ?: state.albumTitle}》已加入音乐库，" +
                        "共 ${state.importedTrackCount} 首曲目。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BiliButton(
                    onClick = { onOpenAlbum(albumId) },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("查看专辑")
                }
                BiliTextButton(
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
// 归档 / 批量导入：模式选择 / 格式说明 / 预览 / 结果
// ---------------------------------------------------------------------------

/** 导入模式文案。 */
fun archiveModeLabel(mode: ArchiveImportMode): String = when (mode) {
    ArchiveImportMode.MERGE -> "合并导入"
    ArchiveImportMode.SEGMENTS_ONLY -> "仅更新分割点"
    ArchiveImportMode.REPLACE -> "覆盖全库"
}

private fun archiveModeDescription(mode: ArchiveImportMode): String = when (mode) {
    ArchiveImportMode.MERGE ->
        "按 BV 号 + 分 P 匹配：不存在则新建，已存在则更新元信息与分曲（保留播放次数）。"

    ArchiveImportMode.SEGMENTS_ONLY ->
        "只替换已存在专辑的分曲时间点；不新建专辑，也不修改标题/艺术家等元信息。"

    ArchiveImportMode.REPLACE ->
        "先自动备份当前音乐库，再清空本地库并导入此文件；操作不可撤销，请谨慎选择。"
}

/** 导入模式单选（胶囊分段按钮 + 危险项样式）。 */
@Composable
fun ArchiveModeSelector(
    mode: ArchiveImportMode,
    onSelect: (ArchiveImportMode) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Text(text = "导入模式", style = MaterialTheme.typography.titleMedium)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    ArchiveImportMode.entries.forEach { candidate ->
                        val selected = candidate == mode
                        val isDanger = candidate == ArchiveImportMode.REPLACE
                        val container = when {
                            selected && isDanger -> MaterialTheme.colorScheme.errorContainer
                            selected -> MaterialTheme.colorScheme.surface
                            else -> Color.Transparent
                        }
                        val content = when {
                            selected && isDanger -> MaterialTheme.colorScheme.error
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(container)
                                .clickable { onSelect(candidate) }
                                .padding(horizontal = Spacing.xs, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = archiveModeLabel(candidate),
                                style = MaterialTheme.typography.labelMedium,
                                color = content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                text = archiveModeDescription(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode == ArchiveImportMode.REPLACE) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "覆盖前会自动备份当前音乐库到应用私有目录；如需保留数据，建议先「导出音乐库」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 「支持的格式」卡片：
 * 主推归档 JSON（核心字段 + 最小分割点示例，示例由数据层 Codec 生成），
 * 旧 JSON 数组 / CSV / 文本块折叠在「兼容旧格式」里。
 */
@Composable
fun ArchiveFormatGuide(
    sampleSegmentJson: String,
    modifier: Modifier = Modifier,
) {
    var showSample by remember { mutableStateOf(false) }
    var showLegacy by remember { mutableStateOf(false) }
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
            Text(text = "支持的格式", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "推荐归档 JSON（*.biliaudio.json）：一个文件承载「专辑 + 分曲时间点」，" +
                    "可做整库备份、单张专辑分享、评论区分割点文件；导入时自动识别格式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "核心字段：format=\"biliaudio-library\"、version、albums[]" +
                    "（每项 bvid 或 url、page、title / artist 可选、segments[]）。" +
                    "分曲时间点二选一：startMs（毫秒整数）或 start（00:00 / 00:45 / 01:02:03.500）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BiliTextButton(onClick = { showSample = !showSample }) {
                    Text(if (showSample) "收起示例" else "查看最小分割点示例")
                }
                BiliTextButton(onClick = { showLegacy = !showLegacy }) {
                    Text(if (showLegacy) "收起旧格式" else "兼容旧格式")
                }
            }
            if (showSample) {
                CodeBlock(text = sampleSegmentJson.ifBlank { "（示例生成失败，请参考 docs/LIBRARY_ARCHIVE.md）" })
            }
            if (showLegacy) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = "旧「快捷导入」格式仍可粘贴或选择文件，系统自动识别为 legacy-batch：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CodeBlock(
                        text = "JSON 数组：[{\"bvid\":\"BV1GJ411x7h7\",\"title\":\"专辑名\"," +
                            "\"segments\":[{\"start\":\"00:00\",\"name\":\"曲目一\"}]}]\n" +
                            "CSV：每行 BV号,专辑名,艺术家,时间点,歌名（同一 BV 多行自动合并）\n" +
                            "文本块：第一行「BV号 专辑名」，后续缩进行「mm:ss 歌名」，空行分隔专辑",
                    )
                }
            }
        }
    }
}

/** 预览卡片头：识别格式、专辑数、分曲数、是否含设置。 */
@Composable
fun ArchivePreviewCard(
    preview: ArchivePreview,
    modifier: Modifier = Modifier,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "解析预览",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                InfoPill(
                    text = when (preview.recognizedAs) {
                        "biliaudio-library" -> "归档格式 v1"
                        "legacy-batch" -> "兼容旧格式"
                        else -> preview.recognizedAs
                    },
                )
            }
            Text(
                text = "以下为计划动作，尚未写入数据库；确认无误后点「开始导入」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                InfoPill("专辑 ${preview.albumCount}")
                InfoPill("分曲 ${preview.trackCount}")
                InfoPill(if (preview.hasSettings) "含设置" else "无设置")
            }
        }
    }
}

/** 单条预览项：标题 / BV 号 / 分曲数 / 计划动作徽章 / note。 */
@Composable
fun ArchivePreviewItemRow(
    item: ArchivePreviewItem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md - 2.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.ifBlank { item.albumKey.ifBlank { "（未识别条目）" } },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                ArchiveActionBadge(item.plannedAction)
            }
            Text(
                text = buildString {
                    if (item.albumKey.isNotBlank()) append(item.albumKey)
                    if (isNotEmpty()) append(" · ")
                    append("${item.segmentCount} 个分曲")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.note.isNullOrBlank()) {
                Text(
                    text = item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 导入结果卡片头：模式 + 统计（新建/更新/分曲更新/跳过/失败）+ 覆盖模式自动备份路径。 */
@Composable
fun ArchiveImportReportCard(
    report: ArchiveImportReport,
    modifier: Modifier = Modifier,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "导入完成",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                InfoPill(archiveModeLabel(report.mode))
            }
            // 统计
            Row(modifier = Modifier.fillMaxWidth()) {
                ArchiveStatColumn("新建", report.createdCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                ArchiveStatColumn("更新", report.updatedCount, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                ArchiveStatColumn(
                    "分曲更新",
                    report.segmentsUpdatedCount,
                    MaterialTheme.colorScheme.secondary,
                    Modifier.weight(1f),
                )
                ArchiveStatColumn(
                    "跳过",
                    report.skippedCount,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.weight(1f),
                )
                ArchiveStatColumn("失败", report.failedCount, MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
            // 覆盖全库：自动备份路径提示
            if (report.mode == ArchiveImportMode.REPLACE) {
                val backupPath = report.backupFilePath
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (backupPath != null) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        Text(
                            text = if (backupPath != null) {
                                "覆盖前已自动备份当前音乐库"
                            } else {
                                "覆盖前自动备份失败"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (backupPath != null) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        if (backupPath != null) {
                            Text(
                                text = backupPath,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "如需回滚，可在「归档导入」里选择该备份文件并使用「覆盖全库」恢复。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        } else {
                            Text(
                                text = "未能创建自动备份（可能没有存储空间）。若未手动导出备份，请勿继续依赖旧数据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单条导入结果行：动作徽章 + 专辑信息 + 中文原因。 */
@Composable
fun ArchiveItemResultRow(
    result: ArchiveItemResult,
    modifier: Modifier = Modifier,
) {
    val isFailed = result.action == ArchiveItemAction.FAILED
    val isSkipped = result.action == ArchiveItemAction.SKIPPED
    val container = when {
        isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        isSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = when {
                    isFailed -> Icons.Rounded.ErrorOutline
                    isSkipped -> Icons.Rounded.Info
                    else -> Icons.Rounded.CheckCircle
                },
                contentDescription = null,
                tint = when {
                    isFailed -> MaterialTheme.colorScheme.error
                    isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title.ifBlank { result.albumKey },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    ArchiveActionBadge(result.action)
                    Text(
                        text = result.albumKey,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!result.message.isNullOrBlank()) {
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFailed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 解析错误行（旧格式中无法识别的单条条目）。 */
@Composable
fun ArchiveParseErrorRow(
    error: ArchiveParseError,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = error.source.ifBlank { "未知条目" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** 计划动作 / 导入结果徽章。 */
@Composable
fun ArchiveActionBadge(
    action: ArchiveItemAction,
    modifier: Modifier = Modifier,
) {
    val (label, container, content) = when (action) {
        ArchiveItemAction.CREATED -> Triple(
            "新建",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        ArchiveItemAction.UPDATED -> Triple(
            "更新",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        ArchiveItemAction.SEGMENTS_UPDATED -> Triple(
            "仅更新分曲",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        ArchiveItemAction.SKIPPED -> Triple(
            "跳过",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ArchiveItemAction.FAILED -> Triple(
            "失败",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArchiveStatColumn(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = color,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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

// 供 LazyColumn 使用的小工具（集中导出 padding）。

internal val ImportListPadding = PaddingValues(
    start = Spacing.lg,
    end = Spacing.lg,
    top = Spacing.lg,
    bottom = Spacing.xxl,
)
