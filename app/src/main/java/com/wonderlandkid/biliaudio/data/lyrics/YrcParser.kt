package com.wonderlandkid.biliaudio.data.lyrics

import com.wonderlandkid.biliaudio.data.model.LyricItem

/** YRC 逐字歌词解析器；格式与容错规则见 docs/LYRICS.md。 */
object YrcParser {

    /** 单行 YRC 行头：`[行开始时间,行持续时间]` 后紧跟逐字标记 `(...)`。 */
    private val LINE_HEADER = Regex("""^\[(\d+),(\d+)](\()""")

    /**
     * 解析 YRC 文本并直接映射为逐字歌词对象。
     *
     * @param yrcContent YRC 格式字符串
     * @return 逐字歌词行列表；空文本/无合法行时返回空列表（不抛异常）
     */
    fun parseToLyricItems(yrcContent: String): List<LyricItem> {
        if (yrcContent.isBlank()) return emptyList()

        val lines = runCatching {
            yrcContent.lineSequence()
                .mapNotNull { parseLine(it) }
                .toList()
        }.getOrElse { emptyList() }

        return lines.mapIndexed { index, line ->
            val words = line.words
            LyricItem.WordsLyric(
                words = words.map { word ->
                    LyricItem.WordsLyric.WordWithTiming(
                        content = word.text,
                        startTime = word.startTime,
                        endTime = maxOf(word.startTime, word.endTime),
                    )
                },
                translation = emptyList(),
                startTime = line.startTime,
                // 行时间覆盖整行，取行结束时间与最后一个字结束时间的较大值
                endTime = maxOf(line.endTime, words.lastOrNull()?.endTime ?: line.endTime),
                key = "${index}_${line.startTime}",
                content = line.text.takeIf { it.isNotBlank() },
            )
        }
    }

    /** 解析单行 YRC；不是合法 YRC 行时返回 null。 */
    private fun parseLine(line: String): YrcLine? {
        val trimmed = line.trim()
        val header = LINE_HEADER.find(trimmed) ?: return null
        val lineStartTime = header.groupValues[1].toLongOrNull() ?: return null
        val lineDuration = header.groupValues[2].toLongOrNull() ?: return null

        val words = mutableListOf<YrcWord>()
        // 从第一个 '(' 开始扫描逐字标记
        var currentIndex = header.groups[3]!!.range.first
        while (currentIndex < trimmed.length) {
            if (trimmed[currentIndex] != '(') {
                currentIndex++
                continue
            }
            val timeEnd = trimmed.indexOf(')', currentIndex)
            if (timeEnd == -1) break

            val times = trimmed.substring(currentIndex + 1, timeEnd).split(",")
            if (times.size < 2) break
            val startOffset = times[0].trim().toLongOrNull() ?: break
            val duration = times[1].trim().toLongOrNull() ?: break

            // 字文本：从 ')' 之后到下一个 '(' 之前
            val textStart = timeEnd + 1
            var textEnd = textStart
            while (textEnd < trimmed.length && trimmed[textEnd] != '(') {
                textEnd++
            }
            val text = trimmed.substring(textStart, textEnd)
            if (text.isNotEmpty()) {
                words += YrcWord(
                    text = text,
                    startTime = lineStartTime + startOffset,
                    duration = duration,
                )
            }
            currentIndex = textEnd
        }

        if (words.isEmpty()) return null
        return YrcLine(startTime = lineStartTime, duration = lineDuration, words = words)
    }

    /** 内部结构：YRC 行（不对外暴露，对外统一为 [LyricItem]）。 */
    private data class YrcLine(
        val startTime: Long,
        val duration: Long,
        val words: List<YrcWord>,
    ) {
        val endTime: Long get() = startTime + duration
        val text: String get() = words.joinToString(separator = "") { it.text }
    }

    /** 内部结构：YRC 逐字。 */
    private data class YrcWord(
        val text: String,
        val startTime: Long,
        val duration: Long,
    ) {
        val endTime: Long get() = startTime + duration
    }
}
