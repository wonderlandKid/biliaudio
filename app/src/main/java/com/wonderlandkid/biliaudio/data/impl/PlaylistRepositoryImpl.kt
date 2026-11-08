package com.wonderlandkid.biliaudio.data.impl

import androidx.room.withTransaction
import com.wonderlandkid.biliaudio.data.db.AppDatabase
import com.wonderlandkid.biliaudio.data.db.PlaylistEntity
import com.wonderlandkid.biliaudio.data.db.PlaylistEntryEntity
import com.wonderlandkid.biliaudio.data.model.Playlist
import com.wonderlandkid.biliaudio.data.model.PlaylistWithTracks
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class PlaylistRepositoryImpl(
    private val database: AppDatabase,
    private val albumRepository: AlbumRepository,
) : PlaylistRepository {

    private val playlistDao = database.playlistDao()

    override val playlists: Flow<List<Playlist>> =
        playlistDao.observeSummaries().map { rows ->
            rows.map { row ->
                Playlist(
                    id = row.id,
                    name = row.name,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    trackCount = row.trackCount,
                )
            }
        }

    override fun playlistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?> =
        combine(
            playlistDao.observeById(playlistId),
            playlistDao.observeEntries(playlistId),
            albumRepository.songs,
        ) { playlist, entries, songs ->
            if (playlist == null) return@combine null
            val songsByUid = songs.associateBy { it.track.uid }
            val tracks = entries.mapNotNull { entry -> songsByUid[entry.trackUid] }
            PlaylistWithTracks(
                playlist = Playlist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAt = playlist.createdAt,
                    updatedAt = playlist.updatedAt,
                    trackCount = tracks.size,
                ),
                tracks = tracks,
            )
        }

    override suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insertPlaylist(
            PlaylistEntity(name = name.trim(), createdAt = now, updatedAt = now),
        )
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String) {
        playlistDao.rename(playlistId, name.trim(), System.currentTimeMillis())
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.delete(playlistId)
    }

    override suspend fun addSongs(playlistId: Long, songs: List<TrackWithAlbum>) {
        if (songs.isEmpty()) return
        database.withTransaction {
            var nextPosition = (playlistDao.maxPosition(playlistId) ?: -1) + 1
            val entries = songs
                .filter { it.track.uid.isNotBlank() }
                .distinctBy { it.track.uid }
                .map { song ->
                    PlaylistEntryEntity(
                        playlistId = playlistId,
                        trackUid = song.track.uid,
                        albumId = song.album.id,
                        position = nextPosition++,
                    )
                }
            if (entries.isNotEmpty()) {
                playlistDao.insertEntries(entries)
                playlistDao.touch(playlistId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun removeSong(playlistId: Long, trackUid: String) {
        database.withTransaction {
            playlistDao.deleteEntry(playlistId, trackUid)
            playlistDao.touch(playlistId, System.currentTimeMillis())
        }
    }
}
