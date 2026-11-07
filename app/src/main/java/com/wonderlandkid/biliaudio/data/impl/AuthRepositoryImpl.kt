package com.wonderlandkid.biliaudio.data.impl

import android.content.Context
import android.content.SharedPreferences
import com.wonderlandkid.biliaudio.data.model.UserProfile
import com.wonderlandkid.biliaudio.data.net.BiliApi
import com.wonderlandkid.biliaudio.data.net.BiliTextUtils
import com.wonderlandkid.biliaudio.data.net.CookieStore
import com.wonderlandkid.biliaudio.data.repository.AuthRepository
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.QrLoginState
import com.wonderlandkid.biliaudio.data.repository.QrPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * B 站账号仓库：扫码登录 + Cookie 持久化 + nav 登录态校验。
 *
 * 装配（AppContainer，注意先创建 streamResolver 再注入回调）：
 * ```
 * val authRepository = AuthRepositoryImpl(
 *     context = appContext,
 *     api = biliApi,
 *     cookieStore = cookieStore,
 *     scope = appScope,
 *     onSessionChanged = { streamResolverImpl.invalidateAll() },  // 登录/退出后清媒体流缓存
 * )
 * ```
 * 冷启动时用本地缓存的账号信息立即显示登录态，再调 [refresh] 向 B 站校验。
 */
