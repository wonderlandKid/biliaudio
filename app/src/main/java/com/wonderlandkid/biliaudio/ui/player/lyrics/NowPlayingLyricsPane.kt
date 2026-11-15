package com.wonderlandkid.biliaudio.ui.player.lyrics

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.LyricItem
import com.wonderlandkid.biliaudio.data.model.findPlayingIndex
import com.wonderlandkid.biliaudio.data.model.getSentenceContent
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.theme.BiliMotion
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.wonderlandkid.biliaudio.ui.components.BiliTextButton

/** 用户手动滚动后暂停自动滚动的时长。 */
private const val AUTO_SCROLL_PAUSE_MS = 3_000L

/** 判断当前行是否已居中/居中的容差（像素）。 */
private const val CENTER_TOLERANCE_PX = 1f

/**
 * 播放页内嵌歌词（「正在播放」左右滑动切换的第二页）。
 *
 * 结构（扁平 Material 3，沿用播放页主题色渐变背景，不使用毛玻璃/液态玻璃）：
 * - 紧凑头部：曲名/艺术家 + 歌词来源入口 + 歌词偏移入口；
 * - 中部：歌词区（上下渐隐遮罩 + 当前行高亮居中 + 逐字推进 + 平滑自动滚动 + 点行跳转）；
 * - 状态：加载中 / 无歌词（重新搜索、导入本地）/ 已关闭歌词（恢复）/ 纯文本歌词（静态展示）；
 * - 弹层：来源面板 [LyricsSourceSheet]、偏移面板 [LyricsOffsetSheet]。
 *
 * 歌词在播放页内，不再是独立覆盖层：返回键在歌词页时先切回封面页（由 NowPlayingScreen 处理）。
 */
@Composable
internal fun NowPlayingLyricsPane(
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

    // 歌词来源 / 偏移调整：从头部移到右下角，做成与播放列表入口同高度的浮动按钮组，
    // 不再占用歌词顶部空间（曲名/艺术家由播放页顶部统一显示）。
    val openSource = {
        showSourceSheet = true
        // 候选列表不随歌词快照持久化：打开面板时补齐在线候选，避免误显示「暂无候选」
        if (state.candidates.isEmpty() && !state.isSearching) {
            viewModel.searchOnline(manual = false)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                            description = "本曲不会显示歌词，可在「歌词来源」里恢复",
                            modifier = Modifier.fillMaxSize(),
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.MusicOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp),
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
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Spacing.sm),
                )
            }
        }

        // 右下角浮动控件：歌词来源 / 时间偏移（高度接近播放列表按钮，非胶囊）
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Spacing.lg, bottom = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsActionSurface(
                onClick = openSource,
                contentDescription = "歌词来源",
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "歌词来源",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LyricsActionSurface(
                onClick = { showOffsetSheet = true },
                enabled = state.parsed != null,
                contentDescription = "歌词时间偏移",
            ) {
                Text(
                    text = formatLyricOffset(state.delayMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.parsed != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
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

/** 歌词页右下角的圆角矩形操作入口（非胶囊，40dp 高）。 */
@Composable
private fun LyricsActionSurface(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
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
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "正在匹配歌词…",
            style = MaterialTheme.typography.bodySmall,
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
                    modifier = Modifier.size(36.dp),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BiliTextButton(onClick = onSearch, enabled = !isSearching) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(Spacing.xs))
                }
                Text("重新搜索")
            }
            BiliTextButton(onClick = onImportLocal) {
                Text("导入本地歌词")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 歌词列表（有时间轴 / 无时间轴）
// ---------------------------------------------------------------------------

/**
 * 有时间轴的歌词列表：逐行高亮 + 逐字推进 + 平滑自动滚动 + 点行跳转。
 *
 * 状态：加载中 / 无歌词 / 已关闭 / 有时间轴 / 纯文本。
 * 「Apple Music 感」的三个关键点：
 * 1. 自动滚动用长缓出曲线（[BiliMotion.LyricsScrollMs]）而不是短促跳转，切行时从当前位置平滑重定向；
 * 2. 当前行放大/着色 + 其余行按「已唱/未唱」分透明度，过渡统一走 Emphasized；
 * 3. 上下边缘用渐变遮罩让歌词自然淡出，不出现生硬截断。
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
        // 上下各 35% 的 contentPadding：当前行滚动到视口中间时，上下仍能看到前后几行。
        val verticalPadding = maxHeight * 0.35f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .lyricsEdgeFade(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
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
}

/** 无时间轴歌词：静态展示，不跟随进度、不高亮、不可点。 */
@Composable
private fun PlainLyricsContent(
    lines: List<LyricItem>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
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
                textAlign = TextAlign.Start,
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

/**
 * 平滑滚动把目标行带到视口中间（Apple Music 手感）。
 *
 * - 目标不在可见范围内时先瞬移再微调（避免长距离飞滚）；
 * - 可见时用长缓出曲线 [BiliMotion.LyricsScrollMs]，并且在切行很快时由外层 collectLatest
 *   取消上一条动画、从当前位置继续，视觉上是一段连续的减速滑动。
 */
private suspend fun LazyListState.animateCenterItem(index: Int) {
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null) {
        val distance = centerDistance(visible)
        if (abs(distance) >= CENTER_TOLERANCE_PX) {
            animateScrollBy(
                value = distance,
                animationSpec = tween(
                    durationMillis = BiliMotion.LyricsScrollMs,
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
                durationMillis = BiliMotion.LyricsScrollMs,
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
// 视觉辅助
// ---------------------------------------------------------------------------

/**
 * 歌词上下边缘渐隐遮罩：用 DstIn 让绘制内容按渐变透明度淡出，
 * 不依赖背景色（播放页是渐变背景，不能用 surface 色块盖）。
 */
private fun Modifier.lyricsEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.12f to Color.Black,
                0.88f to Color.Black,
                1.0f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** 格式化歌词偏移展示：`+0.3s` / `-0.5s` / `0.0s`（正数 = 歌词提前显示）。 */
internal fun formatLyricOffset(delayMs: Long): String {
    if (delayMs == 0L) return "0.0s"
    val sign = if (delayMs > 0L) "+" else "-"
    return String.format(java.util.Locale.US, "%s%.1fs", sign, abs(delayMs) / 1000.0)
}
