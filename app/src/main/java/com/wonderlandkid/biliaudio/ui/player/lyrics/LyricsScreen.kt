package com.wonderlandkid.biliaudio.ui.player.lyrics

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.LyricItem
import com.wonderlandkid.biliaudio.data.model.LyricSourceType
import com.wonderlandkid.biliaudio.data.model.findPlayingIndex
import com.wonderlandkid.biliaudio.data.model.getSentenceContent
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.TimeUtils
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 用户手动滚动后暂停自动滚动的时长。 */
private const val AUTO_SCROLL_PAUSE_MS = 3_000L

/** 判断当前行是否已居中/居中的容差（像素）。 */
private const val CENTER_TOLERANCE_PX = 1f

/**
 * 全屏歌词页（T3 沉浸页，覆盖在「正在播放」页上方，不是独立导航目的地）。
 *
 * 结构（扁平 Material 3，沿用播放页的封面主题色竖向渐变背景，不使用毛玻璃/液态玻璃）：
 * - 顶部栏：返回 + 曲名/艺术家 + 歌词来源入口 + 歌词偏移入口；
 * - 中部：LazyColumn 歌词区（上下 40% 留白让当前行居中、逐行/逐字高亮、点行跳转、自动滚动）；
 * - 底部：当前/总时长 + 上一首/播放暂停/下一首；
 * - 状态：加载中 / 无歌词（重新搜索、导入本地）/ 已关闭歌词（恢复）；
 * - 弹层：来源面板 [LyricsSourceSheet]、偏移面板 [LyricsOffsetSheet]。
 *
 * insets 由页面自己处理（沉浸页规范）；返回键关闭歌词页（弹层打开时由弹层优先消费）。
 * 用独立 ViewModelStore 承载（见 AppNavHost 接入），关闭覆盖层时释放。
 */
