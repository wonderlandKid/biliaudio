package com.wonderlandkid.biliaudio.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.LoadingBox
import com.wonderlandkid.biliaudio.ui.components.SearchField
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.ui.components.BiliIconButton
import com.wonderlandkid.biliaudio.ui.components.BiliOutlinedButton
import com.wonderlandkid.biliaudio.ui.components.BiliTextButton
import com.wonderlandkid.biliaudio.ui.components.BiliButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlaylistDetailViewModel = appViewModel { container ->
        PlaylistDetailViewModel(
            playlistId = playlistId,
            playlistRepository = container.playlistRepository,
            albumRepository = container.albumRepository,
            playbackController = container.playbackController,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    BiliDetailScaffold(
        onBack = onBack,
        title = "歌单",
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = {
            if (state.playlist != null) {
                BiliIconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            renameText = state.playlist?.playlist?.name.orEmpty()
                            showRenameDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除歌单", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        val playlist = state.playlist
        when {
            playlist == null -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = BiliLayout.contentBottomWithMiniPlayer),
            ) {
                item(key = "header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) {
                        Text(
                            text = playlist.playlist.name,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${playlist.tracks.size} 首歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Row(
                            modifier = Modifier.padding(top = Spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            BiliButton(
                                onClick = viewModel::playAll,
                                enabled = playlist.tracks.isNotEmpty(),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(Spacing.xs))
                                Text("播放全部")
                            }
                            BiliOutlinedButton(onClick = { showAddSheet = true }) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(Spacing.xs))
                                Text("添加歌曲")
                            }
                        }
                    }
                }
                if (playlist.tracks.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "歌单还是空的",
                            description = "从音乐库里添加喜欢的歌曲。",
                            actionLabel = "添加歌曲",
                            onAction = { showAddSheet = true },
                        )
                    }
                } else {
                    items(
                        items = playlist.tracks,
                        key = { song -> song.track.uid },
                    ) { song ->
                        PlaylistTrackRow(
                            song = song,
                            onClick = {
                                val index = playlist.tracks.indexOfFirst { it.track.uid == song.track.uid }
                                if (index >= 0) viewModel.playAt(index)
                            },
                            onRemove = { viewModel.removeSong(song.track.uid) },
                            isCurrent = song.track.id == state.currentTrackId,
                            isPlaying = state.isPlaying,
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddSongsSheet(
            songs = state.allSongs,
            existingUids = state.playlist?.tracks?.map { it.track.uid }?.toSet().orEmpty(),
            onDismiss = { showAddSheet = false },
            onConfirm = { selected ->
                showAddSheet = false
                viewModel.addSongs(selected)
            },
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("歌单名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                BiliTextButton(
                    onClick = {
                        showRenameDialog = false
                        viewModel.renamePlaylist(renameText)
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                BiliTextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除歌单？") },
            text = { Text("只会删除歌单本身，不会删除音乐库里的歌曲。") },
            confirmButton = {
                BiliTextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deletePlaylist()
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                BiliTextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PlaylistTrackRow(
    song: TrackWithAlbum,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    TrackRow(
        title = song.track.title,
        subtitle = "${song.album.artist} · ${song.album.title}",
        durationMs = song.track.durationMs,
        onClick = onClick,
        leading = {
            CoverImage(
                url = song.coverModel(),
                modifier = Modifier.size(48.dp),
                cornerRadius = 12.dp,
            )
        },
        trailing = {
            BiliIconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.RemoveCircleOutline,
                    contentDescription = "从歌单移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        isCurrent = isCurrent,
        isPlaying = isPlaying,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongsSheet(
    songs: List<TrackWithAlbum>,
    existingUids: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<TrackWithAlbum>) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // 避免从“新建歌单”对话框带过来的键盘挡住歌曲列表。
    LaunchedEffect(Unit) { focusManager.clearFocus() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val filtered = remember(songs, query) {
        val q = query.trim()
        if (q.isEmpty()) songs else songs.filter {
            it.track.title.contains(q, ignoreCase = true) ||
                it.album.title.contains(q, ignoreCase = true) ||
                it.album.artist.contains(q, ignoreCase = true)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = Spacing.lg),
        ) {
            Text(text = "添加歌曲", style = MaterialTheme.typography.titleLarge)
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索歌曲、专辑、艺术家",
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = Spacing.md),
            ) {
                items(filtered, key = { it.track.uid }) { song ->
                    val already = song.track.uid in existingUids
                    val checked = song.track.uid in selected || already
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !already) {
                                selected = if (song.track.uid in selected) {
                                    selected - song.track.uid
                                } else {
                                    selected + song.track.uid
                                }
                            }
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = !already,
                        )
                        CoverImage(
                            url = song.coverModel(),
                            modifier = Modifier.size(40.dp),
                            cornerRadius = 10.dp,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = Spacing.sm),
                        ) {
                            Text(
                                text = song.track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (already) "已在歌单中" else "${song.album.artist} · ${song.album.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            BiliButton(
                onClick = {
                    onConfirm(filtered.filter { it.track.uid in selected })
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.md),
            ) {
                Text("添加 ${selected.size} 首")
            }
        }
    }
}
