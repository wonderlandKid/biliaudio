package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.AlbumWithTracks
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveKind
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.BatchImportReport
import com.wonderlandkid.biliaudio.data.model.BiliPage
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.CoverCandidate
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryArchiveRepositoryTest {

    @Test
    fun `bundle export splits files and excludes inline cover`() = runBlocking {
        val fakes = fakes()
        val root = fakes.seedWithCover()
        val entries = fakes.repository.exportTreeEntries()
        val paths = entries.map { it.relativePath }
        assertTrue(paths.contains("manifest.json"))
        assertTrue(paths.contains("library.json"))
        assertTrue(paths.contains("settings.json"))
        assertTrue(paths.any { it.startsWith("covers/") })

        val parsed = ArchiveBundleCodec(LibraryArchiveCodec()).parseEntries(entries)
        assertEquals(ArchiveKind.LIBRARY, parsed.library.kind)
        val cover = parsed.library.albums.single().cover
        assertNotNull(cover)
        assertNull(cover?.dataBase64)
        assertEquals(true, parsed.settings?.diagnosticLogsEnabled)
        assertNotNull(parsed.covers[cover!!.hash])
    }

    @Test
    fun `bundle merge creates album with tracks`() = runBlocking {
        val fakes = fakes()
        fakes.seed()
        val entries = fakes.repository.exportTreeEntries()
        fakes.albumRepo.clear()
        val report = fakes.repository.importTreeEntries(entries, ArchiveImportMode.MERGE)
        assertEquals(1, report.createdCount)
        val album = fakes.albumRepo.albumsFlow.value.single()
        assertEquals("BV1GJ411x7h7", album.bvid)
        assertEquals(2, fakes.albumRepo.allTracks(album.id).size)
    }

    @Test
    fun `single album export and import keeps segments`() = runBlocking {
        val fakes = fakes()
        val albumId = fakes.seed()
        val json = fakes.repository.exportAlbumJson(albumId)!!
        fakes.albumRepo.clear()
        val report = fakes.repository.importArchive(json, ArchiveImportMode.MERGE)
        assertEquals(1, report.createdCount)
        val album = fakes.albumRepo.albumsFlow.value.single()
        assertEquals(2, fakes.albumRepo.allTracks(album.id).size)
        val parsed = LibraryArchiveCodec().parse(json).archive
        assertEquals(ArchiveKind.ALBUM, parsed.kind)
    }

    @Test
    fun `merge with missing segments keeps existing and empty clears`() = runBlocking {
        val fakes = fakes()
        fakes.seed()
        val keepJson = """
            {"format":"biliaudio-library","version":3,"kind":"album",
             "albums":[{"bvid":"BV1GJ411x7h7","page":1,"title":"新标题"}]}
        """.trimIndent()
        fakes.repository.importArchive(keepJson, ArchiveImportMode.MERGE)
        var album = fakes.albumRepo.albumsFlow.value.single()
        assertEquals("新标题", album.title)
        assertEquals(2, fakes.albumRepo.allTracks(album.id).size)

        val clearJson = """
            {"format":"biliaudio-library","version":3,"kind":"album",
             "albums":[{"bvid":"BV1GJ411x7h7","page":1,"segments":[]}]}
        """.trimIndent()
        fakes.repository.importArchive(clearJson, ArchiveImportMode.MERGE)
        album = fakes.albumRepo.albumsFlow.value.single()
        assertTrue(fakes.albumRepo.allTracks(album.id).isEmpty())
    }

    @Test
    fun `segments only updates existing and skips missing`() = runBlocking {
        val fakes = fakes()
        fakes.seed()
        val json = """
            {"format":"biliaudio-library","version":3,"kind":"library","albums":[
              {"bvid":"BV1GJ411x7h7","page":1,"segments":[{"startMs":0,"title":"只有一首"}]},
              {"bvid":"BV1GJ411x7h7","page":2,"segments":[{"startMs":0,"title":"不存在"}]}
            ]}
        """.trimIndent()
        // 整库 library kind 不能走单 JSON 导入，改成逐个 album 文件场景：
        val single = """
            {"format":"biliaudio-library","version":3,"kind":"album","albums":[
              {"bvid":"BV1GJ411x7h7","page":1,"segments":[{"startMs":0,"title":"只有一首"}]}
            ]}
        """.trimIndent()
        val report = fakes.repository.importArchive(single, ArchiveImportMode.SEGMENTS_ONLY)
        assertEquals(1, report.segmentsUpdatedCount)
        val album = fakes.albumRepo.albumsFlow.value.single()
        assertEquals("只有一首", fakes.albumRepo.allTracks(album.id).single().title)

        val missingPage = """
            {"format":"biliaudio-library","version":3,"kind":"album","albums":[
              {"bvid":"BV1GJ411x7h7","page":2,"segments":[{"startMs":0}]}
            ]}
        """.trimIndent()
        val missingReport = fakes.repository.importArchive(missingPage, ArchiveImportMode.SEGMENTS_ONLY)
        assertEquals(1, missingReport.skippedCount)
    }

    @Test
    fun `single library kind json is rejected and replace bundles back up`() = runBlocking {
        val fakes = fakes()
        fakes.seed()
        val libraryJson = LibraryArchiveCodec().encode(
            LibraryArchive.create(kind = ArchiveKind.LIBRARY, albums = emptyList()),
        )
        val error = runCatching { fakes.repository.importArchive(libraryJson, ArchiveImportMode.MERGE) }.exceptionOrNull()
        assertTrue(error is ArchiveFormatException)

        val entries = fakes.repository.exportTreeEntries()
        val report = fakes.repository.importTreeEntries(entries, ArchiveImportMode.REPLACE)
        assertEquals(1, report.createdCount)
        assertNotNull(report.backupFilePath)
        assertTrue(File(report.backupFilePath!!).isDirectory)
    }

    @Test
    fun `bundle import applies settings only on replace`() = runBlocking {
        val fakes = fakes()
        fakes.seed()
        fakes.settingsRepo.setDarkTheme(true)
        val entries = fakes.repository.exportTreeEntries()
        fakes.settingsRepo.setDarkTheme(false)
        fakes.settingsRepo.setDiagnosticLogsEnabled(false)

        fakes.repository.importTreeEntries(entries, ArchiveImportMode.MERGE)
        assertEquals(false, fakes.settingsRepo.settings.value.darkTheme)

        fakes.repository.importTreeEntries(entries, ArchiveImportMode.REPLACE)
        assertEquals(true, fakes.settingsRepo.settings.value.darkTheme)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun fakes(): Fakes {
        val settings = FakeSettingsRepository()
        val bili = FakeBiliRepository()
        val cover = FakeAlbumCoverRepository()
        val albumRepo = FakeAlbumRepository()
        val backupDir = createTempDir()
        val repo = LibraryArchiveRepositoryImpl(
            backupDir = backupDir,
            albumRepository = albumRepo,
            biliRepository = bili,
            settingsRepository = settings,
            albumCoverRepository = cover,
        )
        return Fakes(repo, albumRepo, settings, bili, cover, backupDir)
    }

    private data class Fakes(
        val repository: LibraryArchiveRepositoryImpl,
        val albumRepo: FakeAlbumRepository,
        val settingsRepo: FakeSettingsRepository,
        val bili: FakeBiliRepository,
        val cover: FakeAlbumCoverRepository,
        val backupDir: File,
    )

    private suspend fun Fakes.seed(): Long {
        val album = Album(
            id = 1L,
            bvid = "BV1GJ411x7h7",
            cid = 1L,
            page = 1,
            pageCount = 1,
            title = "Abbey Road",
            artist = "The Beatles",
            coverUrl = "https://i1.hdslb.com/a.jpg",
            durationMs = 213_000L,
            playCount = 7,
        )
        albumRepo.seed(album, tracks(1L))
        return album.id
    }

    private suspend fun Fakes.seedWithCover(): Long {
        val id = seed()
        val coverFile = File(backupDir, "local-cover.jpg")
        coverFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()))
        albumRepo.updateAlbum(albumRepo.albumsFlow.value.single().copy(coverLocalPath = coverFile.absolutePath))
        return id
    }

    private fun tracks(albumId: Long): List<Track> = listOf(
        Track(id = albumId * 100, uid = UUID.randomUUID().toString(), albumId = albumId, index = 0, title = "一", startMs = 0, endMs = 100_000),
        Track(id = albumId * 100 + 1, uid = UUID.randomUUID().toString(), albumId = albumId, index = 1, title = "二", startMs = 100_000, endMs = 213_000),
    )

    private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("biliaudio-archive-test").toFile()

    private class FakeSettingsRepository : SettingsRepository {
        private val _settings = MutableStateFlow(AppSettings(diagnosticLogsEnabled = true))
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
        override suspend fun setAudioQuality(preference: AudioQualityPreference) {
            _settings.value = _settings.value.copy(audioQuality = preference)
        }
        override suspend fun setDarkTheme(dark: Boolean?) {
            _settings.value = _settings.value.copy(darkTheme = dark)
        }
        override suspend fun setDiagnosticLogsEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(diagnosticLogsEnabled = enabled)
        }
    }

    private class FakeBiliRepository : BiliRepository {
        override suspend fun previewVideo(bvidOrUrl: String): BiliVideoInfo {
            val bvid = if (bvidOrUrl.startsWith("BV")) bvidOrUrl else "BV1GJ411x7h7"
            return BiliVideoInfo(
                bvid = bvid,
                aid = 1L,
                title = "视频标题",
                coverUrl = "https://i1.hdslb.com/b.jpg",
                durationMs = 213_000L,
                ownerName = "UP主",
                pages = (1..3).map { page -> BiliPage(page = page, cid = page.toLong(), title = "P$page", durationMs = 213_000L) },
            )
        }
        override suspend fun searchVideos(keyword: String, page: Int): List<BiliVideoSearchItem> = emptyList()
    }

    private class FakeAlbumCoverRepository : AlbumCoverRepository {
        val saved = mutableListOf<ByteArray>()
        val fixedPath = "/fake/covers/album-cover.jpg"
        override suspend fun searchNetEaseAlbums(query: String, limit: Int): List<CoverCandidate> = emptyList()
        override suspend fun fetchCoverBytes(url: String): ByteArray? = null
        override suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String? {
            saved += bytes
            return fixedPath
        }
        override fun deleteAlbumCoverFile(path: String?) = Unit
    }

    private class FakeAlbumRepository : AlbumRepository {
        val albumsFlow = MutableStateFlow<List<Album>>(emptyList())
        private val tracksFlow = MutableStateFlow<Map<Long, List<Track>>>(emptyMap())
        private var nextId = 10L

        fun seed(album: Album, tracks: List<Track>) {
            albumsFlow.value = albumsFlow.value.filterNot { it.id == album.id } + album
            tracksFlow.value = tracksFlow.value + (album.id to tracks)
        }

        fun clear() {
            albumsFlow.value = emptyList()
            tracksFlow.value = emptyMap()
        }

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
        override suspend fun getAlbumBySourceKey(source: String, bvid: String, page: Int): Album? =
            albumsFlow.value.firstOrNull { it.source == source && it.bvid == bvid && it.page == page }
        override suspend fun getTracks(albumId: Long): List<Track> = tracksFlow.value[albumId].orEmpty()
        override suspend fun getTrack(trackId: Long): Track? = tracksFlow.value.values.flatten().firstOrNull { it.id == trackId }

        override suspend fun saveAlbumWithTracks(album: Album, tracks: List<Track>): Long {
            val id = if (album.id > 0) album.id else nextId++
            albumsFlow.value = albumsFlow.value.filterNot { it.id == id } + album.copy(id = id)
            tracksFlow.value = tracksFlow.value + (id to tracks.mapIndexed { index, track ->
                track.copy(albumId = id, index = index, uid = track.uid.ifBlank { UUID.randomUUID().toString() })
            })
            return id
        }

        override suspend fun updateAlbum(album: Album) {
            albumsFlow.value = albumsFlow.value.map { if (it.id == album.id) album else it }
        }

        override suspend fun updateAlbumCover(albumId: Long, coverLocalPath: String?): Boolean {
            if (albumsFlow.value.none { it.id == albumId }) return false
            albumsFlow.value = albumsFlow.value.map { if (it.id == albumId) it.copy(coverLocalPath = coverLocalPath) else it }
            return true
        }

        override suspend fun replaceTracks(albumId: Long, tracks: List<Track>) {
            tracksFlow.value = tracksFlow.value + (albumId to tracks.mapIndexed { index, track ->
                track.copy(albumId = albumId, index = index, uid = track.uid.ifBlank { UUID.randomUUID().toString() })
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
            val newAlbum = Album(
                id = 0,
                bvid = bvid,
                cid = 1L,
                page = request.page,
                pageCount = 3,
                title = request.albumTitle ?: "视频标题",
                artist = request.artist ?: "UP主",
                coverUrl = "https://i1.hdslb.com/b.jpg",
                durationMs = duration,
            )
            val newTracks = request.segments.mapIndexed { index, segment ->
                Track(
                    id = 0,
                    albumId = 0,
                    index = index,
                    title = segment.title,
                    startMs = segment.startMs,
                    endMs = request.segments.getOrNull(index + 1)?.startMs ?: duration,
                )
            }
            val id = saveAlbumWithTracks(newAlbum, newTracks)
            return ImportResult.Success(id, newAlbum.title, newTracks.size)
        }

        override suspend fun importBatchText(text: String): BatchImportReport = BatchImportReport(emptyList())
    }
}
