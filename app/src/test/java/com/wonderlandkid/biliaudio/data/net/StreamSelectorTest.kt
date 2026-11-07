package com.wonderlandkid.biliaudio.data.net

import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DASH 音频/视频流选择单测（数据取自真实 playurl 响应）。 */
class StreamSelectorTest {

    private fun audio(id: Int, bandwidth: Long = 1000L, url: String? = "https://x/$id", backup: String? = null) =
        DashMedia(
            id = id,
            baseUrlCamel = url,
            backupUrlCamel = backup?.let { listOf(it) },
            bandwidth = bandwidth,
            codecs = "mp4a.40.2",
        )

    private fun video(
        id: Int,
        height: Int,
        width: Int,
        bandwidth: Long,
        url: String? = "https://v/$id/$height",
        codecs: String = "hev1.1.6.L120.90",
    ) = DashMedia(
        id = id,
        baseUrlCamel = url,
        bandwidth = bandwidth,
        codecs = codecs,
        width = width,
        height = height,
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

    @Test
    fun `视频优先最高分辨率（480P 优先于 360P）`() {
        // 真实响应：480P HEVC 218550 / 480P AVC 786766 / 360P HEVC 153220 / 360P AVC 350253
        val videos = listOf(
            video(32, 480, 852, 218_550, url = "https://v/480-hevc", codecs = "hev1"),
            video(32, 480, 852, 786_766, url = "https://v/480-avc", codecs = "avc1"),
            video(16, 360, 640, 153_220, url = "https://v/360-hevc", codecs = "hev1"),
            video(16, 360, 640, 350_253, url = "https://v/360-avc", codecs = "avc1"),
        )
        val choice = StreamSelector.selectVideo(videos)
        assertEquals(480, choice?.height)
        assertEquals(32, choice?.qualityId)
        assertEquals("https://v/480-avc", choice?.url)
    }

    @Test
    fun `同分辨率优先 AVC，其次码率高`() {
        val hevcHighBitrate = video(64, 720, 1280, 3_000_000, url = "https://v/720-hevc", codecs = "hev1")
        val avcLowBitrate = video(64, 720, 1280, 1_500_000, url = "https://v/720-avc", codecs = "avc1")
        assertEquals(
            "https://v/720-avc",
            StreamSelector.selectVideo(listOf(hevcHighBitrate, avcLowBitrate))?.url,
        )
    }

    @Test
    fun `视频优先最高分辨率且不超过 1080P`() {
        val videos = listOf(
            video(80, 1080, 1920, 3_000_000, url = "https://v/1080"),
            video(64, 720, 1280, 2_500_000, url = "https://v/720"),
            video(32, 480, 852, 800_000, url = "https://v/480"),
        )
        assertEquals("https://v/1080", StreamSelector.selectVideo(videos)?.url)
    }

    @Test
    fun `只有 4K 及以上时取最低分辨率兜底`() {
        val videos = listOf(
            video(120, 2160, 3840, 9_000_000, url = "https://v/2160"),
            video(112, 1440, 2560, 6_000_000, url = "https://v/1440"),
        )
        assertEquals("https://v/1440", StreamSelector.selectVideo(videos)?.url)
    }

    @Test
    fun `没有可用视频返回 null`() {
        assertNull(StreamSelector.selectVideo(emptyList()))
        assertNull(StreamSelector.selectVideo(listOf(video(16, 360, 640, 100, url = null))))
    }
}
