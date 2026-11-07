package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_FORMAT
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_VERSION
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryArchiveCodecTest {

    private val codec = LibraryArchiveCodec()

    // ------------------------------------------------------------------
    // 新归档格式
    // ------------------------------------------------------------------

    @Test
    fun `parses full archive and normalizes segments`() {
        val text = """
            {
              "format": "biliaudio-library",
              "version": 1,
              "exportedAt": "2026-11-07T22:30:00+08:00",
              "settings": {"audioQuality": "HIGH", "darkTheme": true},
              "albums": [
                {
                  "bvid": "BV1GJ411x7h7",
                  "page": 1,
                  "cid": 137649199,
                  "title": "专辑名",
                  "artist": "艺术家",
                  "coverUrl": "https://i1.hdslb.com/a.jpg",
                  "durationMs": 213000,
                  "playCount": 12,
                  "lastPlayedAt": 1794051200000,
                  "segments": [
                    {"start": "00:00", "name": "曲目一"},
                    {"startMs": 45000, "title": "曲目二"},
                    {"start": "02:00", "name": "曲目三"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = codec.parse(text)
        assertEquals("biliaudio-library", result.recognizedAs)
        assertTrue(result.errors.isEmpty())
        val archive = result.archive
        assertEquals(LIBRARY_ARCHIVE_FORMAT, archive.format)
        assertEquals(LIBRARY_ARCHIVE_VERSION, archive.version)
        assertEquals("HIGH", archive.settings?.audioQuality)
        assertEquals(true, archive.settings?.darkTheme)

        val album = archive.albums.single()
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals(1, album.page)
        assertEquals(137649199L, album.cid)
        assertEquals("专辑名", album.title)
        assertEquals("艺术家", album.artist)
        assertEquals(213000L, album.durationMs)
        assertEquals(12, album.playCount)

        assertEquals(listOf(0L, 45_000L, 120_000L), album.segments.map { it.startMs })
        assertEquals(listOf("曲目一", "曲目二", "曲目三"), album.segments.map { it.title })
        // 归一化后不再保留手写字段
        assertTrue(album.segments.all { it.start == null && it.name == null })
    }

    @Test
    fun `parses minimal segments file`() {
        val text = """
            {
              "format": "biliaudio-library",
              "version": 1,
              "albums": [
                {"bvid": "BV1GJ411x7h7", "segments": [{"start": "01:23.500", "name": "只写分割点"}]}
              ]
            }
        """.trimIndent()

        val album = codec.parse(text).archive.albums.single()
        assertEquals(83_500L, album.segments.single().startMs)
        assertEquals("只写分割点", album.segments.single().title)
        assertEquals(1, album.page)
        assertNull(album.title)
        assertNull(album.durationMs)
    }

    @Test
    fun `allows url instead of bvid`() {
        val text = """
            {
              "format": "biliaudio-library",
              "version": 1,
              "albums": [
                {"url": "https://www.bilibili.com/video/BV1GJ411x7h7", "segments": [{"startMs": 0, "title": "A"}]}
              ]
            }
        """.trimIndent()

        val album = codec.parse(text).archive.albums.single()
        assertNull(album.bvid)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", album.url)
    }

    @Test
    fun `rejects higher archive version`() {
        val text = """{"format":"biliaudio-library","version":99,"albums":[]}"""
        val error = expectFailure(text)
        assertTrue(error.contains("版本"))
    }

    @Test
    fun `rejects missing bvid and url`() {
        val text = """{"format":"biliaudio-library","version":1,"albums":[{"title":"x"}]}"""
        assertTrue(expectFailure(text).contains("缺少 bvid"))
    }

    @Test
    fun `rejects invalid bvid`() {
        val text = """{"format":"biliaudio-library","version":1,"albums":[{"bvid":"BV123"}]}"""
        assertTrue(expectFailure(text).contains("BV 号格式"))
    }

    @Test
    fun `rejects non increasing segments`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","segments":[{"startMs":5000},{"startMs":5000}]}
            ]}
        """.trimIndent()
        assertTrue(expectFailure(text).contains("严格递增"))
    }

    @Test
    fun `rejects segment beyond duration`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","durationMs":10000,"segments":[{"startMs":12000}]}
            ]}
        """.trimIndent()
        assertTrue(expectFailure(text).contains("超出专辑时长"))
    }

    @Test
    fun `rejects negative start`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","segments":[{"startMs":-1}]}
            ]}
        """.trimIndent()
        assertTrue(expectFailure(text).contains("不能为负"))
    }

    @Test
    fun `rejects duplicate album key`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","page":1},
              {"bvid":"BV1GJ411x7h7","page":1}
            ]}
        """.trimIndent()
        assertTrue(expectFailure(text).contains("重复专辑"))
    }

    @Test
    fun `rejects invalid cover url scheme`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","coverUrl":"file:///tmp/a.jpg"}
            ]}
        """.trimIndent()
        assertTrue(expectFailure(text).contains("http"))
    }

    @Test
    fun `returns empty album list for empty library`() {
        val text = """{"format":"biliaudio-library","version":1,"albums":[]}"""
        val result = codec.parse(text)
        assertTrue(result.archive.albums.isEmpty())
    }

    @Test
    fun `rejects blank content`() {
        assertTrue(expectFailure("   ").contains("为空"))
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    @Test
    fun `encode round trips through parse`() {
        val album = ArchiveAlbum(
            bvid = "BV1GJ411x7h7",
            page = 1,
            cid = 137649199L,
            title = "专辑名",
            artist = "艺术家",
            coverUrl = "https://i1.hdslb.com/a.jpg",
            durationMs = 213000L,
            createdAt = 1794046700000L,
            lastPlayedAt = 1794051200000L,
            playCount = 12,
            segments = listOf(
                ArchiveSegment(startMs = 0, title = "曲目一"),
                ArchiveSegment(startMs = 45_000, title = "曲目二"),
            ),
        )
        val archive = LibraryArchive.create(
            albums = listOf(album),
            settings = null,
            exportedAt = "2026-11-07T22:30:00+0800",
        )

        val json = codec.encode(archive)
        assertTrue(json.contains("\"format\": \"biliaudio-library\""))
        assertFalse(json.contains("null"))
        assertFalse(json.contains("\"start\":"))

        val parsed = codec.parse(json).archive
        val roundTrip = parsed.albums.single()
        assertEquals(album.bvid, roundTrip.bvid)
        assertEquals(album.cid, roundTrip.cid)
        assertEquals(album.title, roundTrip.title)
        assertEquals(album.durationMs, roundTrip.durationMs)
        assertEquals(album.playCount, roundTrip.playCount)
        assertEquals(album.lastPlayedAt, roundTrip.lastPlayedAt)
        assertEquals(album.segments.map { it.startMs }, roundTrip.segments.map { it.startMs })
        assertEquals(album.segments.map { it.title }, roundTrip.segments.map { it.title })
    }

    @Test
    fun `sample segment json is parseable`() {
        val result = codec.parse(codec.sampleSegmentJson())
        assertEquals("biliaudio-library", result.recognizedAs)
        val album = result.archive.albums.single()
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals(3, album.segments.size)
        assertEquals(listOf(0L, 45_000L, 120_000L), album.segments.map { it.startMs })
    }

    // ------------------------------------------------------------------
    // 旧格式兼容
    // ------------------------------------------------------------------

    @Test
    fun `legacy json array is normalized`() {
        val text = """
            [
              {"bvid":"BV1GJ411x7h7","title":"旧格式专辑","artist":"UP主",
               "segments":[{"start":"00:00","name":"一"},{"start":"01:00","name":"二"}]}
            ]
        """.trimIndent()

        val result = codec.parse(text)
        assertEquals("legacy-batch", result.recognizedAs)
        val album = result.archive.albums.single()
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals("旧格式专辑", album.title)
        assertEquals(2, album.segments.size)
        assertEquals(60_000L, album.segments[1].startMs)
    }

    @Test
    fun `legacy single json object is treated as album`() {
        val text = """{"bvid":"BV1GJ411x7h7","segments":[{"start":"00:00","title":"A"}]}"""
        val result = codec.parse(text)
        assertEquals("legacy-batch", result.recognizedAs)
        assertEquals("BV1GJ411x7h7", result.archive.albums.single().bvid)
    }

    @Test
    fun `legacy text block is normalized`() {
        val text = """
            BV1GJ411x7h7 评论区分割点
              00:00 曲目一
              03:21 曲目二
        """.trimIndent()
        val result = codec.parse(text)
        assertEquals("legacy-batch", result.recognizedAs)
        val album = result.archive.albums.single()
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals("评论区分割点", album.title)
        assertEquals(listOf(0L, 201_000L), album.segments.map { it.startMs })
    }

    @Test
    fun `legacy csv is normalized`() {
        val text = """
            BV1GJ411x7h7,专辑名,艺术家,00:00,曲目一
            BV1GJ411x7h7,专辑名,艺术家,01:30,曲目二
        """.trimIndent()
        val result = codec.parse(text)
        assertEquals("legacy-batch", result.recognizedAs)
        val album = result.archive.albums.single()
        assertEquals(2, album.segments.size)
        assertEquals(90_000L, album.segments[1].startMs)
    }

    @Test
    fun `legacy av number keeps raw input for later resolution`() {
        val text = """
            av170001 旧视频
              00:00 一
        """.trimIndent()
        val result = codec.parse(text)
        val album = result.archive.albums.single()
        assertNull(album.bvid)
        assertNotNull(album.url)
        assertTrue(album.url!!.contains("av170001"))
    }

    // ------------------------------------------------------------------
    // 自定义封面 coverBase64（v0.5.0）
    // ------------------------------------------------------------------

    /** 测试用 B 站风格 base64（JPEG 字节的 base64 文本，导出/导入只做透传，不校验图片内容）。 */
    private val coverBase64Fixture = java.util.Base64.getEncoder()
        .encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte()))

    @Test
    fun `coverBase64 survives encode parse roundtrip`() {
        val archive = LibraryArchive.create(
            albums = listOf(
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    title = "带自定义封面的专辑",
                    segments = listOf(ArchiveSegment(startMs = 0, title = "曲目一")),
                    coverBase64 = coverBase64Fixture,
                ),
            ),
        )

        val json = codec.encode(archive)
        assertTrue("导出 JSON 应包含 coverBase64 字段", json.contains("\"coverBase64\""))

        val parsed = codec.parse(json).archive.albums.single()
        assertEquals(coverBase64Fixture, parsed.coverBase64)
    }

    @Test
    fun `handwritten archive with coverBase64 is parsed`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","title":"手写封面","coverBase64":"$coverBase64Fixture",
               "segments":[{"startMs":0,"title":"一"}]}
            ]}
        """.trimIndent()

        val result = codec.parse(text)
        assertEquals("biliaudio-library", result.recognizedAs)
        assertEquals(coverBase64Fixture, result.archive.albums.single().coverBase64)
    }

    @Test
    fun `blank coverBase64 is normalized to null`() {
        val text = """
            {"format":"biliaudio-library","version":1,"albums":[
              {"bvid":"BV1GJ411x7h7","coverBase64":"   "}
            ]}
        """.trimIndent()

        assertNull(codec.parse(text).archive.albums.single().coverBase64)
    }

    // ------------------------------------------------------------------

    private fun expectFailure(text: String): String {
        return try {
            codec.parse(text)
            fail("应当抛出 ArchiveFormatException")
            ""
        } catch (e: ArchiveFormatException) {
            e.message.orEmpty()
        }
    }
}
