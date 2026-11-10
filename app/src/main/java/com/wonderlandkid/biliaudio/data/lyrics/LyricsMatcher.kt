package com.wonderlandkid.biliaudio.data.lyrics

import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate

/**
 * 在线歌词候选自动匹配。
 *
 * BiliAudio 的曲名来自用户对 B 站视频的分曲标题（先经 [LyricsText.cleanTitle] 清洗），
 * 专辑艺术家是 UP 主、不可靠，因此在线搜索只用「曲名」；候选来自歌词 API 的多来源结果，
 * 需要从中挑出最可能是同一首歌的一条：
 *
 * 1. 标题归一化：全角转半角、小写、去 feat./ft.、去括号后缀（Live/伴奏/版等）、
 *    去常见噪音词、去所有标点与空白，只保留字母/数字/CJK；
 * 2. 标题得分：完全相等（100）> 包含（按长度比 50~90）> 字符/二元组相似度（0~55）；
 * 3. 时长得分：候选时长与曲目时长差 ≤3s 加 40 分，3~20s 线性衰减到 0，>20s 扣 35 分
 *    （防止「晴天」匹配到时长完全不同的无关歌）；
 * 4. 总分低于 [THRESHOLD] 返回 null（宁缺毋滥，UI 显示「暂无歌词」让用户手动选）。
 */
object LyricsMatcher {

    /** 匹配总分门槛；低于该分数视为没有可信候选。 */
    private const val THRESHOLD = 70.0

    /** 标题完全相等时的最低总分：不同版本/翻唱的时长可能差很多，但歌词通常仍可用。 */
    private const val EXACT_TITLE_MIN_SCORE = 75.0

    private const val SCORE_TITLE_EXACT = 100.0
    private const val SCORE_CONTAINS_BASE = 50.0
    private const val SCORE_CONTAINS_RATIO = 40.0
    private const val SCORE_SIMILARITY_MAX = 55.0

    private const val DURATION_TOLERANCE_MS = 3_000L
    private const val DURATION_MAX_MS = 20_000L
    private const val SCORE_DURATION_CLOSE = 40.0
    private const val PENALTY_DURATION_FAR = -35.0

    /** 包住整个歌名的引号/书名号：只去壳、保留内容（`《晴天》` → `晴天`）。 */
    private val TITLE_QUOTES = Regex("""[《》「」『』“”‘’]""")

    /** 标注类括号片段（全角/半角）：整段去掉，如 `(Live)`、`（伴奏）`、`[Remix]`、`【】`。 */
    private val BRACKET_SEGMENT = Regex("""[\(（\[【<][^\(）\)\[\]【】<>]*[\)）\]】>]""")

    /** `feat.` / `ft.` / `featuring` 及其之后的内容（合作歌手信息不参与匹配）。 */
    private val FEAT_SEGMENT = Regex("""\b(?:feat|ft|featuring)\.?\s.*""", RegexOption.IGNORE_CASE)

