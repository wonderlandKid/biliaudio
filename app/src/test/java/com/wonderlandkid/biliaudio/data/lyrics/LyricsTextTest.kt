package com.wonderlandkid.biliaudio.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTextTest {

    @Test
    fun cleanTitle_stripsDotAndChineseCommaNumberPrefixes() {
        assertEquals("晴天", LyricsText.cleanTitle("1. 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("1.晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("01、晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("02: 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("3）晴天"))
    }

    @Test
    fun cleanTitle_stripsBracketNumberPrefixes() {
        assertEquals("晴天", LyricsText.cleanTitle("（3）晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("(3) 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("【7】晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("[12] 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("<5>晴天"))
    }

    @Test
    fun cleanTitle_stripsPageAndOrderPrefixes() {
        assertEquals("晴天", LyricsText.cleanTitle("P1 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("p2.晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("第1首 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("第三曲：晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("No.1 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("Track 3 晴天"))
    }

    @Test
    fun cleanTitle_stripsMultiplePrefixesRepeatedly() {
        assertEquals("晴天", LyricsText.cleanTitle("P1 01、晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("(2) 3. 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("第1首 P2 07.晴天"))
    }

    @Test
    fun cleanTitle_stripsSeparatorsLeftBehindByPrefix() {
        assertEquals("晴天", LyricsText.cleanTitle("1. - 晴天"))
        assertEquals("晴天", LyricsText.cleanTitle("  02、  晴天  "))
    }

    @Test
    fun cleanTitle_keepsTitlesThatStartWithDigits() {
        // 「7 Years」「1973」「24K Magic」都是真实歌名，不能当序号剥掉
        assertEquals("7 Years", LyricsText.cleanTitle("7 Years"))
        assertEquals("1973", LyricsText.cleanTitle("1973"))
        assertEquals("24K Magic", LyricsText.cleanTitle("24K Magic"))
        assertEquals("1.5", LyricsText.cleanTitle("1.5"))
    }

    @Test
    fun cleanTitle_handlesBlankAndBomAndFallback() {
        assertEquals("", LyricsText.cleanTitle(""))
        assertEquals("", LyricsText.cleanTitle("   \n\t"))
        assertEquals("晴天", LyricsText.cleanTitle("\uFEFF  晴天  "))
        // 只有序号没有歌名时回退原文，不返回空串
        assertEquals("1.", LyricsText.cleanTitle("1."))
    }

    @Test
    fun cleanTitle_keepsPlainTitleUntouched() {
        assertEquals("晴天", LyricsText.cleanTitle("晴天"))
        assertEquals("告白气球", LyricsText.cleanTitle("  告白气球 "))
        assertEquals("夜に駆ける", LyricsText.cleanTitle("夜に駆ける"))
    }
}
