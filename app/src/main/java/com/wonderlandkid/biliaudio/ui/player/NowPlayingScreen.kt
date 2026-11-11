package com.wonderlandkid.biliaudio.ui.player

import androidx.compose.animation.core.animateFloat

import kotlin.math.sin

import kotlin.math.PI

import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.graphics.StrokeCap

import androidx.compose.ui.graphics.Path

import androidx.compose.animation.core.RepeatMode

import androidx.compose.animation.core.rememberInfiniteTransition

import androidx.compose.animation.core.infiniteRepeatable

import androidx.compose.ui.unit.IntOffset

import androidx.compose.foundation.layout.offset

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.ui.unit.Dp

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.border

import kotlin.math.roundToInt

import kotlinx.coroutines.delay

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.ui.platform.LocalHapticFeedback

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.hapticfeedback.HapticFeedbackType

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.runtime.mutableLongStateOf

import androidx.compose.runtime.mutableFloatStateOf

import androidx.compose.material3.Surface

import androidx.compose.foundation.gestures.detectVerticalDragGestures

import androidx.compose.animation.fadeOut

import androidx.compose.animation.fadeIn

import androidx.compose.animation.AnimatedVisibility

import android.os.SystemClock

import android.media.AudioManager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.player.PlaybackMode
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.player.QueueEntry
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.components.coverModel
import com.wonderlandkid.biliaudio.ui.player.lyrics.NowPlayingLyricsPane
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils
import kotlinx.coroutines.launch

/** 播放页横向分页：0 = 封面页，1 = 歌词页（左右滑动切换，无指示条）。 */
private const val COVER_PAGE = 0
private const val LYRICS_PAGE = 1
private const val PAGE_COUNT = 2

/**
 * 正在播放页（v0.33.0）。
 *
 * 布局：
 * - 背景主题色渐变；
 * - 无顶栏（去掉左上角收起箭头 / 中央「正在播放」/ 右上角专辑与队列入口）；
 * - 左右翻页：封面页 / 歌词页（无分页指示条，点封面或左右滑仍可切歌词）；
 * - 固定底部：自绘进度条 + 等宽时间 + 播放控制；
 *   播放控制：①合并后的播放模式按钮（随机→顺序→列表循环→单曲循环）
 *   ②上一首 ③播放/暂停 ④下一首 ⑤播放列表；
 * - 去掉「专辑进度」文字；整体控制区通过底部留白上移。
 *
 * 本页是导航层之外的全屏覆盖层，insets 由页面内 Scaffold 处理；
 * 上滑展开/下拉收起由 AppNavHost 的连续手势驱动（页面顶部保留 48dp 下拉关闭热区）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    modifier: Modifier = Modifier,
    /** 重新打开播放页时恢复的页（0 封面 / 1 歌词）；由 AppNavHost 保存。 */
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    /** 供 AppNavHost 做共享元素（连续展开）定位：封面/标题/艺术家在窗口中的位置。 */
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onArtistBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
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
                        initialPage = initialPage,
                        onPageChanged = onPageChanged,
                        onSeek = viewModel::seekToTrackPosition,
                        onTogglePlay = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                        onPrevious = viewModel::previous,
                        onCyclePlaybackMode = viewModel::cyclePlaybackMode,
                        onOpenPlaylist = { showPlaylist = true },
                        onVolumeOffsetChange = viewModel::setVolumeOffsetSteps,
                        onCoverBounds = onCoverBounds,
                        onTitleBounds = onTitleBounds,
                        onArtistBounds = onArtistBounds,
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

/**
 * 播放页主体：横向分页（封面 / 歌词）+ 底部固定进度与控制。
 *
 * 底部控制区上方没有分页指示条、没有专辑进度文字；控制区整体靠底部留白上移。
 */
