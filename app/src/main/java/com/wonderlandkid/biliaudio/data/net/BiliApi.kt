package com.wonderlandkid.biliaudio.data.net

import android.util.Log
import com.wonderlandkid.biliaudio.data.model.BiliApiException
import com.wonderlandkid.biliaudio.data.model.BiliPage
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.util.Diag
import com.wonderlandkid.biliaudio.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * B 站 API 客户端（真实接口，已用 curl 验证）。
 *
 * 职责：
 * - 统一限速（≥300ms/次，避免风控）、失败重试 1 次、Set-Cookie 自动落盘；
 * - 视频信息 / 搜索 / DASH 播放流（wbi 签名，失败自动降级非 wbi 端点）；
 * - 扫码登录 generate/poll；
 * - wbi 密钥缓存（30 分钟）；
 * - 提供媒体播放请求头 [mediaHeaders] 和 Cookie 快照 [cookieSnapshot]。
 *
 * 构造方式（AppContainer 装配）：
 * ```
 * val cookieStore = CookieStore(context)
 * val api = BiliApi(BiliHttp.createClient(cookieStore), cookieStore)   // 推荐
 * // 或 BiliApi(cookieStore) 内部自动创建 OkHttpClient
 * ```
 */
class BiliApi(
    private val client: OkHttpClient,
    private val cookieStore: CookieStore,
    private val json: Json = BiliJson.instance,
) {

    /** 简化构造：内部用 [BiliHttp.createClient] 创建带 UA/Referer/Cookie 的客户端。 */
    constructor(cookieStore: CookieStore) : this(BiliHttp.createClient(cookieStore), cookieStore)

    private data class WbiKeys(val imgKey: String, val subKey: String, val fetchedAt: Long)

    private val rateMutex = Mutex()
    private var lastRequestAtMs = 0L

    /** 搜索端点单独节流（比通用接口更保守，避免连续搜索触发风控）。 */
    private val searchRateMutex = Mutex()
    private var lastSearchRequestAtMs = 0L

    private val wbiMutex = Mutex()
    @Volatile
    private var wbiKeys: WbiKeys? = null

    private val baseCookieMutex = Mutex()

    /** 上次尝试获取 buvid 的时间，失败后 5 分钟内不再重试（避免批量导入时反复请求）。 */
    @Volatile
    private var baseCookieLastAttemptMs = 0L

    // -----------------------------------------------------------------------
    // 对外能力
    // -----------------------------------------------------------------------

    /** 播放媒体流必须的请求头（UA / Referer / Cookie）。 */
    fun mediaHeaders(): Map<String, String> = BiliHttp.mediaHeaders(cookieStore)

    /** 当前 Cookie 快照，用于媒体流缓存判断登录态是否变化。 */
    fun cookieSnapshot(): String = cookieStore.allCookieHeader()

    /** 是否已登录（本地有登录 Cookie）。 */
    fun hasLoginCookie(): Boolean = cookieStore.hasSession()

    /**
     * 未登录时也建议携带 buvid 等基础 Cookie，减少风控；失败不阻断主流程。
     * 首次调用会请求 /x/frontend/finger/spi（结果通过 Set-Cookie 自动落盘）。
     */
    suspend fun ensureBaseCookies() {
        if (cookieStore.baseCookieHeader().isNotBlank()) return
        if (System.currentTimeMillis() - baseCookieLastAttemptMs < BASE_COOKIE_RETRY_INTERVAL_MS) return
        baseCookieMutex.withLock {
            if (cookieStore.baseCookieHeader().isNotBlank()) return
            baseCookieLastAttemptMs = System.currentTimeMillis()
            try {
                val body = execute(buildGet("${BiliConstants.API_BASE}/x/frontend/finger/spi"))
                val response = decodeBody<FingerResponse>(body)
                val data = response.data
                if (response.code == 0 && data != null) {
                    val cookies = LinkedHashMap<String, String>()
                    if (data.b3.isNotBlank()) cookies["buvid3"] = data.b3
                    if (data.b4.isNotBlank()) cookies["buvid4"] = data.b4
                    cookieStore.putCookies(cookies)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // buvid 获取失败不影响未登录场景的 view / playurl
            }
        }
    }

    /** 登录态 + wbi 密钥：`/x/web-interface/nav`（未登录 code=-101，但 data.wbi_img 仍可用）。 */
    suspend fun nav(): NavData {
        val body = execute(buildGet("${BiliConstants.API_BASE}/x/web-interface/nav"))
        val response = decodeBody<NavResponse>(body)
        val data = response.data
            ?: throw BiliApiException(errorMessage(response.code, response.message), response.code)
        cacheWbiKeys(data.wbiImg)
        return data
    }

    /**
     * 视频信息：`/x/web-interface/view`（bvid 与 aid 二选一）。
     * 封面转 https、时长转毫秒、分 P 信息映射为 [BiliPage]。
     */
    suspend fun videoInfo(bvid: String? = null, aid: Long? = null): BiliVideoInfo {
        require(!bvid.isNullOrBlank() || aid != null) { "bvid 与 aid 至少提供一个" }
        ensureBaseCookies()
        val params = LinkedHashMap<String, String>()
        if (!bvid.isNullOrBlank()) params["bvid"] = bvid.trim()
        if (aid != null) params["aid"] = aid.toString()
        val body = execute(buildGet(apiUrl("/x/web-interface/view", params)))
        val response = decodeBody<ViewResponse>(body)
        val data = response.data
        if (response.code != 0 || data == null) {
            throw BiliApiException(errorMessage(response.code, response.message), response.code)
        }
        val pages = data.pages.ifEmpty {
            // 个别老接口/异常响应没有 pages，用顶层 cid 兜底
            if (data.cid > 0) {
                listOf(ViewPage(cid = data.cid, page = 1, part = data.title, duration = data.duration))
            } else {
                emptyList()
            }
        }
        if (pages.isEmpty()) throw BiliApiException("获取视频分 P 信息失败，视频可能已失效", response.code)
        return BiliVideoInfo(
            bvid = data.bvid.ifBlank { bvid.orEmpty() },
            aid = data.aid,
            title = BiliTextUtils.cleanHtml(data.title),
            coverUrl = BiliTextUtils.normalizeImageUrl(data.pic),
            durationMs = data.duration * 1000L,
            ownerName = data.owner?.name.orEmpty(),
            pages = pages.map { page ->
                BiliPage(
                    page = page.page.coerceAtLeast(1),
                    cid = page.cid,
                    title = BiliTextUtils.cleanHtml(page.part).ifBlank { "P${page.page}" },
                    durationMs = page.duration * 1000L,
                )
            },
        )
    }

    /**
     * 搜索视频：优先 wbi `/x/web-interface/wbi/search/type`，失败降级 `/x/web-interface/search/all/v2`。
     *
     * 健壮性处理（真机实测：连续快速搜索会被 B 站风控）：
     * - wbi 请求失败（异常/412）→ 强制刷新一次 wbi 密钥后重试 1 次；
     * - 仍失败 → 降级 all/v2；若 all/v2 也没有 video 分组（风控时只返回 tips/brand_ad 等分组），
     *   抛 [BiliApiException] 让 UI 显示错误+重试态，而不是静默显示「没有找到视频」；
     * - 搜索端点单独节流（间隔 ≥1.2s），避免用户快速连按触发风控。
     */
    suspend fun searchVideos(keyword: String, page: Int = 1): List<BiliVideoSearchItem> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        ensureBaseCookies()
        // 用户连续搜索时保证两次搜索调用间隔 >= SEARCH_MIN_INTERVAL_MS（内部重试不再重复等待）
        throttleSearch()
        val safePage = page.coerceAtLeast(1)
        val params = mapOf(
            "search_type" to "video",
            "keyword" to kw,
            "page" to safePage.toString(),
        )

        // 1) wbi 搜索：第 1 次正常签名，失败后刷新密钥再试 1 次
        var wbiError: Exception? = null
        for (attempt in 0 until WBI_SEARCH_ATTEMPTS) {
            try {
                val query = buildSignedQuery(params, forceRefresh = attempt > 0)
                val body = execute(buildGet("${BiliConstants.API_BASE}/x/web-interface/wbi/search/type?$query"))
                return parseSearchTypeResponse(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wbiError = e
                Log.w(TAG, "wbi 搜索失败（第 ${attempt + 1}/$WBI_SEARCH_ATTEMPTS 次）keyword=$kw: ${e.message}")
                if (attempt < WBI_SEARCH_ATTEMPTS - 1) delay(WBI_SEARCH_RETRY_DELAY_MS)
            }
        }

        // 2) 降级 all/v2
        Log.w(TAG, "wbi 搜索连续失败，降级 /search/all/v2 keyword=$kw，原因：${wbiError?.message}")
        val fallbackItems = try {
            val body = execute(
                buildGet(
                    apiUrl(
                        "/x/web-interface/search/all/v2",
                        mapOf("keyword" to kw, "page" to safePage.toString()),
                    ),
                ),
            )
            parseSearchAllResponse(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "搜索降级接口失败 keyword=$kw: ${e.message}")
            throw BiliApiException(SEARCH_UNAVAILABLE_MESSAGE, (e as? BiliApiException)?.code)
        }
        if (fallbackItems == null) {
            // all/v2 返回了正常响应但没有 video 分组：典型的风控/接口异常表现
            Log.w(TAG, "all/v2 未返回 video 分组（疑似风控），keyword=$kw")
            throw BiliApiException(SEARCH_UNAVAILABLE_MESSAGE)
        }
        Log.d(TAG, "搜索降级成功 keyword=$kw，结果 ${fallbackItems.size} 条")
        return fallbackItems
    }

    /**
     * 解析 DASH 播放流：`/x/player/wbi/playurl?fnval=16`。
     *
     * wbi 签名端点失败（风控 / 密钥异常）时自动降级到普通 playurl（未登录实测可用）。
     * [forceRefresh] 为 true 时会先强制刷新 wbi 密钥（播放地址过期后重新解析用）。
     */
    suspend fun playurl(bvid: String, cid: Long, forceRefresh: Boolean = false): PlayUrlData {
        if (bvid.isBlank() || cid <= 0) throw BiliApiException("视频信息不完整，无法获取播放地址")
        ensureBaseCookies()
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "16",
            "fnver" to "0",
            "fourk" to "1",
        )
        try {
            val query = buildSignedQuery(params, forceRefresh = forceRefresh)
            val body = execute(buildGet("${BiliConstants.API_BASE}/x/player/wbi/playurl?$query"))
            val response = decodeBody<PlayUrlResponse>(body)
            if (response.code == 0 && response.data != null) return response.data
            // 非 0 继续走降级端点
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 签名/网络异常，走降级端点
        }
        val body = execute(buildGet(apiUrl("/x/player/playurl", params)))
        val response = decodeBody<PlayUrlResponse>(body)
        val data = response.data
        if (response.code != 0 || data == null) {
            throw BiliApiException(errorMessage(response.code, response.message), response.code)
        }
        return data
    }

    /** 扫码登录：生成二维码。 */
    suspend fun qrGenerate(): QrGenerateData {
        val body = execute(buildGet("${BiliConstants.PASSPORT_BASE}/x/passport-login/web/qrcode/generate"))
        val response = decodeBody<QrGenerateResponse>(body)
        val data = response.data
        if (response.code != 0 || data == null || data.qrcodeKey.isBlank()) {
            throw BiliApiException("获取登录二维码失败：${errorMessage(response.code, response.message)}", response.code)
        }
        return data
    }

    /**
     * 扫码登录：轮询状态。
     *
     * 注意外层 code 恒为 0，业务码在内层 `data.code`：
     * 0 成功 / 86101 未扫码 / 86090 已扫码待确认 / 86038 已失效。
     * 登录成功的 Cookie 在响应头 Set-Cookie 中，已由 [execute] 自动落盘；
     * data.url 里的跨域登录参数作为兜底再解析一次。
     */
    suspend fun qrPoll(qrcodeKey: String): QrPollData {
        val url = "${BiliConstants.PASSPORT_BASE}/x/passport-login/web/qrcode/poll" +
            "?qrcode_key=${WbiSigner.percentEncode(qrcodeKey)}"
        val body = execute(buildGet(url))
        val response = decodeBody<QrPollResponse>(body)
        val data = response.data
        if (response.code != 0 || data == null) {
            throw BiliApiException(errorMessage(response.code, response.message), response.code)
        }
        if (data.code == 0) {
            // 兜底：跨域登录 URL 里的 Cookie 参数
            val cookies = CookieUtils.cookiesFromLoginUrl(data.url)
            if (cookies.isNotEmpty()) cookieStore.putCookies(cookies)
        }
        return data
    }

    /** 解析 b23.tv 短链（跟随 302 后取最终 URL），失败返回 null。 */
    suspend fun resolveShortLink(shortUrl: String): String? {
        val url = shortUrl.trim()
        if (!url.startsWith("http", ignoreCase = true)) return null
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(buildGet(url)).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    finalUrl.takeIf { it.contains("bilibili.com", ignoreCase = true) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private fun apiUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val base = BiliConstants.API_BASE + path
        return if (params.isEmpty()) base else "$base?${WbiSigner.encodeQuery(params)}"
    }

    private fun buildGet(url: String): Request = Request.Builder().url(url).get().build()

    /** 生成 wbi 签名后的 query（含 wts / w_rid）。 */
    private suspend fun buildSignedQuery(params: Map<String, String>, forceRefresh: Boolean = false): String {
        val keys = obtainWbiKeys(forceRefresh)
            ?: throw BiliApiException("获取 wbi 签名密钥失败，请稍后重试")
        val signed = WbiSigner.sign(params, keys.imgKey, keys.subKey)
        return WbiSigner.encodeQuery(signed)
    }

    private suspend fun obtainWbiKeys(forceRefresh: Boolean): WbiKeys? {
        val cached = wbiKeys
        if (!forceRefresh && cached != null && isWbiKeysFresh(cached)) return cached
        return wbiMutex.withLock {
            val latest = wbiKeys
            if (!forceRefresh && latest != null && isWbiKeysFresh(latest)) {
                return@withLock latest
            }
            // nav 接口未登录也能拿到 wbi_img；失败则沿用旧密钥
            try {
                nav()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 忽略，下面回退到已有密钥
            }
            wbiKeys ?: cached
        }
    }

    private fun isWbiKeysFresh(keys: WbiKeys): Boolean =
        System.currentTimeMillis() - keys.fetchedAt < WBI_KEYS_TTL_MS

    private fun cacheWbiKeys(img: WbiImg?) {
        if (img == null) return
        val imgKey = WbiSigner.keyFromUrl(img.imgUrl)
        val subKey = WbiSigner.keyFromUrl(img.subUrl)
        // 真实 key 为 32 位 hex；长度不足说明字段异常，不缓存（之后走非 wbi 端点）
        if (imgKey.length < 32 || subKey.length < 32) return
        wbiKeys = WbiKeys(imgKey = imgKey, subKey = subKey, fetchedAt = System.currentTimeMillis())
    }

    private suspend fun throttle() {
        rateMutex.withLock {
            val waitMs = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMs)
            if (waitMs > 0) delay(waitMs)
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    /** 搜索接口节流：两次搜索请求至少间隔 [SEARCH_MIN_INTERVAL_MS]。 */
    private suspend fun throttleSearch() {
        searchRateMutex.withLock {
            val waitMs = SEARCH_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastSearchRequestAtMs)
            if (waitMs > 0) delay(waitMs)
            lastSearchRequestAtMs = System.currentTimeMillis()
        }
    }

    /**
     * 执行请求：限速 → 执行 → Set-Cookie 落盘 → 返回 body。
     * IOException / HTTP 412 / 429 会重试 1 次；其他 HTTP 错误直接抛 [BiliApiException]。
     */
    private suspend fun execute(request: Request): String {
        var lastError: Exception? = null
        val path = request.url.encodedPath
        val startMs = System.currentTimeMillis()
        for (attempt in 0 until MAX_ATTEMPTS) {
            throttle()
            try {
                val body = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        cookieStore.parseAndStore(response.headers("Set-Cookie"))
                        val responseBody = response.body.string()
                        when {
                            response.code == 412 || response.code == 429 ->
                                throw BiliApiException(
                                    "请求被 B 站风控拦截（HTTP ${response.code}），请稍后重试",
                                    response.code,
                                )
                            !response.isSuccessful ->
                                throw BiliApiException("网络请求失败（HTTP ${response.code}）", response.code)
                            else -> responseBody
                        }
                    }
                }
                Diag.log(TAG, "GET $path attempt=$attempt HTTP=200 in ${System.currentTimeMillis() - startMs}ms")
                return body
            } catch (e: CancellationException) {
                throw e
            } catch (e: BiliApiException) {
                lastError = e
                Diag.log(TAG, "GET $path attempt=$attempt FAIL(${e.code ?: "-"}) in ${System.currentTimeMillis() - startMs}ms: ${e.message}")
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            } catch (e: IOException) {
                lastError = e
                Diag.log(TAG, "GET $path attempt=$attempt IO_ERROR in ${System.currentTimeMillis() - startMs}ms: ${e.message}")
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        val detail = lastError?.message?.takeIf { it.isNotBlank() }
        throw BiliApiException("网络请求失败，请检查网络后重试" + if (detail != null) "：$detail" else "")
    }

    private inline fun <reified T> decodeBody(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BiliApiException("接口响应解析失败：${e.message ?: "未知错误"}")
    }

    // -----------------------------------------------------------------------
    // 搜索结果解析（容错：不依赖固定 DTO）
    // -----------------------------------------------------------------------

    private fun parseSearchTypeResponse(body: String): List<BiliVideoSearchItem> {
        val root = parseJsonObject(body)
        val code = root.intOrNull("code") ?: -1
        if (code != 0) throw BiliApiException(errorMessage(code, root.stringOrNull("message")), code)
        val result = root.objectOrNull("data")?.arrayOrNull("result") ?: return emptyList()
        return result.mapNotNull { element -> element.jsonObjectOrNull()?.toSearchItem() }
    }

    /**
     * 解析 all/v2 搜索响应。
     *
     * @return 结果列表；**null** 表示响应正常但缺少 video 分组（风控时只剩 tips/brand_ad 等分组），
     * 调用方应作为接口异常处理而不是「没有结果」。
     */
    private fun parseSearchAllResponse(body: String): List<BiliVideoSearchItem>? {
        val root = parseJsonObject(body)
        val code = root.intOrNull("code") ?: -1
        if (code != 0) throw BiliApiException(errorMessage(code, root.stringOrNull("message")), code)
        val groups = root.objectOrNull("data")?.arrayOrNull("result") ?: return null
        val groupTypes = groups.mapNotNull { it.jsonObjectOrNull()?.stringOrNull("result_type") }
        // data.result 是分组数组，取 result_type == video 的那组
        val videoGroup = groups.asSequence()
            .mapNotNull { it.jsonObjectOrNull() }
            .firstOrNull { it.stringOrNull("result_type") == "video" }
        if (videoGroup == null) {
            Log.w(TAG, "all/v2 响应缺少 video 分组，groups=$groupTypes")
            return null
        }
        val items = videoGroup.arrayOrNull("data") ?: return emptyList()
        return items.mapNotNull { element -> element.jsonObjectOrNull()?.toSearchItem() }
    }

    private fun parseJsonObject(body: String): JsonObject =
        try {
            json.parseToJsonElement(body).jsonObjectOrNull()
                ?: throw BiliApiException("接口响应格式异常")
        } catch (e: CancellationException) {
            throw e
        } catch (e: BiliApiException) {
            throw e
        } catch (e: Exception) {
            throw BiliApiException("接口响应解析失败：${e.message ?: "未知错误"}")
        }

    private fun JsonObject.toSearchItem(): BiliVideoSearchItem? {
        val bvid = stringOrNull("bvid")?.trim().orEmpty()
        if (bvid.isEmpty()) return null
        val rawDuration = get("duration")
        val durationText = when {
            rawDuration == null -> ""
            rawDuration is kotlinx.serialization.json.JsonPrimitive && rawDuration.isString ->
                rawDuration.content
            else -> {
                // 少数接口给的是秒数
                val seconds = stringOrNull("duration")?.trim()?.toLongOrNull()
                if (seconds != null) TimeUtils.formatDuration(seconds * 1000L) else ""
            }
        }
        return BiliVideoSearchItem(
            bvid = bvid,
            title = BiliTextUtils.cleanHtml(stringOrNull("title")),
            coverUrl = BiliTextUtils.normalizeImageUrl(stringOrNull("pic") ?: stringOrNull("cover")),
            durationText = durationText,
            author = stringOrNull("author") ?: stringOrNull("uname") ?: "",
            playCount = intOrNull("play") ?: 0,
            pageCount = intOrNull("page_count")?.coerceAtLeast(1) ?: 1,
        )
    }

    companion object {
        private const val TAG = "Api"

        /** 两次 B 站 API 请求的最小间隔（防 -352/-412 风控）。 */
        private const val MIN_REQUEST_INTERVAL_MS = 300L
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_ATTEMPTS = 2
        private const val WBI_KEYS_TTL_MS = 30 * 60 * 1000L
        private const val BASE_COOKIE_RETRY_INTERVAL_MS = 5 * 60 * 1000L

        /** 搜索端点独立节流间隔（实测连续快速搜索易触发风控）。 */
        private const val SEARCH_MIN_INTERVAL_MS = 1_200L

        /** wbi 搜索失败后刷新密钥重试的次数（含首次）。 */
        private const val WBI_SEARCH_ATTEMPTS = 2
        private const val WBI_SEARCH_RETRY_DELAY_MS = 600L

        /** UI 可展示的搜索不可用提示。 */
        const val SEARCH_UNAVAILABLE_MESSAGE = "搜索被 B 站风控或接口异常，请稍后重试"

        /** B 站错误码 → 可直接展示的中文文案。 */
        fun errorMessage(code: Int, serverMessage: String? = null): String {
            val fallback = serverMessage?.takeIf { it.isNotBlank() }
            return when (code) {
                0 -> fallback ?: "请求成功"
                -101 -> "未登录或登录已过期"
                -400 -> "请求参数错误（BV 号格式可能不正确）"
                -403 -> "访问权限不足"
                -404 -> "视频不存在或已失效"
                -412, -352 -> "请求被 B 站风控拦截，请稍后重试"
                -799 -> "请求过于频繁，请稍后重试"
                62002 -> "视频不存在或已失效"
                62004 -> "视频正在审核中，暂时无法播放"
                87007, 87008 -> "该内容仅大会员可用"
                else -> fallback ?: "请求失败（code=$code）"
            }
        }
    }
}
