package com.wonderlandkid.biliaudio.data.lyrics

import com.wonderlandkid.biliaudio.data.model.LyricItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    // ---------------- parse: 格式识别 ----------------

    @Test
    fun parse_detectsLrc() {
        val parsed = LyricsParser.parse("[00:01.00]第一句\n[00:02.50]第二句")

        assertNull(parsed.parseError)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        val first = parsed.lines[0] as LyricItem.NormalLyric
        assertEquals("第一句", first.content)
        assertEquals(1000L, first.time)
    }

    @Test
    fun parse_detectsYrc() {
        val yrc = "[0,268](0,23,0)作(23,23,0)词"

        val parsed = LyricsParser.parse(yrc)

        assertNull(parsed.parseError)
        assertTrue(parsed.isSynced)
        assertEquals(1, parsed.lines.size)
        assertTrue(parsed.lines.single() is LyricItem.WordsLyric)
    }

    @Test
    fun parse_detectsAmllTtml() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                <p begin="00:04.000" end="00:05.000">Plain line</p>
            </div></body></tt>
        """.trimIndent()

        val parsed = LyricsParser.parse(ttml)

        assertNull(parsed.parseError)
        assertTrue(parsed.isSynced)
        assertEquals(1, parsed.lines.size)
        val line = parsed.lines.single() as LyricItem.NormalLyric
        assertEquals("Plain line", line.content)
        assertEquals(4000L, line.time)
    }

    @Test
    fun parse_plainTextFallsBackToStaticLines() {
        val raw = "第一行\n\n  第二行  \n第三行"

        val parsed = LyricsParser.parse(raw)

        assertNull(parsed.parseError)
        assertFalse(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(listOf("第一行", "第二行", "第三行"), parsed.lines.map {
            (it as LyricItem.NormalLyric).content
        })
        assertTrue(parsed.lines.all { it.time == 0L })
        assertEquals("plain_0", parsed.lines.first().key)
    }

    @Test
    fun parse_blankReturnsChineseError() {
        val parsed = LyricsParser.parse("  \n\t ")

        assertTrue(parsed.isEmpty)
        assertFalse(parsed.isSynced)
        assertNotNull(parsed.parseError)
        assertTrue(parsed.parseError!!.isNotBlank())
    }

    @Test
    fun parse_brokenXmlReturnsErrorWithoutThrowing() {
        val parsed = LyricsParser.parse("<?xml version=\"1.0\"?><tt><body><div><p>")

        assertTrue(parsed.isEmpty)
        assertFalse(parsed.isSynced)
        assertNotNull(parsed.parseError)
    }

    @Test
    fun parse_yrcWithoutWordTimingReturnsError() {
        // 行头合法、有逐字标记但没有字文本 → 识别为 YRC 但解析不出歌词行
        val parsed = LyricsParser.parse("[0,100](0,50,0)")

        assertTrue(parsed.isEmpty)
        assertNotNull(parsed.parseError)
    }

    @Test
    fun detectFormat_classifiesAllSupportedFormats() {
        assertEquals(LyricsParser.Format.AMLL_TTML, LyricsParser.detectFormat("<tt xmlns=\"x\"></tt>"))
        assertEquals(LyricsParser.Format.AMLL_TTML, LyricsParser.detectFormat("<?xml version=\"1.0\"?><tt/>"))
        assertEquals(LyricsParser.Format.YRC, LyricsParser.detectFormat("[0,268](0,23,0)作"))
        assertEquals(LyricsParser.Format.LRC, LyricsParser.detectFormat("[00:01.00]hi"))
        assertEquals(LyricsParser.Format.PLAIN_TEXT, LyricsParser.detectFormat("没有时间轴的一行歌词"))
    }

    @Test
    fun parse_handlesBomPrefixedLrc() {
        val parsed = LyricsParser.parse("\uFEFF[00:01.00]带 BOM 的歌词")

        assertTrue(parsed.isSynced)
        assertEquals(1, parsed.lines.size)
        assertEquals("带 BOM 的歌词", (parsed.lines.single() as LyricItem.NormalLyric).content)
    }

    // ---------------- isValid: 本地导入校验 ----------------

    @Test
    fun isValid_acceptsMatchingExtensionAndContent() {
        assertTrue(LyricsParser.isValid("[00:01.00]hi", "song.lrc"))
        assertTrue(LyricsParser.isValid("[0,100](0,50,0)好", "song.yrc"))
        assertTrue(
            LyricsParser.isValid(
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1\">hi</p></div></body></tt>",
                "song.ttml",
            ),
        )
        assertTrue(
            LyricsParser.isValid(
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1\">hi</p></div></body></tt>",
                "song.xml",
            ),
        )
        assertTrue(LyricsParser.isValid("任意一句\n另一句", "song.txt"))
        assertTrue(LyricsParser.isValid("任意一句", "没有扩展名"))
    }

    @Test
    fun isValid_rejectsWrongContentOrUnknownExtension() {
        // LRC 扩展名但没有时间轴
        assertFalse(LyricsParser.isValid("没有时间轴", "song.lrc"))
        // YRC 扩展名但内容是 LRC
        assertFalse(LyricsParser.isValid("[00:01.00]hi", "song.yrc"))
        // TTML 扩展名但内容不是 TTML
        assertFalse(LyricsParser.isValid("plain text", "song.ttml"))
        // 未知扩展名
        assertFalse(LyricsParser.isValid("[00:01.00]hi", "song.ass"))
        // 空文本
        assertFalse(LyricsParser.isValid("", "song.txt"))
        assertFalse(LyricsParser.isValid("   \n ", "song.txt"))
    }

    @Test
    fun isValid_isCaseInsensitiveForExtension() {
        assertTrue(LyricsParser.isValid("[00:01.00]hi", "SONG.LRC"))
        assertTrue(LyricsParser.isValid("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1\">hi</p></div></body></tt>", "song.TTML2"))
    }
}
