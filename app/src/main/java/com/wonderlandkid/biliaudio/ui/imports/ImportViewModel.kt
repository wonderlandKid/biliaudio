package com.wonderlandkid.biliaudio.ui.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.BatchImportReport
import com.wonderlandkid.biliaudio.data.model.BiliApiException
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 导入页的三个 Tab。 */
enum class ImportTab(val label: String) {
    BV("BV 号导入"),
    BATCH("批量导入"),
    SEARCH("搜索导入"),
}

/**
 * 单个视频的「解析预览 → 编辑元信息 → 导入」状态。
 *
 * BV Tab 与搜索结果预览（底部弹窗）复用同一结构，各自持有一份互不干扰。
 */
data class VideoImportState(
    /** 原始输入（BV 号 / 视频链接 / 搜索结果 bvid） */
    val input: String = "",
    val isParsing: Boolean = false,
    val info: BiliVideoInfo? = null,
    /** 可编辑的专辑名，解析成功后默认填视频标题 */
    val albumTitle: String = "",
    /** 可编辑的艺术家，解析成功后默认填 UP 主 */
    val artist: String = "",
    val parseError: String? = null,
    val isImporting: Boolean = false,
    val importError: String? = null,
    /** 导入成功后的专辑 id（非空时展示成功卡片） */
    val importedAlbumId: Long? = null,
    val importedTitle: String? = null,
    val importedTrackCount: Int = 0,
) {
    val canImport: Boolean
        get() = info != null && !isParsing && !isImporting && importedAlbumId == null
}

/** 导入页整体 UI 状态。 */
data class ImportUiState(
    val selectedTab: ImportTab = ImportTab.BV,
    // ---- BV 号导入 ----
    val bv: VideoImportState = VideoImportState(),
    // ---- 批量导入 ----
    val batchText: String = "",
    val batchFileName: String? = null,
    val isReadingFile: Boolean = false,
    val isBatchImporting: Boolean = false,
    val batchReport: BatchImportReport? = null,
    val batchError: String? = null,
    // ---- 搜索导入 ----
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<BiliVideoSearchItem> = emptyList(),
    val searchError: String? = null,
    /** 是否已经执行过搜索（用于区分「初始空态」与「无结果空态」） */
    val hasSearched: Boolean = false,
    /** 搜索结果预览（非空时弹出底部预览面板） */
    val searchPreview: VideoImportState? = null,
)

/**
 * 导入页 ViewModel：
 * - BV 号 / 视频链接 → [BiliRepository.previewVideo] 解析预览 → [AlbumRepository.importAlbum]；
 * - 批量文本 → [AlbumRepository.importBatchText]；
 * - 关键词搜索 → [BiliRepository.searchVideos] → 预览 → 导入。
 *
 * 依赖从 [com.wonderlandkid.biliaudio.di.AppContainer] 注入（通过 appViewModel {}）。
 */
