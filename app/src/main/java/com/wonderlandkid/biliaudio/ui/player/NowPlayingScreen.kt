package com.wonderlandkid.biliaudio.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onArtistBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onPlayBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    sharedElementsAlpha: Float = 1f,
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
                        onCoverBounds = onCoverBounds,
                        onTitleBounds = onTitleBounds,
                        onArtistBounds = onArtistBounds,
                        onPlayBounds = onPlayBounds,
                        sharedElementsAlpha = sharedElementsAlpha,
                        onSeek = viewModel::seekToTrackPosition,
                        onTogglePlay = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                        onPrevious = viewModel::previous,
                        onCyclePlaybackMode = viewModel::cyclePlaybackMode,
                        onOpenPlaylist = { showPlaylist = true },
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
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onArtistBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onPlayBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    sharedElementsAlpha: Float,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenPlaylist: () -> Unit,
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
                modifier = Modifier
                    .graphicsLayer { alpha = sharedElementsAlpha }
                    .onGloballyPositioned { onTitleBounds(it.boundsInWindow()) },
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
                modifier = Modifier
                    .graphicsLayer { alpha = sharedElementsAlpha }
                    .onGloballyPositioned { onArtistBounds(it.boundsInWindow()) },
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
                    onSeek = onSeek,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onCyclePlaybackMode = onCyclePlaybackMode,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenLyrics = { scope.launch { pagerState.animateScrollToPage(LYRICS_PAGE) } },
                    onCoverBounds = onCoverBounds,
                    onPlayBounds = onPlayBounds,
                    sharedElementsAlpha = sharedElementsAlpha,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 封面页：大封面 + 进度/播放控制。控制条在 pager 内部，左右切换时和封面一起滑走。 */
@Composable
private fun NowPlayingCoverPage(
    state: PlaybackState,
    entry: QueueEntry?,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onOpenLyrics: () -> Unit,
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onPlayBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    sharedElementsAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            // 比 v0.35 略小一点：给封面右侧的音量微调小球留出空间，同时保证单手可拨。
            val coverSize = minOf(maxWidth * 0.90f, maxHeight * 0.94f)
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
                        alpha = sharedElementsAlpha
                        scaleX = coverScale
                        scaleY = coverScale
                    }
                    .clickable(onClick = onOpenLyrics)
                    .onGloballyPositioned { onCoverBounds(it.boundsInWindow()) },
                cornerRadius = 22.dp,
            )
        }

        NowPlayingFooter(
            state = state,
            onSeek = onSeek,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrevious = onPrevious,
            onCyclePlaybackMode = onCyclePlaybackMode,
            onOpenPlaylist = onOpenPlaylist,
            onPlayBounds = onPlayBounds,
            playAlpha = sharedElementsAlpha,
        )
        }

    }
}

/** 封面页底部：频谱进度、时间、播放控制（在 pager 内，随封面页一起滑动）。 */
@Composable
private fun NowPlayingFooter(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onPlayBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    playAlpha: Float,
) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        PlaybackSeekBar(
            positionMs = state.positionMs,
            durationMs = state.trackDurationMs,
            onSeek = onSeek,
        )
        Spacer(Modifier.height(Spacing.xs))
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
            onPlayBounds = onPlayBounds,
            playAlpha = playAlpha,
        )
        Spacer(Modifier.height(Spacing.xxl))
    }
}
