package com.wonderlandkid.biliaudio.ui.player

import dev.chrisbanes.haze.rememberHazeState

import dev.chrisbanes.haze.hazeSource

import dev.chrisbanes.haze.blur.hazeBlur

import dev.chrisbanes.haze.blur.HazeColorEffect

import dev.chrisbanes.haze.blur.HazeBlurStyle

import dev.chrisbanes.haze.HazeState

import dev.chrisbanes.haze.HazePerformanceMode

import dev.chrisbanes.haze.HazeInput

import androidx.compose.ui.graphics.BlendMode

import android.os.Build

import com.wonderlandkid.biliaudio.player.RealSpectrumAnalyzer

import androidx.core.content.ContextCompat

import androidx.compose.runtime.DisposableEffect

import androidx.compose.material3.TextButton

import androidx.compose.material3.AlertDialog

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import android.content.pm.PackageManager

import android.content.Context

import android.Manifest

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.ui.draw.shadow

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
    val context = LocalContext.current
    val volumeHazeState = rememberHazeState()

    // 真实频谱（需要麦克风权限，仅在用户确认后启用；拒绝则回退合成波形）
    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var spectrumBands by remember { mutableStateOf(FloatArray(0)) }
    var showSpectrumPrompt by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioPermissionGranted = granted
        spectrumPrompted(context)
    }
    LaunchedEffect(state.audioSessionId, audioPermissionGranted) {
        if (!audioPermissionGranted && state.audioSessionId != 0 && !isSpectrumPrompted(context)) {
            showSpectrumPrompt = true
        }
    }
    DisposableEffect(audioPermissionGranted, state.audioSessionId) {
        if (!audioPermissionGranted || state.audioSessionId == 0) {
            spectrumBands = FloatArray(0)
            onDispose { }
        } else {
            val analyzer = RealSpectrumAnalyzer(state.audioSessionId) { bands ->
                spectrumBands = bands
            }
            analyzer.start()
            onDispose { analyzer.release() }
        }
    }
    var showPlaylist by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 播放错误（含流地址过期重试失败）统一用 Snackbar 提示，展示后清空
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.dismissError()
    }

    if (showSpectrumPrompt) {
        AlertDialog(
            onDismissRequest = {
                showSpectrumPrompt = false
                spectrumPrompted(context)
            },
            title = { Text("真实频谱") },
            text = {
                Text(
                    "让播放进度条随歌曲的真实频谱摆动，需要麦克风权限。\n" +
                        "只会读取当前播放音频的能量用于动画，不会录音、保存或上传；" +
                        "可随时在系统设置中关闭。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSpectrumPrompt = false
                    spectrumPrompted(context)
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSpectrumPrompt = false
                    spectrumPrompted(context)
                }) { Text("暂不") }
            },
        )
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
            )
            .hazeSource(volumeHazeState),
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
                        spectrumBands = spectrumBands,
                        volumeHazeState = volumeHazeState,
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
    spectrumBands: FloatArray,
    volumeHazeState: HazeState,
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
                    volumeHazeState = volumeHazeState,
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
                spectrumBands = spectrumBands,
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
    volumeHazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            // 比 v0.35 略小一点：给封面右侧的音量微调小球留出空间，同时保证单手可拨。
            val coverSize = minOf(maxWidth * 0.84f, maxHeight * 0.88f)
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
        }

        // 音量微调：完美隐藏，点一下在屏幕最右侧显形，再拖动；
        // 位置紧贴屏幕边框，不占封面空间（触控区向左延伸，视觉条贴边）。
        VolumeFineTune(
            offsetSteps = state.volumeOffsetSteps,
            onOffsetChange = onVolumeOffsetChange,
            hazeState = volumeHazeState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
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
    spectrumBands: FloatArray,
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
    // 频谱进度条（v0.38，成熟 seekbar 中常见的「实时频谱/波形」方案）：
    // - 整条由竖条频谱组成，已播放部分用主题色「染色」，未播放部分完全透明；
    // - 播放时优先用真实频谱（Visualizer 授权），否则用合成频谱回退；
    // - 拖动 seek 时把振幅压缩到 30%，避免跳动影响定位手感。
    val waveTransition = rememberInfiniteTransition(label = "seekSpectrum")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2_400,
                easing = BiliMotion.Emphasized,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "seekSpectrumPhase",
    )
    val spectrumActive by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekSpectrumActive",
    )
    val spectrumCompression by animateFloatAsState(
        targetValue = if (dragging) 0.3f else 1f,
        animationSpec = tween(
            durationMillis = BiliMotion.Short4,
            easing = BiliMotion.Emphasized,
        ),
        label = "seekSpectrumCompression",
    )

    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val barWidthPx = with(density) { 2.5.dp.toPx() }
    val barGapPx = with(density) { 2.dp.toPx() }
    val minBarHeightPx = with(density) { 2.5.dp.toPx() }
    val maxBarHeightPx = with(density) { 19.dp.toPx() }
    val haloRadiusPx = with(density) { 2.dp.toPx() }

    val activeColor = MaterialTheme.colorScheme.primary
    val haloColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val liveBands = spectrumBands

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
            val activeWidth = size.width * progress
            val barCount = (((size.width + barGapPx) / (barWidthPx + barGapPx)).toInt())
                .coerceIn(12, 96)
            val step = if (barCount > 1) (size.width - barWidthPx) / (barCount - 1) else 0f
            val scale = spectrumActive * spectrumCompression
            val hasLiveSpectrum = liveBands.size >= 2

            // 已播放部分：实时频谱竖条（未播放部分保持透明，不画底轨）。
            for (index in 0 until barCount) {
                val left = index * step
                val barCenter = left + barWidthPx / 2f
                if (barCenter > activeWidth + 0.5f) break
                val fraction = index / (barCount - 1f).coerceAtLeast(1f)
                val norm = if (hasLiveSpectrum) {
                    val bandPosition = fraction * (liveBands.size - 1)
                    val lower = bandPosition.toInt().coerceIn(0, liveBands.lastIndex)
                    val upper = (lower + 1).coerceAtMost(liveBands.lastIndex)
                    val t = (bandPosition - lower).coerceIn(0f, 1f)
                    val energy = liveBands[lower] * (1f - t) + liveBands[upper] * t
                    // 低频段一般能量更高，做一点视觉压缩避免前段过高。
                    (energy * 1.15f).coerceIn(0f, 1f)
                } else {
                    // 无权限时的合成频谱回退（与播放状态同步的节拍包络）。
                    val low = sin(fraction * 13f + wavePhase) * 0.5f + 0.5f
                    val high = sin(fraction * 37f - wavePhase * 1.6f) * 0.5f + 0.5f
                    (0.22f + 0.58f * low + 0.20f * high).coerceIn(0f, 1f)
                }
                val barHeight = minBarHeightPx + (maxBarHeightPx - minBarHeightPx) * norm * scale
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(left, centerY - barHeight / 2f),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = CornerRadius(barWidthPx / 2f),
                )
            }

            // 小圆点：位于已播放/未播放边界，像给频谱线「染色」。
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
 * 屏幕最右侧的音量微调细条（v0.38：完美隐藏 / 点按显形 / 拖拽调整 / 液态玻璃模糊）。
 *
 * - 平时完全隐身（只有透明触控区）；点一下在屏幕最右侧浮现；
 * - 显形后上下拖动调整 7 档（-3..+3，每档=系统音量 1/4，20dp 一档）；
 * - 视觉条紧贴屏幕右边缘（不占封面空间），材质为 Haze 背景模糊（API31+；低版本半透明兜底）；
 * - 拖动/点按后 2s 自动隐藏，保证不打扰封面与歌词。
 */
