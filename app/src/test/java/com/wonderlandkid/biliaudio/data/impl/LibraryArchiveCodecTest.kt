package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveCover
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryArchiveCodecTest {
    private val codec = LibraryArchiveCodec()

    @Test
    fun `parses v2 archive and derives albumKey`() {
        val parsed = codec.parse(
            """
            {
              "format": "biliaudio-library",
              "version": 2,
              "minReaderVersion": 2,
              "kind": "album",
              "albums": [{
                "source": "bilibili",
                "bvid": "BV1GJ411x7h7",
                "page": 2,
                "pageCount": 3,
                "title": "专辑",
                "artist": "艺术家",
                "durationMs": 213000,
                "segments": [
                  { "id": "a", "startMs": 0, "endMs": 45000, "title": "一" },
                  { "id": "b", "startMs": 45000, "endMs": 213000, "title": "二" }
                ]
              }]
            }
            """.trimIndent(),
        )
        val album = parsed.archive.albums.single()
        assertEquals(ArchiveKind.ALBUM, parsed.archive.kind)
        assertEquals("bilibili:BV1GJ411x7h7#2", album.albumKey)
        assertEquals(2, album.page)
        assertEquals(2, album.segments?.size)
        assertEquals(45_000L, album.segments!![0].endMs)
        assertEquals("a", album.segments!![0].id)
    }

    @Test
    fun `encode round trip keeps v2 fields`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sha = LibraryArchiveCodec.sha256Hex(bytes)
        val archive = LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = listOf(
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    page = 1,
                    title = "专辑",
                    durationMs = 100_000,
                    cover = ArchiveCover(
                        mime = "image/jpeg",
                        sha256 = sha,
                        byteLength = bytes.size,
                        dataBase64 = okio.ByteString.of(*bytes).base64(),
                    ),
                    segments = listOf(ArchiveSegment(id = "tid", startMs = 0, endMs = 100_000, title = "曲")),
                ),
            ),
        )
        val reparsed = codec.parse(codec.encode(archive)).archive
        val album = reparsed.albums.single()
        assertEquals("bilibili:BV1GJ411x7h7#1", album.albumKey)
        assertEquals(bytes.size, album.cover?.byteLength)
        assertEquals(sha, album.cover?.sha256)
        assertEquals("tid", album.segments?.single()?.id)
        assertEquals(2, reparsed.version)
    }

    @Test
    fun `rejects v1 archive`() {
        val error = runCatching {
            codec.parse("""{"format":"biliaudio-library","version":1,"albums":[]}""")
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("旧版"))
    }

    @Test
    fun `rejects higher version`() {
        val error = runCatching {
            codec.parse("""{"format":"biliaudio-library","version":3,"albums":[]}""")
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("高于"))
    }

    @Test
    fun `rejects unknown feature`() {
        val error = runCatching {
            codec.parse(
                """{"format":"biliaudio-library","version":2,"features":["future-data"],"albums":[]}""",
            )
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("不支持的数据特性"))
    }

    @Test
    fun `index kind forbids albums but allows settings`() {
        val parsed = codec.parse(
            """
            {"format":"biliaudio-library","version":2,"kind":"index",
             "settings":{"version":1,"darkTheme":true},"albums":[]}
            """.trimIndent(),
        )
        assertEquals(ArchiveKind.INDEX, parsed.archive.kind)
        assertEquals(true, parsed.archive.settings?.darkTheme)

        val error = runCatching {
            codec.parse(
                """
                {"format":"biliaudio-library","version":2,"kind":"index",
                 "albums":[{"bvid":"BV1GJ411x7h7"}]}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
    }

    @Test
    fun `album and segments kind require exactly one album`() {
        listOf("album", "segments").forEach { kind ->
            val error = runCatching {
                codec.parse("""{"format":"biliaudio-library","version":2,"kind":"$kind","albums":[]}""")
            }.exceptionOrNull()
            assertTrue("$kind: $error", error is ArchiveFormatException)
        }
    }

    @Test
    fun `rejects duplicate albumKey`() {
        val error = runCatching {
            codec.parse(
                """
                {"format":"biliaudio-library","version":2,"albums":[
                  {"bvid":"BV1GJ411x7h7","page":1},
                  {"bvid":"BV1GJ411x7h7","page":1}
                ]}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("重复"))
    }

    @Test
    fun `rejects overlapping explicit endMs`() {
        val error = runCatching {
            codec.parse(
                """
                {"format":"biliaudio-library","version":2,"kind":"album","albums":[{
                  "bvid":"BV1GJ411x7h7","durationMs":100000,
                  "segments":[
                    {"startMs":0,"endMs":60000},
                    {"startMs":50000,"endMs":100000}
                  ]
                }]}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
    }

    @Test
    fun `rejects cover hash mismatch`() {
        val bytes = byteArrayOf(1, 2, 3)
        val wrongSha = "0".repeat(64)
        val error = runCatching {
            codec.parse(
                """
                {"format":"biliaudio-library","version":2,"kind":"album","albums":[{
                  "bvid":"BV1GJ411x7h7",
                  "cover":{"mime":"image/jpeg","sha256":"$wrongSha","byteLength":3,
                           "dataBase64":"${okio.ByteString.of(*bytes).base64()}"}
                }]}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("sha256"))
    }

    @Test
    fun `blank segments field means keep and empty array means clear`() {
        val keep = codec.parse(
            """{"format":"biliaudio-library","version":2,"kind":"album","albums":[{"bvid":"BV1GJ411x7h7"}]}""",
        ).archive.albums.single()
        assertNull(keep.segments)

        val clear = codec.parse(
            """{"format":"biliaudio-library","version":2,"kind":"album",
               "albums":[{"bvid":"BV1GJ411x7h7","segments":[]}]}""",
        ).archive.albums.single()
        assertTrue(clear.segments != null && clear.segments!!.isEmpty())
    }

    @Test
    fun `legacy text block normalized to v2 archive`() {
        val parsed = codec.parse(
            """
            BV1GJ411x7h7 某专辑
              00:00 第一首
              01:30 第二首
            """.trimIndent(),
        )
        assertEquals(ArchiveKind.LIBRARY, parsed.archive.kind)
        val album = parsed.archive.albums.single()
        assertEquals("bilibili:BV1GJ411x7h7#1", album.albumKey)
        assertEquals(2, album.segments?.size)
    }

    @Test
    fun `sample segment json is parseable`() {
        val parsed = codec.parse(codec.sampleSegmentJson())
        assertEquals(ArchiveKind.SEGMENTS, parsed.archive.kind)
        assertEquals(1, parsed.archive.albums.size)
        assertNotNull(parsed.archive.albums.single().albumKey)
    }

    @Test
    fun `rejects invalid bvid and page mismatch`() {
        val error = runCatching {
            codec.parse(
                """{"format":"biliaudio-library","version":2,"albums":[{"bvid":"BVbad"}]}""",
            )
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)

        val pageError = runCatching {
            codec.parse(
                """{"format":"biliaudio-library","version":2,"albums":[
                  {"bvid":"BV1GJ411x7h7","page":3,"pageCount":2}
                ]}""".trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(pageError is ArchiveFormatException)
    }
}
