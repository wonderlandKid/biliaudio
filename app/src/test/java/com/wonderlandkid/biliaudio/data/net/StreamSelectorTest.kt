package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DASH 音频流选择单测（数据取自真实 playurl 响应）。 */
class StreamSelectorTest {

    private fun audio(id: Int, bandwidth: Long = 1000L, url: String? = "https://x/$id", backup: String? = null) =
        DashMedia(
            id = id,
            baseUrlCamel = url,
            backupUrlCamel = backup?.let { listOf(it) },
            bandwidth = bandwidth,
            codecs = "mp4a.40.2",
        )

    @Test
    fun `自动音质选最高可用`() {
        val audios = listOf(audio(30216), audio(30280), audio(30232))
        val choice = StreamSelector.selectAudio(audios, AudioQualityPreference.AUTO)
        assertEquals(30280, choice?.qualityId)
        assertEquals("192K", choice?.label)
        assertEquals("https://x/30280", choice?.url)
    }

    @Test
    fun `指定音质精确匹配`() {
        val audios = listOf(audio(30216), audio(30280), audio(30232))
        assertEquals(30280, StreamSelector.selectAudio(audios, AudioQualityPreference.HIGH)?.qualityId)
        assertEquals(30232, StreamSelector.selectAudio(audios, AudioQualityPreference.MEDIUM)?.qualityId)
        assertEquals(30216, StreamSelector.selectAudio(audios, AudioQualityPreference.LOW)?.qualityId)
    }

    @Test
    fun `指定音质缺失时降级`() {
        assertEquals(
            30232,
            StreamSelector.selectAudio(listOf(audio(30216), audio(30232)), AudioQualityPreference.HIGH)?.qualityId,
        )
        assertEquals(
            30216,
            StreamSelector.selectAudio(listOf(audio(30216)), AudioQualityPreference.MEDIUM)?.qualityId,
        )
        // 只要 192K 时选低音质偏好，回退到最高可用
        assertEquals(
            30280,
            StreamSelector.selectAudio(listOf(audio(30280)), AudioQualityPreference.LOW)?.qualityId,
        )
    }

    @Test
    fun `未知音质 id 兜底`() {
        val audios = listOf(audio(30100), audio(30101))
        assertEquals(30100, StreamSelector.selectAudio(audios, AudioQualityPreference.AUTO)?.qualityId)
    }

    @Test
    fun `没有可用音频返回 null`() {
        assertNull(StreamSelector.selectAudio(emptyList(), AudioQualityPreference.AUTO))
        assertNull(StreamSelector.selectAudio(listOf(audio(30280, url = null)), AudioQualityPreference.AUTO))
    }

    @Test
    fun `主地址缺失时使用备份地址`() {
        val choice = StreamSelector.selectAudio(
            listOf(audio(30280, url = null, backup = "https://backup/30280")),
            AudioQualityPreference.AUTO,
        )
        assertEquals("https://backup/30280", choice?.url)
    }
}
