package com.wonderlandkid.biliaudio.ui.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.impl.LibraryArchiveCodec
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.BiliApiException
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.ImportMode
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
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

    /** 「归档 / 批量导入」：归档 JSON（推荐）+ 兼容旧批量格式。 */
    BATCH("归档导入"),
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

/**
 * 「归档 / 批量导入」Tab 状态。
 *
 * 流程：输入文本 / 选择文件 → 选导入模式 → 解析预览（不写库，展示计划动作）
 * → 开始导入 → 逐条结果报告。
 */
data class ArchiveImportState(
    /** 导入模式，默认合并导入 */
    val mode: ArchiveImportMode = ArchiveImportMode.MERGE,
    /** 粘贴或从文件读取的归档文本 */
    val text: String = "",
    /** 从文件读取时的文件名（仅展示用） */
    val fileName: String? = null,
    val isReadingFile: Boolean = false,
    val isPreviewing: Boolean = false,
    /** 解析预览结果（不写库） */
    val preview: ArchivePreview? = null,
    val previewError: String? = null,
    val isImporting: Boolean = false,
    /** 导入结果报告 */
    val report: ArchiveImportReport? = null,
    val importError: String? = null,
) {
    /** 有内容且无进行中的任务才能解析预览 */
    val canPreview: Boolean
        get() = text.isNotBlank() && !isReadingFile && !isPreviewing && !isImporting

    /** 必须先解析预览、确认计划动作后才能执行导入 */
    val canImport: Boolean
        get() = preview != null && !isPreviewing && !isImporting && previewError == null
}

