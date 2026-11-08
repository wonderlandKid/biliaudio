package com.wonderlandkid.biliaudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackStatsDao {

    @Query("SELECT * FROM track_stats ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeTopByPlayCount(limit: Int): Flow<List<TrackStatsEntity>>

    @Query(
        "SELECT albumId, SUM(listenedMs) AS listenedMs FROM track_stats " +
            "GROUP BY albumId ORDER BY listenedMs DESC LIMIT :limit",
    )
    fun observeAlbumListening(limit: Int): Flow<List<AlbumListeningRow>>

    @Query("SELECT * FROM track_stats WHERE trackUid = :trackUid")
    suspend fun getByUid(trackUid: String): TrackStatsEntity?

    @Query("SELECT * FROM track_stats ORDER BY albumId ASC")
    suspend fun getAll(): List<TrackStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: TrackStatsEntity)

    @Query(
        "UPDATE track_stats SET playCount = playCount + 1, lastPlayedAt = :playedAt " +
            "WHERE trackUid = :trackUid",
    )
    suspend fun incrementPlay(trackUid: String, playedAt: Long): Int

    @Query("UPDATE track_stats SET listenedMs = listenedMs + :deltaMs WHERE trackUid = :trackUid")
    suspend fun addListening(trackUid: String, deltaMs: Long): Int
}
