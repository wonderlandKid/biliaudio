package com.wonderlandkid.biliaudio.ui.player.lyrics

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.lyrics.LyricsMatcher
import com.wonderlandkid.biliaudio.data.lyrics.LyricsParser
import com.wonderlandkid.biliaudio.data.lyrics.LyricsText
import com.wonderlandkid.biliaudio.data.model.LyricSourceType
import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate
import com.wonderlandkid.biliaudio.data.model.ParsedLyrics
import com.wonderlandkid.biliaudio.data.repository.LyricsRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.player.PlaybackState
import com.wonderlandkid.biliaudio.util.Diag
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌词页 UI 状态（不随播放进度 tick 变化，只有歌词/来源/偏移变化时才更新）。
 *
 * @param parsed 解析后的歌词；null = 尚未加载/已关闭/无歌词
 * @param rawText 当前来源的歌词原文（用于来源面板判断「当前使用」）
 * @param candidates 在线歌词候选（自动匹配与手动搜索共用，供来源面板手动切换）
 * @param message 一次性提示（导入结果等），由页面 Snackbar 消费后 [consumeMessage]
 */
@Immutable
data class LyricsUiState(
    val trackUid: String = "",
    val title: String = "",
    val artist: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val parsed: ParsedLyrics? = null,
    val rawText: String = "",
    val sourceType: LyricSourceType = LyricSourceType.NONE,
    val sourceName: String = "",
    val isManual: Boolean = false,
    val delayMs: Long = 0L,
    val suppressed: Boolean = false,
    val candidates: List<OnlineLyricCandidate> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val message: String? = null,
)

/**
 * 歌词页播放状态（单独暴露，避免播放进度 tick 触发整份歌词重新解析/重建）。
 *
 * @param positionMs 曲内进度（不含歌词偏移）
 */
@Immutable
data class LyricsPlaybackState(
    val trackUid: String = "",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val trackDurationMs: Long = 0L,
)

/**
 * 全屏歌词页 ViewModel（移植自 SPICaMusic_Android 的 LyricsViewModel 机制，按 BiliAudio 契约收敛）。
 *
 * 数据流：
 * 1. 播放曲目变化（[com.wonderlandkid.biliaudio.data.model.Track.uid]）→ 读本机快照；
 * 2. 有快照 → 直接解析展示（[LyricsUiState.delayMs] 来自快照）；快照被关闭 → 显示「已关闭歌词」；
 * 3. 无快照 → 用 [LyricsText.cleanTitle] 清洗分曲标题后在线搜索，[LyricsMatcher.bestMatch]
 *    按时长/标题挑最匹配候选，命中后保存（`isManual=false`，不覆盖用户手动选择）并解析展示；
 * 4. 展示时间 = 播放进度 + 偏移（正数 = 歌词提前），由 UI 侧按帧插值；本类只提供原始播放状态。
 *
 * 竞态处理：每次切歌/重载都递增 [generation]，异步结果回来时校验 generation，避免旧歌的
 * 搜索结果/解析结果覆盖新歌（对齐参考项目的 generation 机制）。
 *
 * 用 `appViewModel { c -> LyricsViewModel(c.playbackController, c.lyricsRepository, c.appContext) }` 创建。
 */
