package com.wonderlandkid.biliaudio.data.lyrics

import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LyricsMatcherTest {

    private fun candidate(
        id: Long,
        name: String,
        durationSec: Int,
        artist: String = "某歌手",
    ) = OnlineLyricCandidate(
        id = id,
        name = name,
        artist = artist,
        durationSec = durationSec,
        rawLyrics = "[00:01.00]$name",
    )

    @Test
    fun bestMatch_prefersExactTitleOverContainment() {
        val exact = candidate(1, "晴天", 269)
        val longer = candidate(2, "晴天娃娃", 269)

        val matched = LyricsMatcher.bestMatch(listOf(longer, exact), "晴天", 269_000)

        assertSame(exact, matched)
    }

    @Test
    fun bestMatch_returnsNullWhenNoCandidateTitleMatches() {
        val unrelated = candidate(1, "七里香", 269)

        assertNull(LyricsMatcher.bestMatch(listOf(unrelated), "晴天", 269_000))
    }

    @Test
    fun bestMatch_returnsNullForEmptyCandidatesOrTitle() {
        assertNull(LyricsMatcher.bestMatch(emptyList(), "晴天", 269_000))
        assertNull(LyricsMatcher.bestMatch(listOf(candidate(1, "晴天", 269)), "   ", 269_000))
    }

    @Test
    fun bestMatch_usesDurationToChooseBetweenSameTitleCandidates() {
        val rightLength = candidate(1, "起风了", 326)
        val wrongLength = candidate(2, "起风了", 200)

        val matched = LyricsMatcher.bestMatch(listOf(wrongLength, rightLength), "起风了", 326_000)

        assertSame(rightLength, matched)
    }

    @Test
    fun bestMatch_acceptsExactTitleWhenDurationIsFarOff() {
        // 曲名完全相同但时长差 31s：可能是翻唱/现场版；歌词一般仍可用，因此精确标题兜底匹配，
        // 时长只用于在多个同名候选之间排序（不再是硬性拒绝条件）。
        val differentVersion = candidate(1, "晴天", 300)

        assertSame(differentVersion, LyricsMatcher.bestMatch(listOf(differentVersion), "晴天", 269_000))
    }

    @Test
    fun bestMatch_matchesExactTitleWhenDurationUnknown() {
        val exact = candidate(1, "晴天", 0)

        // durationMs = 0（曲目时长未知）时不参与扣分，曲名完全一致即可匹配
        assertSame(exact, LyricsMatcher.bestMatch(listOf(exact), "晴天", 0L))
    }

    @Test
    fun bestMatch_acceptsCandidateNameWithExtraWords() {
        val withArtistSuffix = candidate(1, "起风了 吴青峰", 326)

        val matched = LyricsMatcher.bestMatch(listOf(withArtistSuffix), "起风了", 326_000)

        assertEquals(withArtistSuffix, matched)
    }

    @Test
    fun bestMatch_rejectsVeryShortTitleAgainstUnrelatedLongerTitle() {
        // 单字曲名「爱」不能因为包含关系匹配到「爱你一万年」
        val longer = candidate(1, "爱你一万年", 200)

        assertNull(LyricsMatcher.bestMatch(listOf(longer), "爱", 200_000))
    }

    @Test
    fun bestMatch_normalizesLiveSuffixInTrackTitle() {
        val exact = candidate(1, "晴天", 269)

        val matched = LyricsMatcher.bestMatch(listOf(exact), "晴天（Live）", 269_000)

        assertSame(exact, matched)
    }

    @Test
    fun normalizeTitle_stripsNoiseBracketsFeatAndPunctuation() {
        assertEquals("晴天", LyricsMatcher.normalizeTitle("晴天 (Live)"))
        assertEquals("晴天", LyricsMatcher.normalizeTitle("晴天【伴奏】"))
        assertEquals("晴天", LyricsMatcher.normalizeTitle("晴天 feat. 周杰伦"))
        assertEquals("晴天", LyricsMatcher.normalizeTitle("《晴天》"))
        assertEquals("abc", LyricsMatcher.normalizeTitle("Ａ Ｂ Ｃ"))
        assertEquals("", LyricsMatcher.normalizeTitle("   "))
    }
}
