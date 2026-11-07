package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference

/**
 * DASH 音/视频流选择（纯函数，便于单测）。
 *
 * 音质 id（docs/API_NOTES.md §3）：30280=192K、30232=132K、30216=64K；
 * 30250/30251 是杜比/Hi-Res，需要大会员，主动排除。
 * 视频：按需求选 ≤720P 中码率最低的一路（歌词页视频用，省流量）。
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

    /** 选择 ≤720P 中码率最低的视频流；返回 null 表示没有可用视频。 */
    fun selectVideo(videos: List<DashMedia>): VideoChoice? {
        val candidates = videos.mapNotNull { media ->
            val url = media.url ?: media.backupUrls.firstOrNull() ?: return@mapNotNull null
            VideoPick(url = url, height = media.height, qualityId = media.id, bandwidth = media.bandwidth)
        }
        if (candidates.isEmpty()) return null
        val within720 = candidates.filter { it.height in 1..720 }
        val best = if (within720.isNotEmpty()) {
            // 码率最低优先；码率相同取分辨率更低的一路
            within720.minWithOrNull(compareBy<VideoPick> { it.bandwidth }.thenBy { it.height })
        } else {
            // 没有 ≤720P（异常情况）：取分辨率最低的一路
            candidates.minByOrNull { it.height }
        }
        return best?.let { VideoChoice(url = it.url, height = it.height, qualityId = it.qualityId) }
    }

    private data class VideoPick(
        val url: String,
        val height: Int,
        val qualityId: Int,
        val bandwidth: Long,
    )

    private fun audioQualityRank(qualityId: Int): Int = when (qualityId) {
        30280 -> 3
        30232 -> 2
        30216 -> 1
        else -> 0
    }
}
