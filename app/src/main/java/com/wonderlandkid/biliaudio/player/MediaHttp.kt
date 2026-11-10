package com.wonderlandkid.biliaudio.player

import com.wonderlandkid.biliaudio.data.net.BiliConstants
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放层（音频 / 封面）共用的 OkHttp 客户端与请求头工具。
 *
 * B 站媒体 CDN 对请求头校验严格：**缺少 Referer / UA 会 403**（见 docs/API_NOTES.md）。
 * 这里统一注入兜底的 UA + Referer；[com.wonderlandkid.biliaudio.data.model.ResolvedStreams.headers]
 * 中的请求头（含 Cookie）由数据源在实际请求时按 URL 追加，优先级更高。
 */
internal object MediaHttp {

    /** 拉流 / 拉取封面用的 OkHttp 客户端（超时适中，避免卡住播放启动）。 */
    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", BiliConstants.USER_AGENT)
                .header("Referer", BiliConstants.REFERER)
                .build()
            chain.proceed(request)
        }
        .build()

    /** 兜底请求头。 */
    fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BiliConstants.USER_AGENT,
        "Referer" to BiliConstants.REFERER,
    )

    /** 合并兜底头与解析结果中的请求头（[headers] 优先）。 */
    fun mergeHeaders(headers: Map<String, String>): Map<String, String> =
        if (headers.isEmpty()) baseHeaders() else baseHeaders() + headers
}
