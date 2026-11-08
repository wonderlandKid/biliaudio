package com.wonderlandkid.biliaudio.ui.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.SnackbarHostState
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.BiliGroup
import com.wonderlandkid.biliaudio.ui.components.BiliGroupDivider
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 多 P 专辑：编辑每一 P 的曲目名。
 *
 * 与单视频的「编辑分割点」对应，但这里只改标题，不动分 P 顺序、时长和音频来源。
 */
@Composable
fun MultiPageTrackEditorScreen(
    albumId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MultiPageTrackEditorViewModel = appViewModel { container ->
        MultiPageTrackEditorViewModel(
            albumId = albumId,
            albumRepository = container.albumRepository,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    BiliDetailScaffold(
        onBack = onBack,
        title = "编辑分 P 标题",
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(
                onClick = viewModel::save,
                enabled = state.canSave,
            ) {
                Text(if (state.isSaving) "保存中…" else "保存")
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            state.album == null || state.rows.isEmpty() -> EmptyState(
                title = "没有可编辑的分 P",
                description = "这张专辑没有分 P 曲目，或已被删除。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = BiliLayout.pageHorizontal,
                    end = BiliLayout.pageHorizontal,
                    top = Spacing.sm,
                    bottom = BiliLayout.pageBottom + Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "intro") {
                    Text(
                        text = "只修改每一 P 的曲目名；播放顺序、分 P 来源和时长保持不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
                item(key = "rows") {
                    BiliGroup(contentPadding = PaddingValues(0.dp)) {
                        state.rows.forEachIndexed { index, row ->
                            val track = row.track
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "P${track.sourcePage.takeIf { it > 0 } ?: (index + 1)}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = TimeUtils.formatDuration(track.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(Spacing.xs))
                                OutlinedTextField(
                                    value = row.title,
                                    onValueChange = { viewModel.updateTitle(track.id, it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("曲目名") },
                                    shape = RoundedCornerShape(14.dp),
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            if (index != state.rows.lastIndex) BiliGroupDivider()
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        text = "留空保存时会回退为 P 序号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
            }
        }
    }
}