class ImportViewModel(
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    /** 一次性提示（Snackbar 用），避免把瞬时事件塞进持久状态。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ------------------------------------------------------------------
    // Tab
    // ------------------------------------------------------------------

    fun selectTab(tab: ImportTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    // ------------------------------------------------------------------
    // BV 号导入
    // ------------------------------------------------------------------

    fun updateBvInput(text: String) {
        _uiState.update { state ->
            state.copy(
                bv = state.bv.copy(
                    input = text,
                    // 用户重新编辑输入后，清掉上一次的解析错误与成功态
                    parseError = null,
                    importedAlbumId = null,
                ),
            )
        }
    }

    fun updateBvAlbumTitle(text: String) {
        _uiState.update { it.copy(bv = it.bv.copy(albumTitle = text)) }
    }

    fun updateBvArtist(text: String) {
        _uiState.update { it.copy(bv = it.bv.copy(artist = text)) }
    }

    /** 解析 BV 号 / 链接，成功后展示可编辑的预览卡片。 */
    fun parseBvInput() {
        val input = _uiState.value.bv.input.trim()
        if (input.isEmpty()) {
            _uiState.update { it.copy(bv = it.bv.copy(parseError = "请输入 BV 号或视频链接")) }
            return
        }
        _uiState.update {
            it.copy(
                bv = it.bv.copy(
                    isParsing = true,
                    parseError = null,
                    importError = null,
                    importedAlbumId = null,
                    info = null,
                ),
            )
        }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { biliRepository.previewVideo(input) }
                _uiState.update {
                    it.copy(
                        bv = it.bv.copy(
                            isParsing = false,
                            info = info,
                            albumTitle = info.title,
                            artist = info.ownerName,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(bv = it.bv.copy(isParsing = false, parseError = friendlyError(t)))
                }
            }
        }
    }

    /** 把当前预览导入为一张专辑（SINGLE_VIDEO 模式）。 */
    fun importBv() {
        val state = _uiState.value.bv
        val info = state.info ?: return
        if (state.isImporting) return
        _uiState.update { it.copy(bv = it.bv.copy(isImporting = true, importError = null)) }
        viewModelScope.launch {
            try {
                // 防御性切 IO：即使实现层未自行切线程，也不会阻塞主线程
                val result = withContext(Dispatchers.IO) {
                    albumRepository.importAlbum(
                        AlbumImportRequest(
                            bvidOrUrl = info.bvid,
                            albumTitle = state.albumTitle.trim().ifBlank { null },
                            artist = state.artist.trim().ifBlank { null },
                            mode = ImportMode.SINGLE_VIDEO,
                        ),
                    )
                }
                when (result) {
                    is ImportResult.Success -> {
                        _uiState.update {
                            it.copy(
                                bv = it.bv.copy(
                                    isImporting = false,
                                    importedAlbumId = result.albumId,
                                    importedTitle = result.albumTitle,
                                    importedTrackCount = result.trackCount,
                                ),
                            )
                        }
                        _messages.tryEmit("导入成功：《${result.albumTitle}》，共 ${result.trackCount} 首")
                    }

                    is ImportResult.Failure -> {
                        _uiState.update {
                            it.copy(bv = it.bv.copy(isImporting = false, importError = result.message))
                        }
                        _messages.tryEmit(result.message)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { it.copy(bv = it.bv.copy(isImporting = false, importError = friendlyError(t))) }
            }
        }
    }

    /** 清空 BV Tab 的输入与预览（成功卡片上的「再导入一个」）。 */
    fun resetBvImport() {
        _uiState.update { it.copy(bv = VideoImportState()) }
    }

    // ------------------------------------------------------------------
    // 批量导入
    // ------------------------------------------------------------------

    fun updateBatchText(text: String) {
        _uiState.update { it.copy(batchText = text, batchError = null) }
    }

    /** 读取用户通过 OpenDocument 选择的 txt/json 等文本文件。 */
    fun loadBatchFile(uri: Uri) {
        _uiState.update { it.copy(isReadingFile = true, batchError = null) }
        viewModelScope.launch {
            try {
                val (fileName, text) = readBatchTextFile(uri)
                _uiState.update {
                    it.copy(isReadingFile = false, batchFileName = fileName, batchText = text)
                }
                _messages.tryEmit("已读取文件：$fileName")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val reason = when (t) {
                    is IOException -> t.message?.takeIf { it.isNotBlank() } ?: "无法读取该文件"
                    is SecurityException -> "没有文件访问权限"
                    else -> t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
                }
                _uiState.update {
                    it.copy(isReadingFile = false, batchError = "读取文件失败：$reason")
                }
            }
        }
    }

    fun clearBatch() {
        _uiState.update {
            it.copy(
                batchText = "",
                batchFileName = null,
                batchReport = null,
                batchError = null,
            )
        }
    }

    /** 执行批量导入，结果（成功/失败原因）写入 batchReport。 */
    fun runBatchImport() {
        val text = _uiState.value.batchText.trim()
        if (text.isEmpty()) {
            _uiState.update { it.copy(batchError = "请先粘贴批量导入内容，或从文件读取") }
            return
        }
        if (_uiState.value.isBatchImporting) return
        _uiState.update { it.copy(isBatchImporting = true, batchError = null, batchReport = null) }
        viewModelScope.launch {
            try {
                // 大文本解析可能耗时，切到 IO，避免卡主线程
                val report = withContext(Dispatchers.IO) { albumRepository.importBatchText(text) }
                _uiState.update { it.copy(isBatchImporting = false, batchReport = report) }
                _messages.tryEmit(
                    "批量导入完成：成功 ${report.successCount} 张，失败 ${report.failureCount} 条",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isBatchImporting = false, batchError = friendlyError(t))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 搜索导入
    // ------------------------------------------------------------------

    fun updateSearchQuery(text: String) {
        _uiState.update { it.copy(searchQuery = text, searchError = null) }
    }

    fun search() {
        val keyword = _uiState.value.searchQuery.trim()
        if (keyword.isEmpty()) {
            _messages.tryEmit("请输入搜索关键词")
            return
        }
        if (_uiState.value.isSearching) return
        _uiState.update {
            it.copy(isSearching = true, searchError = null, hasSearched = true, searchResults = emptyList())
        }
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { biliRepository.searchVideos(keyword) }
                _uiState.update { it.copy(isSearching = false, searchResults = results) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isSearching = false, searchError = friendlyError(t))
                }
            }
        }
    }

    /** 点击搜索结果：解析 bvid 并弹出预览面板。 */
    fun openSearchPreview(item: BiliVideoSearchItem) {
        _uiState.update {
            it.copy(searchPreview = VideoImportState(input = item.bvid, isParsing = true))
        }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { biliRepository.previewVideo(item.bvid) }
                updateSearchPreview {
                    it.copy(
                        isParsing = false,
                        info = info,
                        albumTitle = info.title,
                        artist = info.ownerName,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateSearchPreview { it.copy(isParsing = false, parseError = friendlyError(t)) }
            }
        }
    }

    fun retrySearchPreview() {
        val bvid = _uiState.value.searchPreview?.input.orEmpty()
        if (bvid.isBlank()) return
        updateSearchPreview {
            it.copy(isParsing = true, parseError = null, info = null, importedAlbumId = null)
        }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { biliRepository.previewVideo(bvid) }
                updateSearchPreview {
                    it.copy(
                        isParsing = false,
                        info = info,
                        albumTitle = info.title,
                        artist = info.ownerName,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateSearchPreview { it.copy(isParsing = false, parseError = friendlyError(t)) }
            }
        }
    }

    fun updateSearchPreviewTitle(text: String) {
        updateSearchPreview { it.copy(albumTitle = text) }
    }

    fun updateSearchPreviewArtist(text: String) {
        updateSearchPreview { it.copy(artist = text) }
    }

    fun importSearchPreview() {
        val state = _uiState.value.searchPreview ?: return
        val info = state.info ?: return
        if (state.isImporting) return
        updateSearchPreview { it.copy(isImporting = true, importError = null) }
        viewModelScope.launch {
            try {
                // 防御性切 IO：即使实现层未自行切线程，也不会阻塞主线程
                val result = withContext(Dispatchers.IO) {
                    albumRepository.importAlbum(
                        AlbumImportRequest(
                            bvidOrUrl = info.bvid,
                            albumTitle = state.albumTitle.trim().ifBlank { null },
                            artist = state.artist.trim().ifBlank { null },
                            mode = ImportMode.SINGLE_VIDEO,
                        ),
                    )
                }
                when (result) {
                    is ImportResult.Success -> {
                        updateSearchPreview {
                            it.copy(
                                isImporting = false,
                                importedAlbumId = result.albumId,
                                importedTitle = result.albumTitle,
                                importedTrackCount = result.trackCount,
                            )
                        }
                        _messages.tryEmit("导入成功：《${result.albumTitle}》，共 ${result.trackCount} 首")
                    }

                    is ImportResult.Failure -> {
                        updateSearchPreview {
                            it.copy(isImporting = false, importError = result.message)
                        }
                        _messages.tryEmit(result.message)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateSearchPreview {
                    it.copy(isImporting = false, importError = friendlyError(t))
                }
            }
        }
    }

    fun dismissSearchPreview() {
        _uiState.update { it.copy(searchPreview = null) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private inline fun updateSearchPreview(crossinline transform: (VideoImportState) -> VideoImportState) {
        _uiState.update { state ->
            val current = state.searchPreview ?: return@update state
            state.copy(searchPreview = transform(current))
        }
    }

    /**
     * 读取文本文件（限制 500 KB，UTF-8）。
     * 返回 (文件名, 文本内容)；异常信息为中文，可直接展示。
     */
    private suspend fun readBatchTextFile(uri: Uri): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val displayName = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            }.getOrNull() ?: "导入文本"
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BATCH_FILE_BYTES) {
                        throw IOException("文件超过 500 KB 限制")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: throw IOException("无法打开所选文件")
            val text = String(bytes, Charsets.UTF_8)
            if (text.isBlank()) throw IOException("文件内容为空")
            displayName to text
        }

    /** 把底层异常转换为用户可读的中文提示。 */
    private fun friendlyError(t: Throwable): String = when (t) {
        is BiliApiException -> t.message?.takeIf { it.isNotBlank() }
            ?: "B 站接口错误${t.code?.let { "（code $it）" } ?: ""}"

        is IOException -> "网络连接失败，请检查网络后重试"
        is SecurityException -> "没有访问权限"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
    }

    private companion object {
        const val MAX_BATCH_FILE_BYTES = 500 * 1024
    }
}
