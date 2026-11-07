package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.AlbumWithTracks
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.ArchiveAlbum
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveItemAction
import com.wonderlandkid.biliaudio.data.model.ArchiveSegment
import com.wonderlandkid.biliaudio.data.model.ArchiveSettings
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.BatchImportReport
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.CoverCandidate
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.model.SegmentSpec
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumCoverRepository
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LibraryArchiveRepositoryTest {

    private val codec = LibraryArchiveCodec()

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    @Test
    fun `export all includes albums tracks and settings`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑A", playCount = 7), tracks = tracks(1, 2, 2))
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAudioQuality(AudioQualityPreference.HIGH)
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), settingsRepo)

        val json = repo.exportAllJson()
        val archive = codec.parse(json).archive

        assertEquals(1, archive.albums.size)
        assertEquals("专辑A", archive.albums.single().title)
        assertEquals(2, archive.albums.single().segments.size)
        assertEquals("HIGH", archive.settings?.audioQuality)
    }

    @Test
    fun `export album returns null for missing album`() = runBlocking {
        val repo = LibraryArchiveRepositoryImpl(null, FakeAlbumRepository(), FakeBiliRepository(), FakeSettingsRepository())
        assertNull(repo.exportAlbumJson(999L))
    }

    // ------------------------------------------------------------------
    // 合并导入
    // ------------------------------------------------------------------

    @Test
    fun `merge creates new album`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), FakeSettingsRepository())
        val text = archiveText(
            ArchiveAlbum(
                bvid = "BV1GJ411x7h7",
                title = "备份专辑",
                artist = "备份艺术家",
                segments = listOf(
                    ArchiveSegment(startMs = 0, title = "曲目一"),
                    ArchiveSegment(startMs = 45_000, title = "曲目二"),
                ),
            ),
        )

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.createdCount)
        assertEquals(0, report.failedCount)
        val album = albumRepo.allAlbums().single()
        assertEquals("备份专辑", album.title)
        assertEquals("备份艺术家", album.artist)
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals(2, albumRepo.allTracks(album.id).size)
    }

    @Test
    fun `merge updates existing album and keeps play count`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "旧名字", playCount = 7), tracks = tracks(1, 1, 2))
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), FakeSettingsRepository())
        val text = archiveText(
            ArchiveAlbum(
                bvid = "BV1GJ411x7h7",
                title = "新名字",
                artist = "新艺术家",
                durationMs = 200_000L,
                segments = listOf(
                    ArchiveSegment(startMs = 0, title = "一"),
                    ArchiveSegment(startMs = 60_000, title = "二"),
                    ArchiveSegment(startMs = 120_000, title = "三"),
                ),
            ),
        )

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        val updated = albumRepo.allAlbums().single()
        assertEquals("新名字", updated.title)
        assertEquals("新艺术家", updated.artist)
        assertEquals(7, updated.playCount)
        val updatedTracks = albumRepo.allTracks(updated.id)
        assertEquals(3, updatedTracks.size)
        assertEquals(120_000L, updatedTracks[2].startMs)
        assertEquals(200_000L, updatedTracks[2].endMs)
    }

    @Test
    fun `merge with empty segments keeps existing tracks`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "旧名字"), tracks = tracks(1, 3, 3))
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), FakeSettingsRepository())
        val text = archiveText(ArchiveAlbum(bvid = "BV1GJ411x7h7", title = "只更新名字"))

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        assertEquals("只更新名字", albumRepo.allAlbums().single().title)
        assertEquals(3, albumRepo.allTracks(1).size)
    }

    // ------------------------------------------------------------------
    // 内嵌封面 coverBase64（v0.5.0）
    // ------------------------------------------------------------------

    /** 测试用封面字节与 base64（只做透传，不校验图片内容）。 */
    private fun coverBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private val coverBytesFixture = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `merge applies embedded cover to existing album`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "已有专辑"), tracks = tracks(1, 2, 2))
        val coverRepo = FakeAlbumCoverRepository()
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), coverRepo,
        )
        val text = archiveText(
            ArchiveAlbum(
                bvid = "BV1GJ411x7h7",
                title = "已有专辑",
                coverBase64 = coverBase64(coverBytesFixture),
            ),
        )

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        // 1) fake 封面仓库收到 saveAlbumCover(1, bytes)
        assertEquals(1, coverRepo.saved.size)
        assertEquals(1L, coverRepo.saved.single().albumId)
        assertTrue(coverBytesFixture.contentEquals(coverRepo.saved.single().bytes))
        // 2) 专辑仓库收到 updateAlbumCover(1, fake 返回的固定路径)
        assertEquals(listOf(1L to coverRepo.fixedPath), albumRepo.coverUpdates.toList())
        assertEquals(coverRepo.fixedPath, albumRepo.allAlbums().single().coverLocalPath)
    }

    @Test
    fun `merge applies embedded cover to newly created album`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        val coverRepo = FakeAlbumCoverRepository()
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), coverRepo,
        )
        val text = archiveText(
            ArchiveAlbum(
                bvid = "BV1uv411q7Mv",
                title = "新建专辑",
                segments = listOf(ArchiveSegment(startMs = 0, title = "一")),
                coverBase64 = coverBase64(coverBytesFixture),
            ),
        )

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.createdCount)
        val created = albumRepo.allAlbums().single()
        assertEquals(1, coverRepo.saved.size)
        assertEquals(created.id, coverRepo.saved.single().albumId)
        assertEquals(listOf(created.id to coverRepo.fixedPath), albumRepo.coverUpdates.toList())
        assertEquals(coverRepo.fixedPath, created.coverLocalPath)
    }

    @Test
    fun `export embeds custom cover as coverBase64`() = runBlocking {
        val coverFile = java.io.File.createTempFile("biliaudio-cover", ".jpg").apply { writeBytes(coverBytesFixture) }
        try {
            val albumRepo = FakeAlbumRepository()
            albumRepo.seed(
                album(1, "BV1GJ411x7h7", "封面专辑").copy(coverLocalPath = coverFile.absolutePath),
                tracks = tracks(1, 1, 1),
            )
            val repo = LibraryArchiveRepositoryImpl(
                null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
            )

            val exported = codec.parse(repo.exportAllJson()).archive.albums.single()

            assertEquals(coverBase64(coverBytesFixture), exported.coverBase64)
        } finally {
            coverFile.delete()
        }
    }

    @Test
    fun `archive without coverBase64 does not touch cover repository`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "已有专辑"), tracks = tracks(1, 2, 2))
        val coverRepo = FakeAlbumCoverRepository()
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), coverRepo,
        )
        val text = archiveText(ArchiveAlbum(bvid = "BV1GJ411x7h7", title = "只改名字"))

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        assertTrue(coverRepo.saved.isEmpty())
        assertTrue(albumRepo.coverUpdates.isEmpty())
        assertNull(albumRepo.allAlbums().single().coverLocalPath)
    }

    @Test
    fun `invalid coverBase64 is ignored without failing import`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "已有专辑"), tracks = tracks(1, 2, 2))
        val coverRepo = FakeAlbumCoverRepository()
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), coverRepo,
        )
        val text = archiveText(
            ArchiveAlbum(bvid = "BV1GJ411x7h7", title = "已有专辑", coverBase64 = "!!!not-base64!!!"),
        )

        val report = repo.importArchive(text, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        assertTrue("非法 base64 不应调用 saveAlbumCover", coverRepo.saved.isEmpty())
        assertTrue(albumRepo.coverUpdates.isEmpty())
    }

    // ------------------------------------------------------------------
    // 目录形态归档（v0.6.0：根索引 + 艺术家/专辑/album.json + segments.json）
    // ------------------------------------------------------------------

    @Test
    fun `export tree creates root index and artist album hierarchy`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑A"), tracks = tracks(1, 2, 2))
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAudioQuality(AudioQualityPreference.HIGH)
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), settingsRepo, FakeAlbumCoverRepository(),
        )

        val entries = repo.exportTreeEntries()
        val paths = entries.map { it.relativePath }

        assertEquals("条目应按路径排序", paths.sorted(), paths)
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.mimeType == "application/json" })
        assertTrue("biliaudio-library.json" in paths)
        assertTrue("UP主/专辑A/album.json" in paths)
        assertTrue("UP主/专辑A/segments.json" in paths)

        // 根索引：只含 settings，albums 为空
        val root = parseTreeEntry(entries.single { it.relativePath == "biliaudio-library.json" })
        assertTrue(root.albums.isEmpty())
        assertEquals("HIGH", root.settings?.audioQuality)

        // album.json：元信息，segments 为空
        val albumMeta = parseTreeEntry(entries.single { it.relativePath == "UP主/专辑A/album.json" }).albums.single()
        assertEquals("专辑A", albumMeta.title)
        assertEquals("UP主", albumMeta.artist)
        assertEquals("BV1GJ411x7h7", albumMeta.bvid)
        assertTrue("album.json 不应包含 segments", albumMeta.segments.isEmpty())

        // segments.json：仅分割点（可含 bvid/page）
        val segments = parseTreeEntry(entries.single { it.relativePath == "UP主/专辑A/segments.json" }).albums.single()
        assertEquals("BV1GJ411x7h7", segments.bvid)
        assertEquals(2, segments.segments.size)
        assertEquals(listOf(0L, 106_500L), segments.segments.map { it.startMs })
    }

    @Test
    fun `export tree album json embeds custom cover base64`() = runBlocking {
        val coverFile = java.io.File.createTempFile("biliaudio-tree-cover", ".jpg").apply { writeBytes(coverBytesFixture) }
        try {
            val albumRepo = FakeAlbumRepository()
            albumRepo.seed(
                album(1, "BV1GJ411x7h7", "封面专辑").copy(coverLocalPath = coverFile.absolutePath),
                tracks = tracks(1, 1, 1),
            )
            val repo = LibraryArchiveRepositoryImpl(
                null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
            )

            val entries = repo.exportTreeEntries()
            val albumMeta = parseTreeEntry(entries.single { it.relativePath.endsWith("/album.json") }).albums.single()
            assertEquals(coverBase64(coverBytesFixture), albumMeta.coverBase64)

            // segments 文件不重复携带封面
            val segments = parseTreeEntry(entries.single { it.relativePath.endsWith("/segments.json") }).albums.single()
            assertNull(segments.coverBase64)
        } finally {
            coverFile.delete()
        }
    }

    @Test
    fun `export tree sanitizes illegal path characters`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(
            album(1, "BV1GJ411x7h7", "Best/Of: \"Fav\"*<>|?").copy(artist = "AC/DC\\Live: Band?"),
            tracks = tracks(1, 1, 1),
        )
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )

        val entries = repo.exportTreeEntries().filter { it.relativePath != "biliaudio-library.json" }
        assertEquals(2, entries.size)

        val illegal = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        for (entry in entries) {
            val parts = entry.relativePath.split('/')
            assertEquals("路径层级应为 艺术家/专辑/文件：${entry.relativePath}", 3, parts.size)
            parts.forEach { part ->
                assertTrue("目录/文件名不能为空：${entry.relativePath}", part.isNotBlank())
                assertFalse("不能包含非法字符：$part", part.any { it in illegal })
                assertFalse("不能包含控制字符：$part", part.any { it.code < 0x20 })
            }
        }
    }

    @Test
    fun `export tree uses fallback names and dedupes duplicate album dirs`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "").copy(artist = ""), tracks = tracks(1, 1, 1))
        albumRepo.seed(album(2, "BV1uv411q7Mv", "").copy(artist = ""), tracks = tracks(2, 1, 1))
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )

        val dirs = repo.exportTreeEntries()
            .filter { it.relativePath.endsWith("/album.json") }
            .map { it.relativePath.substringBeforeLast('/') }
            .sorted()

        assertEquals(2, dirs.size)
        assertEquals("重名目录必须去重，不能互相覆盖", 2, dirs.toSet().size)
        assertTrue(dirs.all { it.startsWith("未知艺术家/未命名专辑") })
    }

    @Test
    fun `export tree roundtrip imports metadata and segments`() = runBlocking {
        // 源库：导出到目录形态
        val sourceRepo = FakeAlbumRepository()
        sourceRepo.seed(
            album(1, "BV1GJ411x7h7", "新专辑名").copy(artist = "新艺术家"),
            tracks = tracks(1, 3, 3),
        )
        val entries = LibraryArchiveRepositoryImpl(
            null, sourceRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        ).exportTreeEntries()

        // 目标库：同 bvid 的旧专辑
        val targetRepo = FakeAlbumRepository()
        targetRepo.seed(album(1, "BV1GJ411x7h7", "旧专辑名").copy(artist = "旧艺术家"), tracks = tracks(1, 1, 2))
        val target = LibraryArchiveRepositoryImpl(
            null, targetRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )

        val report = target.importTreeEntries(entries, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        assertEquals(0, report.failedCount)
        val updated = targetRepo.allAlbums().single()
        assertEquals("新专辑名", updated.title)
        assertEquals("新艺术家", updated.artist)
        assertEquals(3, targetRepo.allTracks(updated.id).size)
    }

    @Test
    fun `import tree merges album and segments files in same directory`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "旧标题"), tracks = tracks(1, 1, 2))
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )
        val entries = listOf(
            treeJsonEntry("A/B/album.json", ArchiveAlbum(bvid = "BV1GJ411x7h7", title = "新标题", artist = "新艺术家")),
            treeJsonEntry(
                "A/B/segments.json",
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    page = 1,
                    segments = listOf(ArchiveSegment(startMs = 0, title = "一"), ArchiveSegment(startMs = 60_000, title = "二")),
                ),
            ),
        )

        val report = repo.importTreeEntries(entries, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        val updated = albumRepo.allAlbums().single()
        assertEquals("新标题", updated.title)
        assertEquals("新艺术家", updated.artist)
        assertEquals(2, albumRepo.allTracks(updated.id).size)
        assertEquals(listOf(0L, 60_000L), albumRepo.allTracks(updated.id).map { it.startMs })
    }

    @Test
    fun `import tree segments only file updates tracks by bvid`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "保留标题"), tracks = tracks(1, 1, 2))
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )
        // 只有 segments.json、没有 album.json
        val entries = listOf(
            treeJsonEntry(
                "某艺术家/某专辑/segments.json",
                ArchiveAlbum(
                    bvid = "BV1GJ411x7h7",
                    page = 1,
                    segments = listOf(
                        ArchiveSegment(startMs = 0, title = "甲"),
                        ArchiveSegment(startMs = 90_000, title = "乙"),
                        ArchiveSegment(startMs = 150_000, title = "丙"),
                    ),
                ),
            ),
        )

        val report = repo.importTreeEntries(entries, ArchiveImportMode.MERGE)

        assertEquals(1, report.updatedCount)
        val album = albumRepo.allAlbums().single()
        assertEquals("没有 album.json 时保留原元信息", "保留标题", album.title)
        assertEquals(3, albumRepo.allTracks(album.id).size)
        assertEquals(listOf(0L, 90_000L, 150_000L), albumRepo.allTracks(album.id).map { it.startMs })
    }

    @Test
    fun `import tree applies root settings except segments only mode`() = runBlocking {
        val rootEntry = treeRootEntry(
            ArchiveSettings(audioQuality = AudioQualityPreference.LOW.name, darkTheme = true),
        )
        val segmentsEntry = treeJsonEntry(
            "A/B/segments.json",
            ArchiveAlbum(bvid = "BV1GJ411x7h7", page = 1, segments = listOf(ArchiveSegment(startMs = 0, title = "一"))),
        )

        // MERGE：应用根索引设置
        val settingsRepo = FakeSettingsRepository()
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑"), tracks = tracks(1, 1, 1))
        LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), settingsRepo, FakeAlbumCoverRepository(),
        ).importTreeEntries(listOf(rootEntry, segmentsEntry), ArchiveImportMode.MERGE)
        assertEquals(AudioQualityPreference.LOW, settingsRepo.quality)
        assertEquals(true, settingsRepo.darkTheme)

        // SEGMENTS_ONLY：不应用设置（与单文件导入语义一致）
        val settingsRepo2 = FakeSettingsRepository()
        val albumRepo2 = FakeAlbumRepository()
        albumRepo2.seed(album(1, "BV1GJ411x7h7", "专辑"), tracks = tracks(1, 1, 1))
        LibraryArchiveRepositoryImpl(
            null, albumRepo2, FakeBiliRepository(), settingsRepo2, FakeAlbumCoverRepository(),
        ).importTreeEntries(listOf(rootEntry, segmentsEntry), ArchiveImportMode.SEGMENTS_ONLY)
        assertEquals(AudioQualityPreference.AUTO, settingsRepo2.quality)
        assertNull(settingsRepo2.darkTheme)
    }

    @Test
    fun `import tree records parse errors and still imports valid directories`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "旧标题"), tracks = tracks(1, 1, 2))
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )
        val entries = listOf(
            ArchiveTreeEntry("坏/目录/album.json", "{not-json".toByteArray(Charsets.UTF_8), "application/json"),
            // 版本过高 → codec 拒绝
            ArchiveTreeEntry(
                "坏/目录/segments.json",
                """{"format":"biliaudio-library","version":99,"albums":[]}""".toByteArray(Charsets.UTF_8),
                "application/json",
            ),
            ArchiveTreeEntry("biliaudio-library.json", "{bad-root".toByteArray(Charsets.UTF_8), "application/json"),
            treeJsonEntry("好/目录/album.json", ArchiveAlbum(bvid = "BV1GJ411x7h7", title = "新标题")),
            treeJsonEntry(
                "好/目录/segments.json",
                ArchiveAlbum(bvid = "BV1GJ411x7h7", page = 1, segments = listOf(ArchiveSegment(startMs = 0, title = "一"))),
            ),
        )

        val report = repo.importTreeEntries(entries, ArchiveImportMode.MERGE)

        assertTrue("坏 JSON/坏根索引应记入 parseErrors", report.parseErrors.size >= 3)
        assertEquals(1, report.updatedCount)
        assertEquals("新标题", albumRepo.allAlbums().single().title)
    }

    @Test
    fun `import tree without recognized files reports error`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑"), tracks = tracks(1, 1, 1))
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )

        val report = repo.importTreeEntries(
            listOf(
                ArchiveTreeEntry("README.md", "说明".toByteArray(Charsets.UTF_8), "text/markdown"),
                ArchiveTreeEntry(".DS_Store", byteArrayOf(1, 2, 3)),
            ),
            ArchiveImportMode.MERGE,
        )

        assertTrue(report.parseErrors.any { it.message.contains("未找到") })
        assertTrue(report.items.isEmpty())
        assertEquals(1, albumRepo.allAlbums().size)
    }

    @Test
    fun `import tree replace does not wipe library when no valid albums`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑"), tracks = tracks(1, 1, 1))
        val repo = LibraryArchiveRepositoryImpl(
            null, albumRepo, FakeBiliRepository(), FakeSettingsRepository(), FakeAlbumCoverRepository(),
        )

        val report = repo.importTreeEntries(
            listOf(ArchiveTreeEntry("坏/album.json", "{broken".toByteArray(Charsets.UTF_8), "application/json")),
            ArchiveImportMode.REPLACE,
        )

        assertEquals("没有有效专辑时不能清空用户数据", 1, albumRepo.allAlbums().size)
        assertEquals(0, report.createdCount)
        assertTrue(report.parseErrors.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 仅更新分割点
    // ------------------------------------------------------------------

    @Test
    fun `segments only updates matched and skips missing`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑A", playCount = 3), tracks = tracks(1, 1, 2))
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), FakeSettingsRepository())
        val text = codec.encode(
            LibraryArchive.create(
                albums = listOf(
                    ArchiveAlbum(
                        bvid = "BV1GJ411x7h7",
                        title = "不应被改的名字",
                        segments = listOf(
                            ArchiveSegment(startMs = 0, title = "甲"),
                            ArchiveSegment(startMs = 30_000, title = "乙"),
                            ArchiveSegment(startMs = 90_000, title = "丙"),
                        ),
                    ),
                    ArchiveAlbum(bvid = "BV1uv411q7Mv", title = "本地没有"),
                ),
            ),
        )

        val report = repo.importArchive(text, ArchiveImportMode.SEGMENTS_ONLY)

        assertEquals(1, report.segmentsUpdatedCount)
        assertEquals(1, report.skippedCount)
        val album = albumRepo.allAlbums().single()
        assertEquals("专辑A", album.title)
        assertEquals(3, album.playCount)
        assertEquals(3, albumRepo.allTracks(album.id).size)
    }

    @Test
    fun `segments only ignores settings in file`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "专辑A"), tracks = tracks(1, 1, 2))
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAudioQuality(AudioQualityPreference.AUTO)
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), settingsRepo)
        val text = codec.encode(
            LibraryArchive.create(
                albums = listOf(ArchiveAlbum(bvid = "BV1GJ411x7h7", segments = listOf(ArchiveSegment(startMs = 0, title = "一")))),
                settings = com.wonderlandkid.biliaudio.data.model.ArchiveSettings(
                    audioQuality = AudioQualityPreference.LOW.name,
                    darkTheme = true,
                ),
            ),
        )

        repo.importArchive(text, ArchiveImportMode.SEGMENTS_ONLY)

        assertEquals(AudioQualityPreference.AUTO, settingsRepo.quality)
    }

    // ------------------------------------------------------------------
    // 覆盖全库
    // ------------------------------------------------------------------

    @Test
    fun `replace backs up deletes and reimports`() = runBlocking {
        val backupDir = createTempDir()
        try {
            val albumRepo = FakeAlbumRepository()
            albumRepo.seed(album(1, "BV1GJ411x7h7", "旧专辑"), tracks = tracks(1, 1, 2))
            val repo = LibraryArchiveRepositoryImpl(backupDir, albumRepo, FakeBiliRepository(), FakeSettingsRepository())
            val text = codec.encode(
                LibraryArchive.create(
                    albums = listOf(
                        ArchiveAlbum(bvid = "BV1uv411q7Mv", title = "新专辑", segments = listOf(ArchiveSegment(startMs = 0, title = "一"))),
                    ),
                ),
            )

            val report = repo.importArchive(text, ArchiveImportMode.REPLACE)

            assertEquals(1, report.createdCount)
            assertNotNull(report.backupFilePath)
            assertTrue(File(report.backupFilePath!!).exists())
            val albums = albumRepo.allAlbums()
            assertEquals(1, albums.size)
            assertEquals("新专辑", albums.single().title)
            assertNotNull(repo.lastAutoBackupAt())
        } finally {
            backupDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 预览 / 校验
    // ------------------------------------------------------------------

    @Test
    fun `preview reports planned actions without writing`() = runBlocking {
        val albumRepo = FakeAlbumRepository()
        albumRepo.seed(album(1, "BV1GJ411x7h7", "已有"), tracks = tracks(1, 1, 1))
        val repo = LibraryArchiveRepositoryImpl(null, albumRepo, FakeBiliRepository(), FakeSettingsRepository())
        val text = codec.encode(
            LibraryArchive.create(
                albums = listOf(
                    ArchiveAlbum(bvid = "BV1GJ411x7h7", title = "已有"),
                    ArchiveAlbum(bvid = "BV1uv411q7Mv", title = "新增"),
                ),
            ),
        )

        val preview = repo.preview(text, ArchiveImportMode.MERGE)

        assertEquals(2, preview.albumCount)
        assertEquals(2, preview.items.size)
        assertTrue(preview.items.single { it.bvid == "BV1GJ411x7h7" }.exists)
        assertEquals(ArchiveItemAction.UPDATED, preview.items.single { it.bvid == "BV1GJ411x7h7" }.plannedAction)
        assertEquals(ArchiveItemAction.CREATED, preview.items.single { it.bvid == "BV1uv411q7Mv" }.plannedAction)
        // 预览不写库
        assertEquals(1, albumRepo.allAlbums().size)
    }

    @Test
    fun `invalid archive throws format exception`() = runBlocking {
        val repo = LibraryArchiveRepositoryImpl(null, FakeAlbumRepository(), FakeBiliRepository(), FakeSettingsRepository())
        try {
            repo.importArchive("""{"format":"biliaudio-library","version":99,"albums":[]}""", ArchiveImportMode.MERGE)
            fail("应当抛出 ArchiveFormatException")
        } catch (_: ArchiveFormatException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun archiveText(vararg albums: ArchiveAlbum): String =
        codec.encode(LibraryArchive.create(albums = albums.toList()))

    /** 目录形态归档的单文件条目（JSON，UTF-8）。 */
    private fun treeJsonEntry(path: String, vararg albums: ArchiveAlbum): ArchiveTreeEntry = ArchiveTreeEntry(
        relativePath = path,
        bytes = codec.encode(LibraryArchive.create(albums = albums.toList())).toByteArray(Charsets.UTF_8),
        mimeType = "application/json",
    )

    /** 目录形态归档的根索引条目。 */
    private fun treeRootEntry(settings: ArchiveSettings? = null): ArchiveTreeEntry = ArchiveTreeEntry(
        relativePath = "biliaudio-library.json",
        bytes = codec.encode(LibraryArchive.create(albums = emptyList(), settings = settings)).toByteArray(Charsets.UTF_8),
        mimeType = "application/json",
    )

    private fun parseTreeEntry(entry: ArchiveTreeEntry): LibraryArchive =
        codec.parse(entry.bytes.toString(Charsets.UTF_8)).archive

    private fun album(id: Long, bvid: String, title: String, playCount: Int = 0): Album = Album(
        id = id,
        bvid = bvid,
        cid = 1000L + id,
        title = title,
        artist = "UP主",
        coverUrl = "https://i1.hdslb.com/a.jpg",
        durationMs = 213_000L,
        playCount = playCount,
    )

    private fun tracks(albumId: Long, count: Int, total: Int): List<Track> {
        val step = 213_000L / total
        return (0 until count).map { index ->
            Track(
                id = albumId * 100 + index,
                albumId = albumId,
                index = index,
                title = "曲目 ${index + 1}",
                startMs = index * step,
                endMs = (index + 1) * step,
            )
        }
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("biliaudio-archive-test").toFile()

    // ------------------------------------------------------------------
    // Fake 仓库
    // ------------------------------------------------------------------

    private class FakeSettingsRepository : SettingsRepository {
        var quality: AudioQualityPreference = AudioQualityPreference.AUTO
        var darkTheme: Boolean? = null
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

        override suspend fun setAudioQuality(preference: AudioQualityPreference) {
            quality = preference
            _settings.value = _settings.value.copy(audioQuality = preference)
        }

        override suspend fun setDarkTheme(dark: Boolean?) {
            darkTheme = dark
            _settings.value = _settings.value.copy(darkTheme = dark)
        }
    }

    private class FakeBiliRepository : BiliRepository {
        override suspend fun previewVideo(bvidOrUrl: String): BiliVideoInfo =
            BiliVideoInfo(
                bvid = if (bvidOrUrl.startsWith("BV")) bvidOrUrl else "BV1GJ411x7h7",
                aid = 1L,
                title = "视频标题",
                coverUrl = "https://i1.hdslb.com/b.jpg",
                durationMs = 213_000L,
                ownerName = "UP主",
                pages = emptyList(),
            )

        override suspend fun searchVideos(keyword: String, page: Int): List<BiliVideoSearchItem> = emptyList()
    }

    /** 记录封面保存调用并返回固定路径的 fake。 */
    private class FakeAlbumCoverRepository : AlbumCoverRepository {
        data class SaveCall(val albumId: Long, val bytes: ByteArray)

        val saved = mutableListOf<SaveCall>()
        val fixedPath: String = "/fake/covers/album-cover.jpg"

        override suspend fun searchNetEaseAlbums(query: String, limit: Int): List<CoverCandidate> = emptyList()

        override suspend fun fetchCoverBytes(url: String): ByteArray? = null

        override suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String? {
            saved += SaveCall(albumId, bytes)
            return fixedPath
        }

        override fun deleteAlbumCoverFile(path: String?) = Unit
    }

    private class FakeAlbumRepository : AlbumRepository {
        private val albumsFlow = MutableStateFlow<List<Album>>(emptyList())
        private val tracksFlow = MutableStateFlow<Map<Long, List<Track>>>(emptyMap())
        private var nextId = 10L

        fun seed(album: Album, tracks: List<Track>) {
            albumsFlow.value = albumsFlow.value + album
            tracksFlow.value = tracksFlow.value + (album.id to tracks)
        }

        fun allAlbums(): List<Album> = albumsFlow.value
        fun allTracks(albumId: Long): List<Track> = tracksFlow.value[albumId].orEmpty()

        override val albums: Flow<List<Album>> = albumsFlow
        override val songs: Flow<List<TrackWithAlbum>> =
            combine(albumsFlow, tracksFlow) { albums, tracks ->
                albums.flatMap { album -> tracks[album.id].orEmpty().map { TrackWithAlbum(it, album) } }
            }
        override val recentAlbums: Flow<List<Album>> = albumsFlow

        override fun albumWithTracks(albumId: Long): Flow<AlbumWithTracks?> =
            combine(albumsFlow, tracksFlow) { albums, tracks ->
                albums.firstOrNull { it.id == albumId }?.let { AlbumWithTracks(it, tracks[albumId].orEmpty()) }
            }

        override suspend fun getAlbum(albumId: Long): Album? = albumsFlow.value.firstOrNull { it.id == albumId }
        override suspend fun getAlbumByBvid(bvid: String): Album? = albumsFlow.value.firstOrNull { it.bvid == bvid }
        override suspend fun getTracks(albumId: Long): List<Track> = tracksFlow.value[albumId].orEmpty()
        override suspend fun getTrack(trackId: Long): Track? = tracksFlow.value.values.flatten().firstOrNull { it.id == trackId }

        override suspend fun saveAlbumWithTracks(album: Album, tracks: List<Track>): Long {
            val id = if (album.id > 0) album.id else nextId++
            val saved = album.copy(id = id)
            albumsFlow.value = albumsFlow.value.filterNot { it.id == id } + saved
            tracksFlow.value = tracksFlow.value + (id to tracks.mapIndexed { index, track -> track.copy(albumId = id, index = index) })
            return id
        }

        /** 记录 updateAlbumCover 调用：(albumId, 新封面路径) */
        val coverUpdates = mutableListOf<Pair<Long, String?>>()

        override suspend fun updateAlbumCover(albumId: Long, coverLocalPath: String?): Boolean {
            val exists = albumsFlow.value.any { it.id == albumId }
            if (!exists) return false
            coverUpdates += albumId to coverLocalPath
            albumsFlow.value = albumsFlow.value.map {
                if (it.id == albumId) it.copy(coverLocalPath = coverLocalPath) else it
            }
            return true
        }

        override suspend fun updateAlbum(album: Album) {
            albumsFlow.value = albumsFlow.value.map { if (it.id == album.id) album else it }
        }

        override suspend fun replaceTracks(albumId: Long, tracks: List<Track>) {
            tracksFlow.value = tracksFlow.value + (albumId to tracks.mapIndexed { index, track ->
                track.copy(albumId = albumId, index = index)
            })
        }

        override suspend fun deleteAlbum(albumId: Long) {
            albumsFlow.value = albumsFlow.value.filterNot { it.id == albumId }
            tracksFlow.value = tracksFlow.value - albumId
        }

        override suspend fun markPlayed(albumId: Long, trackId: Long) = Unit

        override suspend fun cleanupLegacyPlaceholderTracks(): Int = 0

        override fun searchSongs(query: String): Flow<List<TrackWithAlbum>> = songs
        override fun searchAlbums(query: String): Flow<List<Album>> = albumsFlow

        override suspend fun importAlbum(request: AlbumImportRequest): ImportResult {
            val bvid = if (request.bvidOrUrl.startsWith("BV")) request.bvidOrUrl else "BV1GJ411x7h7"
            val duration = 213_000L
            val segments = request.segments
            val newTracks = if (segments.isEmpty()) {
                listOf(Track(albumId = 0, index = 0, title = "整段", startMs = 0, endMs = duration))
            } else {
                segments.mapIndexed { index, segment ->
                    Track(
                        albumId = 0,
                        index = index,
                        title = segment.title.ifBlank { "曲目 ${index + 1}" },
                        startMs = segment.startMs,
                        endMs = segments.getOrNull(index + 1)?.startMs ?: duration,
                    )
                }
            }
            val id = saveAlbumWithTracks(
                Album(
                    bvid = bvid,
                    cid = 1L,
                    title = request.albumTitle ?: "视频标题",
                    artist = request.artist ?: "UP主",
                    coverUrl = "https://i1.hdslb.com/b.jpg",
                    durationMs = duration,
                ),
                newTracks,
            )
            return ImportResult.Success(albumId = id, albumTitle = request.albumTitle ?: "视频标题", trackCount = newTracks.size)
        }

        override suspend fun importBatchText(text: String): BatchImportReport = BatchImportReport(emptyList())
    }
}
