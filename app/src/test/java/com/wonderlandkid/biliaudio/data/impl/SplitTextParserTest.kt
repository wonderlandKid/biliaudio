package com.wonderlandkid.biliaudio.data.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分割点文本识别单测：直接使用真实用户粘贴的三种格式作为用例。
 */
class SplitTextParserTest {

    // ------------------------------------------------------------------
    // 格式 1：编号 + 歌名 + 时间（时间在后，标题与时间之间可能没有空格）
    // ------------------------------------------------------------------
    private val formatNumberedTitleTime = """
        1.Breakaway00:00
        2.Since U Been Gone03:57
        3.Behind These Hazel Eyes07:06
        4.Because of You10:25
        5.Gone14:04
        6.Addicted17:32
        7.Where Is Your Heart21:29
        8.Walk Away26:08
        9.You Found Me29:17
        10.I Hate Myself For Losing You32:56
        11.Hear Me36:17
        12.Beautiful Disaster(Live)40:13
        13.Miss Independent44:48
        14.Low48:23
    """.trimIndent()

    // ------------------------------------------------------------------
    // 格式 2：时间 + 歌名（时间在前）
    // ------------------------------------------------------------------
    private val formatTimeTitle = """
        00:00 Tap Out
        03:42 All The Time
        06:44 One Way Trigger
        10:46 Welcome To Japan
        14:37 80's Comedown Machine
        19:36 50/50
        22:19 Slow Animals
        26:40 Partners In Crime
        30:01 Chances
        33:38 Happy Ending
        36:30 Call It Fate, Call It Karma
    """.trimIndent()

    // ------------------------------------------------------------------
    // 格式 3：歌名 + 时间（时间在后，首行有前导空格，部分行无空格）
    // ------------------------------------------------------------------
    private val formatTitleTime = """
         Come Together00:00
        Something04:20
        Maxwell's Silver Hammer07:22
        Oh! Darling 10:50
        Octopus's Garden14:17
        I Want You (She's So Heavy)17:08
        Here Comes The Sun24:55
        Because28:01
        You Never Give Me Your Money30:47
        Sun King34:50
        Mean Mr Mustard37:16
        Polythene Pam38:23
        She Came In Through The Bathroom Window39:35
        Golden Slumbers41:34
        Carry That Weight43:06
        The End44:42
        Her Majesty47:04
    """.trimIndent()

    @Test
    fun `格式1 编号加歌名加时间`() {
        val result = SplitTextParser.parse(formatNumberedTitleTime)

        assertEquals(14, result.segments.size)
        assertTrue(result.skippedLines.isEmpty())
        assertEquals(14, result.timeLastCount)
        assertEquals(0, result.timeFirstCount)

        assertEquals(0L, result.segments.first().startMs)
        assertEquals("Breakaway", result.segments.first().title)
        assertEquals(237_000L, result.segments[1].startMs)
        assertEquals("Since U Been Gone", result.segments[1].title)
        assertEquals(426_000L, result.segments[2].startMs)
        assertEquals("Behind These Hazel Eyes", result.segments[2].title)
        assertEquals(2_413_000L, result.segments[11].startMs)
        assertEquals("Beautiful Disaster(Live)", result.segments[11].title)
        assertEquals(2_903_000L, result.segments.last().startMs)
        assertEquals("Low", result.segments.last().title)
    }

    @Test
    fun `格式2 时间加歌名`() {
        val result = SplitTextParser.parse(formatTimeTitle)

        assertEquals(11, result.segments.size)
        assertTrue(result.skippedLines.isEmpty())
        assertEquals(11, result.timeFirstCount)
        assertEquals("识别为「时间 + 歌名」格式", result.styleHint)
        assertEquals("Tap Out", result.segments.first().title)
        assertEquals("80's Comedown Machine", result.segments[4].title)
        assertEquals(877_000L, result.segments[4].startMs)
        assertEquals("Call It Fate, Call It Karma", result.segments.last().title)
        assertEquals(2_190_000L, result.segments.last().startMs)
    }

