package com.wonderlandkid.biliaudio.ui.player.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderlandkid.biliaudio.data.model.LyricSourceType
import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 歌词来源面板（T4 弹层，ModalBottomSheet）。
 *
 * 分区：
 * - 在线歌词：重新搜索 + 多来源候选（歌名 / 歌手 · 专辑 · 时长，当前使用项打勾）；
 * - 本地歌词：导入本地歌词文件（LRC/YRC/TTML/纯文本）；
 * - 歌词设置：关闭本曲歌词 / 恢复自动匹配。
 *
 * 视觉保持本项目扁平 Material 3（列表项用 MaterialTheme 排版，不新增卡片组件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSourceSheet(
    state: LyricsUiState,
    onSelectCandidate: (OnlineLyricCandidate) -> Unit,
    onSearchOnline: () -> Unit,
    onImportLocal: () -> Unit,
    onSuppress: () -> Unit,
    onRestoreAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
            dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LyricsSourceHeader(state = state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                item { SourceSectionTitle("在线歌词") }
                item {
                    SourceRow(
                        icon = Icons.Rounded.Refresh,
                        title = "重新搜索在线歌词",
                        subtitle = state.searchError ?: "按当前分曲标题重新匹配多来源歌词",
                        enabled = !state.isSearching,
                        onClick = onSearchOnline,
                    )
                }
                when {
                    state.isSearching -> item { SourceLoadingRow() }
                    state.candidates.isEmpty() -> item { SourceHintRow("暂无在线歌词候选") }
                    else -> itemsIndexed(
                        items = state.candidates,
                        key = { index, candidate -> "candidate:$index:${candidate.id}" },
                        contentType = { _, _ -> "candidate" },
                    ) { _, candidate ->
                        val selected = !state.suppressed &&
                            state.sourceType == LyricSourceType.ONLINE &&
                            state.rawText == candidate.rawLyrics
                        SourceRow(
                            icon = Icons.Rounded.Language,
                            title = candidate.name,
                            subtitle = candidate.toSubtitle(),
                            selected = selected,
                            onClick = { onSelectCandidate(candidate) },
                        )
                    }
                }

                item { SourceSectionTitle("本地歌词") }
                item {
                    SourceRow(
                        icon = Icons.Rounded.FolderOpen,
                        title = "导入本地歌词文件",
                        subtitle = "支持 LRC / YRC / TTML / 纯文本（最大 1MB）",
                        onClick = onImportLocal,
                    )
                }

                item { SourceSectionTitle("歌词设置") }
                if (state.suppressed) {
                    item {
                        SourceRow(
                            icon = Icons.Rounded.Replay,
                            title = "恢复歌词匹配",
                            subtitle = "清除本机歌词缓存后重新自动匹配",
                            onClick = onRestoreAuto,
                        )
                    }
                } else {
                    item {
                        SourceRow(
                            icon = Icons.Rounded.MusicOff,
                            title = "关闭本曲歌词",
                            subtitle = "保留歌词缓存与偏移，可随时恢复",
                            onClick = onSuppress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSourceHeader(state: LyricsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lyrics,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "歌词来源",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            Text(
                text = state.currentSourceDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Spacing.xl,
            end = Spacing.xl,
            top = Spacing.lg,
            bottom = Spacing.xs,
        ),
    )
}

/**
 * 来源面板统一行：leading 图标 + 标题/副标题 + 可选打勾。
 *
 * @param selected 当前正在使用的来源（高亮 + 打勾）
 */
@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xxs)
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "当前使用的歌词",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SourceLoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SourceHintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
    )
}

/** 面板头部「当前来源」说明。 */
private fun LyricsUiState.currentSourceDescription(): String {
    if (suppressed) return "已关闭歌词"
    if (rawText.isBlank()) return "尚未使用歌词"
    val typeLabel = when (sourceType) {
        LyricSourceType.ONLINE -> "在线歌词"
        LyricSourceType.LOCAL_FILE -> "本地歌词"
        LyricSourceType.NONE -> "歌词"
    }
    val manualLabel = if (isManual) "手动选择" else "自动匹配"
    val name = sourceName.ifBlank { typeLabel }
    return "$typeLabel · $manualLabel · $name"
}

/** 候选副标题：歌手 · 专辑 · 时长。 */
private fun OnlineLyricCandidate.toSubtitle(): String = buildString {
    if (artist.isNotBlank()) append(artist)
    if (album.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(album)
    }
    if (durationSec > 0) {
        if (isNotEmpty()) append(" · ")
        append(TimeUtils.formatDuration(durationSec * 1000L))
    }
    if (isEmpty()) append("未知来源")
}
