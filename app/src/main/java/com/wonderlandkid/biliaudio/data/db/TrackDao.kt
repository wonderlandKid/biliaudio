package com.wonderlandkid.biliaudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 曲目表 DAO。 */
@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY albumId ASC, `index` ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY `index` ASC")
    fun observeByAlbum(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY `index` ASC")
    suspend fun getByAlbum(albumId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getById(trackId: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: Long)
}
