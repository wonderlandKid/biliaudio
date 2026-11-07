package com.wonderlandkid.biliaudio.data.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端工厂。
 *
 * 统一做三件事（docs/API_NOTES.md §0）：
 * 1. 所有请求带桌面 UA + Referer + Origin（B 站接口/媒体/CDN 都校验）；
 * 2. 所有 bilibili.com 域名请求自动注入 Cookie（登录态 + buvid）；
 * 3. 合理的超时时间。
 *
 * 媒体播放请求（ExoPlayer 用）不经过该客户端，需要把 [mediaHeaders] 的结果传给播放器。
 */
object BiliHttp {

    fun createClient(cookieStore: CookieStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(BaseHeaderInterceptor())
            .addInterceptor(CookieInterceptor(cookieStore))
            .build()

    /** 播放媒体流必须的请求头（UA / Referer / Cookie），交给 ExoPlayer 的 DataSource。 */
    fun mediaHeaders(cookieStore: CookieStore): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to BiliConstants.USER_AGENT,
            "Referer" to BiliConstants.MEDIA_REFERER,
            "Origin" to BiliConstants.ORIGIN,
        )
        val cookie = cookieStore.allCookieHeader()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }

    /** 统一请求头（真实接口验证过：缺 Referer 媒体流 403）。 */
    private class BaseHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
                .header("User-Agent", BiliConstants.USER_AGENT)
                .header("Referer", BiliConstants.REFERER)
                .header("Origin", BiliConstants.ORIGIN)
            if (original.header("Accept") == null) builder.header("Accept", "*/*")
            return chain.proceed(builder.build())
        }
    }

    /** 给 bilibili.com 域名自动注入 Cookie（调用方显式设置了 Cookie 则以调用方为准）。 */
    private class CookieInterceptor(private val cookieStore: CookieStore) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host.lowercase()
            val isBiliHost = host == "bilibili.com" || host.endsWith(".bilibili.com")
            if (!isBiliHost || request.header("Cookie") != null) {
                return chain.proceed(request)
            }
            val cookie = cookieStore.allCookieHeader()
            if (cookie.isBlank()) return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("Cookie", cookie).build())
        }
    }
}
