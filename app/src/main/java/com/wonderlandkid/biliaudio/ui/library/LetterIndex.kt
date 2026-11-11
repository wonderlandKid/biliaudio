package com.wonderlandkid.biliaudio.ui.library

import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import android.icu.util.ULocale

/** 「# A B C …」快速索引栏中的一个字母分组。 */
data class LetterSection<T>(
    /** 分组标签：`#` 或 `A`..`Z` */
    val label: String,
    /** 该分组下的条目（已按「标签顺序 + 名称拼音顺序」排好序） */
    val items: List<T>,
)

/**
 * 名称首字母分组工具：给歌曲 / 专辑 / 艺术家列表做「# / A..Z」分组与排序。
 *
 * - 分组标签用 [AlphabeticIndex]（API 24+）按中文拼音归桶：`周杰伦 → Z`、`方大同 → F`；
 *   数字/符号等归下溢出桶 → `#`；
 * - 排序用 [AlphabeticIndex] 自带的 [Collator]（中文拼音序），保证分组内顺序稳定；
 * - 任何 ICU 异常（数据缺失、极端字符）都回退到「首字母 A-Z，其余 #」，保证 UI 不崩。
 *
 * 只在 UI 主线程使用（[collator] 非线程安全）。
 */
object LetterIndex {

    /** 数字 / 符号等无法归入 A-Z 的标签。 */
    const val OTHER_LABEL = "#"

    /** 索引栏完整标签顺序：`#` 在前，随后 A..Z。 */
    val ALL_LABELS: List<String> = listOf(OTHER_LABEL) + ('A'..'Z').map(Char::toString)

    /**
     * ICU 不可变索引（懒加载一次）。
     *
     * 注意：**不要**调用 `setMaxLabelCount()`，否则 ICU 会改用「均匀分布」标签集，
     * 中文拼音桶会出现 `周杰伦 → Y` 这类错位（实测 ICU 77）。
     */
    private val immutableIndex: AlphabeticIndex.ImmutableIndex<String>? by lazy {
        runCatching {
            AlphabeticIndex<String>(ULocale.SIMPLIFIED_CHINESE).buildImmutableIndex()
        }.getOrNull()
    }

    /** 中文拼音排序 Collator；初始化失败返回 null（走简单字符串比较兜底）。 */
    private val collator: Collator? by lazy {
        runCatching { Collator.getInstance(ULocale.SIMPLIFIED_CHINESE) }.getOrNull()
    }

    /**
     * 把 [items] 按 [nameOf] 的名称分组并排序。
     *
     * 返回的列表顺序 = `#` → `A` → … → `Z`，只包含实际有数据的标签。
     */
    fun <T> sections(items: List<T>, nameOf: (T) -> String): List<LetterSection<T>> {
        if (items.isEmpty()) return emptyList()
        val labeled = items.map { item -> item to letterOf(nameOf(item)) }
        val sorted = labeled.sortedWith { first, second ->
            val byLabel = letterOrder(first.second).compareTo(letterOrder(second.second))
            if (byLabel != 0) byLabel else compareName(nameOf(first.first), nameOf(second.first))
        }
        return sorted
            .groupBy(keySelector = { pair -> pair.second }, valueTransform = { pair -> pair.first })
            .map { (label, sectionItems) -> LetterSection(label = label, items = sectionItems) }
    }

    /** 标题开头的装饰符号（B 站标题常见「【官方 MV】」前缀）：分桶时跳过，避免全部落到 `#`。 */
    private val LEADING_DECORATIONS = setOf(
        '【', '[', '（', '(', '《', '〈', '「', '『', '“', '\'', '<',
        '#', '·', '—', '-',
    )

    /** 单个名称的索引标签：`#` 或 `A`..`Z`。 */
    fun letterOf(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return OTHER_LABEL
        // 去掉开头的装饰括号/引号再取首字母：「【黑胶试听合辑】周杰伦」→「黑」(H) 而不是 #
        val name = trimmed.trimStart { char -> char.isWhitespace() || char in LEADING_DECORATIONS }
            .ifEmpty { trimmed }
        val index = immutableIndex ?: return fallbackLetter(name)
        return runCatching {
            val bucket = index.getBucket(index.getBucketIndex(name))
            if (bucket.labelType != AlphabeticIndex.Bucket.LabelType.NORMAL) {
                OTHER_LABEL
            } else {
                val label = bucket.label
                if (label.length == 1 && label[0] in 'A'..'Z') label else fallbackLetter(name)
            }
        }.getOrElse { fallbackLetter(name) }
    }

    /** 兜底字母：首字符是 ASCII 字母 → 大写，否则 `#`。 */
    fun fallbackLetter(name: String): String {
        val first = name.trim().firstOrNull() ?: return OTHER_LABEL
        return when (first) {
            in 'A'..'Z' -> first.toString()
            in 'a'..'z' -> first.uppercaseChar().toString()
            else -> OTHER_LABEL
        }
    }

    /** 标签在索引栏中的顺序（`#`=0，A=1 … Z=26；未知标签排最后）。 */
    fun letterOrder(label: String): Int {
        val index = ALL_LABELS.indexOf(label)
        return if (index >= 0) index else ALL_LABELS.size
    }

    private fun compareName(first: String, second: String): Int {
        val current = collator
        return if (current != null) {
            current.compare(first, second)
        } else {
            // Collator 初始化失败时的兜底：忽略大小写的字符串比较
            first.lowercase().compareTo(second.lowercase())
        }
    }
}

// ---------------------------------------------------------------------------
// Lazy 列表下标工具：用于「索引栏点击/拖动 → 滚动到分组」以及反查当前分组
//
// v0.33.0 起音乐库列表不再渲染字母分隔标题（LetterHeader），Lazy 列表里每个分组
// 只占 items.size 个条目 → 下面所有下标计算都不再 +1。
// ---------------------------------------------------------------------------

/**
 * 计算每个分组在 Lazy 列表中的起始下标（列表不含分组标题 item，每组 = items.size 个条目）。
 *
 * @param prefixItems 列表顶部固定 item 数（如歌曲列表顶部的「全部歌曲 / 播放全部」行）
 */
/** 根据 Lazy 列表当前首个可见下标反推它属于哪个分组标签（不在任何分组时返回 null）。 */
fun <T> List<LetterSection<T>>.labelAt(flatIndex: Int, prefixItems: Int = 0): String? {
    var index = prefixItems
    for (section in this) {
        val end = index + section.items.size
        if (flatIndex < end) return section.label
        index = end
    }
    return null
}

/**
 * 索引栏选中 [label] 时应该滚动到的列表下标：
 * 取「标签顺序 >= label 的第一个分组」；若选中的字母没有分组，则落到下一组 / 最后一组。
 *
 * 列表不含分组标题 item，每个分组第一个条目的下标 = 之前所有分组的条目数之和。
 */
fun <T> List<LetterSection<T>>.scrollTargetFor(label: String, prefixItems: Int = 0): Int {
    if (isEmpty()) return prefixItems
    val wanted = LetterIndex.letterOrder(label)
    var index = prefixItems
    for (section in this) {
        if (LetterIndex.letterOrder(section.label) >= wanted) return index
        index += section.items.size
    }
    return index - last().items.size
}