    /** 常见英文版本/噪音词。 */
    private val LATIN_NOISE = Regex(
        """\b(?:live|remix|cover|instrumental|off\s*vocal|acoustic|version|ver|mv|official|audio|lyrics|studio)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 常见中文版本/噪音词。 */
    private val CJK_NOISE = Regex(
        """(伴奏|纯音乐|纯音樂|现场版|現場版|live版|翻唱|完整版|高音质|高音質|动态歌词|動態歌詞|""" +
            """无损|無損|抖音版|片段|剪辑版|剪輯版|升调|升調|降调|降調|dj版|女声版|男声版|原唱)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 从在线候选里挑出最匹配的一首。
     *
     * @param candidates 歌词 API 返回的多来源候选
     * @param title 曲名（建议先 [LyricsText.cleanTitle]；这里仍会再归一化一次）
     * @param durationMs 曲目时长（毫秒），≤0 表示未知（此时忽略时长维度）
     * @return 最佳候选；没有可信匹配时返回 null
     */
    fun bestMatch(
        candidates: List<OnlineLyricCandidate>,
        title: String,
        durationMs: Long,
    ): OnlineLyricCandidate? {
        if (candidates.isEmpty()) return null
        val normalizedTitle = normalizeTitle(title)
        if (normalizedTitle.isEmpty()) return null

        var best: OnlineLyricCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val normalizedName = normalizeTitle(candidate.name)
            if (normalizedName.isEmpty()) continue
            val titlePart = titleScore(normalizedTitle, normalizedName)
            var score = titlePart + durationScore(candidate.durationSec, durationMs)
            // 标题完全相等时几乎可以确定是同一首歌：封底到 EXACT_TITLE_MIN_SCORE，
            // 避免因翻唱/现场版时长差异被时长惩罚误杀（时长仍参与排序，挑最接近的版本）。
            if (titlePart >= SCORE_TITLE_EXACT) {
                score = maxOf(score, EXACT_TITLE_MIN_SCORE)
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.takeIf { bestScore >= THRESHOLD }
    }

    /** 标题归一化：去空白/大小写/全半角标点/括号后缀/feat./噪音词。 */
    fun normalizeTitle(raw: String): String {
        var value = toHalfWidth(raw.trim()).lowercase()
        if (value.isEmpty()) return ""
        value = FEAT_SEGMENT.replace(value, " ")
        value = TITLE_QUOTES.replace(value, "")
        value = BRACKET_SEGMENT.replace(value, " ")
        value = LATIN_NOISE.replace(value, " ")
        value = CJK_NOISE.replace(value, " ")
        return value.filter { it.isLetterOrDigit() }
    }

    /** 标题维度得分：完全相等 > 包含 > 相似度。 */
    private fun titleScore(title: String, candidate: String): Double {
        if (title == candidate) return SCORE_TITLE_EXACT

        // 包含关系仅对 ≥2 字标题生效：单字曲名（如「爱」）包含在长标题里太容易误判，
        // 交给相似度打分兜底（通常不足以越过阈值）
        val shorterText = if (title.length <= candidate.length) title else candidate
        val longerText = if (title.length <= candidate.length) candidate else title
        if (shorterText.length >= 2 && longerText.contains(shorterText)) {
            val shorter = shorterText.length.toDouble()
            val longer = longerText.length.toDouble()
            return SCORE_CONTAINS_BASE + SCORE_CONTAINS_RATIO * (shorter / longer)
        }

        return SCORE_SIMILARITY_MAX * similarity(title, candidate)
    }

    /** 时长维度得分：越接近越高，差太远扣分；任一时长未知时不计分。 */
    private fun durationScore(candidateDurationSec: Int, durationMs: Long): Double {
        if (candidateDurationSec <= 0 || durationMs <= 0L) return 0.0
        val diffMs = kotlin.math.abs(candidateDurationSec.toLong() * 1000L - durationMs)
        return when {
            diffMs <= DURATION_TOLERANCE_MS -> SCORE_DURATION_CLOSE
            diffMs <= DURATION_MAX_MS ->
                SCORE_DURATION_CLOSE * (DURATION_MAX_MS - diffMs).toDouble() /
                    (DURATION_MAX_MS - DURATION_TOLERANCE_MS).toDouble()
            else -> PENALTY_DURATION_FAR
        }
    }

    /** 字符相似度：长串用二元组 Dice 系数，单字串退化为字符集合 Jaccard。 */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.length == 1 || b.length == 1) {
            val setA = a.toSet()
            val setB = b.toSet()
            val intersection = setA.count { it in setB }
            val union = (setA + setB).size
            return if (union == 0) 0.0 else intersection.toDouble() / union
        }
        val bigramsA = a.windowed(2).toSet()
        val bigramsB = b.windowed(2).toSet()
        val intersection = bigramsA.count { it in bigramsB }
        val total = bigramsA.size + bigramsB.size
        return if (total == 0) 0.0 else 2.0 * intersection / total
    }

    /** 全角 ASCII 可见字符转半角、全角空格转普通空格；CJK 原样保留。 */
    private fun toHalfWidth(value: String): String = buildString(value.length) {
        for (char in value) {
            when {
                char.code in 0xFF01..0xFF5E -> append((char.code - 0xFEE0).toChar())
                char == '\u3000' -> append(' ')
                else -> append(char)
            }
        }
    }
}
