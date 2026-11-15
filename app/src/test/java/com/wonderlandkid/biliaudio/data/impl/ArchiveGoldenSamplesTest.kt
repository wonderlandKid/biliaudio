package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.ArchiveManifest
import com.wonderlandkid.biliaudio.data.model.ArchiveSettingsDocument
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.model.PlaylistsArchive
import com.wonderlandkid.biliaudio.data.model.UserDataBackup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 归档/备份 JSON 冻结守卫：docs/samples/ 下的样例必须是"当前 schema 的黄金样本"。
 *
 * 这些样例同时是给人看的格式说明，改了 schema 却没同步样例/文档时，本测试会失败。
 * 新增字段时：更新样例 + 文档字段表 + 保持向后兼容（旧读者忽略未知字段）。
 */
class ArchiveGoldenSamplesTest {

    private val samplesDir: File = listOf(
        File("../docs/samples"),
        File("docs/samples"),
        File("app/src/../docs/samples"),
    ).firstOrNull { it.isDirectory } ?: error("找不到 docs/samples 目录（单元测试工作目录异常）")

    private fun sample(name: String): String = File(samplesDir, name).readText()

    private val json: Json = ArchiveBundleCodec.prettyJson
    private val libraryCodec = LibraryArchiveCodec()

    @Test
    fun `library v3 golden sample parses and round-trips`() {
        val parsed = libraryCodec.parse(sample("biliaudio-library-full.sample.json"))
        assertEquals("biliaudio-v3", parsed.recognizedAs)
        assertEquals(ArchiveKind.LIBRARY, parsed.archive.kind)
        assertEquals(1, parsed.archive.albums.size)

        val album = parsed.archive.albums.single()
        assertEquals("bilibili:BV1GJ411x7h7#1", album.albumKey)
        assertEquals(3, album.segments?.size)
        assertNotNull("样例应包含导出元数据 archiveId", parsed.archive.archiveId)
        assertNotNull("样例应包含 exportedAt", parsed.archive.exportedAt)

        // 冻结守卫：读 → 写 → 再读，结构必须完全一致
        val encoded = libraryCodec.encode(parsed.archive)
        val reparsed = libraryCodec.parse(encoded)
        assertEquals(parsed.archive, reparsed.archive)
        assertTrue(encoded.contains("\"format\": \"biliaudio-library\""))
        assertTrue(encoded.contains("\"version\": 3"))
        assertTrue(encoded.contains("\"minReaderVersion\": 3"))
    }

    @Test
    fun `library albums use canonical albumKey order on encode`() {
        val sample = libraryCodec.parse(sample("biliaudio-library-full.sample.json")).archive
        val reversed = sample.copy(
            albums = sample.albums + sample.albums.map { it.copy(albumKey = "bilibili:BV1GJ411x7h8#1", bvid = "BV1GJ411x7h8") },
        ).let { it.copy(albums = it.albums.reversed()) }

        val encoded = libraryCodec.encode(reversed)
        val keys = libraryCodec.parse(encoded).archive.albums.map { it.albumKey }
        assertEquals(listOf("bilibili:BV1GJ411x7h7#1", "bilibili:BV1GJ411x7h8#1"), keys)
    }

    @Test
    fun `segments golden sample parses and round-trips`() {
        val parsed = libraryCodec.parse(sample("biliaudio-segments.sample.json"))
        assertEquals(ArchiveKind.SEGMENTS, parsed.archive.kind)
        val album = parsed.archive.albums.single()
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals(3, album.segments?.size)
        assertEquals(0L, album.segments?.first()?.startMs)

        val reparsed = libraryCodec.parse(libraryCodec.encode(parsed.archive))
        assertEquals(parsed.archive, reparsed.archive)
    }

    @Test
    fun `archive manifest golden sample decodes and round-trips`() {
        val manifest = json.decodeFromString(ArchiveManifest.serializer(), sample("biliaudio-archive-manifest.sample.json"))
        assertEquals("biliaudio-archive", manifest.format)
        assertEquals(1, manifest.version)
        assertEquals("library", manifest.kind)
        assertTrue(manifest.files.isNotEmpty())

        val reparsed = json.decodeFromString(ArchiveManifest.serializer(), json.encodeToString(ArchiveManifest.serializer(), manifest))
        assertEquals(manifest, reparsed)
    }

    @Test
    fun `settings golden sample decodes and round-trips`() {
        val doc = json.decodeFromString(ArchiveSettingsDocument.serializer(), sample("biliaudio-settings.sample.json"))
        assertEquals("biliaudio-settings", doc.format)
        assertEquals(1, doc.version)
        val reparsed = json.decodeFromString(ArchiveSettingsDocument.serializer(), json.encodeToString(ArchiveSettingsDocument.serializer(), doc))
        assertEquals(doc, reparsed)
    }

    @Test
    fun `playlists golden sample uses albumKey plus startMs as primary key`() {
        val doc = json.decodeFromString(PlaylistsArchive.serializer(), sample("biliaudio-playlists.sample.json"))
        assertEquals("biliaudio-playlists", doc.format)
        val items = doc.playlists.single().items
        assertEquals(2, items.size)
        // 主键字段必须存在；trackId 只是可选辅助
        assertEquals("bilibili:BV1GJ411x7h7#1", items[0].albumKey)
        assertEquals(0L, items[0].startMs)
        assertEquals(120_000L, items[1].startMs)
        assertNull("第二个条目故意不给 trackId，验证可选性", items[1].trackId)

        val reparsed = json.decodeFromString(PlaylistsArchive.serializer(), json.encodeToString(PlaylistsArchive.serializer(), doc))
        assertEquals(doc, reparsed)
    }

    @Test
    fun `user data golden sample decodes and round-trips`() {
        val backup = json.decodeFromString(UserDataBackup.serializer(), sample("biliaudio-userdata.sample.json"))
        assertEquals("biliaudio-user-data", backup.format)
        assertEquals(1, backup.version)
        assertEquals(1, backup.minReaderVersion)
        assertEquals(1, backup.playlists.size)
        assertEquals(1, backup.stats.size)

        val encoded = json.encodeToString(UserDataBackup.serializer(), backup)
        assertTrue(encoded.contains("\"minReaderVersion\": 1"))
        val reparsed = json.decodeFromString(UserDataBackup.serializer(), encoded)
        assertEquals(backup, reparsed)
    }

    @Test
    fun `old user data backup without minReaderVersion is still readable`() {
        val old = """{"format":"biliaudio-user-data","version":1,"exportedAt":0,"playlists":[],"stats":[]}"""
        val backup = json.decodeFromString(UserDataBackup.serializer(), old)
        assertEquals(1, backup.minReaderVersion)
    }

    @Test
    fun `unknown future fields are ignored`() {
        val text = sample("biliaudio-library-full.sample.json")
            .replaceFirst("\"version\": 3,", "\"version\": 3,\n    \"futureField\": {\"a\": 1},")
        val parsed = libraryCodec.parse(text)
        assertEquals(1, parsed.archive.albums.size)

        val userText = sample("biliaudio-userdata.sample.json")
            .replaceFirst("\"version\": 1,", "\"version\": 1,\n    \"futureTracking\": true,")
        val backup = json.decodeFromString(UserDataBackup.serializer(), userText)
        assertEquals(1, backup.playlists.size)
    }
}
