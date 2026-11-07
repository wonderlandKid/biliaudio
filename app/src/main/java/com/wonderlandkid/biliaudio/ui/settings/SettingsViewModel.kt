package com.wonderlandkid.biliaudio.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 设置页 ViewModel：账号态、播放音质、深色模式、备份与恢复。
 *
 * 所有写入都通过 [SettingsRepository] / [LibraryArchiveRepository]，
 * 状态由仓库的 StateFlow 回流，页面只负责展示与触发。
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val libraryArchiveRepository: LibraryArchiveRepository,
    private val appContext: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val authState: StateFlow<AuthState> = authRepository.authState

    /** 最近一次「覆盖全库」前的自动备份时间（毫秒），null 表示暂无。 */
    private val _lastAutoBackupAt = MutableStateFlow<Long?>(null)
    val lastAutoBackupAt: StateFlow<Long?> = _lastAutoBackupAt.asStateFlow()

    /** 是否正在导出音乐库（导出按钮 loading）。 */
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    /** 是否正在导出「目录形态归档」到文件夹。 */
    private val _isExportingTree = MutableStateFlow(false)
    val isExportingTree: StateFlow<Boolean> = _isExportingTree.asStateFlow()

    /** 是否正在从文件夹导入「目录形态归档」。 */
    private val _isImportingTree = MutableStateFlow(false)
    val isImportingTree: StateFlow<Boolean> = _isImportingTree.asStateFlow()

    /** 目录导入结果（非空时由页面弹窗展示统计与备份路径）。 */
    private val _treeImportResult = MutableStateFlow<ArchiveImportReport?>(null)
    val treeImportResult: StateFlow<ArchiveImportReport?> = _treeImportResult.asStateFlow()

    /** 一次性提示（退出登录 / 导出结果等）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // 进入设置页时刷新一次登录态（可能在其他设备退出 / Cookie 失效）；失败时保持当前展示
        viewModelScope.launch {
            try {
                authRepository.refresh()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // 刷新失败不阻塞设置页
            }
        }
        refreshLastAutoBackup()
    }

    /** 刷新「上次自动备份」时间（读取私有备份目录，切 IO）。 */
    fun refreshLastAutoBackup() {
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                runCatching { libraryArchiveRepository.lastAutoBackupAt() }.getOrNull()
            }
            _lastAutoBackupAt.value = timestamp
        }
    }

    /**
     * 导出整个音乐库到用户通过 SAF 选择的目标文件（JSON）。
     *
     * @param uri ActivityResultContracts.CreateDocument 返回的目标文件 uri
     */
    fun exportLibrary(uri: Uri) {
        if (_isExporting.value) return
        _isExporting.value = true
        viewModelScope.launch {
            try {
                val albumCount = withContext(Dispatchers.IO) {
                    val json = libraryArchiveRepository.exportAllJson()
                    // 解析一次只为统计专辑数（导出内容本身已由数据层保证格式）
                    val albumCount = runCatching {
                        com.wonderlandkid.biliaudio.data.impl.LibraryArchiveCodec.defaultJson
                            .decodeFromString<LibraryArchive>(json)
                            .albums.size
                    }.getOrDefault(0)
                    val resolver = appContext.contentResolver
                    val output = resolver.openOutputStream(uri)
                        ?: throw IOException("无法打开目标文件")
                    output.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                    albumCount
                }
                _isExporting.value = false
                _messages.tryEmit("已导出（$albumCount 张专辑）")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _isExporting.value = false
                _messages.tryEmit("导出失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 目录形态归档（顶层 → 艺术家 → 专辑 → 分割）
    // ------------------------------------------------------------------

    /**
     * 导出「目录形态归档」到用户选择的文件夹（SAF OpenDocumentTree）。
     *
     * 按 [ArchiveTreeEntry.relativePath] 自动创建子目录并写入文件（JSON UTF-8），
     * 已存在的同名文件会覆盖内容。
     */
    fun exportTreeToFolder(treeUri: Uri) {
        if (_isExportingTree.value) return
        _isExportingTree.value = true
        viewModelScope.launch {
            try {
                val fileCount = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(appContext, treeUri)
                        ?: throw IOException("无法访问所选文件夹")
                    val entries = libraryArchiveRepository.exportTreeEntries()
                    if (entries.isEmpty()) throw IOException("当前音乐库为空，没有可导出的内容")
                    var written = 0
                    entries.forEach { entry ->
                        val segments = entry.relativePath.split('/').filter { it.isNotBlank() }
                        if (segments.isEmpty()) return@forEach
                        var dir = root
                        for (index in 0 until segments.lastIndex) {
                            dir = ensureDirectory(dir, segments[index])
                        }
                        writeTreeEntry(dir, segments.last(), entry)
                        written++
                    }
                    written
                }
                _isExportingTree.value = false
                _messages.tryEmit("已导出到文件夹：$fileCount 个文件")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _isExportingTree.value = false
                _messages.tryEmit("导出到文件夹失败：${messageOf(t)}")
            }
        }
    }

    /**
     * 从文件夹导入「目录形态归档」。
     *
     * 递归读取所选目录下的 *.json（深度/文件数/单文件大小均有限制），
     * 以相对路径构造 [ArchiveTreeEntry] 后交给数据层按 [mode] 导入。
     */
    fun importTreeFromFolder(treeUri: Uri, mode: ArchiveImportMode) {
        if (_isImportingTree.value) return
        _isImportingTree.value = true
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(appContext, treeUri)
                        ?: throw IOException("无法访问所选文件夹")
                    val entries = readTreeEntries(root)
                    if (entries.isEmpty()) {
                        throw IOException("所选文件夹中没有找到 .json 归档文件（请选择「导出到文件夹」生成的目录或其中一级）")
                    }
                    libraryArchiveRepository.importTreeEntries(entries, mode)
                }
                _isImportingTree.value = false
                _treeImportResult.value = report
                // 覆盖全库模式会自动备份，刷新「上次自动备份」时间
                refreshLastAutoBackup()
                _messages.tryEmit(
                    "目录导入完成：新建 ${report.createdCount} / 更新 ${report.updatedCount} / " +
                        "分曲更新 ${report.segmentsUpdatedCount} / 跳过 ${report.skippedCount} / " +
                        "失败 ${report.failedCount}",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _isImportingTree.value = false
                _messages.tryEmit("从文件夹导入失败：${messageOf(t)}")
            }
        }
    }

    /** 关闭目录导入结果弹窗。 */
    fun dismissTreeImportResult() {
        _treeImportResult.value = null
    }

    // ------------------------------------------------------------------
    // SAF 文件 / 目录写入工具（均在 IO 线程被调用）
    // ------------------------------------------------------------------

    /** 在 SAF 目录下确保存在子目录，返回该目录。 */
    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return parent.createDirectory(name)
            ?: throw IOException("创建目录失败：$name")
    }

    /** 把归档条目写入 SAF 目录（同名文件覆盖内容）。 */
    private fun writeTreeEntry(parent: DocumentFile, fileName: String, entry: ArchiveTreeEntry) {
        val document = parent.findFile(fileName)?.takeIf { it.isFile }
            ?: parent.createFile(entry.mimeType.ifBlank { "application/octet-stream" }, fileName)
            ?: throw IOException("创建文件失败：$fileName")
        val output = appContext.contentResolver.openOutputStream(document.uri)
            ?: throw IOException("无法写入文件：$fileName")
        output.use { stream ->
            stream.write(entry.bytes)
            stream.flush()
        }
    }

    /** 递归读取目录下的 *.json 文件为归档条目（限制深度/数量/单文件大小）。 */
    private fun readTreeEntries(root: DocumentFile): List<ArchiveTreeEntry> {
        val entries = ArrayList<ArchiveTreeEntry>()

        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
            if (depth > MAX_TREE_DEPTH || entries.size >= MAX_TREE_FILES) return
            val children = dir.listFiles()
            for (child in children) {
                if (entries.size >= MAX_TREE_FILES) return
                val name = child.name?.takeIf { it.isNotBlank() } ?: continue
                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, relativePath, depth + 1)
                    continue
                }
                if (!name.endsWith(".json", ignoreCase = true)) continue
                val bytes = runCatching { readDocumentBytes(child.uri) }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue
                entries += ArchiveTreeEntry(
                    relativePath = relativePath,
                    bytes = bytes,
                    mimeType = child.type?.takeIf { it.isNotBlank() } ?: "application/json",
                )
            }
        }

        walk(root, prefix = "", depth = 0)
        return entries.sortedBy { it.relativePath }
    }

    /** 读取单个 SAF 文档的字节（带大小上限）。 */
    private fun readDocumentBytes(uri: Uri): ByteArray {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取文件")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_TREE_FILE_BYTES) {
                    throw IOException("单个文件超过 ${MAX_TREE_FILE_BYTES / 1024 / 1024} MB 限制")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun messageOf(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: "未知错误"

    /** 维测日志开关（立即生效并持久化）。 */
    fun setDiagnosticLogsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                // 关闭前先记一条，便于回溯是谁关的；本身也遵守开关
                if (!enabled) Diag.log("App", "diagnostic logs disabled by user")
                settingsRepository.setDiagnosticLogsEnabled(enabled)
                Diag.enabled = enabled
                if (enabled) Diag.log("App", "diagnostic logs enabled by user")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _messages.tryEmit("保存维测日志设置失败：${t.message ?: "未知错误"}")
            }
        }
    }

    fun setAudioQuality(preference: AudioQualityPreference) {
        viewModelScope.launch {
            try {
                settingsRepository.setAudioQuality(preference)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _messages.tryEmit("保存音质设置失败：${t.message ?: "未知错误"}")
            }
        }
    }

    /** @param dark null = 跟随系统，false = 浅色，true = 深色 */
    fun setDarkTheme(dark: Boolean?) {
        viewModelScope.launch {
            try {
                settingsRepository.setDarkTheme(dark)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _messages.tryEmit("保存外观设置失败：${t.message ?: "未知错误"}")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                authRepository.logout()
                _messages.tryEmit("已退出登录")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _messages.tryEmit("退出登录失败：${t.message ?: "未知错误"}")
            }
        }
    }

    private companion object {
        /** 目录导入递归深度 / 文件数 / 单文件大小限制（防止误选超大目录）。 */
        const val MAX_TREE_DEPTH = 8
        const val MAX_TREE_FILES = 3000
        const val MAX_TREE_FILE_BYTES = 8 * 1024 * 1024
    }
}
