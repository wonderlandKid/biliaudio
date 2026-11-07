package com.wonderlandkid.biliaudio.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.player.VideoPlaybackState
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.TrackRow
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils
import kotlinx.coroutines.delay

/**
 * 歌词（原视频）页（全屏路由）。
 *
 * - 非全屏：顶部 16:9 视频 + 当前曲信息 / 专辑进度 / 控制 + 专辑分曲列表；
 * - 全屏：视频铺满 + 沉浸式（隐藏系统栏）+ 点击显示/隐藏覆盖控制；
 * - 视频与音频每 500ms 校正一次漂移（>300ms 才 seek），播放/暂停/拖动联动；
 * - 返回键：全屏时先退出全屏，再返回上一页。
 *
 * 注意：本页是导航层之外的全屏路由，非全屏时由页面自己处理系统栏 insets。
 *
 * @param onBack 返回上一页
 */
@Composable
fun VideoScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoScreenViewModel = appViewModel { container ->
        VideoScreenViewModel(
            playbackController = container.playbackController,
            albumRepository = container.albumRepository,
            streamResolver = container.streamResolver,
            context = container.appContext,
            scope = container.appScope,
        )
    },
) {
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val view = LocalView.current

    // 全屏时返回键先退出全屏
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    // 退到后台时暂停视频（音频继续后台播放，回到前台后由同步轮询恢复）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pauseVideo()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进入/退出全屏：隐藏系统栏 + 保持屏幕常亮；离开页面时恢复系统栏
    DisposableEffect(isFullscreen) {
        val window = context.findActivity()?.window
        if (window != null) {
            val barsController = WindowCompat.getInsetsController(window, view)
            if (isFullscreen) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                barsController.hide(WindowInsetsCompat.Type.systemBars())
                barsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                barsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 离开页面时暂停视频（音频继续）；真正 release 在 ViewModel.onCleared()
    DisposableEffect(Unit) {
        onDispose { viewModel.pauseVideo() }
    }

    // 全屏播放时控制层 3 秒后自动隐藏
    LaunchedEffect(isFullscreen, controlsVisible, videoState.isPlaying) {
        if (isFullscreen && controlsVisible && videoState.isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    val currentEntry = audioState.queue.getOrNull(audioState.currentIndex)
    val hasPlayback = audioState.currentTrack != null

    when {
        !hasPlayback -> EmptyVideoPage(onBack = onBack, modifier = modifier)
        isFullscreen -> FullscreenVideoContent(
            audioState = audioState,
            videoState = videoState,
            player = viewModel.player,
            albumTitle = currentEntry?.albumTitle.orEmpty(),
            controlsVisible = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            onExitFullscreen = { isFullscreen = false },
            onTogglePlay = viewModel::togglePlayPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onSeekAlbum = viewModel::seekToAlbumPosition,
            onRetry = viewModel::retry,
            modifier = modifier,
        )
        else -> NonFullscreenVideoContent(
            audioState = audioState,
            videoState = videoState,
            player = viewModel.player,
            albumTitle = currentEntry?.albumTitle.orEmpty(),
            onBack = onBack,
            onEnterFullscreen = {
                controlsVisible = true
                isFullscreen = true
            },
            onTogglePlay = viewModel::togglePlayPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onSeekAlbum = viewModel::seekToAlbumPosition,
            onSelectQueueIndex = viewModel::jumpToQueueIndex,
            onRetry = viewModel::retry,
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------------
// 非全屏
// ---------------------------------------------------------------------------

@Composable
private fun NonFullscreenVideoContent(
    audioState: PlaybackState,
    videoState: VideoPlaybackState,
    player: Player?,
    albumTitle: String,
    onBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekAlbum: (Long) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "歌词视频",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = albumTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEnterFullscreen) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = "进入全屏")
            }
        }

        // 16:9 视频
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            VideoSurface(player = player, modifier = Modifier.fillMaxSize())
            VideoStatusOverlay(state = videoState, onRetry = onRetry)
        }

        // 当前曲目信息
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        ) {
            Text(
                text = audioState.currentTrack?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    audioState.queue.getOrNull(audioState.currentIndex)?.artist,
                    albumTitle.takeIf { it.isNotBlank() },
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 专辑绝对进度（拖到别的曲目会联动音频跳曲）
        AlbumProgressSection(
            audioState = audioState,
            onSeekAlbum = onSeekAlbum,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TransportControls(
            isPlaying = audioState.isPlaying,
            isLoading = audioState.isLoading,
            onTogglePlay = onTogglePlay,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        ComposeQueueList(
            audioState = audioState,
            onSelectQueueIndex = onSelectQueueIndex,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 非全屏页底部的“专辑分曲”列表（与音频队列联动）。 */
@Composable
private fun ComposeQueueList(
    audioState: PlaybackState,
    onSelectQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val queue = audioState.queue
    val currentAlbumId = audioState.currentAlbumId
    val visibleIndices = remember(queue, currentAlbumId) {
        queue.indices.filter { currentAlbumId == null || queue[it].albumId == currentAlbumId }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "专辑分曲",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${visibleIndices.size} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items = visibleIndices, key = { index -> queue[index].track.id }) { index ->
                val entry = queue[index]
                TrackRow(
                    title = entry.track.title,
                    subtitle = entry.artist,
                    durationMs = entry.durationMs,
                    onClick = { onSelectQueueIndex(index) },
                    index = entry.track.index,
                    isCurrent = index == audioState.currentIndex,
                    isPlaying = audioState.isPlaying,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 全屏
// ---------------------------------------------------------------------------

@Composable
private fun FullscreenVideoContent(
    audioState: PlaybackState,
    videoState: VideoPlaybackState,
    player: Player?,
    albumTitle: String,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onExitFullscreen: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekAlbum: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoSurface(player = player, modifier = Modifier.fillMaxSize())

        // 点击画面显示 / 隐藏控制层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleControls,
                ),
        )

        // 视频错误 / 加载中提示（不随控制层隐藏）
        VideoStatusOverlay(state = videoState, onRetry = onRetry)

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f),
                            ),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                // 顶部：退出全屏 / 标题
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExitFullscreen) {
                        Icon(
                            imageVector = Icons.Rounded.FullscreenExit,
                            contentDescription = "退出全屏",
                            tint = Color.White,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = audioState.currentTrack?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = albumTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // 底部：专辑进度 + 控制
                AlbumProgressSection(
                    audioState = audioState,
                    onSeekAlbum = onSeekAlbum,
                    contentColor = Color.White.copy(alpha = 0.85f),
                    sliderColors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                TransportControls(
                    isPlaying = audioState.isPlaying,
                    isLoading = audioState.isLoading,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    contentColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.2f),
                    onContainerColor = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 公共子组件
// ---------------------------------------------------------------------------

/** PlayerView 容器：只负责渲染视频，控制条全部用 Compose 自绘。 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { playerView ->
            if (playerView.player !== player) playerView.player = player
        },
        onRelease = { playerView -> playerView.player = null },
        modifier = modifier,
    )
}

/** 视频加载中 / 错误覆盖层。 */
@Composable
private fun VideoStatusOverlay(
    state: VideoPlaybackState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.error != null -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        !state.isPrepared -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }

        else -> Unit
    }
}

/** 专辑绝对进度条（歌词页与视频同步的核心控件）。 */
@Composable
private fun AlbumProgressSection(
    audioState: PlaybackState,
    onSeekAlbum: (Long) -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
    sliderColors: SliderColors = SliderDefaults.colors(),
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val duration = audioState.albumDurationMs.coerceAtLeast(1L)
    val displayValue = (dragValue ?: audioState.albumPositionMs.toFloat())
        .coerceIn(0f, duration.toFloat())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl),
    ) {
        Slider(
            value = displayValue,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeekAlbum(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..duration.toFloat(),
            colors = sliderColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "专辑 " +
                TimeUtils.formatDuration(displayValue.toLong()) +
                " / " +
                TimeUtils.formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

/** 上一首 / 播放暂停 / 下一首。 */
@Composable
private fun TransportControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    onContainerColor: Color = MaterialTheme.colorScheme.onPrimary,
    playButtonSize: Dp = 64.dp,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "上一首",
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
        }
        FilledIconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(playButtonSize),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = onContainerColor,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = onContainerColor,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "下一首",
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 没有正在播放内容时的空状态页。 */
@Composable
private fun EmptyVideoPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "歌词视频",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = "还没有正在播放的内容",
                description = "先播放一首歌，再来看歌词视频",
            )
        }
    }
}

/** 取当前 Activity（用于沉浸式系统栏控制）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
