package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import kotlin.math.roundToLong
import com.wonderlandkid.biliaudio.util.Diag

/**
 * 分割点文本解析器。
 *
 * 把用户从评论区/专辑信息里粘贴的「歌名 + 时间点」列表识别为 [SegmentSpec]。
 * 支持（可混用、每行独立判断）：
 *
 * ### A. 编号 + 歌名 + 时间（时间在后）
 * ```text
 * 1.Breakaway00:00
 * 2.Since U Been Gone03:57
 * ```
 * ### B. 时间 + 歌名（时间在前）
 * ```text
 * 00:00 Tap Out
 * 03:42 All The Time
 * ```
 * ### C. 歌名 + 时间（时间在后，允许前导空格/无空格）
 * ```text
 *  Come Together00:00
 * Something04:20
 * Oh! Darling 10:50
 * ```
 *
 * 容错：全角数字/冒号、`h:mm:ss`、毫秒、编号分隔符（`.`/`、`/`)`/`）`）、
 * 前后分隔符（`-`/`·`）会被规范化；无法识别的行放入 skippedLines 由 UI 提示。
 * 时间点按升序排序，重复时间只保留第一条；标题为空时自动命名「曲目 N」。
 */
object SplitTextParser {

    /**
     * 时间格式（用户可在导入弹窗里手动指定，用于消除「1:03 到底是 1 分 3 秒还是 1 小时 3 分」的歧义）。
     * - [AUTO]：自动识别（三段 = 时:分:秒；两段 = 分:秒，分钟允许 1–3 位）；
     * - [MINUTES_SECONDS]：两段 = 分:秒（分钟可超过 99，如 103:48）；
     * - [HOURS_MINUTES_SECONDS]：两段 = 时:分（如 1:03 = 1 小时 3 分），三段 = 时:分:秒。
     */
    enum class TimeFormat { AUTO, MINUTES_SECONDS, HOURS_MINUTES_SECONDS }

    /** 解析出的单个分割点。 */
    data class ParsedSegment(
        val startMs: Long,
        val title: String,
        val rawLine: String,
    )

    data class Result(
        val segments: List<ParsedSegment>,
        val skippedLines: List<String>,
        val timeFirstCount: Int,
        val timeLastCount: Int,
        val timeFormat: TimeFormat = TimeFormat.AUTO,
    ) {
        val isEmpty: Boolean get() = segments.isEmpty()

        /** 供 UI 展示的格式识别提示。 */
        val styleHint: String
            get() = when {
                segments.isEmpty() -> "未识别到分割点"
                timeFormat == TimeFormat.MINUTES_SECONDS -> "时间格式：分:秒（分钟可超过 99，如 103:48）"
                timeFormat == TimeFormat.HOURS_MINUTES_SECONDS -> "时间格式：时:分:秒（两段按 时:分 解析）"
                timeFirstCount > 0 && timeLastCount > 0 ->
                    "识别为「时间在前 / 时间在后」混合格式"
                timeFirstCount > 0 -> "识别为「时间 + 歌名」格式"
                else -> "识别为「歌名 + 时间」格式（可带序号）"
            }

        /** 转换成交给导入/保存用的 SegmentSpec。 */
        fun toSegmentSpecs(): List<SegmentSpec> =
            segments.map { SegmentSpec(startMs = it.startMs, title = it.title) }
    }

    // ------------------------------------------------------------------
    // 语法
    // ------------------------------------------------------------------

    /** 行首编号：`1.` / `01、` / `3)`（不匹配 `00:00` 这类时间，避免误删）。 */
    private val INDEX_PREFIX = Regex("^\\s*\\d{1,3}\\s*[.、)）]\\s*")

    /**
     * 时间 token：`m:s` / `mm:ss` / `mm:ss.SSS` / `h:mm:ss` / `h:mm:ss.SSS`。
     *
     * 注意：演唱会/现场专辑的总时长经常超过 1 小时，列表里的「分钟」可能写成 3 位数
     * （如 `103:48`、`118:36`，等价于 1:43:48 / 1:58:36），因此两段式时间的分钟位允许 1–3 位。
     * 三段式（h:mm:ss）放在前面优先匹配，避免把 `01:02:03` 误拆成 `01:02` + 标题。
     */
    private const val TIME =
        "(?:\\d{1,2}:\\d{1,2}:\\d{1,2}(?:[.,]\\d{1,3})?|\\d{1,3}:\\d{1,2}(?:[.,]\\d{1,3})?)"

    /** 时间在前：`00:00 Tap Out`、`00:00Tap Out`。 */
    private val TIME_FIRST = Regex("^\\s*($TIME)\\s*(.+?)\\s*$")

    /** 时间在后：`Come Together00:00`、`Oh! Darling 10:50`。 */
    private val TIME_LAST = Regex("^\\s*(.+?)\\s*($TIME)\\s*$")