    @Test
    fun `格式3 歌名加时间`() {
        val result = SplitTextParser.parse(formatTitleTime)

        assertEquals(17, result.segments.size)
        assertTrue(result.skippedLines.isEmpty())
        assertEquals("Come Together", result.segments.first().title)
        assertEquals(0L, result.segments.first().startMs)
        assertEquals("Oh! Darling", result.segments[3].title)
        assertEquals(650_000L, result.segments[3].startMs)
        assertEquals("I Want You (She's So Heavy)", result.segments[5].title)
        assertEquals(1_028_000L, result.segments[5].startMs)
        assertEquals("Her Majesty", result.segments.last().title)
        assertEquals(2_824_000L, result.segments.last().startMs)
    }

    @Test
    fun `混合三种格式也能识别`() {
        val text = """
            1.Breakaway00:00
            03:42 All The Time
             Come Together07:22
        """.trimIndent()

        val result = SplitTextParser.parse(text)
        assertEquals(3, result.segments.size)
        assertEquals("Breakaway", result.segments[0].title)
        assertEquals("All The Time", result.segments[1].title)
        assertEquals("Come Together", result.segments[2].title)
        assertEquals("识别为「时间在前 / 时间在后」混合格式", result.styleHint)
    }

    @Test
    fun `支持序号的其他写法`() {
        val text = """
            1) Song A 00:10
            02、Song B 01:20
            3）Song C 02:30
        """.trimIndent()
        val result = SplitTextParser.parse(text)
        assertEquals(listOf("Song A", "Song B", "Song C"), result.segments.map { it.title })
        assertEquals(listOf(10_000L, 80_000L, 150_000L), result.segments.map { it.startMs })
    }

    @Test
    fun `支持全角数字和冒号`() {
        val result = SplitTextParser.parse("０：００ 第一首\n０１：２３ 第二首")
        assertEquals(2, result.segments.size)
        assertEquals("第一首", result.segments[0].title)
        assertEquals(83_000L, result.segments[1].startMs)
    }

    @Test
    fun `支持 hh mm ss 与毫秒`() {
        val text = """
            01:02:03.500 Long Song
            04:05 Short Song
        """.trimIndent()
        // 解析后按时间升序排序：04:05 在 01:02:03 之前
        val result = SplitTextParser.parse(text)
        assertEquals(245_000L, result.segments[0].startMs)
        assertEquals("Short Song", result.segments[0].title)
        assertEquals(3_723_500L, result.segments[1].startMs)
        assertEquals("Long Song", result.segments[1].title)
    }

    @Test
    fun `无法识别的行会跳过并保留提示`() {
        val text = """
            专辑曲目
            00:00 第一首
            这一行没有时间点
            03:21 第二首
        """.trimIndent()
        val result = SplitTextParser.parse(text)
        assertEquals(2, result.segments.size)
        assertEquals(2, result.skippedLines.size)
    }

    @Test
    fun `重复时间点只保留第一条`() {
        val result = SplitTextParser.parse("00:00 第一首\n00:00 重复的一首\n01:00 第二首")
        assertEquals(2, result.segments.size)
        assertEquals("第一首", result.segments[0].title)
        assertEquals(1, result.skippedLines.size)
    }

    @Test
    fun `空文本返回空结果`() {
        val result = SplitTextParser.parse("   \n\n  ")
        assertTrue(result.isEmpty)
        assertTrue(result.skippedLines.isEmpty())
    }

    @Test
    fun `标题与时间之间的分隔符会被清理`() {
        val result = SplitTextParser.parse("歌曲一 - 00:30\n歌曲二·01:40")
        assertEquals(listOf("歌曲一", "歌曲二"), result.segments.map { it.title })
    }

    @Test
    fun `toSegmentSpecs 保持顺序与标题`() {
        val specs = SplitTextParser.parse(formatTimeTitle).toSegmentSpecs()
        assertEquals(11, specs.size)
        assertEquals("Tap Out", specs.first().title)
        assertEquals(0L, specs.first().startMs)
        assertEquals(2_190_000L, specs.last().startMs)
    }
}
