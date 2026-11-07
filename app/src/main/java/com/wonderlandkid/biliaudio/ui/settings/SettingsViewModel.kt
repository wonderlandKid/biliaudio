package com.wonderlandkid.biliaudio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：账号态、播放音质、深色模式。
 *
 * 所有写入都通过 [SettingsRepository]，状态由仓库的 [SettingsRepository.settings] 回流，
 * 页面只负责展示与触发。
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val authState: StateFlow<AuthState> = authRepository.authState

    /** 一次性提示（退出登录结果等）。 */
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
