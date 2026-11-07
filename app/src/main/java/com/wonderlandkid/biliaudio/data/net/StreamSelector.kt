package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference

/**
 * DASH 音/视频流选择（纯函数，便于单测）。
 *
 * 音质 id（docs/API_NOTES.md §3）：30280=192K、30232=132K、30216=64K；
 * 30250/30251 是杜比/Hi-Res，需要大会员，主动排除。
 * 视频：歌词页用，**优先最高分辨率**（≤1080P，未登录一般最高 480P），
 * 同分辨率优先 AVC（兼容性最好）再按码率高低，避免选到低清低码率流导致画面糊。
 */
object StreamSelector {

    data class AudioChoice(
        val url: String,
        val qualityId: Int,
        val label: String,
    )

    data class VideoChoice(
        val url: String,
        val height: Int,
        val qualityId: Int,
    )

    /** 音质 id → 展示文案。 */
    fun qualityLabel(qualityId: Int): String = when (qualityId) {
        30280 -> "192K"
        30232 -> "132K"
        30216 -> "64K"
        30250 -> "杜比全景声"
        30251 -> "Hi-Res 无损"
        else -> "音质 $qualityId"
    }

    /** 按偏好选择音频流；返回 null 表示没有可用音频。 */
    fun selectAudio(
        audios: List<DashMedia>,
        preference: AudioQualityPreference,
    ): AudioChoice? {
        val candidates = audios.mapNotNull { media ->
            val url = media.url ?: media.backupUrls.firstOrNull() ?: return@mapNotNull null
            AudioChoice(url = url, qualityId = media.id, label = qualityLabel(media.id))
        }
        if (candidates.isEmpty()) return null
        // 按偏好从高到低尝试；AUTO 取最高可用
        val preferredOrder = when (preference) {
            AudioQualityPreference.AUTO -> listOf(30280, 30232, 30216)
            AudioQualityPreference.HIGH -> listOf(30280, 30232, 30216)
            AudioQualityPreference.MEDIUM -> listOf(30232, 30216)
            AudioQualityPreference.LOW -> listOf(30216)
        }
        preferredOrder.forEach { qualityId ->
            candidates.firstOrNull { it.qualityId == qualityId }?.let { return it }
        }
        // 全是未知 id（接口变更）：AUTO/HIGH 取已知音质里最高的，其余取任意一路兜底
        return candidates.maxByOrNull { audioQualityRank(it.qualityId) }
    }

    /**
     * 选择视频流（歌词页）：
     * 1. 优先最高分辨率（封顶 1080P，避免 4K 解码/流量开销）；
     * 2. 同分辨率优先 AVC（兼容性最好），其次按码率从高到低；
     * 3. 若接口只返回 >1080P 的流（异常/会员高画质），取其中分辨率最低的一路。
     *
     * 返回 null 表示没有可用视频。
     */
    fun selectVideo(videos: List<DashMedia>): VideoChoice? {
        val candidates = videos.mapNotNull { media ->
            if (media.height <= 0) return@mapNotNull null
            val url = media.url ?: media.backupUrls.firstOrNull() ?: return@mapNotNull null
            VideoPick(
                url = url,
                height = media.height,
                qualityId = media.id,
                bandwidth = media.bandwidth,
                isAvc = media.codecs.startsWith("avc", ignoreCase = true),
            )
        }
        if (candidates.isEmpty()) return null
        val within1080 = candidates.filter { it.height <= 1080 }
        val best = if (within1080.isNotEmpty()) {
            within1080.maxWithOrNull(
                compareBy<VideoPick> { it.height }
                    .thenBy { if (it.isAvc) 1 else 0 }
                    .thenBy { it.bandwidth },
            )
        } else {
            // 只有 >1080P 的流（异常/高画质会员）：取其中分辨率最低的一路，避免解码/流量过大
            candidates.minWithOrNull(
                compareBy<VideoPick> { it.height }
                    .thenByDescending { if (it.isAvc) 1 else 0 }
                    .thenByDescending { it.bandwidth },
            )
        }
        return best?.let { VideoChoice(url = it.url, height = it.height, qualityId = it.qualityId) }
    }

    private data class VideoPick(
        val url: String,
        val height: Int,
        val qualityId: Int,
        val bandwidth: Long,
        val isAvc: Boolean,
    )

    private fun audioQualityRank(qualityId: Int): Int = when (qualityId) {
        30280 -> 3
        30232 -> 2
        30216 -> 1
        else -> 0
    }
}
