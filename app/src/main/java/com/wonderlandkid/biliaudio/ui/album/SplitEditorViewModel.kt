package com.wonderlandkid.biliaudio.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.impl.SplitTextParser
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** 分曲编辑列表中的一个草稿分割点（未保存到数据库）。 */
data class SegmentDraft(
    /** 稳定的编辑 key，只用于列表 key / 定位，和数据库 track id 无关 */
    val key: Long,
    val startMs: Long,
    val title: String,
)

/** 分曲编辑页 UI 状态。 */
data class SplitEditorUiState(
    val isLoading: Boolean = true,
    val album: Album? = null,
    val segments: List<SegmentDraft> = emptyList(),
    val timeInput: String = "",
    val titleInput: String = "",
    val inputError: String? = null,
    val isSaving: Boolean = false,
    /** 保存成功，页面收到后返回上一页 */
    val saved: Boolean = false,
    /** 一次性提示（Snackbar） */
    val message: String? = null,
    /** 播放器里当前是否是本专辑（决定「使用当前播放位置」是否可用） */
    val isAlbumInPlayer: Boolean = false,
    /** 播放器的专辑绝对进度（毫秒） */
    val albumPositionMs: Long = 0L,
    // ---------------- 文本导入分割点 ----------------
    /** 「文本导入分割点」弹窗是否可见 */
    val textImportVisible: Boolean = false,
    /** 文本导入输入框内容 */
    val textImportText: String = "",
    /** 文本实时识别结果（null = 尚未输入或已清空） */
    val textImportResult: SplitTextParser.Result? = null,
    /** 文本导入的时间格式（手动指定可消除 1:03 这类歧义） */
    val textImportTimeFormat: SplitTextParser.TimeFormat = SplitTextParser.TimeFormat.AUTO,
) {
    val canSave: Boolean get() = !isSaving && album != null && segments.isNotEmpty()

    /** 分割点列表摘要文案（未分割时给更容易理解的说明）。 */
    val summaryText: String
        get() {
            val total = album?.durationMs ?: 0L
            return if (segments.isEmpty()) {
                "尚未分割 · 专辑时长 ${TimeUtils.formatDuration(total)}"
            } else {
                "${segments.size} 个分割点 · 专辑时长 ${TimeUtils.formatDuration(total)}"
            }
        }
}

/**
 * 分曲编辑 ViewModel。
 *
 * - 加载专辑与已有分曲到本地草稿；
 * - 支持新增 / 编辑 / 删除 / 按时间排序；
 * - 「使用当前播放位置」：若播放器正在播放本专辑，取 `PlaybackState.albumPositionMs`
 *   并四舍五入到 0.5 秒；
 * - 保存前校验时间点严格递增且不超过专辑时长，通过后调用 [AlbumRepository.replaceTracks]。
 */
