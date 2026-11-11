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

    // ------------------------------------------------------------------
    // 现场演唱会专辑：总时长超过 99 分钟，分钟位会出现 3 位数（103:48 / 118:36）
    // ------------------------------------------------------------------
    private val formatLiveConcertOver99Minutes = """
        1. Neon 0:00
        2. Stop This Train 5:45
        3. In Your Atmosphere 10:45
        4. Daughters 16:31
        5. Free Fallin 21:35
        6. Everyday I Have the Blues 25:59
        7. Wait Until Tomorrow 31:13
        8. Who Did You Think I Was 34:32
        9. Come When I Call 38:56
        10. Good Love Is On the Way 42:19
        11. Out of My Mind 46:38
        12. Vultures 56:49
        13. Bold As Love 62:09
        14. Waiting On the World to Change 70:46
        15. Slow Dancing In a Burning Room 74:37
        16. Why Georgia 79:57
        17. The Heart of Life 84:24
        18. I Don't Need No Doctor 88:04
        19. Gravity 94:05
        20. I Don't Trust Myself (With Loving You) 103:48
        21. Belief 112:33
        22. I'm Gonna Find Another You 118:36
    """.trimIndent()

    @Test
    fun `支持超过 99 分钟的演唱会专辑（3 位分钟数）`() {
        val result = SplitTextParser.parse(formatLiveConcertOver99Minutes)

        assertEquals(22, result.segments.size)
        assertTrue("不应有跳过的行：${result.skippedLines}", result.skippedLines.isEmpty())
        assertEquals(0L, result.segments.first().startMs)
        assertEquals("Neon", result.segments.first().title)
        assertEquals(5 * 60_000L + 45_000L, result.segments[1].startMs)
        assertEquals("Stop This Train", result.segments[1].title)
        // 62:09 已经超过 1 小时，但仍按「分钟:秒」解析
        assertEquals((62 * 60 + 9) * 1000L, result.segments[12].startMs)
        assertEquals("Bold As Love", result.segments[12].title)
        // 3 位分钟数：103:48 / 112:33 / 118:36
        assertEquals((103 * 60 + 48) * 1000L, result.segments[19].startMs)
        assertEquals("I Don't Trust Myself (With Loving You)", result.segments[19].title)
        assertEquals((112 * 60 + 33) * 1000L, result.segments[20].startMs)
        assertEquals("Belief", result.segments[20].title)
        assertEquals((118 * 60 + 36) * 1000L, result.segments.last().startMs)
        assertEquals("I'm Gonna Find Another You", result.segments.last().title)
        assertEquals("识别为「歌名 + 时间」格式（可带序号）", result.styleHint)
    }

    @Test
    fun `3 位分钟数不会与 hh mm ss 混淆`() {
        val result = SplitTextParser.parse(
            """
            01:02:03 Long Song
            103:48 Very Long Song
            """.trimIndent(),
        )
        assertEquals(2, result.segments.size)
        assertEquals(3_723_000L, result.segments[0].startMs)
        assertEquals("Long Song", result.segments[0].title)
        assertEquals((103 * 60 + 48) * 1000L, result.segments[1].startMs)
        assertEquals("Very Long Song", result.segments[1].title)
    }

    // ------------------------------------------------------------------
    // 用户手动指定时间格式（导入弹窗里的「自动 / 分:秒 / 时:分:秒」）
    // ------------------------------------------------------------------

    @Test
    fun `手动指定分秒格式可解析整份演唱会文本`() {
        val result = SplitTextParser.parse(
            formatLiveConcertOver99Minutes,
            SplitTextParser.TimeFormat.MINUTES_SECONDS,
        )
        assertEquals(22, result.segments.size)
        assertTrue("不应有跳过的行：${result.skippedLines}", result.skippedLines.isEmpty())
        assertEquals(SplitTextParser.TimeFormat.MINUTES_SECONDS, result.timeFormat)
        assertEquals((103 * 60 + 48) * 1000L, result.segments[19].startMs)
        assertEquals((118 * 60 + 36) * 1000L, result.segments.last().startMs)
        assertTrue(result.styleHint.contains("分:秒"))
    }

    @Test
    fun `自动模式下两段式时间按分秒解析`() {
        val result = SplitTextParser.parse("1:03 Song A\n103:48 Song B")
        assertEquals(63_000L, result.segments[0].startMs)
        assertEquals((103 * 60 + 48) * 1000L, result.segments[1].startMs)
    }

    @Test
    fun `手动指定有小时格式时两段式按小时分钟解析`() {
        val result = SplitTextParser.parse(
            "1:03 Song A\n1:02:03 Song B",
            SplitTextParser.TimeFormat.HOURS_MINUTES_SECONDS,
        )
        assertEquals(2, result.segments.size)
        // 排序后：1:02:03（1 小时 2 分 3 秒）在前，1:03（1 小时 3 分）在后
        assertEquals("Song B", result.segments[0].title)
        assertEquals(3_723_000L, result.segments[0].startMs)
        assertEquals("Song A", result.segments[1].title)
        assertEquals(3_780_000L, result.segments[1].startMs)
        assertTrue(result.styleHint.contains("时:分:秒"))
    }

    @Test
    fun `时间格式不会影响三段式显式写法`() {
        val result = SplitTextParser.parse(
            "1:02:03 Long Song",
            SplitTextParser.TimeFormat.MINUTES_SECONDS,
        )
        assertEquals(1, result.segments.size)
        assertEquals(3_723_000L, result.segments.first().startMs)
    }
}