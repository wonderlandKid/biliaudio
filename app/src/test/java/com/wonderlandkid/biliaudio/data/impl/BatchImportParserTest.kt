package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ImportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量导入解析单测（格式规范：docs/IMPORT_FORMATS.md）。
 * 覆盖 JSON 数组 / CSV 5 列 / 文本块三种格式 + 兜底与错误处理。
 */
class BatchImportParserTest {

    private val parser = BatchImportParser()

    // ------------------------------------------------------------------
    // 格式 A：JSON 数组
    // ------------------------------------------------------------------

    @Test
    fun `JSON 数组解析专辑与分曲`() {
        val text = """
            [
              {
                "bvid": "BV1GJ411x7h7",
                "title": "专辑 A",
                "artist": "艺术家 A",
                "segments": [
                  { "start": "00:00", "name": "曲目一" },
                  { "start": "03:21", "name": "曲目二" },
                  { "start": "07:45.500", "name": "曲目三" }
                ]
              },
              {
                "url": "https://www.bilibili.com/video/BV1uv411q7Mv",
                "mode": "multipage"
              }
            ]
        """.trimIndent()

        val result = parser.parse(text)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.items.size)

        val first = result.items[0]
        assertEquals("BV1GJ411x7h7", first.bvidOrUrl)
        assertEquals("专辑 A", first.albumTitle)
        assertEquals("艺术家 A", first.artist)
        assertEquals(ImportMode.SINGLE_VIDEO, first.mode)
        assertEquals(3, first.segments.size)
        assertEquals(0L, first.segments[0].startMs)
        assertEquals("曲目一", first.segments[0].title)
        assertEquals(201_000L, first.segments[1].startMs)
        assertEquals(465_500L, first.segments[2].startMs)