@Composable
private fun NowPlayingBody(
    state: PlaybackState,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onVolumeOffsetChange: (Int) -> Unit,
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onArtistBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.queue.getOrNull(state.currentIndex)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, PAGE_COUNT - 1),
        pageCount = { PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()

    // 记录当前页，供 AppNavHost 在收起/重新打开时恢复（歌词页收起后再拉起来仍是歌词页）。
    LaunchedEffect(pagerState.currentPage) { onPageChanged(pagerState.currentPage) }

    // 歌词页按返回键：先滑回封面页，不直接关播放页。
    BackHandler(enabled = pagerState.currentPage == LYRICS_PAGE) {
        scope.launch { pagerState.animateScrollToPage(COVER_PAGE) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 曲名/艺术家移到页面顶部（封面页与歌词页共用），字号放大到 headlineLarge（30sp），
        // 与歌词页/播放页的排版统一；封面页不再在封面下方重复显示。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Text(
                text = state.currentTrack?.title.orEmpty(),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.onGloballyPositioned { onTitleBounds(it.boundsInWindow()) },
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
                modifier = Modifier.onGloballyPositioned { onArtistBounds(it.boundsInWindow()) },
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                LYRICS_PAGE -> NowPlayingLyricsPane(modifier = Modifier.fillMaxSize())
                else -> NowPlayingCoverPage(
                    state = state,
                    entry = entry,
                    onOpenLyrics = { scope.launch { pagerState.animateScrollToPage(LYRICS_PAGE) } },
                    onCoverBounds = onCoverBounds,
                    onVolumeOffsetChange = onVolumeOffsetChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 歌词页不显示进度条/时间/播放控制，把空间留给歌词本身。
        if (pagerState.currentPage == COVER_PAGE) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
            PlaybackSeekBar(
                positionMs = state.positionMs,
                durationMs = state.trackDurationMs,
                isPlaying = state.isPlaying,
                onSeek = onSeek,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xxs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = TimeUtils.formatDuration(state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = TimeUtils.formatDuration(state.trackDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            ControlsRow(
                state = state,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onCyclePlaybackMode = onCyclePlaybackMode,
                onOpenPlaylist = onOpenPlaylist,
            )
            // 底部留白：让控制区/进度条相对原来的「贴底」布局整体上移
            Spacer(Modifier.height(Spacing.xxxl))
        }
        }
    }
}

/** 封面页：大封面；点封面进入歌词页。曲名/艺术家已上移到页面顶部。 */
@Composable
private fun NowPlayingCoverPage(
    state: PlaybackState,
    entry: QueueEntry?,
    onOpenLyrics: () -> Unit,
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onVolumeOffsetChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.lg),
        horizontalAlignment = Alignment.Start,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            // 比 v0.35 略小一点：给封面右侧的音量微调小球留出空间，同时保证单手可拨。
            val coverSize = minOf(maxWidth * 0.86f, maxHeight * 0.90f)
            val coverScale by animateFloatAsState(
                targetValue = if (state.isPlaying) 1f else 0.92f,
                animationSpec = tween(
                    durationMillis = BiliMotion.Medium2,
                    easing = BiliMotion.EmphasizedDecelerate,
                ),
                label = "coverScale",
            )
            CoverImage(
                url = entry?.coverModel(),
                modifier = Modifier
                    .size(coverSize)
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                    }
                    .clickable(onClick = onOpenLyrics)
                    .onGloballyPositioned { onCoverBounds(it.boundsInWindow()) },
                cornerRadius = 22.dp,
            )
            // 封面右侧居中：7 档音量微调小球（默认居中，拨动后呈玻璃质感并显示 ±1..±3）。
            VolumeFineTune(
                offsetSteps = state.volumeOffsetSteps,
                onOffsetChange = onVolumeOffsetChange,
                coverSize = coverSize,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
    }
}

/**
 * 自绘播放进度条（Material 3 Expressive 风格：厚轨道 + 大滑块 + 拖动时滑块放大）。
 *
 * 支持点击/拖动定位、无障碍进度语义；不显示专辑进度等额外文字。
 */
@Composable
private fun PlaybackSeekBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxValue = durationMs.coerceAtLeast(1L).toFloat()
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val displayValue = (dragValue ?: positionMs.toFloat()).coerceIn(0f, maxValue)
    val progress = (displayValue / maxValue).coerceIn(0f, 1f)

    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) 9.dp else 6.dp,
        animationSpec = tween(
            durationMillis = BiliMotion.Short4,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekThumbRadius",
    )
    // 波形进度条：播放时 active 轨道按合成频谱包络摆动，暂停时收平为直线。
    // （真正的 FFT 频谱需要 Visualizer + RECORD_AUDIO，会引入麦克风权限；这里用与播放状态同步的
    //   程序化节拍/频谱包络做视觉摆动，避免申请录音权限。）
    val waveTransition = rememberInfiniteTransition(label = "seekWave")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1_800,
                easing = BiliMotion.Emphasized,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "seekWavePhase",
    )
    val waveEnergy by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekWaveEnergy",
    )

    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val waveAmplitudePx = with(density) { 3.2.dp.toPx() }
    val waveLengthPx = with(density) { 26.dp.toPx() }
    val activeStrokeWidthPx = with(density) { 4.5.dp.toPx() }
    val trackHeightPx = with(density) { 8.dp.toPx() }
    val haloRadiusPx = with(density) { 2.dp.toPx() }

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val haloColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
            .pointerInput(maxValue) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((ratio * maxValue).toLong())
                }
            }
            .pointerInput(maxValue) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragValue = (
                            offset.x / size.width.toFloat() * maxValue
                            ).coerceIn(0f, maxValue)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragValue = (
                            change.position.x / size.width.toFloat() * maxValue
                            ).coerceIn(0f, maxValue)
                    },
                    onDragEnd = {
                        dragValue?.let { onSeek(it.toLong()) }
                        dragValue = null
                        dragging = false
                    },
                    onDragCancel = {
                        dragValue = null
                        dragging = false
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                size = Size(size.width, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2f),
            )
            val activeWidth = size.width * progress
            if (activeWidth > 0f) {
                if (waveEnergy > 0.01f) {
                    // 沿 x 方向叠加多频正弦，形成随播放律动的「频谱波浪」；
                    // 振幅越靠滑块越大，尾部收平，避免结束点跳动。
                    val path = Path()
                    var x = 0f
                    val step = 2f
                    while (x <= activeWidth) {
                        val envelope = (x / activeWidth).coerceIn(0f, 1f)
                        val spectrum = 0.55f + 0.45f * sin(x / waveLengthPx * 2.2f + wavePhase * 1.7f)
                        val y = centerY + sin(
                            x / waveLengthPx * (2f * PI).toFloat() + wavePhase,
                        ) * waveAmplitudePx * waveEnergy * envelope * spectrum
                        if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += step
                    }
                    drawPath(
                        path = path,
                        color = activeColor,
                        style = Stroke(width = activeStrokeWidthPx, cap = StrokeCap.Round),
                    )
                } else {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                        size = Size(activeWidth, trackHeightPx),
                        cornerRadius = CornerRadius(trackHeightPx / 2f),
                    )
                }
            }
            val safeWidth = (size.width - thumbRadiusPx).coerceAtLeast(thumbRadiusPx)
            val centerX = activeWidth.coerceIn(thumbRadiusPx, safeWidth)
            drawCircle(
                color = haloColor,
                radius = thumbRadiusPx + haloRadiusPx,
                center = Offset(centerX, centerY),
            )
            drawCircle(
                color = activeColor,
                radius = thumbRadiusPx,
                center = Offset(centerX, centerY),
            )
        }
    }
}