@Composable
fun LyricsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LyricsViewModel = appViewModel { container ->
        LyricsViewModel(
            playbackController = container.playbackController,
            lyricsRepository = container.lyricsRepository,
            appContext = container.appContext,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    var showOffsetSheet by rememberSaveable { mutableStateOf(false) }
    val lyricTimeProvider = rememberLyricTimeProvider(playback = playback, delayMs = state.delayMs)

    // 返回键：优先关闭歌词页（回到完全展开的播放页）；来源/偏移弹层打开时由 ModalBottomSheet 先消费。
    BackHandler { onBack() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 本地歌词导入：歌词扩展名没有统一 MIME，使用 */* 并在 ViewModel 里做内容/大小校验。
    val localLyricsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importLocal(uri)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            LyricsTopBar(
                title = state.title.ifBlank { "歌词" },
                artist = state.artist,
                sourceType = state.sourceType,
                delayMs = state.delayMs,
                canAdjustOffset = state.parsed != null,
                onBack = onBack,
                onOpenSource = { showSourceSheet = true },
                onOpenOffset = { showOffsetSheet = true },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val parsed = state.parsed
                when {
                    state.isLoading ||
                        (state.isSearching && parsed == null && !state.suppressed) -> {
                        LyricsLoadingState()
                    }

                    state.suppressed -> {
                        EmptyState(
                            title = "已关闭歌词",
                            description = "本曲不会再匹配/显示歌词，可在「歌词来源」里恢复",
                            modifier = Modifier.fillMaxSize(),
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.MusicOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp),
                                )
                            },
                            actionLabel = "恢复歌词",
                            onAction = viewModel::restoreAuto,
                        )
                    }

                    parsed != null && parsed.lines.isNotEmpty() -> {
                        if (parsed.isSynced) {
                            SyncedLyricsContent(
                                lines = parsed.lines,
                                currentTimeProvider = lyricTimeProvider,
                                onSeekToLine = viewModel::seekToLine,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            PlainLyricsContent(
                                lines = parsed.lines,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    else -> {
                        LyricsEmptyState(
                            title = state.errorMessage ?: "暂无歌词",
                            description = state.searchError
                                ?: "可以重新搜索在线歌词，或导入本地 LRC / YRC / TTML 文件",
                            isSearching = state.isSearching,
                            onSearch = { viewModel.searchOnline() },
                            onImportLocal = { localLyricsPicker.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }
            LyricsBottomBar(
                playback = playback,
                onPrevious = viewModel::previous,
                onTogglePlay = viewModel::togglePlayPause,
                onNext = viewModel::next,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 88.dp),
        )
    }

    if (showSourceSheet) {
        LyricsSourceSheet(
            state = state,
            onSelectCandidate = { candidate ->
                viewModel.selectCandidate(candidate)
                showSourceSheet = false
            },
            onSearchOnline = { viewModel.searchOnline() },
            onImportLocal = {
                showSourceSheet = false
                localLyricsPicker.launch(arrayOf("*/*"))
            },
            onSuppress = {
                viewModel.suppress()
                showSourceSheet = false
            },
            onRestoreAuto = {
                viewModel.restoreAuto()
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false },
        )
    }
    if (showOffsetSheet) {
        LyricsOffsetSheet(
            delayMs = state.delayMs,
            onAdjust = viewModel::adjustOffset,
            onReset = viewModel::resetOffset,
            onDismiss = { showOffsetSheet = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部栏 / 底部控制条 / 状态页
// ---------------------------------------------------------------------------

@Composable
private fun LyricsTopBar(
    title: String,
    artist: String,
    sourceType: LyricSourceType,
    delayMs: Long,
    canAdjustOffset: Boolean,
    onBack: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenOffset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回播放页",
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenSource) {
            Icon(
                imageVector = Icons.Rounded.Lyrics,
                contentDescription = "歌词来源",
                tint = if (sourceType == LyricSourceType.NONE) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        TextButton(
            onClick = onOpenOffset,
            enabled = canAdjustOffset,
        ) {
            Text(
                text = formatLyricOffset(delayMs),
                style = MaterialTheme.typography.labelLarge,
                color = if (canAdjustOffset) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun LyricsBottomBar(
    playback: LyricsPlaybackState,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val durationMs = playback.trackDurationMs.coerceAtLeast(0L)
    val positionMs = playback.positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TimeUtils.formatDuration(positionMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Start,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(56.dp),
            ) {
                if (playback.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playback.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Text(
            text = TimeUtils.formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun LyricsLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "正在匹配歌词…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LyricsEmptyState(
    title: String,
    description: String,
    isSearching: Boolean,
    onSearch: () -> Unit,
    onImportLocal: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            title = title,
            description = description,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = onSearch, enabled = !isSearching) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text("重新搜索")
            }
            TextButton(onClick = onImportLocal) {
                Text("导入本地歌词")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 歌词列表（有时间轴 / 无时间轴）
// ---------------------------------------------------------------------------

/**
 * 有时间轴的歌词列表：逐行高亮 + 逐字推进 + 自动滚动 + 点行跳转。
 *
 * 播放时间用「按帧驱动的 State<Long>」暴露：只有当前逐字行读取它，避免整页每帧重组；
 * 无时间轴歌词走 [PlainLyricsContent]（静态展示，不高亮、不滚动、不可点）。
 */
@Composable
private fun SyncedLyricsContent(
    lines: List<LyricItem>,
    currentTimeProvider: () -> Long,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedLines = remember(lines) { lines.sortedBy { it.time } }
    val listState = rememberLazyListState()
    val latestTimeProvider = rememberUpdatedState(currentTimeProvider)
    val frameTime = remember { mutableLongStateOf(currentTimeProvider()) }

    // 帧级时间：只在歌词区可见/组合期间运行；值不变（暂停）时不会触发任何重组。
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val value = latestTimeProvider.value()
            if (value != frameTime.longValue) frameTime.longValue = value
        }
    }

    val playingIndex by remember(sortedLines) {
        derivedStateOf { sortedLines.findPlayingIndex(frameTime.longValue) }
    }

    // 自动滚动状态：用户手动滚动后暂停 3s，再恢复到当前行。
    var autoScrolling by remember { mutableStateOf(false) }
    var pauseAutoScrollUntil by remember { mutableLongStateOf(0L) }
    var initialCentered by remember(sortedLines) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        // 只认「滚动开始」：程序自动滚动（autoScrolling）不计入；也不能用滚动结束时刻判断，
        // 否则自动滚动每次收尾都会被误判成用户滚动、导致每行都白等 3 秒。
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect {
                if (!autoScrolling) {
                    pauseAutoScrollUntil = SystemClock.elapsedRealtime() + AUTO_SCROLL_PAUSE_MS
                }
            }
    }

    LaunchedEffect(listState, sortedLines) {
        snapshotFlow { playingIndex to pauseAutoScrollUntil }
            .collectLatest { (index, pauseUntil) ->
                if (index == Int.MAX_VALUE || index !in sortedLines.indices) return@collectLatest
                val waitMs = pauseUntil - SystemClock.elapsedRealtime()
                if (waitMs > 0L) delay(waitMs)
                autoScrolling = true
                try {
                    if (!initialCentered) {
                        // 首次进入：直接精确居中，避免从列表顶部动画飞过来的廉价感。
                        initialCentered = true
                        listState.scrollToItem(index)
                        listState.awaitVisibleItem(index)?.let { item ->
                            val distance = listState.centerDistance(item)
                            if (abs(distance) >= CENTER_TOLERANCE_PX) listState.scrollBy(distance)
                        }
                    } else {
                        listState.animateCenterItem(index)
                    }
                } catch (e: CancellationException) {
                    // 用户手势抢占滚动动画 / 本 effect 被新的行变化取消，都是正常取消；
                    // 只有整个 effect 真的被销毁时才向上抛（ensureActive 判断）。
                    currentCoroutineContext().ensureActive()
                } finally {
                    autoScrolling = false
                }
            }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 上下各 40% 的 contentPadding：当前行滚动到视口中间时，上下仍能看到前后几行。
        val verticalPadding = maxHeight * 0.4f
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = verticalPadding,
                bottom = verticalPadding,
            ),
        ) {
            itemsIndexed(
                items = sortedLines,
                key = { index, line -> "$index:${line.key}" },
                contentType = { _, line -> if (line is LyricItem.WordsLyric) "words" else "normal" },
            ) { index, line ->
                val isActive = index == playingIndex
                val isPast = playingIndex != Int.MAX_VALUE && index < playingIndex
                LyricLineRow(
                    line = line,
                    currentTimeState = frameTime,
                    isActive = isActive,
                    isPast = isPast,
                    isSynced = true,
                    onClick = { onSeekToLine(line.time) },
                )
            }
        }
    }
}

/** 无时间轴歌词：静态展示，不跟随进度、不高亮、不可点。 */
@Composable
private fun PlainLyricsContent(
    lines: List<LyricItem>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = PaddingValues(
            horizontal = Spacing.xl,
            vertical = Spacing.xl,
        ),
    ) {
        itemsIndexed(
            items = lines,
            key = { index, line -> "plain:$index:${line.key}" },
            contentType = { _, _ -> "plain" },
        ) { _, line ->
            val text = when (line) {
                is LyricItem.NormalLyric -> line.content
                is LyricItem.WordsLyric -> line.getSentenceContent()
            }
            Text(
                text = text.ifBlank { EMPTY_LINE_PLACEHOLDER },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 播放时间：250ms 状态 tick 之间按真实经过时间插值，保证逐字歌词平滑推进
// ---------------------------------------------------------------------------

/**
 * 返回「当前歌词时间（已叠加偏移）」提供器。
 *
 * 播放引擎每 250ms 才更新一次 positionMs；逐字高亮需要更平滑的位置，因此在两次 tick 之间
 * 按真实经过时间线性插值（这是进度显示，不是自动动画，暂停时精确回到状态里的位置）。
 */
@Composable
private fun rememberLyricTimeProvider(
    playback: LyricsPlaybackState,
    delayMs: Long,
): () -> Long {
    val anchorPosition = remember { mutableLongStateOf(playback.positionMs) }
    val anchorAt = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val latestPlayback = rememberUpdatedState(playback)
    val latestDelay = rememberUpdatedState(delayMs)
    LaunchedEffect(playback.positionMs, playback.isPlaying) {
        anchorPosition.longValue = playback.positionMs
        anchorAt.longValue = SystemClock.elapsedRealtime()
    }
    return remember {
        {
            val current = latestPlayback.value
            val basePosition = if (current.isPlaying) {
                anchorPosition.longValue + (SystemClock.elapsedRealtime() - anchorAt.longValue)
            } else {
                current.positionMs
            }
            val shifted = basePosition + latestDelay.value
            val duration = current.trackDurationMs
            if (duration > 0L) shifted.coerceIn(0L, duration) else shifted.coerceAtLeast(0L)
        }
    }
}

// ---------------------------------------------------------------------------
// 自动滚动工具
// ---------------------------------------------------------------------------

/** 平滑滚动把目标行带到视口中间；目标不在可见范围内时先瞬移再微调。 */
private suspend fun LazyListState.animateCenterItem(index: Int) {
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null) {
        val distance = centerDistance(visible)
        if (abs(distance) >= CENTER_TOLERANCE_PX) {
            animateScrollBy(
                value = distance,
                animationSpec = tween(
                    durationMillis = BiliMotion.Medium2,
                    easing = BiliMotion.Emphasized,
                ),
            )
        }
        return
    }
    scrollToItem(index)
    val settled = awaitVisibleItem(index) ?: return
    val distance = centerDistance(settled)
    if (abs(distance) >= CENTER_TOLERANCE_PX) {
        animateScrollBy(
            value = distance,
            animationSpec = tween(
                durationMillis = BiliMotion.Medium2,
                easing = BiliMotion.Emphasized,
            ),
        )
    }
}

/** 等待目标行完成一次布局并出现在 visibleItemsInfo 里（最多 500ms）。 */
private suspend fun LazyListState.awaitVisibleItem(index: Int): LazyListItemInfo? =
    withTimeoutOrNull(500L) {
        snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
            .first { it != null }
    }

/** 目标行中心与视口中心的滚动距离（正数 = 需要向前滚动）。 */
private fun LazyListState.centerDistance(item: LazyListItemInfo): Float {
    val info = layoutInfo
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val itemCenter = item.offset + item.size / 2f
    return itemCenter - viewportCenter
}

// ---------------------------------------------------------------------------
// 格式化
// ---------------------------------------------------------------------------

/** 歌词偏移展示：`+0.3s` / `-0.5s` / `0.0s`（正数 = 歌词提前显示）。 */
internal fun formatLyricOffset(delayMs: Long): String {
    if (delayMs == 0L) return "0.0s"
    val sign = if (delayMs > 0L) "+" else "-"
    return String.format(Locale.US, "%s%.1fs", sign, abs(delayMs) / 1000.0)
}
