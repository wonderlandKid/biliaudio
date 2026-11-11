package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.db.AppDatabase
import com.wonderlandkid.biliaudio.data.db.PlaylistEntity
import com.wonderlandkid.biliaudio.data.db.PlaylistEntryEntity
import com.wonderlandkid.biliaudio.data.db.TrackStatsEntity
import com.wonderlandkid.biliaudio.data.model.BackupPlaylist
import com.wonderlandkid.biliaudio.data.model.BackupPlaylistEntry
import com.wonderlandkid.biliaudio.data.model.BackupStat
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.model.UserDataBackup
import com.wonderlandkid.biliaudio.data.model.UserDataImportReport
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.UserDataBackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 独立用户数据备份：歌单 + 听歌统计。
 *
 * 导出只包含用户产生的数据；恢复时按 `(source,bvid,page) + 分曲 startMs` 在当前音乐库中找歌，
 * 因此不依赖本机的 trackUid，也不需要把用户数据塞进音乐库归档。
 */
class UserDataBackupRepositoryImpl(
    private val database: AppDatabase,
    private val albumRepository: AlbumRepository,
) : UserDataBackupRepository {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val playlistDao = database.playlistDao()
    private val statsDao = database.trackStatsDao()

    override suspend fun exportBackup(): UserDataBackup {
        val albums = albumRepository.albums.first()
        val songs = albumRepository.songs.first()
        val albumsById = albums.associateBy { it.id }
        val songsByUid = songs.associateBy { it.track.uid }

        val playlists = playlistDao.getAllPlaylists().map { playlist ->
            val entries = playlistDao.getEntries(playlist.id).mapNotNull { entry ->
                val song = songsByUid[entry.trackUid] ?: return@mapNotNull null
                val album = albumsById[song.album.id] ?: return@mapNotNull null
                BackupPlaylistEntry(
                    source = album.source,
                    bvid = album.bvid,
                    page = album.page,
                    startMs = song.track.startMs,
                    trackTitle = song.track.title,
                )
            }
            BackupPlaylist(
                name = playlist.name,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                entries = entries,
            )
        }

        val stats = statsDao.getAll().mapNotNull { stat ->
            val song = songsByUid[stat.trackUid]
            if (song != null) {
                val album = albumsById[stat.albumId] ?: return@mapNotNull null
                BackupStat(
                    source = album.source,
                    bvid = album.bvid,
                    page = album.page,
                    startMs = song.track.startMs,
                    wholeAlbum = false,
                    playCount = stat.playCount,
                    listenedMs = stat.listenedMs,
                    lastPlayedAt = stat.lastPlayedAt,
                )
            } else if (stat.trackUid.startsWith("album:")) {
                val album = albumsById[stat.albumId] ?: return@mapNotNull null
                BackupStat(
                    source = album.source,
                    bvid = album.bvid,
                    page = album.page,
                    startMs = 0L,
                    wholeAlbum = true,
                    playCount = stat.playCount,
                    listenedMs = stat.listenedMs,
                    lastPlayedAt = stat.lastPlayedAt,
                )
            } else {
                null
            }
        }

        return UserDataBackup(
            exportedAt = System.currentTimeMillis(),
            playlists = playlists,
            stats = stats,
        )
    }

    override suspend fun exportJson(): String =
        json.encodeToString(UserDataBackup.serializer(), exportBackup())

    override suspend fun importJson(jsonText: String): UserDataImportReport {
        val backup = try {
            json.decodeFromString(UserDataBackup.serializer(), jsonText)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的用户数据备份：${e.message}")
        }
        require(backup.format == UserDataBackup.FORMAT) { "不是 BiliAudio 用户数据备份" }
        require(backup.version <= UserDataBackup.VERSION) { "备份版本高于当前 App，请升级后再导入" }

        val albums = albumRepository.albums.first()
        val songs = albumRepository.songs.first()
        val albumsByKey = albums.associateBy { albumKey(it.source, it.bvid, it.page) }
        val songsByAlbum = songs.groupBy { it.album.id }
        var playlistsCreated = 0
        var playlistsMerged = 0
        var entriesRestored = 0
        var statsRestored = 0
        var skipped = 0
        val warnings = mutableListOf<String>()

        backup.playlists.forEach { backupPlaylist ->
            if (backupPlaylist.name.isBlank()) {
                skipped++
                return@forEach
            }
            val existing = playlistDao.getByName(backupPlaylist.name)
            val playlistId: Long
            if (existing == null) {
                playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = backupPlaylist.name,
                        createdAt = backupPlaylist.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        updatedAt = backupPlaylist.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
                playlistsCreated++
            } else {
                playlistId = existing.id
                playlistsMerged++
            }
            var nextPosition = (playlistDao.maxPosition(playlistId) ?: -1) + 1
            val entries = backupPlaylist.entries.mapNotNull { entry ->
                val album = albumsByKey[albumKey(entry.source, entry.bvid, entry.page)]
                if (album == null) {
                    skipped++
                    return@mapNotNull null
                }
                val track = findTrack(songsByAlbum[album.id].orEmpty(), entry.startMs)
                if (track == null) {
                    skipped++
                    return@mapNotNull null
                }
                PlaylistEntryEntity(
                    playlistId = playlistId,
                    trackUid = track.track.uid,
                    albumId = album.id,
                    position = nextPosition++,
                )
            }
            if (entries.isNotEmpty()) {
                playlistDao.insertEntries(entries)
                playlistDao.touch(playlistId, System.currentTimeMillis())
                entriesRestored += entries.size
            }
        }

        backup.stats.forEach { backupStat ->
            val album = albumsByKey[albumKey(backupStat.source, backupStat.bvid, backupStat.page)]
            if (album == null) {
                skipped++
                return@forEach
            }
            val albumSongs = songsByAlbum[album.id].orEmpty()
            val track = if (backupStat.wholeAlbum) null else findTrack(albumSongs, backupStat.startMs)
            val trackUid = if (backupStat.wholeAlbum) {
                "album:${album.id}"
            } else {
                track?.track?.uid
            }
            if (!backupStat.wholeAlbum && track == null) {
                skipped++
                return@forEach
            }
            val uid = trackUid ?: run { skipped++; return@forEach }
            val old = statsDao.getByUid(uid)
            statsDao.upsert(
                TrackStatsEntity(
                    trackUid = uid,
                    albumId = album.id,
                    playCount = maxOf(old?.playCount ?: 0, backupStat.playCount),
                    listenedMs = maxOf(old?.listenedMs ?: 0L, backupStat.listenedMs),
                    lastPlayedAt = maxOf(old?.lastPlayedAt ?: 0L, backupStat.lastPlayedAt ?: 0L)
                        .takeIf { it > 0L },
                ),
            )
            statsRestored++
        }

        if (skipped > 0) warnings += "有 $skipped 条记录在当前音乐库中找不到对应歌曲，已跳过"
        return UserDataImportReport(
            playlistsCreated = playlistsCreated,
            playlistsMerged = playlistsMerged,
            entriesRestored = entriesRestored,
            statsRestored = statsRestored,
            skipped = skipped,
            warnings = warnings,
        )
    }

    private fun findTrack(songs: List<TrackWithAlbum>, startMs: Long): TrackWithAlbum? =
        songs.minByOrNull { kotlin.math.abs(it.track.startMs - startMs) }
            ?.takeIf { kotlin.math.abs(it.track.startMs - startMs) <= TRACK_START_TOLERANCE_MS }

    private fun albumKey(source: String, bvid: String, page: Int): String =
        "$source\u0000$bvid\u0000$page"

    private companion object {
        /** 允许分曲编辑时微调几秒仍能恢复歌单/统计。 */
        const val TRACK_START_TOLERANCE_MS = 5_000L
    }
}
