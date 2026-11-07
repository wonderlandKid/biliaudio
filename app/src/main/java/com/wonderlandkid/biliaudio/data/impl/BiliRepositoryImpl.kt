package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.BiliApiException
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.net.BiliApi
import com.wonderlandkid.biliaudio.data.net.BiliTextUtils
import com.wonderlandkid.biliaudio.data.net.BiliUrlParser
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import kotlinx.coroutines.CancellationException

/**
 * B 站视频信息 / 搜索仓库。
 *
 * 装配：`BiliRepositoryImpl(biliApi)`。
 */
class BiliRepositoryImpl(private val biliApi: BiliApi) : BiliRepository {

    /** 解析 BV 号 / av 号 / 完整链接 / b23.tv 短链并拉取视频信息。 */
    override suspend fun previewVideo(bvidOrUrl: String): BiliVideoInfo {
        var ref = BiliUrlParser.parse(bvidOrUrl)
            ?: throw BiliApiException("BV 号或视频链接格式不正确")
        if (ref is BiliUrlParser.VideoRef.ShortUrl) {
            val resolvedUrl = try {
                biliApi.resolveShortLink(ref.url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: throw BiliApiException("短链接解析失败，请检查网络或改用 BV 号导入")
            ref = BiliUrlParser.parse(resolvedUrl)
                ?: throw BiliApiException("短链接中没有找到 BV 号")
        }
        return when (val target = ref) {
            is BiliUrlParser.VideoRef.Bvid -> biliApi.videoInfo(bvid = target.bvid)
            is BiliUrlParser.VideoRef.Aid -> biliApi.videoInfo(aid = target.aid)
            is BiliUrlParser.VideoRef.ShortUrl -> throw BiliApiException("短链接解析失败，请改用 BV 号导入")
        }
    }

    override suspend fun searchVideos(keyword: String, page: Int): List<BiliVideoSearchItem> =
        biliApi.searchVideos(keyword, page).map { item ->
            // B 站返回的时长是非补零格式（如 "4:9"），统一归一化成 "04:09" 再交给 UI
            item.copy(durationText = BiliTextUtils.normalizeDurationText(item.durationText))
        }
}
