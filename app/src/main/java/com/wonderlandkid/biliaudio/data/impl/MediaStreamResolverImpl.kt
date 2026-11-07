package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.BiliApiException
import com.wonderlandkid.biliaudio.data.model.ResolvedStreams
import com.wonderlandkid.biliaudio.data.net.BiliApi
import com.wonderlandkid.biliaudio.data.net.StreamSelector
import com.wonderlandkid.biliaudio.data.repository.MediaStreamResolver
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import com.wonderlandkid.biliaudio.util.Diag
import java.util.concurrent.ConcurrentHashMap

/**
 * B 站播放流解析：wbi playurl + 音质/视频选择 + 30 分钟缓存。
 *
 * 装配：`MediaStreamResolverImpl(biliApi, settingsRepository)`。
 *
 * 要点：
 * - 音质按设置选择（AUTO 取最高：30280→30232→30216），视频选 ≤720P 中码率最低一路；
 * - [ResolvedStreams.headers] 带 UA / Referer / Cookie，播放器必须透传给 DataSource，否则 403；
 * - 缓存 30 分钟；Cookie 变化（登录/退出）时自动清空；[forceRefresh] 强制重新解析；
 * - [invalidateAll] 供 AuthRepository 在登录态变化时清缓存（接口外的扩展方法）。
 */
class MediaStreamResolverImpl(
    private val biliApi: BiliApi,
    private val settingsRepository: SettingsRepository,
) : MediaStreamResolver {

    private data class CacheEntry(
        val streams: ResolvedStreams,
        val fetchedAtMs: Long,
        val cid: Long,
    )

    private val cache = ConcurrentHashMap<Long, CacheEntry>()

    @Volatile
    private var lastCookieSnapshot: String = biliApi.cookieSnapshot()

    override suspend fun resolve(album: Album, forceRefresh: Boolean): ResolvedStreams {
        dropCacheIfSessionChanged()
        if (!forceRefresh && album.id > 0L) {
            val cached = cache[album.id]
            if (cached != null && cached.cid == album.cid && isFresh(cached)) {
                Diag.log(TAG, "resolve cache HIT album=${album.id} bvid=${album.bvid} cid=${album.cid}")
                return cached.streams
            }
        }

        val startMs = System.currentTimeMillis()
        Diag.log(TAG, "resolve START album=${album.id} bvid=${album.bvid} cid=${album.cid} force=$forceRefresh")
        val preference = settingsRepository.settings.value.audioQuality
        val playData = biliApi.playurl(album.bvid, album.cid, forceRefresh = forceRefresh)
        val dash = playData.dash
            ?: throw BiliApiException("该视频不支持 DASH 音频流，暂时无法播放")
        val audio = StreamSelector.selectAudio(dash.audio, preference)
            ?: throw BiliApiException("未获取到可用的音频流（视频可能已失效或需要大会员）")
        val video = StreamSelector.selectVideo(dash.video)

        val now = System.currentTimeMillis()
        val streams = ResolvedStreams(
            audioUrl = audio.url,
            audioQualityId = audio.qualityId,
            audioQualityLabel = audio.label,
            videoUrl = video?.url,
            videoHeight = video?.height,
            expiresAtEpochMs = now + CACHE_TTL_MS,
            headers = biliApi.mediaHeaders(),
        )
        if (album.id > 0L) {
            cache[album.id] = CacheEntry(streams = streams, fetchedAtMs = now, cid = album.cid)
        }
        Diag.log(
            TAG,
            "resolve DONE in ${System.currentTimeMillis() - startMs}ms " +
                "audio=${audio.qualityId}(QLabel ${audio.label}) " +
                "video=${video?.height ?: "-"}p id=${video?.qualityId ?: "-"} " +
                "bw=${video?.bandwidth ?: "-"} codecs=${video?.codecs ?: "-"}",
        )
        return streams
    }

    override fun invalidate(albumId: Long) {
        cache.remove(albumId)
        Diag.log(TAG, "resolve invalidate album=$albumId")
    }

    /** 清空全部流缓存（登录/退出时由 AuthRepository 回调）。 */
    fun invalidateAll() {
        cache.clear()
        Diag.log(TAG, "resolve invalidateAll")
    }

    private fun dropCacheIfSessionChanged() {
        val snapshot = biliApi.cookieSnapshot()
        if (snapshot != lastCookieSnapshot) {
            cache.clear()
            lastCookieSnapshot = snapshot
        }
    }

    private fun isFresh(entry: CacheEntry): Boolean {
        val now = System.currentTimeMillis()
        return now - entry.fetchedAtMs < CACHE_TTL_MS && entry.streams.expiresAtEpochMs > now
    }

    companion object {
        private const val TAG = "Resolver"

        /** 播放地址缓存时长（API_NOTES：URL 约 2 小时有效，缓存建议 ≤30 分钟）。 */
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }
}
