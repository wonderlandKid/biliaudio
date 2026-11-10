package com.wonderlandkid.biliaudio.data.lyrics

/**
 * 歌词相关的曲名文本清洗。
 *
 * BiliAudio 的曲名来自用户对 B 站视频的分曲标题，常见形态如
 * `01、晴天`、`3. 晴天`、`（2）晴天`、`P1 晴天`、`第1首 晴天`。
 * 在线歌词搜索前需要去掉这些序号前缀，否则搜不到结果。
 */
object LyricsText {

    /** 最多连续剥离的序号前缀层数（防御异常输入造成死循环）。 */
    private const val MAX_STRIP_ROUNDS = 8

    /** 全角/半角数字、常见分隔符。 */
    private const val DIGITS = "0-9０-９"

    /** 括号型序号前缀：`(3)` / `（3）` / `[3]` / `【3】` / `<3>`。 */
    private val BRACKET_PREFIXES = listOf(
        Regex("""^\(\s*[$DIGITS]{1,3}\s*\)"""),
        Regex("""^（\s*[$DIGITS]{1,3}\s*）"""),
        Regex("""^\[\s*[$DIGITS]{1,3}\s*]"""),
        Regex("""^【\s*[$DIGITS]{1,3}\s*】"""),
        Regex("""^<\s*[$DIGITS]{1,3}\s*>"""),
    )

    /** 文字型序号前缀：`P1 `、`第1首`、`No.1`、`Track 1`。 */
    private val TEXT_PREFIXES = listOf(
        // P1 / p2 / P 3
        Regex("""^[Pp]\s*[0-9０-９]{1,3}(?:\s*[.、．,，:：)）\]】\-–—]|\s+)"""),
        // 第1首 / 第三曲（支持阿拉伯数字与中文数字）
        Regex("""^第\s*[0-9０-９一二三四五六七八九十百]{1,3}\s*[首曲集]\s*[.、．,，:：]?"""),
        // No.1 / NO.2 / No 3
        Regex("""^(?:No|NO|no)\.?\s*[0-9０-９]{1,3}\s*[.、．,，:：)）\-–—]?\s*"""),
        // Track 1 / TRACK.2
        Regex("""^(?:Track|TRACK|track)\.?\s*[0-9０-９]{1,3}\s*[.、．,，:：)）\-–—]?\s*"""),
    )

    /** 纯数字前缀：`1.` / `01、` / `3）` / `02:`。 */
    private val NUMBER_PREFIXES = listOf(
        Regex("""^[0-9０-９]{1,3}\s*[.、．,，:：)）\]】]\s*"""),
        // `1 - 晴天`（连字符后需有空白，避免误伤 `1-800` 之类）
        Regex("""^[0-9０-９]{1,3}\s*[-–—]\s+"""),
    )

    /** 剥离前缀后可能残留的分隔符（仅当后面还有内容时才清理）。 */
    private val LEADING_SEPARATORS =
        Regex("""^[-–—·•:：、,，.．_\s\u3000]+""")

    /**
     * 清理曲名：去掉序号前缀（可多次剥离）与首尾空白，保留歌名本身。
     *
     * - 输入为空/全是空白时原样返回 trim 后的内容；
     * - 只去掉「像序号前缀」的部分，`7 Years`、`1973`、`1.5` 等真实歌名不会被误伤；
     * - 剥离后为空（例如输入只有 `1.`）时回退到原始输入，保证不返回空串。
     */
    fun cleanTitle(raw: String): String {
        val input = raw
            .removePrefix("\uFEFF")
            .replace("\u200B", "")
            .trim()
        if (input.isEmpty()) return input

        var current = input
        var round = 0
        while (round < MAX_STRIP_ROUNDS) {
            val next = stripOnePrefix(current)
            if (next == current) break
            current = next
            round++
        }
        return current.trim().ifEmpty { input }
    }

    /** 尝试剥离一层序号前缀；没有可剥离内容时原样返回。 */
    private fun stripOnePrefix(value: String): String {
        val candidate = value.trim()
        if (candidate.isEmpty()) return value

        for (pattern in BRACKET_PREFIXES) {
            val match = pattern.find(candidate) ?: continue
            return finishStrip(candidate, match, allowDecimalGuard = false)
        }
        for (pattern in TEXT_PREFIXES) {
            val match = pattern.find(candidate) ?: continue
            return finishStrip(candidate, match, allowDecimalGuard = false)
        }
        for (pattern in NUMBER_PREFIXES) {
            val match = pattern.find(candidate) ?: continue
            return finishStrip(candidate, match, allowDecimalGuard = true)
        }
        return value
    }

    private fun finishStrip(
        candidate: String,
        match: MatchResult,
        allowDecimalGuard: Boolean,
    ): String {
        val rest = candidate.substring(match.range.last + 1)
        if (rest.isEmpty()) {
            // 只有序号本身（例如 "1."），没有歌名可保留：视为无前缀，保留原文
            return candidate
        }
        // 小数保护：`1.5` / `24.7` 这类数字开头的真实歌名不当作序号
        if (allowDecimalGuard && match.value.trimEnd().lastOrNull()?.let { it == '.' || it == '．' } == true &&
            rest.firstOrNull()?.let { it.isDigit() || it in '０'..'９' } == true
        ) {
            return candidate
        }
        val cleaned = rest.trimStart()
        if (cleaned.isEmpty()) return candidate
        return cleaned.replaceFirst(LEADING_SEPARATORS, "").trim().ifEmpty { cleaned }
    }
}
