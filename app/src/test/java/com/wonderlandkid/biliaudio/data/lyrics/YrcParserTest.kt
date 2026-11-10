package com.wonderlandkid.biliaudio.data.lyrics

import com.wonderlandkid.biliaudio.data.model.LyricItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YrcParserTest {

    @Test
    fun parseToLyricItems_parsesWordsWithAbsoluteTimeline() {
        val yrc = "[0,268](0,23,0)作(23,23,0)词(46,11,0)：(57,346,0)A(403,11,0)-(414,450,0)L(864,11,0)I"

        val items = YrcParser.parseToLyricItems(yrc)

        assertEquals(1, items.size)
        val line = items.single() as LyricItem.WordsLyric
        assertEquals(0L, line.startTime)
        // 行时长 268ms，但最后一个字的结束时间更晚 → 行结束时间取较大值
        assertEquals(875L, line.endTime)
        assertEquals("作词：A-LI", line.content)
        assertEquals(listOf("作", "词", "：", "A", "-", "L", "I"), line.words.map { it.content })
        assertEquals(
            listOf(0L, 23L, 46L, 57L, 403L, 414L, 864L),
            line.words.map { it.startTime },
        )
        assertEquals(
            listOf(23L, 46L, 57L, 403L, 414L, 864L, 875L),
            line.words.map { it.endTime },
        )
        assertTrue(line.translation.isEmpty())
        assertEquals("0_0", line.key)
    }

    @Test
    fun parseToLyricItems_parsesMultipleLinesWithOffsetTimeline() {
        val yrc = """
            [1000,500](0,100,0)你(100,100,0)好
            [2000,300](0,50,0)再(50,50,0)见
        """.trimIndent()

        val items = YrcParser.parseToLyricItems(yrc)

        assertEquals(2, items.size)
        val first = items[0] as LyricItem.WordsLyric
        assertEquals(1000L, first.startTime)
        // 行时长 500ms 覆盖到 1500ms，比最后一个字（1200ms）更晚
        assertEquals(1500L, first.endTime)
        assertEquals(listOf("你", "好"), first.words.map { it.content })
        assertEquals(listOf(1000L, 1100L), first.words.map { it.startTime })
        assertEquals("0_1000", first.key)

        val second = items[1] as LyricItem.WordsLyric
        assertEquals(2000L, second.startTime)
        assertEquals(2300L, second.endTime)
        assertEquals("1_2000", second.key)
    }

    @Test
    fun parseToLyricItems_returnsEmptyForBlankInput() {
        assertTrue(YrcParser.parseToLyricItems("").isEmpty())
        assertTrue(YrcParser.parseToLyricItems("   \n\t").isEmpty())
    }

    @Test
    fun parseToLyricItems_ignoresLinesThatAreNotYrc() {
        val yrc = """
            [00:01.00]普通 LRC 行
            not a yrc line
            [0,100]行头但没有逐字标记
            [0,100](0,50,0)有效
        """.trimIndent()

        val items = YrcParser.parseToLyricItems(yrc)

        assertEquals(1, items.size)
        val line = items.single() as LyricItem.WordsLyric
        assertEquals(listOf("有效"), line.words.map { it.content })
    }

    @Test
    fun parseToLyricItems_skipsLineWithInvalidWordTiming() {
        // 第一个逐字标记缺少时间字段 → 该行无法可靠解析，整行跳过（与参考实现一致）
        val yrc = "[0,100]()坏(0,50,0)好"

        val items = YrcParser.parseToLyricItems(yrc)

        assertTrue(items.isEmpty())
    }

    @Test
    fun parseToLyricItems_neverThrowsForGarbage() {
        val items = YrcParser.parseToLyricItems("((((]]]][0,](,,)(((")
        assertTrue(items.isEmpty())
    }
}