class SplitEditorViewModel(
    private val albumId: Long,
    private val albumRepository: AlbumRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplitEditorUiState())
    val uiState: StateFlow<SplitEditorUiState> = _uiState.asStateFlow()

    /** 草稿 key 自增器（只在主线程/VM 内部使用） */
    private var nextDraftKey = 1L

    init {
        viewModelScope.launch { loadAlbum() }
        viewModelScope.launch { observePlayback() }
    }

    private suspend fun loadAlbum() {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val album = albumRepository.getAlbum(albumId)
            if (album == null) {
                _uiState.update {
                    it.copy(isLoading = false, message = "专辑不存在或已被删除")
                }
                return
            }
            val drafts = albumRepository.getTracks(albumId)
                .sortedBy { track -> track.startMs }
                .map { track -> SegmentDraft(key = nextDraftKey(), startMs = track.startMs, title = track.title) }
            _uiState.update {
                it.copy(isLoading = false, album = album, segments = drafts)
            }
        } catch (throwable: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    message = "加载分割点失败：${throwable.message ?: "未知错误"}",
                )
            }
        }
    }

    private suspend fun observePlayback() {
        playbackController.state.collect { playback ->
            val isThisAlbum = playback.currentAlbumId == albumId && playback.queue.isNotEmpty()
            _uiState.update {
                it.copy(
                    isAlbumInPlayer = isThisAlbum,
                    albumPositionMs = if (isThisAlbum) playback.albumPositionMs else 0L,
                )
            }
        }
    }

    fun onTimeInputChange(value: String) {
        _uiState.update { it.copy(timeInput = value, inputError = null) }
    }

    fun onTitleInputChange(value: String) {
        _uiState.update { it.copy(titleInput = value) }
    }

    /**
     * 「使用当前播放位置」：取播放器的专辑绝对进度，四舍五入到 0.5 秒后填入时间输入框。
     */
    fun useCurrentPlaybackPosition() {
        val state = _uiState.value
        val album = state.album ?: return
        if (!state.isAlbumInPlayer) {
            _uiState.update { it.copy(message = "请先播放这张专辑，再取当前播放位置") }
            return
        }
        val snapped = snapToHalfSecond(state.albumPositionMs)
            .coerceIn(0L, (album.durationMs - SNAP_INTERVAL_MS).coerceAtLeast(0L))
        val text = formatInputTime(snapped)
        _uiState.update {
            it.copy(
                timeInput = text,
                inputError = null,
                message = "已填入当前播放位置 $text",
            )
        }
    }

    /** 新增分割点（时间 + 歌名），插入后按时间自动排序。 */
    fun addSegment() {
        val state = _uiState.value
        val album = state.album ?: return
        val startMs = TimeUtils.parseTimeToMs(state.timeInput)
        if (startMs == null) {
            _uiState.update { it.copy(inputError = INPUT_ERROR_FORMAT) }
            return
        }
        val error = validateStartMs(startMs = startMs, album = album, segments = state.segments, ignoreKey = null)
        if (error != null) {
            _uiState.update { it.copy(inputError = error) }
            return
        }
        val segment = SegmentDraft(
            key = nextDraftKey(),
            startMs = startMs,
            title = state.titleInput.trim().ifBlank { "曲目 ${state.segments.size + 1}" },
        )
        _uiState.update {
            it.copy(
                segments = (it.segments + segment).sortedBy { draft -> draft.startMs },
                timeInput = "",
                titleInput = "",
                inputError = null,
            )
        }
    }

    /** 编辑已有分割点（时间点 / 歌名），保存后按时间重新排序。 */
    fun updateSegment(key: Long, startMs: Long, rawTitle: String) {
        val state = _uiState.value
        val album = state.album ?: return
        val error = validateStartMs(startMs = startMs, album = album, segments = state.segments, ignoreKey = key)
        if (error != null) {
            _uiState.update { it.copy(message = error) }
            return
        }
        val title = rawTitle.trim()
        _uiState.update { current ->
            val updated = current.segments.map { draft ->
                if (draft.key == key) {
                    draft.copy(startMs = startMs, title = title.ifBlank { draft.title })
                } else {
                    draft
                }
            }
            current.copy(segments = updated.sortedBy { draft -> draft.startMs })
        }
    }

    fun deleteSegment(key: Long) {
        _uiState.update { current ->
            current.copy(segments = current.segments.filterNot { draft -> draft.key == key })
        }
    }

    /** 按时间点重新排序（编辑过程中顺序乱了可一键恢复）。 */
    fun sortByTime() {
        _uiState.update { it.copy(segments = it.segments.sortedBy { draft -> draft.startMs }) }
    }

    // ------------------------------------------------------------------
    // 文本导入分割点
    // ------------------------------------------------------------------

    /** 打开「文本导入分割点」弹窗（清空上次输入与识别结果）。 */
    fun openTextImport() {
        _uiState.update {
            it.copy(
                textImportVisible = true,
                textImportText = "",
                textImportResult = null,
                textImportTimeFormat = SplitTextParser.TimeFormat.AUTO,
            )
        }
    }

    /** 关闭「文本导入分割点」弹窗（保留状态，下次打开会重置）。 */
    fun dismissTextImport() {
        _uiState.update { it.copy(textImportVisible = false) }
    }

    /** 输入变化时实时识别（解析纯文本很轻，直接同步执行）。 */
    fun onTextImportInputChange(text: String) {
        _uiState.update { state ->
            state.copy(
                textImportText = text,
                textImportResult = parseTextImport(text, state.textImportTimeFormat),
            )
        }
    }

    /** 切换时间格式（自动 / 分:秒 / 时:分:秒），并用当前文本重新识别。 */
    fun onTextImportTimeFormatChange(format: SplitTextParser.TimeFormat) {
        _uiState.update { state ->
            state.copy(
                textImportTimeFormat = format,
                textImportResult = parseTextImport(state.textImportText, format),
            )
        }
    }

    private fun parseTextImport(
        text: String,
        format: SplitTextParser.TimeFormat,
    ): SplitTextParser.Result? =
        if (text.isBlank()) null else SplitTextParser.parse(text, format)

    /**
     * 应用文本导入结果。
     *
     * @param text 用户粘贴的分割点文本（直接交给 [SplitTextParser] 解析）
     * @param replace true = 替换全部分割点；false = 追加到末尾（与现有分割点重复的时间点以现有为准）
     */
    fun importSegmentsFromText(text: String, replace: Boolean) {
        val state = _uiState.value
        val album = state.album ?: return
        val result = SplitTextParser.parse(text, state.textImportTimeFormat)
        if (result.segments.isEmpty()) {
            _uiState.update { it.copy(message = "未识别到分割点，请检查文本格式") }
            return
        }
        // 超出专辑时长的时间点无法成为分割点，导入时跳过
        val inRange = result.segments.filter { it.startMs in 0L until album.durationMs }
        val outOfRangeCount = result.segments.size - inRange.size
        if (inRange.isEmpty()) {
            _uiState.update {
                it.copy(message = "识别到的分割点都超出了专辑时长（${TimeUtils.formatDuration(album.durationMs)}）")
            }
            return
        }
        val existingTimes = state.segments.map { draft -> draft.startMs }.toSet()
        var duplicateCount = 0
        val importedDrafts: List<SegmentDraft> = if (replace) {
            inRange.map { parsed -> SegmentDraft(key = nextDraftKey(), startMs = parsed.startMs, title = parsed.title) }
        } else {
            inRange.mapNotNull { parsed ->
                if (parsed.startMs in existingTimes) {
                    duplicateCount++
                    null
                } else {
                    SegmentDraft(key = nextDraftKey(), startMs = parsed.startMs, title = parsed.title)
                }
            }
        }
        if (importedDrafts.isEmpty()) {
            _uiState.update { it.copy(message = "没有可导入的分割点：时间点与现有分割点重复") }
            return
        }
        val merged = (if (replace) importedDrafts else state.segments + importedDrafts)
            .sortedBy { draft -> draft.startMs }
        val extraNote = buildString {
            if (duplicateCount > 0) append("，跳过重复 $duplicateCount 个")
            if (outOfRangeCount > 0) append("，跳过超时长 $outOfRangeCount 个")
        }
        _uiState.update {
            it.copy(
                segments = merged,
                textImportVisible = false,
                textImportText = "",
                textImportResult = null,
                message = "已导入 ${importedDrafts.size} 个分割点$extraNote",
            )
        }
    }

    /**
     * 预览播放：播放整张专辑并跳到指定的专辑绝对时间点。
     * 用于试听某个分割点（保存前也能大致对位置）。
     */
    fun previewFrom(albumPositionMs: Long) {
        val position = albumPositionMs.coerceAtLeast(0L)
        playbackController.playAlbum(albumId)
        playbackController.seekToAlbumPosition(position)
    }

    /** 保存分割点：校验 → 生成 Track 列表 → [AlbumRepository.replaceTracks]。 */
    fun saveSplit() {
        val state = _uiState.value
        val album = state.album ?: return
        if (state.isSaving) return
        val segments = state.segments
        if (segments.isEmpty()) {
            _uiState.update { it.copy(message = "至少需要一个分割点") }
            return
        }
        // 校验 1：每个分割点必须落在 [0, 专辑总时长) 内
        val outOfRange = segments.firstOrNull { it.startMs < 0L || it.startMs >= album.durationMs }
        if (outOfRange != null) {
            _uiState.update {
                it.copy(message = "分割点「${outOfRange.title}」超出专辑时长，请调整")
            }
            return
        }
        // 校验 2：时间点必须严格递增
        val isIncreasing = segments.zipWithNext().all { (previous, next) -> previous.startMs < next.startMs }
        if (!isIncreasing) {
            _uiState.update {
                it.copy(message = "分割点必须按时间严格递增，可点击「按时间排序」")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val tracks = segments.mapIndexed { index, segment ->
                    Track(
                        albumId = albumId,
                        index = index,
                        title = segment.title.trim().ifBlank { "曲目 ${index + 1}" },
                        startMs = segment.startMs,
                        endMs = segments.getOrNull(index + 1)?.startMs ?: album.durationMs,
                    )
                }
                albumRepository.replaceTracks(albumId, tracks)
                _uiState.update {
                    it.copy(isSaving = false, saved = true, message = "已保存 ${tracks.size} 个分割点")
                }
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        message = "保存失败：${throwable.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 校验单个时间点，返回错误文案（null = 合法）。 */
    private fun validateStartMs(
        startMs: Long,
        album: Album,
        segments: List<SegmentDraft>,
        ignoreKey: Long?,
    ): String? {
        if (startMs < 0L) return "时间不能为负数"
        if (startMs >= album.durationMs) {
            return "分割点不能超过专辑总时长（${TimeUtils.formatDuration(album.durationMs)}）"
        }
        if (segments.any { draft -> draft.key != ignoreKey && draft.startMs == startMs }) {
            return "该时间点已存在，请换一个"
        }
        return null
    }

    private fun nextDraftKey(): Long = nextDraftKey++

    /** 四舍五入到 0.5 秒。 */
    private fun snapToHalfSecond(ms: Long): Long =
        (ms / SNAP_INTERVAL_MS.toDouble()).roundToLong() * SNAP_INTERVAL_MS

    /** 输入框里的时间文案：整秒用 mm:ss，带半秒用 mm:ss.SSS。 */
    private fun formatInputTime(ms: Long): String =
        if (ms % 1000L == 0L) TimeUtils.formatDuration(ms) else TimeUtils.formatDurationPrecise(ms)

    private companion object {
        /** 取当前播放位置的吸附精度：0.5 秒 */
        const val SNAP_INTERVAL_MS = 500L
        const val INPUT_ERROR_FORMAT = "时间格式不正确，请输入 mm:ss 或 mm:ss.SSS"
    }
}