class LyricsViewModel(
    private val playbackController: PlaybackController,
    private val lyricsRepository: LyricsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    /** 播放状态：播放/暂停、进度、时长；与歌词解析结果分离。 */
    val playback: StateFlow<LyricsPlaybackState> = playbackController.state
        .map { state -> state.toLyricsPlaybackState() }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = playbackController.state.value.toLyricsPlaybackState(),
        )

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0L
    private var currentKey: TrackKey? = null

    /** 当前曲目的稳定标识 + 自动匹配所需信息。 */
    private data class TrackKey(
        val uid: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    private sealed interface LocalImportResult {
        data class Success(val raw: String, val fileName: String) : LocalImportResult
        data class Failure(val message: String) : LocalImportResult
    }

    init {
        viewModelScope.launch {
            playbackController.state
                .map { state ->
                    val entry = state.queue.getOrNull(state.currentIndex)
                    val track = state.currentTrack
                    TrackKey(
                        uid = track?.lyricsStorageKey().orEmpty(),
                        title = track?.title.orEmpty(),
                        // B 站专辑艺术家是 UP 主，展示用 QueueEntry.artist，缺失时退回专辑名
                        artist = entry?.artist?.takeIf { it.isNotBlank() }
                            ?: entry?.albumTitle.orEmpty(),
                        durationMs = track?.durationMs?.takeIf { it > 0L } ?: state.trackDurationMs,
                    )
                }
                .distinctUntilChanged()
                .collect { key -> loadTrack(key) }
        }
    }

    // ------------------------------------------------------------------
    // 切歌 / 加载
    // ------------------------------------------------------------------

    private fun loadTrack(key: TrackKey) {
        generation++
        val version = generation
        loadJob?.cancel()
        searchJob?.cancel()
        currentKey = key
        if (key.uid.isBlank()) {
            _uiState.value = LyricsUiState(isLoading = false)
            return
        }
        _uiState.value = LyricsUiState(
            trackUid = key.uid,
            title = key.title,
            artist = key.artist,
            isLoading = true,
        )
        loadJob = viewModelScope.launch {
            try {
                resolveLyrics(key, version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.log(TAG, "load lyrics failed: ${e.javaClass.simpleName}: ${e.message}", e)
                updateIfCurrent(version) {
                    it.copy(
                        isLoading = false,
                        isSearching = false,
                        errorMessage = it.errorMessage ?: "歌词加载失败，请重试",
                    )
                }
            }
        }
    }

    private suspend fun resolveLyrics(key: TrackKey, version: Long) {
        val stored = withContext(Dispatchers.IO) {
            runCatching { lyricsRepository.getStored(key.uid) }.getOrNull()
        }
        if (!isCurrent(version)) return

        if (stored != null && stored.suppressed) {
            updateIfCurrent(version) {
                it.copy(
                    isLoading = false,
                    parsed = null,
                    rawText = "",
                    sourceType = stored.sourceType,
                    sourceName = stored.sourceName,
                    isManual = stored.isManual,
                    delayMs = stored.delayMs,
                    suppressed = true,
                    errorMessage = null,
                )
            }
            return
        }

        if (stored != null && stored.rawLyrics.isNotBlank()) {
            val parsed = parseOnDefault(stored.rawLyrics)
            if (!isCurrent(version)) return
            if (parsed.lines.isNotEmpty()) {
                updateIfCurrent(version) {
                    it.copy(
                        isLoading = false,
                        parsed = parsed,
                        rawText = stored.rawLyrics,
                        sourceType = stored.sourceType,
                        sourceName = stored.sourceName,
                        isManual = stored.isManual,
                        delayMs = stored.delayMs,
                        suppressed = false,
                        errorMessage = null,
                    )
                }
                return
            }
            Diag.log(TAG, "stored lyrics parse empty uid=${key.uid}, will search online")
        }

        // 无快照（或快照解析失败）→ 自动在线匹配
        updateIfCurrent(version) { it.copy(isSearching = true) }
        searchAndApplyAuto(key, version)
    }

    /**
     * 自动在线匹配：清洗标题（去掉分曲序号/「专辑_01_」前缀）→ 搜索 → 按时长/标题挑最佳候选 →
     * 保存并解析。专辑艺术家是 UP 主不可靠，因此搜索 artist 传空串。
     */
    private suspend fun searchAndApplyAuto(key: TrackKey, version: Long) {
        val pool = searchCandidatePool(key)
        if (!isCurrent(version)) return

        if (pool.failed && pool.candidates.isEmpty()) {
            updateIfCurrent(version) {
                it.copy(
                    isLoading = false,
                    isSearching = false,
                    candidates = emptyList(),
                    searchError = "歌词搜索失败，请稍后重试",
                    errorMessage = "歌词搜索失败，请稍后重试",
                )
            }
            return
        }

        val candidates = pool.candidates
        val matched = LyricsMatcher.bestMatch(candidates, pool.searchTitle, key.durationMs)
        if (matched != null) {
            val parsed = parseOnDefault(matched.rawLyrics)
            if (!isCurrent(version)) return
            if (parsed.lines.isNotEmpty()) {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        lyricsRepository.save(
                            trackUid = key.uid,
                            rawLyrics = matched.rawLyrics,
                            sourceType = LyricSourceType.ONLINE,
                            sourceName = matched.sourceLabel(),
                            isManual = false,
                        )
                    }.getOrNull()
                }
                if (!isCurrent(version)) return
                Diag.log(TAG, "auto matched uid=${key.uid} source=${matched.sourceLabel()}")
                updateIfCurrent(version) {
                    it.copy(
                        isLoading = false,
                        isSearching = false,
                        parsed = parsed,
                        rawText = matched.rawLyrics,
                        sourceType = LyricSourceType.ONLINE,
                        sourceName = saved?.sourceName ?: matched.sourceLabel(),
                        isManual = false,
                        delayMs = saved?.delayMs ?: it.delayMs,
                        suppressed = false,
                        candidates = candidates,
                        searchError = null,
                        errorMessage = null,
                    )
                }
                return
            }
        }

        updateIfCurrent(version) {
            it.copy(
                isLoading = false,
                isSearching = false,
                candidates = candidates,
                searchError = null,
                errorMessage = when {
                    candidates.isEmpty() -> "暂无歌词"
                    else -> "没有匹配到合适的歌词"
                },
            )
        }
    }

    /**
     * 在线候选池：先按清洗后的曲名搜索；没有可信匹配时再补搜原始曲名
     * （多 P 分曲标题常带「专辑_01_」前缀，两种查询命中的候选集合可能不同），合并去重。
     *
     * 艺人是 UP 主、不可靠，所以 artist 一律传空串；已有可信匹配就不再发第二次请求。
     */
    private suspend fun searchCandidatePool(key: TrackKey): CandidatePoolResult {
        val cleanedTitle = LyricsText.cleanTitle(key.title).ifBlank { key.title.trim() }
        val rawTitle = key.title.trim()
        val queries = LinkedHashSet<String>().apply {
            add(cleanedTitle)
            if (rawTitle.isNotEmpty() && rawTitle != cleanedTitle) add(rawTitle)
        }
        val merged = LinkedHashMap<Long, OnlineLyricCandidate>()
        var failed = false
        var index = 0
        for (query in queries) {
            index++
            val results = withContext(Dispatchers.IO) {
                try {
                    lyricsRepository.searchOnline(query, "")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    Diag.log(TAG, "search online failed q=$query: ${e.javaClass.simpleName}: ${e.message}", e)
                    emptyList()
                }
            }
            results.forEach { candidate -> merged.putIfAbsent(candidate.id, candidate) }
            val matched = LyricsMatcher.bestMatch(merged.values.toList(), cleanedTitle, key.durationMs)
            if (matched != null) break
            if (index == 1 && queries.size > 1) {
                Diag.log(TAG, "no confident match for cleaned title, retry raw title uid=${key.uid}")
            }
        }
        return CandidatePoolResult(candidates = merged.values.toList(), searchTitle = cleanedTitle, failed = failed)
    }

    /** [searchCandidatePool] 的结果：候选、用于打分的清洗标题、是否发生过网络失败。 */
    private data class CandidatePoolResult(
        val candidates: List<OnlineLyricCandidate>,
        val searchTitle: String,
        val failed: Boolean,
    )

    // ------------------------------------------------------------------
    // 用户动作：来源 / 偏移 / 播放控制
    // ------------------------------------------------------------------

    /** 手动重新搜索在线歌词（不清空当前已展示的歌词）。 */
    fun searchOnline(manual: Boolean = true) {
        val key = currentKey ?: return
        if (key.uid.isBlank()) return
        val version = generation
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearching = true,
                searchError = null,
                isLoading = manual && it.parsed == null && !it.suppressed,
            )
        }
        searchJob = viewModelScope.launch {
            val pool = searchCandidatePool(key)
            if (!isCurrent(version)) return@launch
            updateIfCurrent(version) {
                it.copy(
                    isLoading = false,
                    isSearching = false,
                    candidates = pool.candidates,
                    searchError = if (pool.failed && pool.candidates.isEmpty()) "歌词搜索失败，请稍后重试" else null,
                    errorMessage = if (pool.failed && pool.candidates.isEmpty()) {
                        it.errorMessage ?: "歌词搜索失败，请稍后重试"
                    } else if (pool.candidates.isEmpty() && it.parsed == null) {
                        "暂无歌词"
                    } else {
                        it.errorMessage
                    },
                )
            }
        }
    }

    /** 用户在来源面板选中某个在线候选：保存为手动歌词（清掉关闭状态）并立即展示。 */
    fun selectCandidate(candidate: OnlineLyricCandidate) {
        val key = currentKey ?: return
        if (key.uid.isBlank()) return
        val uid = key.uid
        val version = generation
        viewModelScope.launch {
            try {
                val parsed = parseOnDefault(candidate.rawLyrics)
                if (parsed.lines.isEmpty()) {
                    _uiState.update { it.copy(message = "歌词为空或无法解析") }
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        lyricsRepository.save(
                            trackUid = uid,
                            rawLyrics = candidate.rawLyrics,
                            sourceType = LyricSourceType.ONLINE,
                            sourceName = candidate.sourceLabel(),
                            delayMs = _uiState.value.delayMs,
                            isManual = true,
                        )
                    }.getOrNull()
                }
                if (!isCurrent(version) || currentKey?.uid != uid) return@launch
                Diag.log(TAG, "manual select uid=$uid source=${candidate.sourceLabel()}")
                updateIfCurrent(version) {
                    it.copy(
                        isLoading = false,
                        parsed = parsed,
                        rawText = candidate.rawLyrics,
                        sourceType = LyricSourceType.ONLINE,
                        sourceName = saved?.sourceName ?: candidate.sourceLabel(),
                        isManual = true,
                        delayMs = saved?.delayMs ?: it.delayMs,
                        suppressed = false,
                        errorMessage = null,
                        message = "已切换歌词来源",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.log(TAG, "select candidate failed", e)
                _uiState.update { it.copy(message = "歌词保存失败，请重试") }
            }
        }
    }

    /** 调整歌词偏移（毫秒，正数 = 歌词提前显示），立即生效并持久化。 */
    fun adjustOffset(deltaMs: Long) {
        val state = _uiState.value
        if (state.trackUid.isBlank()) return
        val newDelay = (state.delayMs + deltaMs).coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        if (newDelay == state.delayMs) return
        applyOffset(newDelay)
    }

    /** 重置偏移为 0。 */
    fun resetOffset() {
        if (_uiState.value.delayMs == 0L) return
        applyOffset(0L)
    }

    private fun applyOffset(delayMs: Long) {
        val uid = _uiState.value.trackUid
        if (uid.isBlank()) return
        _uiState.update { it.copy(delayMs = delayMs) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { lyricsRepository.updateDelay(uid, delayMs) }
            }
        }
    }

    /** 点击歌词行跳转：歌词时间先减掉偏移，再换算成曲内播放进度。 */
    fun seekToLine(lineTimeMs: Long) {
        val delayMs = _uiState.value.delayMs
        val durationMs = playbackController.state.value.trackDurationMs
        val target = (lineTimeMs - delayMs).coerceIn(
            minimumValue = 0L,
            maximumValue = if (durationMs > 0L) durationMs else Long.MAX_VALUE,
        )
        playbackController.seekToTrackPosition(target)
    }

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun next() = playbackController.next()

    fun previous() = playbackController.previous()

    // ------------------------------------------------------------------
    // 本地歌词 / 关闭 / 恢复
    // ------------------------------------------------------------------

    /** 导入用户选择的本地歌词文件（content:// URI）。 */
    fun importLocal(uri: Uri) {
        val key = currentKey ?: return
        if (key.uid.isBlank()) return
        val uid = key.uid
        val version = generation
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readLocalLyrics(uri) }
            when (result) {
                is LocalImportResult.Failure -> {
                    _uiState.update { it.copy(message = result.message) }
                }

                is LocalImportResult.Success -> {
                    val parsed = parseOnDefault(result.raw)
                    if (parsed.lines.isEmpty()) {
                        _uiState.update { it.copy(message = "歌词文件无法解析") }
                        return@launch
                    }
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            lyricsRepository.save(
                                trackUid = uid,
                                rawLyrics = result.raw,
                                sourceType = LyricSourceType.LOCAL_FILE,
                                sourceName = result.fileName,
                                delayMs = _uiState.value.delayMs,
                                isManual = true,
                            )
                        }.getOrNull()
                    }
                    if (!isCurrent(version) || currentKey?.uid != uid) return@launch
                    Diag.log(TAG, "import local lyrics uid=$uid file=${result.fileName}")
                    updateIfCurrent(version) {
                        it.copy(
                            isLoading = false,
                            parsed = parsed,
                            rawText = result.raw,
                            sourceType = LyricSourceType.LOCAL_FILE,
                            sourceName = saved?.sourceName ?: result.fileName,
                            isManual = true,
                            suppressed = false,
                            errorMessage = null,
                            message = "已导入本地歌词",
                        )
                    }
                }
            }
        }
    }

    /** 关闭该曲歌词（保留快照与偏移，可在来源面板恢复）。 */
    fun suppress() {
        val uid = _uiState.value.trackUid
        if (uid.isBlank()) return
        _uiState.update {
            it.copy(
                isLoading = false,
                parsed = null,
                rawText = "",
                suppressed = true,
                errorMessage = null,
                message = "已关闭本曲歌词",
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { lyricsRepository.suppress(uid) }
            }
        }
    }

    /** 恢复自动匹配：清除本机快照后重新走「在线搜索 → 匹配」流程。 */
    fun restoreAuto() {
        val key = currentKey ?: return
        if (key.uid.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { lyricsRepository.clear(key.uid) }
            }
            if (currentKey?.uid != key.uid) return@launch
            loadTrack(key)
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun isCurrent(version: Long): Boolean = version == generation

    private fun updateIfCurrent(
        version: Long,
        transform: (LyricsUiState) -> LyricsUiState,
    ) {
        if (isCurrent(version)) _uiState.update(transform)
    }

    private suspend fun parseOnDefault(raw: String): ParsedLyrics =
        withContext(Dispatchers.Default) { LyricsParser.parse(raw) }

    private fun readLocalLyrics(uri: Uri): LocalImportResult {
        val resolver = appContext.contentResolver
        val fileName = queryDisplayName(resolver, uri)
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_LOCAL_LYRICS_BYTES) {
                        return LocalImportResult.Failure("歌词文件过大（最大 1MB）")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (e: Exception) {
            Diag.log(TAG, "read local lyrics failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
        if (bytes == null) return LocalImportResult.Failure("无法读取该歌词文件")
        val raw = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (raw.isBlank()) return LocalImportResult.Failure("歌词文件为空")
        if (!LyricsParser.isValid(raw, fileName)) {
            return LocalImportResult.Failure("无法识别该歌词文件（支持 LRC/YRC/TTML/纯文本）")
        }
        return LocalImportResult.Success(raw = raw, fileName = fileName.ifBlank { "本地歌词" })
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull().orEmpty()

    private fun PlaybackState.toLyricsPlaybackState(): LyricsPlaybackState =
        LyricsPlaybackState(
            trackUid = currentTrack?.lyricsStorageKey().orEmpty(),
            isPlaying = isPlaying,
            isLoading = isLoading,
            positionMs = positionMs,
            trackDurationMs = trackDurationMs,
        )

    companion object {
        private const val TAG = "Lyrics"

        /** 本地歌词文件大小上限（与需求一致：<= 1MB）。 */
        private const val MAX_LOCAL_LYRICS_BYTES = 1024L * 1024L

        /** 偏移允许范围：±30s，避免误操作导致歌词完全错位。 */
        private const val MIN_OFFSET_MS = -30_000L
        private const val MAX_OFFSET_MS = 30_000L
    }
}

/** 歌词快照的稳定 key：优先 Track.uid，历史数据为空时退回专辑级稳定 key。 */
private fun com.wonderlandkid.biliaudio.data.model.Track.lyricsStorageKey(): String =
    uid.ifBlank { "album:$albumId" }

/** 来源面板/顶栏展示用的候选名称。 */
private fun OnlineLyricCandidate.sourceLabel(): String =
    if (artist.isBlank()) name else "$artist - $name"