    private val LEADING_SEPARATOR = Regex("^[\\s\\-–—·•]+")
    private val TRAILING_SEPARATOR = Regex("[\\s\\-–—·•]+$")

    private data class ParsedLine(
        val timeText: String,
        val title: String,
        val timeFirst: Boolean,
    )

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    fun parse(text: String, timeFormat: TimeFormat = TimeFormat.AUTO): Result {
        val segments = mutableListOf<ParsedSegment>()
        val skipped = mutableListOf<String>()
        val usedTimes = HashSet<Long>()
        var timeFirstCount = 0
        var timeLastCount = 0

        text.lineSequence()
            .map { normalizeLine(it) }
            .filter { it.isNotBlank() }
            .forEach { rawLine ->
                val line = rawLine.replace(INDEX_PREFIX, "").trim()
                val parsed = parseTimeFirst(line) ?: parseTimeLast(line)
                if (parsed == null) {
                    skipped += rawLine
                    return@forEach
                }
                val startMs = parseTimeToken(parsed.timeText, timeFormat)
                if (startMs == null || startMs < 0) {
                    skipped += rawLine
                    return@forEach
                }
                if (!usedTimes.add(startMs)) {
                    // 重复时间点只保留第一条
                    skipped += rawLine
                    return@forEach
                }
                val title = parsed.title
                    .replace(LEADING_SEPARATOR, "")
                    .replace(TRAILING_SEPARATOR, "")
                    .trim()
                    .ifBlank { "曲目 ${segments.size + 1}" }
                segments += ParsedSegment(startMs = startMs, title = title, rawLine = rawLine)
                if (parsed.timeFirst) timeFirstCount++ else timeLastCount++
            }

        val sorted = segments.sortedBy { it.startMs }
        Diag.log(
            "Import",
            "splitText parsed=${sorted.size} skipped=${skipped.size} " +
                "timeFirst=$timeFirstCount timeLast=$timeLastCount " +
                "first=${sorted.firstOrNull()?.startMs} last=${sorted.lastOrNull()?.startMs}",
        )
        return Result(
            segments = sorted,
            skippedLines = skipped,
            timeFirstCount = timeFirstCount,
            timeLastCount = timeLastCount,
            timeFormat = timeFormat,
        )
    }

    /**
     * 按用户选择的时间格式解析时间 token。
     * - AUTO：三段 → 时:分:秒；两段 → 分:秒（分钟允许 1–3 位）；
     * - MINUTES_SECONDS：两段 → 分:秒；三段仍按 时:分:秒（显式写法不歧义）；
     * - HOURS_MINUTES_SECONDS：两段 → 时:分；三段 → 时:分:秒。
     */
    private fun parseTimeToken(text: String, format: TimeFormat): Long? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        if (raw.matches(Regex("^\\d+$"))) return raw.toLong() * 1000L
        val parts = raw.split(':')
        if (parts.size !in 2..3) return null
        return try {
            val last = parts.last().replace(',', '.').toDouble()
            if (last < 0) return null
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                if (hours < 0 || minutes < 0) return null
                hours * 3_600_000L + minutes * 60_000L + (last * 1000).roundToLong()
            } else {
                val first = parts[0].toLong()
                if (first < 0) return null
                when (format) {
                    TimeFormat.HOURS_MINUTES_SECONDS ->
                        first * 3_600_000L + (last * 60_000).roundToLong()
                    TimeFormat.AUTO, TimeFormat.MINUTES_SECONDS ->
                        first * 60_000L + (last * 1000).roundToLong()
                }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseTimeFirst(line: String): ParsedLine? {
        val match = TIME_FIRST.matchEntire(line) ?: return null
        val title = match.groupValues[2]
        // 时间在前的行，标题不应再以时间开头（避免把时间当标题）
        if (TIME_FIRST.matchEntire(title) != null) return null
        return ParsedLine(timeText = match.groupValues[1], title = title, timeFirst = true)
    }

    private fun parseTimeLast(line: String): ParsedLine? {
        val match = TIME_LAST.matchEntire(line) ?: return null
        val title = match.groupValues[1]
        if (title.isBlank()) return null
        return ParsedLine(timeText = match.groupValues[2], title = title, timeFirst = false)
    }

    /** 全角数字/冒号/句点规范化，避免中文输入法粘贴导致识别失败。 */
    private fun normalizeLine(line: String): String {
        if (line.isEmpty()) return line
        val builder = StringBuilder(line.length)
        for (char in line) {
            builder.append(
                when (char) {
                    in '０'..'９' -> '0' + (char - '０')
                    '：' -> ':'
                    '．' -> '.'
                    '，' -> ','
                    else -> char
                },
            )
        }
        return builder.toString().trim()
    }
}
