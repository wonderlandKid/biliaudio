package com.wonderlandkid.biliaudio.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** B 站文本/图片地址清洗单测。 */
class BiliTextUtilsTest {

    @Test
    fun `去除搜索高亮标签`() {
        val raw = "【<em class=\"keyword\">周杰伦</em>】15张<em class=\"keyword\">专辑</em>全"
        assertEquals("【周杰伦】15张专辑全", BiliTextUtils.cleanHtml(raw))
    }

    @Test
    fun `解 HTML 实体并压缩空白`() {
        assertEquals("A & B 'x' <y>", BiliTextUtils.cleanHtml("A &amp; B &#39;x&#39; &lt;y&gt;"))
        assertEquals("A B", BiliTextUtils.cleanHtml("  A\n\t B  "))
        assertEquals("", BiliTextUtils.cleanHtml(null))
        assertEquals("", BiliTextUtils.cleanHtml(""))
        // 未识别的实体保持原样
        assertEquals("&unknown;", BiliTextUtils.cleanHtml("&unknown;"))
        // 十六进制实体
        assertEquals("'", BiliTextUtils.cleanHtml("&#x27;"))
    }

    @Test
    fun `解数字实体`() {
        // 真实 B 站搜索标题：数学粗体字符被转成十进制实体（U+1D407 等）
        val raw = "【&#119815;&#119842;-&#119825;&#119838;&#119852;·无损】KOKIA (吉田亚纪子)"
        assertEquals("【𝐇𝐢-𝐑𝐞𝐬·无损】KOKIA (吉田亚纪子)", BiliTextUtils.cleanHtml(raw))
        // 十六进制实体（大小写 x 均支持）
        assertEquals("𝐇𝐢-𝐑𝐞𝐬", BiliTextUtils.cleanHtml("&#x1D407;&#x1D422;-&#X1D411;&#x1D41E;&#x1D42C;"))
        // 常见命名实体
        assertEquals(
            "A & B < C > D \"q\" 's' 空格",
            BiliTextUtils.cleanHtml("A &amp; B &lt; C &gt; D &quot;q&quot; &#39;s&#39; &nbsp;空格"),
        )
        // 表情（代理对）实体
        assertEquals("😀", BiliTextUtils.decodeEntities("&#55357;&#56832;"))
        // 非法/未知实体保持原样
        assertEquals("&#0; &bogus;", BiliTextUtils.cleanHtml("&#0; &bogus;"))
    }

    @Test
    fun `解二次转义的 HTML 实体`() {
        // B 站部分响应会二次转义：&amp;#119815; / &amp;amp;
        assertEquals("𝐇𝐢 & <", BiliTextUtils.cleanHtml("&amp;#119815;&amp;#119842; &amp;amp; &amp;lt;"))
        assertEquals("A & B", BiliTextUtils.cleanHtml("A &amp;amp; B"))
        // 正常标题不受二次解码影响
        assertEquals("AT&T", BiliTextUtils.cleanHtml("AT&amp;T"))
    }

    @Test
    fun `搜索标题清洗组合场景`() {
        val raw = "【LIVE】未来古代楽団 feat.<em class=\"keyword\">KOKIA</em> - オルフェ &amp; 咲ク耶"
        assertEquals("【LIVE】未来古代楽団 feat.KOKIA - オルフェ & 咲ク耶", BiliTextUtils.cleanHtml(raw))
    }

    @Test
    fun `时长文本归一化`() {
        // B 站搜索返回非补零格式
        assertEquals("04:09", BiliTextUtils.normalizeDurationText("4:9"))
        assertEquals("04:07", BiliTextUtils.normalizeDurationText("4:7"))
        assertEquals("45:04", BiliTextUtils.normalizeDurationText("45:4"))
        assertEquals("06:41", BiliTextUtils.normalizeDurationText("6:41"))
        assertEquals("03:38", BiliTextUtils.normalizeDurationText(" 3:38 "))
        // 已经是标准格式时保持不变
        assertEquals("04:09", BiliTextUtils.normalizeDurationText("04:09"))
        assertEquals("1:02:03", BiliTextUtils.normalizeDurationText("1:02:03"))
        assertEquals("10:55:53", BiliTextUtils.normalizeDurationText("655:53"))
        // 非可解析值原样保留
        assertEquals("--:--", BiliTextUtils.normalizeDurationText("--:--"))
        assertEquals("", BiliTextUtils.normalizeDurationText(null))
        assertEquals("", BiliTextUtils.normalizeDurationText("  "))
    }

    @Test
    fun `图片地址补全 https`() {
        assertEquals(
            "https://i1.hdslb.com/bfs/archive/x.jpg",
            BiliTextUtils.normalizeImageUrl("//i1.hdslb.com/bfs/archive/x.jpg"),
        )
        assertEquals(
            "https://i1.hdslb.com/bfs/archive/x.jpg",
            BiliTextUtils.normalizeImageUrl("http://i1.hdslb.com/bfs/archive/x.jpg"),
        )
        assertEquals(
            "https://i1.hdslb.com/bfs/archive/x.jpg",
            BiliTextUtils.normalizeImageUrl("https://i1.hdslb.com/bfs/archive/x.jpg"),
        )
        assertEquals("", BiliTextUtils.normalizeImageUrl(null))
    }
}
