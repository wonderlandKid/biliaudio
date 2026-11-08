package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveCover
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.ArchiveSettingsDocument
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveBundleCodecTest {
    private val libraryCodec = LibraryArchiveCodec()
    private val bundleCodec = ArchiveBundleCodec(libraryCodec)

    @Test
    fun `build and parse bundle splits library settings covers`() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val hash = LibraryArchiveCodec.sha256Hex(cover)
        val library = LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = listOf(
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    page = 1,
                    title = "专辑",
                    durationMs = 100_000,
                    cover = ArchiveCover(hash = hash, mime = "image/jpeg", byteLength = cover.size),
                    segments = listOf(ArchiveSegment(startMs = 0, endMs = 100_000, title = "曲")),
                ),
            ),
        )
        val entries = bundleCodec.buildEntries(
            library = library,
            settings = ArchiveSettingsDocument(darkTheme = true),
            playlists = null,
            coverBytes = mapOf(hash to cover),
        )
        assertTrue(entries.any { it.relativePath == ArchiveBundleCodec.MANIFEST_FILE })
        assertTrue(entries.any { it.relativePath == ArchiveBundleCodec.LIBRARY_FILE })
        assertTrue(entries.any { it.relativePath == ArchiveBundleCodec.SETTINGS_FILE })
        assertTrue(entries.any { it.relativePath == "covers/$hash.jpg" })

        val parsed = bundleCodec.parseEntries(entries)
        assertEquals(1, parsed.library.albums.size)
        assertEquals(true, parsed.settings?.darkTheme)
        assertNotNull(parsed.covers[hash])
    }

    @Test
    fun `rejects non library bundle and missing manifest`() {
        val error = runCatching { bundleCodec.parseEntries(emptyList()) }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("manifest"))
    }

    @Test
    fun `rejects corrupted library file hash`() {
        val library = LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = listOf(ArchiveAlbum(bvid = "BV1GJ411x7h7")),
        )
        val entries = bundleCodec.buildEntries(library, null, null, emptyMap())
            .map { if (it.relativePath == ArchiveBundleCodec.LIBRARY_FILE) it.copy(bytes = it.bytes + byteArrayOf(1)) else it }
        val error = runCatching { bundleCodec.parseEntries(entries) }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("大小"))
    }

    @Test
    fun `rejects bundle without cover file`() {
        val cover = byteArrayOf(1, 2, 3)
        val hash = LibraryArchiveCodec.sha256Hex(cover)
        val library = LibraryArchive.create(
            kind = ArchiveKind.LIBRARY,
            albums = listOf(
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    cover = ArchiveCover(hash = hash, mime = "image/jpeg", byteLength = cover.size),
                ),
            ),
        )
        val error = runCatching {
            bundleCodec.buildEntries(library, null, null, coverBytes = emptyMap())
        }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)
        assertTrue(error!!.message!!.contains("封面"))
    }
}