        val second = result.items[1]
        assertEquals("https://www.bilibili.com/video/BV1uv411q7Mv", second.bvidOrUrl)
        assertEquals(ImportMode.MULTI_PAGE, second.mode)
        assertNull(second.albumTitle)
        assertTrue(second.segments.isEmpty())
    }

    @Test
    fun `JSON 数字秒数与空歌名`() {
        val text = """[{"bvid":"BV1GJ411x7h7","segments":[{"start":83},{"start":"1:02:03","name":"第三首"}]}]"""
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        val segments = result.items[0].segments
        assertEquals(83_000L, segments[0].startMs)
        assertEquals("曲目 1", segments[0].title)
        assertEquals(3_723_000L, segments[1].startMs)
        assertEquals("第三首", segments[1].title)
    }

    @Test
    fun `JSON 单个对象也接受`() {
        val text = """{"bvid":"BV1GJ411x7h7","title":"单张"}"""
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        assertEquals("单张", result.items[0].albumTitle)
    }

    @Test
    fun `JSON 单项失败不影响其他项`() {
        val text = """
            [
              { "bvid": "not-a-bv" },
              { "bvid": "BV1GJ411x7h7", "segments": [ { "start": "??" } ] },
              { "bvid": "BV1uv411q7Mv" }
            ]
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        assertEquals("BV1uv411q7Mv", result.items[0].bvidOrUrl)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.all { it.message.isNotBlank() })
    }

    // ------------------------------------------------------------------
    // 格式 B：CSV
    // ------------------------------------------------------------------

    @Test
    fun `CSV 同一 BV 多行合并为一张专辑`() {
        val text = """
            bvid,专辑名,艺术家,时间点,歌名
            BV1GJ411x7h7,专辑 A,艺术家 A,00:00,曲目一
            BV1GJ411x7h7,专辑 A,艺术家 A,03:21,
            BV1uv411q7Mv,,,0:00,开场
        """.trimIndent()

        val result = parser.parse(text)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.items.size)

        val first = result.items[0]
        assertEquals("专辑 A", first.albumTitle)
        assertEquals("艺术家 A", first.artist)
        assertEquals(2, first.segments.size)
        assertEquals("曲目一", first.segments[0].title)
        // 空歌名自动命名
        assertEquals("曲目 2", first.segments[1].title)
        assertEquals(201_000L, first.segments[1].startMs)

        val second = result.items[1]
        assertNull(second.albumTitle)
        assertNull(second.artist)
        assertEquals("开场", second.segments[0].title)
    }

    @Test
    fun `CSV 无表头也能解析`() {
        val text = "BV1GJ411x7h7,专辑 A,艺术家 A,00:00,曲目一"
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        assertEquals("专辑 A", result.items[0].albumTitle)
        assertEquals(1, result.items[0].segments.size)
    }

    @Test
    fun `CSV 支持引号包裹的逗号`() {
        val text = "BV1GJ411x7h7,\"专辑,带逗号\",\"艺术家, 也带\",00:00,\"歌,名\""
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        assertEquals("专辑,带逗号", result.items[0].albumTitle)
        assertEquals("艺术家, 也带", result.items[0].artist)
        assertEquals("歌,名", result.items[0].segments[0].title)
    }

    @Test
    fun `CSV 列数不足或时间点缺失报错`() {
        val text = """
            BV1GJ411x7h7,专辑 A,艺术家 A,00:00
            BV1uv411q7Mv,专辑 B,艺术家 B,,曲目二
        """.trimIndent()
        val result = parser.parse(text)
        assertTrue(result.items.isEmpty())
        assertEquals(2, result.errors.size)
    }

    // ------------------------------------------------------------------
    // 格式 C：文本块
    // ------------------------------------------------------------------

    @Test
    fun `文本块解析多张专辑与分曲`() {
        val text = """
            BV1GJ411x7h7 专辑 A
              00:00 曲目一
              03:21 曲目二

            BV1uv411q7Mv
              00:00 第一首
              01:02.500 第二首
        """.trimIndent()

        val result = parser.parse(text)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.items.size)

        val first = result.items[0]
        assertEquals("BV1GJ411x7h7", first.bvidOrUrl)
        assertEquals("专辑 A", first.albumTitle)
        assertEquals(2, first.segments.size)
        assertEquals(201_000L, first.segments[1].startMs)

        val second = result.items[1]
        assertEquals("BV1uv411q7Mv", second.bvidOrUrl)
        assertNull(second.albumTitle)
        assertEquals(2, second.segments.size)
        assertEquals(62_500L, second.segments[1].startMs)
    }

    @Test
    fun `文本块支持 av 号与列表符号`() {
        val text = """
            av80433022 专辑 B
              - 00:00 曲目一
              • 03:21 曲目二
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        assertEquals("av80433022", result.items[0].bvidOrUrl)
        assertEquals("专辑 B", result.items[0].albumTitle)
        assertEquals(2, result.items[0].segments.size)
    }

    @Test
    fun `文本块空行结束专辑块`() {
        val text = """
            BV1GJ411x7h7 专辑 A
              00:00 曲目一
            BV1uv411q7Mv 专辑 B
              00:00 仅一首
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(2, result.items.size)
        assertEquals(1, result.items[0].segments.size)
        assertEquals(1, result.items[1].segments.size)
    }

    @Test
    fun `文本块非法分曲行报错但不影响其他专辑`() {
        val text = """
            BV1GJ411x7h7 专辑 A
              这一行不是时间点
              00:00 曲目一
            not-a-bv 坏专辑
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.items.size)
        assertEquals("BV1GJ411x7h7", result.items[0].bvidOrUrl)
        assertEquals(2, result.errors.size)
    }

    // ------------------------------------------------------------------
    // 兜底 / 边界
    // ------------------------------------------------------------------

    @Test
    fun `空内容返回错误`() {
        val result = parser.parse("   \n  ")
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size)
        assertEquals("导入内容为空", result.errors[0].message)
    }

    @Test
    fun `无专辑头的分曲文本返回无法识别错误`() {
        val result = parser.parse("00:00 曲目一\n03:21 曲目二")
        assertTrue(result.items.isEmpty())
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `逐行兜底识别裸 BV 行`() {
        // 文本块会先识别为专辑；这里验证同样输入结果（无分曲单专辑）
        val result = parser.parse("BV1GJ411x7h7\nBV1uv411q7Mv 专辑 B")
        assertEquals(2, result.items.size)
        assertEquals("BV1uv411q7Mv", result.items[1].bvidOrUrl)
        assertEquals("专辑 B", result.items[1].albumTitle)
        assertTrue(result.items[0].segments.isEmpty())
    }

    @Test
    fun `无法识别的混合文本每行报错`() {
        val result = parser.parse("hello\nworld BV-not-valid")
        assertTrue(result.items.isEmpty())
        assertEquals(2, result.errors.size)
    }
}
