package com.wonderlandkid.biliaudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 专辑表 DAO：列表/详情/搜索用 Flow 订阅，写操作用 suspend。 */
@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AlbumEntity>>

    /** 最近播放（按 lastPlayedAt 倒序，最多 [limit] 条）。 */
    @Query("SELECT * FROM albums WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    fun observeById(albumId: Long): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    suspend fun getById(albumId: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE bvid = :bvid LIMIT 1")
    suspend fun getByBvid(bvid: String): AlbumEntity?

    @Query("SELECT * FROM albums ORDER BY createdAt ASC")
    suspend fun getAll(): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity): Long

    @Update
    suspend fun update(album: AlbumEntity)

    /** 记录一次播放：最近播放时间 + 播放次数 +1。 */
    @Query("UPDATE albums SET lastPlayedAt = :playedAt, playCount = playCount + 1 WHERE id = :albumId")
    suspend fun markPlayed(albumId: Long, playedAt: Long)

    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteById(albumId: Long)
}
