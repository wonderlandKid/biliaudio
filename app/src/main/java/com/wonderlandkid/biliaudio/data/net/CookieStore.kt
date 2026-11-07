package com.wonderlandkid.biliaudio.data.net

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * B 站 Cookie 持久化（SharedPreferences）。
 *
 * - [parseAndStore]：从任意接口响应的 `Set-Cookie` 头更新 Cookie（含扫码登录成功）；
 * - [cookieHeader]：登录态 Cookie（未登录返回空串）；
 * - [baseCookieHeader]：未登录也需要的基础 Cookie（buvid3/buvid4）；
 * - [allCookieHeader]：两者合并，实际请求统一用这个；
 * - [clearSession]：退出登录只清登录态，保留设备指纹。
 *
 * 所有读写加锁，可被 OkHttp 拦截器（工作线程）和 UI 协程同时访问。
 */
class CookieStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** 用接口响应的 Set-Cookie 头更新 Cookie。 */
    fun parseAndStore(setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        synchronized(lock) {
            val cookies = readCookies()
            var changed = false
            for (header in setCookieHeaders) {
                val parsed = CookieUtils.parseSetCookie(header) ?: continue
                val (name, value) = parsed
                if (value == null) {
                    if (cookies.remove(name) != null) changed = true
                } else {
                    if (cookies[name] != value) {
                        cookies[name] = value
                        changed = true
                    }
                }
            }
            if (changed) writeCookies(cookies)
        }
    }

    /** 直接写入若干 Cookie（扫码登录跨域 URL / buvid 兜底用）。 */
    fun putCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val current = readCookies()
            var changed = false
            for ((name, value) in cookies) {
                if (name.isBlank() || value.isBlank()) continue
                if (current[name] != value) {
                    current[name] = value
                    changed = true
                }
            }
            if (changed) writeCookies(current)
        }
    }

    /** 登录态 Cookie 请求头；未登录返回空串。 */
    fun cookieHeader(): String = synchronized(lock) {
        if (!hasSessionUnlocked()) "" else CookieUtils.buildCookieHeader(readCookies(), CookieUtils.SESSION_COOKIE_NAMES)
    }

    /** 未登录也需要的基础 Cookie 请求头。 */
    fun baseCookieHeader(): String = synchronized(lock) {
        CookieUtils.buildCookieHeader(readCookies(), CookieUtils.BASE_COOKIE_NAMES)
    }

    /** 登录态 + 基础 Cookie，请求统一携带。 */
    fun allCookieHeader(): String = synchronized(lock) {
        val cookies = readCookies()
        if (cookies.isEmpty()) return ""
        val orderedNames = CookieUtils.SESSION_COOKIE_NAMES + CookieUtils.BASE_COOKIE_NAMES +
            cookies.keys.filterNot { it in CookieUtils.SESSION_COOKIE_NAMES || it in CookieUtils.BASE_COOKIE_NAMES }
        CookieUtils.buildCookieHeader(cookies, orderedNames)
    }

    /** 是否有登录态（SESSDATA 存在）。 */
    fun hasSession(): Boolean = synchronized(lock) { hasSessionUnlocked() }

    /** 退出登录：清登录态 Cookie，保留 buvid 等设备指纹。 */
    fun clearSession() {
        synchronized(lock) {
            val cookies = readCookies()
            val removed = cookies.keys.filter { it in CookieUtils.SESSION_COOKIE_NAMES }
            if (removed.isNotEmpty()) {
                removed.forEach { cookies.remove(it) }
                writeCookies(cookies)
            }
        }
    }

    /** 清空全部 Cookie（一般不需要，调试 / 重新初始化用）。 */
    fun clearAll() {
        synchronized(lock) { prefs.edit().remove(KEY_COOKIES).apply() }
    }

    private fun hasSessionUnlocked(): Boolean =
        readCookies()["SESSDATA"]?.isNotBlank() == true

    private fun readCookies(): MutableMap<String, String> {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return LinkedHashMap()
        return try {
            val map = json.decodeFromString(MAP_SERIALIZER, raw)
            LinkedHashMap(map)
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun writeCookies(cookies: Map<String, String>) {
        val raw = try {
            json.encodeToString(MAP_SERIALIZER, cookies)
        } catch (_: Exception) {
            return
        }
        prefs.edit().putString(KEY_COOKIES, raw).apply()
    }

    private companion object {
        const val PREFS_NAME = "biliaudio_cookies"
        const val KEY_COOKIES = "cookies_json"
        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
