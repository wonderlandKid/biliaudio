package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference

/**
 * DASH 音频流选择（纯函数，便于单测）。
 *
 * 音质 id（docs/API_NOTES.md §3）：30280=192K、30232=132K、30216=64K；
 * 30250/30251 是杜比/Hi-Res，需要大会员，主动排除。
 */
object StreamSelector {

    data class AudioChoice(
        val url: String,
        val qualityId: Int,
        val label: String,
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

    private fun audioQualityRank(qualityId: Int): Int = when (qualityId) {
        30280 -> 3
        30232 -> 2
        30216 -> 1
        else -> 0
    }
}
