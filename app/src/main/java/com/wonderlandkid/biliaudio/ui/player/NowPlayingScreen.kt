package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.player.RepeatMode
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils

/**
 * 正在播放页（全屏路由）。
 *
 * 结构：主题色渐变背景 + 大封面 + 标题/艺术家/专辑 + 分曲进度条 + 播放控制 + 播放列表面板 +
 * “看歌词视频”入口。点顶部/列表可以打开播放列表面板（ModalBottomSheet）。
 *
 * 注意：
 * - 本页是导航层之外的全屏路由，由页面内的 Scaffold 自行处理系统栏 insets；
 * - 通知权限（POST_NOTIFICATIONS）由 MainActivity 统一申请，这里不处理。
 *
 * @param onBack 返回上一页
 * @param onOpenVideo 打开歌词（原视频）页
 * @param onOpenAlbum 打开当前专辑详情（可选，不传则顶部不显示入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenVideo: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAlbum: (Long) -> Unit = {},
    viewModel: NowPlayingViewModel = appViewModel { container ->
        NowPlayingViewModel(container.playbackController)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPlaylist by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 播放错误（含流地址过期重试失败）统一用 Snackbar 提示，展示后清空
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.dismissError()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NowPlayingTopBar(
                    onBack = onBack,
                    canOpenAlbum = state.currentAlbumId != null,
                    onOpenAlbum = { state.currentAlbumId?.let(onOpenAlbum) },
                    onTogglePlaylist = { showPlaylist = true },
                )
                if (state.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
                val currentTrack = state.currentTrack
                if (currentTrack == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = "还没有正在播放的内容",
                            description = "从专辑或音乐库选择一首歌开始播放",
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Album,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp),
                                )
                            },
                        )
                    }
                } else {
                    NowPlayingBody(
                        state = state,
                        onSeek = viewModel::seekToTrackPosition,
                        onTogglePlay = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                        onPrevious = viewModel::previous,
                        onCycleRepeat = viewModel::cycleRepeatMode,
                        onToggleShuffle = viewModel::toggleShuffle,
                        onOpenVideo = onOpenVideo,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showPlaylist) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylist = false },
            sheetState = sheetState,
        ) {
            PlaylistPanel(
                state = state,
                onSelectIndex = { index ->
                    viewModel.jumpToQueueIndex(index)
                    showPlaylist = false
                },
            )
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    onBack: () -> Unit,
    canOpenAlbum: Boolean,
    onOpenAlbum: () -> Unit,
    onTogglePlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "返回")
        }
        Text(
            text = "正在播放",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        if (canOpenAlbum) {
            IconButton(onClick = onOpenAlbum) {
                Icon(Icons.Rounded.Album, contentDescription = "专辑详情")
            }
        }
        IconButton(onClick = onTogglePlaylist) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "播放列表")
        }
    }
}

@Composable
private fun NowPlayingBody(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.queue.getOrNull(state.currentIndex)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
    ) {
        // 大封面：在剩余空间内取 min(可用宽, 可用高) 的正方形，小屏不会溢出
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            val coverSize = minOf(maxWidth * 0.82f, maxHeight)
            // 封面优先用本机自定义封面（coverLocalPath），否则回退 B 站远程图
            CoverImage(
                url = entry?.coverModel(),
                modifier = Modifier.size(coverSize),
                cornerRadius = 22.dp,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = state.currentTrack?.title.orEmpty(),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = listOfNotNull(entry?.artist, entry?.albumTitle)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.sm))
        TrackProgressSection(state = state, onSeek = onSeek)
        Spacer(Modifier.height(Spacing.xs))
        ControlsRow(
            state = state,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrevious = onPrevious,
            onCycleRepeat = onCycleRepeat,
            onToggleShuffle = onToggleShuffle,
        )
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(
            onClick = onOpenVideo,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(text = "看歌词视频")
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

/** 分曲进度条 + 时间 + 专辑绝对进度（歌词页同步用）。 */
@Composable
private fun TrackProgressSection(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val maxValue = state.trackDurationMs.coerceAtLeast(1L).toFloat()
    val displayValue = (dragValue ?: state.positionMs.toFloat()).coerceIn(0f, maxValue)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayValue,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..maxValue,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = TimeUtils.formatDuration(displayValue.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = TimeUtils.formatDuration(state.trackDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.albumDurationMs > 0L) {
            Text(
                text = "专辑进度 " +
                    TimeUtils.formatDuration(state.albumPositionMs) +
                    " / " +
                    TimeUtils.formatDuration(state.albumDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxs),
            )
        }
    }
}

/** 随机 / 上一首 / 播放暂停 / 下一首 / 循环。 */
@Composable
private fun ControlsRow(
    state: PlaybackState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = if (state.shuffle) "关闭随机播放" else "开启随机播放",
                tint = if (state.shuffle) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "上一首",
                modifier = Modifier.size(36.dp),
            )
        }
        FilledIconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(76.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "下一首",
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = when (state.repeatMode) {
                    RepeatMode.ONE -> Icons.Rounded.RepeatOne
                    else -> Icons.Rounded.Repeat
                },
                contentDescription = when (state.repeatMode) {
                    RepeatMode.OFF -> "开启列表循环"
                    RepeatMode.ALL -> "切换为单曲循环"
                    RepeatMode.ONE -> "关闭循环"
                },
                tint = if (state.repeatMode == RepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}