@Composable
private fun VolumeFineTune(
    offsetSteps: Int,
    onOffsetChange: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacingPx = remember(density) { with(density) { 20.dp.toPx() } }

    val currentOffset by rememberUpdatedState(offsetSteps)
    val currentOnChange by rememberUpdatedState(onOffsetChange)

    var revealed by remember { mutableStateOf(false) }
    var revealedTick by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var dragBaseOffset by remember { mutableIntStateOf(offsetSteps) }

    LaunchedEffect(revealedTick) {
        if (!revealed) return@LaunchedEffect
        delay(2_000)
        revealed = false
    }
    val revealAlpha by animateFloatAsState(
        targetValue = if (revealed || dragging) 1f else 0f,
        animationSpec = tween(BiliMotion.Short4, easing = BiliMotion.Emphasized),
        label = "volumeFineTuneReveal",
    )

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val tintColor = MaterialTheme.colorScheme.surface
    val fallbackColor = containerColor.copy(alpha = 0.94f)
    val blurStyle = remember(containerColor, tintColor) {
        val glassTint = HazeColorEffect.tint(
            color = tintColor.copy(alpha = 0.22f),
            blendMode = BlendMode.SrcOver,
        )
        val fallbackTint = HazeColorEffect.tint(
            color = fallbackColor,
            blendMode = BlendMode.SrcOver,
        )
        HazeBlurStyle {
            blurRadius(28.dp)
            backgroundColor(containerColor.copy(alpha = 0.14f))
            colorEffects(listOf(glassTint))
            fallbackColorEffect(fallbackTint)
            noiseFactor(0.04f)
        }
    }
    val glassModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = blurStyle,
            performanceMode = HazePerformanceMode.Balanced,
        )
    } else {
        Modifier.background(fallbackColor)
    }

    Box(
        modifier = modifier
            .width(48.dp)
            .height(240.dp)
            .semantics { contentDescription = "音量微调：点按后在屏幕右缘显形，上下拖动" }
            .pointerInput(Unit) {
                detectTapGestures {
                    revealed = true
                    revealedTick = SystemClock.elapsedRealtime()
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        revealed = true
                        revealedTick = SystemClock.elapsedRealtime()
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
                            revealedTick = SystemClock.elapsedRealtime()
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        revealedTick = SystemClock.elapsedRealtime()
                    },
                    onDragCancel = {
                        dragging = false
                        revealedTick = SystemClock.elapsedRealtime()
                    },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = revealAlpha }
                .width(24.dp)
                .height(128.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(glassModifier)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 3.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                repeat(7) { index ->
                    val dotActive = index == dragBaseOffset + 3
                    Box(
                        modifier = Modifier
                            .width(if (dotActive) 12.dp else 6.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (dotActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                            ),
                    )
                }
            }
            if (dragBaseOffset != 0) {
                Text(
                    text = if (dragBaseOffset > 0) "+$dragBaseOffset" else "$dragBaseOffset",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** 真实频谱权限提示是否已问过（只问一次，之后可在系统设置修改）。 */
private fun isSpectrumPrompted(context: android.content.Context): Boolean =
    context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
        .getBoolean("spectrum_prompted", false)

private fun spectrumPrompted(context: android.content.Context) {
    context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
        .edit().putBoolean("spectrum_prompted", true).apply()
}