class AuthRepositoryImpl(
    context: Context,
    private val api: BiliApi,
    private val cookieStore: CookieStore,
    private val scope: CoroutineScope,
    /** 登录态变化（登录成功 / 退出 / Cookie 失效）时回调，用于清空媒体流缓存。 */
    private val onSessionChanged: (() -> Unit)? = null,
) : AuthRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _qrState = MutableStateFlow(QrLoginState())
    override val qrState: StateFlow<QrLoginState> = _qrState.asStateFlow()

    @Volatile
    override var currentProfile: UserProfile? = null
        private set

    private var qrPollJob: Job? = null

    init {
        val cachedProfile = loadCachedProfile()
        _authState.value = when {
            cookieStore.hasSession() && cachedProfile != null -> {
                currentProfile = cachedProfile
                AuthState.LoggedIn(cachedProfile)
            }
            cookieStore.hasSession() -> AuthState.Unknown
            else -> AuthState.LoggedOut
        }
    }

    override suspend fun refresh() {
        // 未登录也尝试拿 buvid 基础 Cookie（失败不阻断）
        try {
            api.ensureBaseCookies()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 忽略
        }
        try {
            val nav = api.nav()
            if (nav.isLogin) {
                applyLoggedIn(
                    UserProfile(
                        mid = nav.mid,
                        name = nav.uname.ifBlank { "B 站用户" },
                        avatarUrl = BiliTextUtils.normalizeImageUrl(nav.face),
                    ),
                )
            } else {
                applyLoggedOut(hadSession = cookieStore.hasSession())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 网络失败：有本地缓存就保留登录态，避免网络抖动把用户踢成未登录
            val cached = currentProfile
            _authState.value = when {
                cached != null && cookieStore.hasSession() -> AuthState.LoggedIn(cached)
                cookieStore.hasSession() -> AuthState.Unknown
                else -> AuthState.LoggedOut
            }
        }
    }

    override suspend fun startQrLogin() {
        qrPollJob?.cancel()
        qrPollJob = null
        _qrState.value = QrLoginState(phase = QrPhase.LOADING, message = "正在获取二维码…")
        val generated = try {
            api.qrGenerate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _qrState.value = QrLoginState(phase = QrPhase.ERROR, message = e.message ?: "获取登录二维码失败")
            return
        }
        _qrState.value = QrLoginState(
            phase = QrPhase.WAITING,
            qrUrl = generated.url,
            message = "请使用哔哩哔哩客户端扫码登录",
        )
        qrPollJob = scope.launch { pollQrCode(generated.qrcodeKey) }
    }

    override suspend fun cancelQrLogin() {
        qrPollJob?.cancel()
        qrPollJob = null
        _qrState.value = QrLoginState()
    }

    override suspend fun logout() {
        qrPollJob?.cancel()
        qrPollJob = null
        _qrState.value = QrLoginState()
        val hadSession = cookieStore.hasSession()
        cookieStore.clearSession()
        currentProfile = null
        persistProfile(null)
        _authState.value = AuthState.LoggedOut
        if (hadSession) onSessionChanged?.invoke()
    }

    override fun cookieHeader(): String = cookieStore.cookieHeader()

    override fun baseCookieHeader(): String = cookieStore.baseCookieHeader()

    // -----------------------------------------------------------------------
    // 扫码轮询
    // -----------------------------------------------------------------------

    private suspend fun pollQrCode(qrcodeKey: String) {
        val deadline = System.currentTimeMillis() + QR_LOGIN_TIMEOUT_MS
        while (true) {
            delay(QR_POLL_INTERVAL_MS)
            if (System.currentTimeMillis() > deadline) {
                _qrState.value = _qrState.value.copy(phase = QrPhase.EXPIRED, message = "二维码已过期，请刷新")
                return
            }
            val result = try {
                api.qrPoll(qrcodeKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 轮询网络抖动：继续重试直到超时
                continue
            }
            when (result.code) {
                0 -> {
                    handleQrLoginSuccess()
                    return
                }
                86090 -> _qrState.value = _qrState.value.copy(
                    phase = QrPhase.SCANNED,
                    message = "已扫码，请在手机上确认登录",
                )
                86038 -> {
                    _qrState.value = _qrState.value.copy(phase = QrPhase.EXPIRED, message = "二维码已失效，请刷新")
                    return
                }
                86101 -> _qrState.value = _qrState.value.copy(
                    phase = QrPhase.WAITING,
                    message = "等待扫码…",
                )
                // 其他业务码：继续轮询
            }
        }
    }

    private suspend fun handleQrLoginSuccess() {
        _qrState.value = _qrState.value.copy(phase = QrPhase.SUCCESS, message = "登录成功")
        // Cookie 已由 BiliApi 自动落盘；通知媒体流缓存失效
        onSessionChanged?.invoke()
        try {
            val nav = api.nav()
            if (nav.isLogin) {
                applyLoggedIn(
                    UserProfile(
                        mid = nav.mid,
                        name = nav.uname.ifBlank { "B 站用户" },
                        avatarUrl = BiliTextUtils.normalizeImageUrl(nav.face),
                    ),
                )
            } else {
                _authState.value = AuthState.Unknown
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cookie 已拿到，账号信息稍后可通过 refresh() 补
            _authState.value = AuthState.Unknown
        }
    }

    // -----------------------------------------------------------------------
    // 状态 / 本地缓存
    // -----------------------------------------------------------------------

    private fun applyLoggedIn(profile: UserProfile) {
        currentProfile = profile
        persistProfile(profile)
        _authState.value = AuthState.LoggedIn(profile)
    }

    private fun applyLoggedOut(hadSession: Boolean) {
        if (hadSession) cookieStore.clearSession()
        currentProfile = null
        persistProfile(null)
        _authState.value = AuthState.LoggedOut
        if (hadSession) onSessionChanged?.invoke()
    }

    private fun loadCachedProfile(): UserProfile? {
        val mid = prefs.getLong(KEY_MID, 0L)
        if (mid <= 0L) return null
        return UserProfile(
            mid = mid,
            name = prefs.getString(KEY_NAME, null).orEmpty().ifBlank { "B 站用户" },
            avatarUrl = prefs.getString(KEY_FACE, null).orEmpty(),
        )
    }

    private fun persistProfile(profile: UserProfile?) {
        val editor = prefs.edit()
        if (profile == null) {
            editor.remove(KEY_MID).remove(KEY_NAME).remove(KEY_FACE)
        } else {
            editor.putLong(KEY_MID, profile.mid)
                .putString(KEY_NAME, profile.name)
                .putString(KEY_FACE, profile.avatarUrl)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "biliaudio_auth"
        const val KEY_MID = "profile_mid"
        const val KEY_NAME = "profile_name"
        const val KEY_FACE = "profile_face"
        const val QR_POLL_INTERVAL_MS = 2_000L
        const val QR_LOGIN_TIMEOUT_MS = 180_000L
    }
}
