package com.wonderlandkid.biliaudio.data.net

/**
 * Cookie 解析工具（纯函数，便于单测）。
 *
 * B 站登录态靠 Cookie（SESSDATA / bili_jct / DedeUserID 等）传递：
 * - 普通接口通过 `Cookie` 请求头携带；
 * - 扫码登录成功后，Cookie 在轮询接口的 `Set-Cookie` 响应头里返回；
 * - 部分场景（跨域登录 URL）Cookie 以 URL 参数形式返回，需要兜底解析。
 */
object CookieUtils {

    /** 登录态 Cookie 名（退出登录时清除）。 */
    val SESSION_COOKIE_NAMES = listOf(
        "SESSDATA",
        "bili_jct",
        "DedeUserID",
        "DedeUserID__ckMd5",
        "sid",
    )

    /** 未登录也要带的基础 Cookie（设备指纹，退出登录时保留）。 */
    val BASE_COOKIE_NAMES = listOf("buvid3", "buvid4", "b_nut")

    /**
     * 解析一条 `Set-Cookie`。
     *
     * @return `name to value`；value 为 null 表示该 Cookie 被删除（Max-Age=0 或空值）。
     */
    fun parseSetCookie(header: String?): Pair<String, String?>? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val firstPart = raw.substringBefore(';').trim()
        if (firstPart.isEmpty()) return null
        val name = firstPart.substringBefore('=', "").trim()
        if (name.isEmpty()) return null
        val value = firstPart.substringAfter('=', "").trim()
        val attributes = raw.split(';').drop(1).map { it.trim() }
        val removed = value.isEmpty() || attributes.any {
            it.startsWith("Max-Age=0", ignoreCase = true) ||
                it.startsWith("Max-Age=-", ignoreCase = true)
        }
        return name to if (removed) null else value
    }

    /** 从登录成功返回的跨域登录 URL 中提取 Cookie（值为 URL 编码原样保留）。 */
    fun cookiesFromLoginUrl(url: String?): Map<String, String> {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val query = raw.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return emptyMap()
        val known = (SESSION_COOKIE_NAMES + BASE_COOKIE_NAMES).toSet()
        val result = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            val name = part.substring(0, index)
            val value = part.substring(index + 1)
            if (name in known && value.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** 按 [names] 的顺序拼 Cookie 请求头，缺失的跳过。 */
    fun buildCookieHeader(cookies: Map<String, String>, names: Collection<String>): String =
        names.mapNotNull { name -> cookies[name]?.takeIf { it.isNotBlank() }?.let { "$name=$it" } }
            .joinToString("; ")
}