/**
 * 播放控制：合并播放模式 + 上一首 + 播放/暂停 + 下一首 + 播放列表。
 */
@Composable
private fun ControlsRow(
    state: PlaybackState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenPlaylist: () -> Unit,
) {
    val mode = state.playbackMode
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCyclePlaybackMode) {
            Icon(
                imageVector = modeIcon(mode),
                contentDescription = "播放模式：${modeLabel(mode)}（点击切换）",
                tint = if (mode == PlaybackMode.SEQUENTIAL) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
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
        IconButton(onClick = onOpenPlaylist) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = "播放列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modeIcon(mode: PlaybackMode) = when (mode) {
    PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
    PlaybackMode.SEQUENTIAL -> Icons.AutoMirrored.Rounded.PlaylistPlay
    PlaybackMode.LOOP_ALL -> Icons.Rounded.Repeat
    PlaybackMode.LOOP_ONE -> Icons.Rounded.RepeatOne
}

private fun modeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.SHUFFLE -> "随机播放"
    PlaybackMode.SEQUENTIAL -> "顺序播放"
    PlaybackMode.LOOP_ALL -> "列表循环"
    PlaybackMode.LOOP_ONE -> "单曲循环"
}


/**
 * 封面右侧的「音量微调」小球（v0.36：7 档棘轮）。
 *
 * - 位置：小球默认与封面右缘垂直居中对齐；上下拨动时沿封面右缘移动，最多 ±3 档；
 * - 档位：7 个位置（-3..+3），每档 = 系统媒体音量单次变化的 1/4；中心 0 表示不偏移；
 * - 外观：未调整时是低调的半透明小圆点；调整后变成磨砂玻璃质感的大圆球并显示 `+1..+3` / `-1..-3`；
 * - 手势：上下拨动，34dp 一档（避免一次跳 2 格），每档触觉反馈；到 ±3 边界停止。
 */
