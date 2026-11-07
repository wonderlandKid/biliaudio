package com.wonderlandkid.biliaudio.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
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
}