/** 导入页整体 UI 状态。 */
data class ImportUiState(
    val selectedTab: ImportTab = ImportTab.BV,
    // ---- BV 号导入 ----
    val bv: VideoImportState = VideoImportState(),
    // ---- 归档 / 批量导入 ----
    val archive: ArchiveImportState = ArchiveImportState(),
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
 * - 归档 / 批量文本 → [LibraryArchiveRepository.preview] 预览 → [LibraryArchiveRepository.importArchive]；
 * - 关键词搜索 → [BiliRepository.searchVideos] → 预览 → 导入。
 *
 * 依赖从 [com.wonderlandkid.biliaudio.di.AppContainer] 注入（通过 appViewModel {}）。
 */
class ImportViewModel(
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val libraryArchiveRepository: LibraryArchiveRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    /** 一次性提示（Snackbar 用），避免把瞬时事件塞进持久状态。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * 最小分割点归档示例（由数据层 Codec 生成，保证与解析器完全一致）。
     * 供「支持的格式」卡片展示，懒加载避免无谓构造。
     */
    val sampleSegmentJson: String by lazy {
        runCatching { LibraryArchiveCodec().sampleSegmentJson() }
            .getOrDefault("")
    }

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
    // 归档 / 批量导入
    // ------------------------------------------------------------------

    /** 切换导入模式；模式影响预览的计划动作，因此清空旧预览与报告。 */
    fun setArchiveMode(mode: ArchiveImportMode) {
        _uiState.update { state ->
            if (state.archive.mode == mode) {
                state
            } else {
                state.copy(
                    archive = state.archive.copy(
                        mode = mode,
                        preview = null,
                        previewError = null,
                        report = null,
                        importError = null,
                    ),
                )
            }
        }
    }

    /** 文本变化：清空旧预览/报告/文件名（内容已与预览不对应）。 */
    fun updateArchiveText(text: String) {
        _uiState.update { state ->
            state.copy(
                archive = state.archive.copy(
                    text = text,
                    fileName = null,
                    preview = null,
                    previewError = null,
                    report = null,
                    importError = null,
                ),
            )
        }
    }

    /** 读取用户通过 OpenDocument 选择的归档文件（json/txt）。 */
    fun loadArchiveFile(uri: Uri) {
        _uiState.update {
            it.copy(
                archive = it.archive.copy(isReadingFile = true, previewError = null, importError = null),
            )
        }
        viewModelScope.launch {
            try {
                val (fileName, text) = readTextFile(uri, MAX_ARCHIVE_FILE_BYTES)
                _uiState.update {
                    it.copy(
                        archive = it.archive.copy(
                            isReadingFile = false,
                            fileName = fileName,
                            text = text,
                            preview = null,
                            previewError = null,
                            report = null,
                        ),
                    )
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
                _uiState.update { it.copy(archive = it.archive.copy(isReadingFile = false)) }
                _messages.tryEmit("读取文件失败：$reason")
            }
        }
    }

    /** 清空归档输入与结果（保留当前导入模式）。 */
    fun clearArchive() {
        _uiState.update {
            it.copy(archive = ArchiveImportState(mode = it.archive.mode))
        }
    }

    /** 解析归档文本并预览计划动作（不写库）。 */
    fun previewArchive() {
        val state = _uiState.value.archive
        val text = state.text
        if (text.isBlank()) {
            _uiState.update {
                it.copy(archive = it.archive.copy(previewError = "请先粘贴归档内容，或从文件读取"))
            }
            return
        }
        if (state.isPreviewing) return
        _uiState.update {
            it.copy(
                archive = it.archive.copy(
                    isPreviewing = true,
                    preview = null,
                    previewError = null,
                    report = null,
                    importError = null,
                ),
            )
        }
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    libraryArchiveRepository.preview(text, state.mode)
                }
                _uiState.update {
                    it.copy(archive = it.archive.copy(isPreviewing = false, preview = preview))
                }
                if (preview.parseErrors.isNotEmpty()) {
                    _messages.tryEmit("预览完成：${preview.parseErrors.size} 条条目解析失败，详见下方列表")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        archive = it.archive.copy(
                            isPreviewing = false,
                            previewError = archiveErrorMessage(t),
                        ),
                    )
                }
            }
        }
    }

    /** 按当前模式执行归档导入（REPLACE 的二次确认由 UI 在选模式时完成）。 */
    fun importArchive() {
        val state = _uiState.value.archive
        if (state.text.isBlank()) {
            _uiState.update {
                it.copy(archive = it.archive.copy(importError = "请先粘贴归档内容，或从文件读取"))
            }
            return
        }
        if (state.preview == null) {
            _uiState.update {
                it.copy(archive = it.archive.copy(importError = "请先解析预览，确认导入计划后再开始导入"))
            }
            return
        }
        if (state.isImporting) return
        _uiState.update {
            it.copy(archive = it.archive.copy(isImporting = true, importError = null, report = null))
        }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    libraryArchiveRepository.importArchive(state.text, state.mode)
                }
                // 预览的计划动作已执行完毕，清空避免与报告混淆
                _uiState.update {
                    it.copy(
                        archive = it.archive.copy(
                            isImporting = false,
                            report = report,
                            preview = null,
                            previewError = null,
                        ),
                    )
                }
                _messages.tryEmit(archiveReportSummary(report))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        archive = it.archive.copy(
                            isImporting = false,
                            importError = archiveErrorMessage(t),
                        ),
                    )
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
     * 读取文本文件（UTF-8，带体积上限）。
     * 返回 (文件名, 文本内容)；异常信息为中文，可直接展示。
     */
    private suspend fun readTextFile(uri: Uri, maxBytes: Int): Pair<String, String> =
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
                    if (total > maxBytes) {
                        throw IOException("文件过大（超过 ${maxBytes / 1024 / 1024} MB）")
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
        is ArchiveFormatException -> t.message?.takeIf { it.isNotBlank() } ?: "归档文件格式不正确"

        is BiliApiException -> t.message?.takeIf { it.isNotBlank() }
            ?: "B 站接口错误${t.code?.let { "（code $it）" } ?: ""}"

        is IOException -> "网络连接失败，请检查网络后重试"
        is SecurityException -> "没有访问权限"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
    }

    /** 归档预览/导入的异常提示（ArchiveFormatException 的 message 可直接展示）。 */
    private fun archiveErrorMessage(t: Throwable): String = when (t) {
        is ArchiveFormatException -> t.message?.takeIf { it.isNotBlank() } ?: "归档文件格式不正确"
        else -> friendlyError(t)
    }

    /** 归档导入完成后的 Snackbar 摘要。 */
    private fun archiveReportSummary(report: ArchiveImportReport): String {
        val base = buildString {
            append("归档导入完成：")
            append("新建 ${report.createdCount}")
            append(" / 更新 ${report.updatedCount}")
            append(" / 分曲更新 ${report.segmentsUpdatedCount}")
            append(" / 跳过 ${report.skippedCount}")
            append(" / 失败 ${report.failedCount}")
        }
        return if (report.mode == ArchiveImportMode.REPLACE) {
            if (report.backupFilePath != null) "$base（覆盖前已自动备份）" else "$base（自动备份失败，请谨慎操作）"
        } else {
            base
        }
    }

    private companion object {
        /** 归档文件大小上限：比 Codec 的 5 MB 校验略宽，让 Codec 给出更准确的提示。 */
        const val MAX_ARCHIVE_FILE_BYTES = 8 * 1024 * 1024
    }
}
