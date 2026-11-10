package com.wonderlandkid.biliaudio.data.lyrics

import com.wonderlandkid.biliaudio.data.model.LyricItem
import com.wonderlandkid.biliaudio.data.model.ParsedLyrics

/**
 * 歌词原文解析入口（统一识别 LRC / YRC / AMLL-TTML / 纯文本）。
 *
 * 数据流：`LyricsRepository` 只存原始文本，展示前由本入口解析成 [ParsedLyrics]。
 * 四种格式的识别顺序（越靠前越优先）：
 * 1. TTML / AMLL：以 `<?xml` / `<tt` 开头或含 TTML 命名空间 → [AmllParser]；
 * 2. YRC：行首形如 `[123,456](0,100,0)…` → [YrcParser]；
 * 3. LRC：含 `[mm:ss]` / `[mm:ss.xx]` 时间标签 → [LrcParser]；
 * 4. 其他：纯文本，每行一句无时间轴歌词（`isSynced = false`，静态展示）。
 */
object LyricsParser {

    /** 有真正时间轴的歌词格式。 */
    enum class Format { AMLL_TTML, YRC, LRC, PLAIN_TEXT }

    /** YRC 行首标记：`[行开始,行时长](字开始,字时长,空格数)`。 */
    private val YRC_LINE = Regex("""^\s*\[\d+,\d+]\(\d+,\d+,\d+\)""", RegexOption.MULTILINE)

    /** LRC 时间标签：`[mm:ss]`、`[mm:ss.x]`、`[m:ss.xxx]` 等。 */
    private val LRC_TIME_TAG = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,6})?]""")

    /** TTML 命名空间（AMLL/Apple TTML 的稳定特征，避免普通歌词里偶然出现 `<tt` 误判）。 */
    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"

    private const val ERROR_EMPTY = "歌词内容为空"
    private const val ERROR_AMLL = "AMLL/TTML 歌词解析失败"
    private const val ERROR_YRC = "YRC 歌词解析失败"
    private const val ERROR_LRC = "LRC 歌词解析失败"

    /**
     * 自动识别格式并解析歌词原文。
     *
     * 解析不出任何歌词行时 [ParsedLyrics.isEmpty] 为 true，并在 [ParsedLyrics.parseError]
     * 给出中文简短错误信息；任何输入都不会抛异常。
     */
    fun parse(raw: String): ParsedLyrics {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) {
            return ParsedLyrics(lines = emptyList(), isSynced = false, parseError = ERROR_EMPTY)
        }

        return when (detectFormat(text)) {
            Format.AMLL_TTML -> {
                // 用 parseDetailed 保留错误信息；对外只暴露中文提示，不把英文异常抛给 UI
                val result = AmllParser.parseDetailed(text)
                if (result.items.isEmpty()) {
                    ParsedLyrics(emptyList(), isSynced = false, parseError = ERROR_AMLL)
                } else {
                    ParsedLyrics(result.items, isSynced = true)
                }
            }

            Format.YRC -> {
                val lines = YrcParser.parseToLyricItems(text)
                if (lines.isEmpty()) {
                    ParsedLyrics(emptyList(), isSynced = false, parseError = ERROR_YRC)
                } else {
                    ParsedLyrics(lines, isSynced = true)
                }
            }

            Format.LRC -> {
                val lines = LrcParser.parse(text)
                if (lines.isEmpty()) {
                    ParsedLyrics(emptyList(), isSynced = false, parseError = ERROR_LRC)
                } else {
                    ParsedLyrics(lines, isSynced = true)
                }
            }

            Format.PLAIN_TEXT -> parsePlainText(text)
        }
    }

    /**
     * 本地导入校验：扩展名与内容必须匹配，且能解析出歌词行。
     *
     * - `ttml` / `ttml2` / `xml`：必须能被 [AmllParser] 解析出歌词行；
     * - `yrc`：必须能被 [YrcParser] 解析出歌词行；
     * - `lrc`：必须能被 [LrcParser] 解析出歌词行；
     * - `txt` 或无扩展名：至少有一行非空文本；
     * - 其他扩展名：一律不接受。
     */
    fun isValid(raw: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").trim().lowercase()
        return when (extension) {
            "ttml", "ttml2", "xml" -> AmllParser.parse(raw).isNotEmpty()
            "yrc" -> YrcParser.parseToLyricItems(raw).isNotEmpty()
            "lrc" -> LrcParser.parse(raw).isNotEmpty()
            "txt", "" -> raw.lineSequence().any { it.isNotBlank() }
            else -> false
        }
    }

    /** 根据原文特征识别歌词格式。 */
    fun detectFormat(raw: String): Format {
        val text = raw.removePrefix("\uFEFF")
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<tt") ||
            text.contains(TTML_NAMESPACE)
        ) {
            return Format.AMLL_TTML
        }
        if (YRC_LINE.containsMatchIn(text)) return Format.YRC
        if (LRC_TIME_TAG.containsMatchIn(text)) return Format.LRC
        return Format.PLAIN_TEXT
    }

    /** 纯文本歌词：逐行成句，无时间轴（`isSynced = false`），忽略空行。 */
    private fun parsePlainText(text: String): ParsedLyrics {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, content ->
                LyricItem.NormalLyric(
                    content = content,
                    time = 0L,
                    key = "plain_$index",
                )
            }
            .toList()
        return if (lines.isEmpty()) {
            ParsedLyrics(emptyList(), isSynced = false, parseError = ERROR_EMPTY)
        } else {
            ParsedLyrics(lines, isSynced = false)
        }
    }
}