@Composable
private fun VolumeFineTune(
    offsetSteps: Int,
    onOffsetChange: (Int) -> Unit,
    coverSize: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacingPx = remember(density) { with(density) { 34.dp.toPx() } }
    val coverHalfPx = remember(density, coverSize) { with(density) { coverSize.toPx() } / 2f }

    val currentOffset by rememberUpdatedState(offsetSteps)
    val currentOnChange by rememberUpdatedState(onOffsetChange)

    var dragging by remember { mutableStateOf(false) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var dragBaseOffset by remember { mutableIntStateOf(offsetSteps) }

    val effectiveOffset = if (dragging) dragBaseOffset else currentOffset
    val expanded = dragging || effectiveOffset != 0

    Box(
        modifier = modifier
            .offset { IntOffset(coverHalfPx.roundToInt(), 0) }
            .width(64.dp)
            .height(300.dp)
            .semantics { contentDescription = "音量微调：在封面右侧上下拨动，共 7 档" }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragBaseOffset = currentOffset
                        dragAccumulated = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulated -= dragAmount
                        val steps = (dragAccumulated / spacingPx).toInt()
                        if (steps != 0) {
                            dragAccumulated -= steps * spacingPx
                            val next = (dragBaseOffset + steps).coerceIn(-3, 3)
                            if (next != dragBaseOffset) {
                                dragBaseOffset = next
                                currentOnChange(next)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val knobSize = if (expanded) 44.dp else 14.dp
        // +档向上（增大音量），-档向下；方向与手指一致。
        val knobOffsetY = -effectiveOffset * spacingPx
        val knobBrush = if (expanded) {
            // 玻璃质感：上亮下暗的轻微渐变 + 描边（不使用大面积模糊）。
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                ),
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(0, knobOffsetY.roundToInt()) }
                .size(knobSize)
                .clip(CircleShape)
                .background(knobBrush)
                .then(
                    if (expanded) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (expanded) {
                Text(
                    text = when {
                        effectiveOffset > 0 -> "+$effectiveOffset"
                        effectiveOffset < 0 -> "$effectiveOffset"
                        else -> "0"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
