package com.wonderlandkid.biliaudio.data.impl

import androidx.room.withTransaction
import com.wonderlandkid.biliaudio.data.db.AppDatabase
import com.wonderlandkid.biliaudio.data.db.TrackStatsEntity
import com.wonderlandkid.biliaudio.data.model.AlbumListeningStat
import com.wonderlandkid.biliaudio.data.model.TrackPlayStat
import com.wonderlandkid.biliaudio.data.repository.PlaybackStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaybackStatsRepositoryImpl(
    private val database: AppDatabase,
) : PlaybackStatsRepository {

    private val dao = database.trackStatsDao()

    override suspend fun recordTrackStarted(albumId: Long, trackUid: String) {
        if (trackUid.isBlank() || albumId <= 0L) return
        database.withTransaction {
            val now = System.currentTimeMillis()
            if (dao.incrementPlay(trackUid, now) == 0) {
                dao.upsert(
                    TrackStatsEntity(
                        trackUid = trackUid,
                        albumId = albumId,
                        playCount = 1,
                        listenedMs = 0L,
                        lastPlayedAt = now,
                    ),
                )
            }
        }
    }

    override suspend fun addListeningTime(albumId: Long, trackUid: String, deltaMs: Long) {
        if (trackUid.isBlank() || albumId <= 0L || deltaMs <= 0L) return
        database.withTransaction {
            if (dao.addListening(trackUid, deltaMs) == 0) {
                dao.upsert(
                    TrackStatsEntity(
                        trackUid = trackUid,
                        albumId = albumId,
                        playCount = 0,
                        listenedMs = deltaMs,
                        lastPlayedAt = null,
                    ),
                )
            }
        }
    }

    override fun observeAlbumListening(limit: Int): Flow<List<AlbumListeningStat>> =
        dao.observeAlbumListening(limit).map { rows ->
            rows.map { AlbumListeningStat(albumId = it.albumId, listenedMs = it.listenedMs) }
        }

    override fun observeTrackPlayCounts(limit: Int): Flow<List<TrackPlayStat>> =
        dao.observeTopByPlayCount(limit).map { rows ->
            rows.map {
                TrackPlayStat(
                    trackUid = it.trackUid,
                    albumId = it.albumId,
                    playCount = it.playCount,
                    listenedMs = it.listenedMs,
                )
            }
        }
}
