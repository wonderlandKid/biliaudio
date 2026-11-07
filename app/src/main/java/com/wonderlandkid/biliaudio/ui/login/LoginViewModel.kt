package com.wonderlandkid.biliaudio.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.QrLoginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 扫码登录 ViewModel。
 *
 * 直接透传 [AuthRepository] 的 [authState] / [qrState]（状态由数据层轮询维护），
 * 页面只负责根据 phase 渲染二维码与文案。
 *
 * 额外维护一个 [qrError] 兜底：如果数据层的 startQrLogin 直接抛异常（而不是把 qrState 置为 ERROR），
 * 页面也能展示明确的错误 + 重试按钮，不会一直停在 loading。
 *
 * @param appScope 应用级协程作用域，用于「取消轮询」这类不能随页面/VM 一起被取消的收尾操作，
 *                避免 ViewModel 清理后轮询协程仍在后台跑（例如用户快速返回时）。
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState
    val qrState: StateFlow<QrLoginState> = authRepository.qrState

    private val _qrError = MutableStateFlow<String?>(null)
    val qrError: StateFlow<String?> = _qrError.asStateFlow()

    init {
        // 先刷新一次登录态（可能之前已登录）；失败不阻塞页面，按未登录展示二维码
        viewModelScope.launch {
            try {
                authRepository.refresh()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // 登录态刷新失败交由二维码流程兜底
            }
        }
        // 未登录才生成二维码；已登录直接展示账号信息
        if (authRepository.currentProfile == null) {
            startQrLogin()
        }
    }

    /** 生成新的登录二维码（进入页面 / 刷新过期二维码 / 出错重试）。 */
    fun startQrLogin() {
        _qrError.value = null
        viewModelScope.launch {
            try {
                authRepository.startQrLogin()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _qrError.value = "二维码获取失败：${t.message?.takeIf { it.isNotBlank() } ?: "请检查网络后重试"}"
            }
        }
    }

    fun refreshQrCode() = startQrLogin()

    /**
     * 取消扫码轮询。
     * 用户点返回时立即调用；ViewModel 销毁时作为兜底再调用一次，避免后台轮询泄漏。
     */
    fun cancelLogin() {
        appScope.launch { runCatching { authRepository.cancelQrLogin() } }
    }

    override fun onCleared() {
        super.onCleared()
        // 页面从返回栈移除（返回 / 登录成功跳转）时停止轮询；配置变更不会触发 onCleared
        appScope.launch { runCatching { authRepository.cancelQrLogin() } }
    }
}
