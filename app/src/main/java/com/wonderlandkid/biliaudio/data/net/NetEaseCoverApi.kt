package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.CoverCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐封面搜索（网易云官方 `music.163.com/api` 公开接口，无需登录/签名）。
 *
 * 用途：导入专辑/编辑专辑时，按「专辑名 + 艺术家」搜索在线专辑封面。
 * 接口（实测 v0.5.0 可用）：
 * - 搜索专辑：`GET https://music.163.com/api/search/get?s=<kw>&type=10&limit=N`
 *   → `result.albums[]`（含 id/name/picUrl/artists）
 * - 下载图片：直接 GET `picUrl`（需带 `Referer: https://music.163.com`）
 *
 * 注意：这是非官方/未承诺稳定的接口，失败时静默降级为空列表；日志里不要打印完整 URL 签名参数。
 */
class NetEaseCoverApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun searchAlbums(query: String, limit: Int = 20): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            val url = buildString {
                append("https://music.163.com/api/search/get?s=")
                append(URLEncoder.encode(trimmed, "UTF-8"))
                append("&type=10&limit=").append(limit.coerceIn(1, 50))
            }
            val request = Request.Builder().url(url).headers(HEADERS).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                parseAlbums(response.body.string(), limit)
            }
        }

    /** 下载封面图片字节；失败返回 null。 */
    suspend fun fetchImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val request = Request.Builder().url(normalizeImageUrl(url)).headers(HEADERS).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.isEmpty() && !contentType.startsWith("image/")) return@use null
                val length = response.body.contentLength()
                if (length > MAX_IMAGE_BYTES) return@use null
                val bytes = response.body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) null else bytes
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CoverApi"
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        private val HEADERS: Headers = Headers.Builder()
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .add("Referer", "https://music.163.com")
            .add("Accept", "application/json, image/*")
            .build()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        /** 网易云图片 URL 统一升级 https（部分接口返回 http）。 */
        fun normalizeImageUrl(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
                else -> trimmed
            }
        }

        /** 列表缩略图（网易云支持 `?param=300y300` 缩放，节省流量）。 */
        fun thumbnailUrl(raw: String): String {
            val url = normalizeImageUrl(raw)
            if (url.isBlank()) return url
            return if (url.contains("?")) url else "$url?param=300y300"
        }

        /**
         * 解析专辑搜索响应（纯函数，便于单元测试）。
         *
         * JSON 形如：`{"result":{"albums":[{"id":1,"name":"专辑","picUrl":"http://...","artists":[{"name":"歌手"}]}]}}`
         */
        fun parseAlbums(body: String, limit: Int = 20): List<CoverCandidate> {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return emptyList()
            val result = root["result"] as? JsonObject ?: return emptyList()
            val albums = result["albums"] as? JsonArray ?: return emptyList()
            return albums.mapNotNull { element ->
                val album = element as? JsonObject ?: return@mapNotNull null
                val id = album.stringOrEmpty("id")
                val name = album.stringOrEmpty("name").trim()
                val picUrl = album.stringOrEmpty("picUrl")
                if (id.isEmpty() || name.isEmpty() || picUrl.isEmpty()) return@mapNotNull null
                val artists = (album["artists"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { it.stringOrEmpty("name").trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(" / ")
                    .orEmpty()
                CoverCandidate(
                    source = "netease",
                    id = id,
                    title = name,
                    artist = artists,
                    thumbnailUrl = thumbnailUrl(picUrl),
                    fullUrl = normalizeImageUrl(picUrl),
                )
            }.take(limit)
        }

        private fun JsonObject.stringOrEmpty(key: String): String {
            val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return ""
            return primitive.content
        }
    }
}
