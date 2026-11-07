package com.wonderlandkid.biliaudio.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BV / av / 链接解析单测。 */
class BiliUrlParserTest {

    @Test
    fun `裸 BV 号`() {
        assertEquals(
            BiliUrlParser.VideoRef.Bvid("BV1GJ411x7h7"),
            BiliUrlParser.parse("BV1GJ411x7h7"),
        )
        assertEquals(
            BiliUrlParser.VideoRef.Bvid("BV1GJ411x7h7"),
            BiliUrlParser.parse("  BV1GJ411x7h7  "),
        )
    }

    @Test
    fun `完整视频链接`() {
        assertEquals(
            BiliUrlParser.VideoRef.Bvid("BV1GJ411x7h7"),
            BiliUrlParser.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=2&spm_id_from=333.999"),
        )
    }

    @Test
    fun `文本中的 BV 号`() {
        assertEquals(
            BiliUrlParser.VideoRef.Bvid("BV1GJ411x7h7"),
            BiliUrlParser.parse("看看这个 BV1GJ411x7h7 吧"),
        )
    }

    @Test
    fun `av 号`() {
        assertEquals(BiliUrlParser.VideoRef.Aid(80433022L), BiliUrlParser.parse("av80433022"))
        assertEquals(
            BiliUrlParser.VideoRef.Aid(80433022L),
            BiliUrlParser.parse("https://www.bilibili.com/video/av80433022"),
        )
        // 链接同时含 BV 与 aid 时以 BV 为准（BV 是规范标识）
        assertEquals(
            BiliUrlParser.VideoRef.Bvid("BV1GJ411x7h7"),
            BiliUrlParser.parse("https://www.bilibili.com/video/BV1GJ411x7h7?aid=80433022"),
        )
        // 只有 aid 参数的链接
        assertEquals(
            BiliUrlParser.VideoRef.Aid(80433022L),
            BiliUrlParser.parse("https://api.bilibili.com/x/web-interface/view?aid=80433022"),
        )
    }

    @Test
    fun `b23tv 短链`() {
        assertEquals(
            BiliUrlParser.VideoRef.ShortUrl("https://b23.tv/abc123"),
            BiliUrlParser.parse("https://b23.tv/abc123"),
        )
        assertEquals(
            BiliUrlParser.VideoRef.ShortUrl("https://b23.tv/abc123"),
            BiliUrlParser.parse("b23.tv/abc123"),
        )
    }

    @Test
    fun `无效输入返回 null`() {
        assertNull(BiliUrlParser.parse(null))
        assertNull(BiliUrlParser.parse(""))
        assertNull(BiliUrlParser.parse("   "))
        assertNull(BiliUrlParser.parse("hello world"))
        assertNull(BiliUrlParser.parse("BV123"))
        assertNull(BiliUrlParser.parse("av"))
    }
}
