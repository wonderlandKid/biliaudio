package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 在线歌词 API 客户端（参考 SPICaMusic_Android `feature-lyrics-data` 的 LyricsApi/ApiClient，
 * 用本项目已有的 OkHttp + kotlinx.serialization 实现，不额外引入 Retrofit/Moshi/Sandwich）。
 *
 * 接口（实测可用，返回多来源歌词）：
 * ```
 * GET http://106.54.25.152:4141/api/lyrics?title=<曲名>&artist=<歌手>
 * → { "code": 200, "message": "...", "data": [
 *       { "id": 1, "name": "...", "artist": "...", "album": "...",
 *         "albumArt": "...", "duration": 269, "lyrics": "..." } ] }
 * ```
 * - `duration` 单位是秒；`artist` 为空时省略该参数（BiliAudio 的艺术家是 UP 主，不可靠，
 *   自动匹配只按曲名搜索，见 [com.wonderlandkid.biliaudio.data.lyrics.LyricsMatcher]）；
 * - 网络失败 / 非 200 / JSON 解析异常统一返回空列表并记录 [Diag] 日志，不向调用方抛异常。
 */
class LyricsApiClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 按曲名（可选歌手）搜索在线歌词候选。
     *
     * @return 候选列表；失败时为 null（网络/解析失败）或空列表（无结果），均按空列表返回
     */
    suspend fun search(title: String, artist: String = ""): List<OnlineLyricCandidate> =
        withContext(Dispatchers.IO) {
            val safeTitle = title.trim()
            if (safeTitle.isEmpty()) return@withContext emptyList()

            val url = buildUrl(safeTitle, artist.trim())
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Diag.log(TAG, "搜索失败 HTTP ${response.code} title=$safeTitle")
                        return@withContext emptyList()
                    }
                    val body = runCatching { response.body.string() }
                        .getOrElse { error ->
                            Diag.log(TAG, "读取响应失败 title=$safeTitle", error)
                            return@withContext emptyList()
                        }
                    parseResponse(body)
                }
            }.getOrElse { error ->
                Diag.log(TAG, "搜索异常 title=$safeTitle", error)
                emptyList()
            }
        }

    companion object {
        /**
         * SPICaMusic 参考项目同款在线歌词服务（HTTP，仅该域名允许明文）。
         *
         * 明文放行配置见 `res/xml/network_security_config.xml`；
         * Manifest 仍保留 `usesCleartextTraffic="false"`，仅该域名例外。
         */
        const val BASE_URL: String = "http://106.54.25.152:4141/api/lyrics"

        private const val TAG = "LyricsApi"
        private const val TIMEOUT_SECONDS = 10L

        /** 歌词接口超时（连接/读取/整体均 10s，避免弱网下长时间卡住切歌流程）。 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private fun buildUrl(title: String, artist: String): String {
            val builder = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("title", title)
            if (artist.isNotEmpty()) builder.addQueryParameter("artist", artist)
            return builder.build().toString()
        }

        /**
         * 解析歌词 API 响应（纯函数，便于排查/单测）。
         *
         * 兼容 `data` 为 null、字段缺失/为 null 的情况；整体 JSON 非法时抛异常由调用方兜底。
         */
        internal fun parseResponse(body: String): List<OnlineLyricCandidate> {
            if (body.isBlank()) return emptyList()
            val response = BiliJson.instance.decodeFromString<LyricsResponseDto>(body)
            val songs = response.data ?: return emptyList()
            return songs.mapNotNull { song ->
                val name = song.name.trim()
                if (name.isEmpty()) return@mapNotNull null
                OnlineLyricCandidate(
                    id = song.id,
                    name = name,
                    artist = song.artist.trim(),
                    album = song.album.trim(),
                    albumArt = song.albumArt.trim(),
                    durationSec = song.duration,
                    rawLyrics = song.lyrics,
                )
            }
        }
    }

    /** 歌词 API 外层响应：`{code, message, data}`。 */
    @Serializable
    private data class LyricsResponseDto(
        val code: Int = 0,
        val message: String = "",
        val data: List<LyricsSongDto>? = null,
    )

    /** 歌词 API 单条结果（`duration` 单位秒）。 */
    @Serializable
    private data class LyricsSongDto(
        val id: Long = 0L,
        val name: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArt: String = "",
        val duration: Int = 0,
        val lyrics: String = "",
    )
}
