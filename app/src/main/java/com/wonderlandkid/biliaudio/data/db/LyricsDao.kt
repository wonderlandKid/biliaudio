package com.wonderlandkid.biliaudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 歌词本机缓存 DAO（表 `lyrics`，见 [LyricEntity]）。 */
@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE trackUid = :trackUid")
    suspend fun getByTrackUid(trackUid: String): LyricEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricEntity)

    /** 只更新已有行的偏移；无该行时返回 0（不新建）。 */
    @Query(
        "UPDATE lyrics SET delayMs = :delayMs, updatedAtMs = :updatedAtMs " +
            "WHERE trackUid = :trackUid",
    )
    suspend fun updateDelay(trackUid: String, delayMs: Long, updatedAtMs: Long): Int

    /** 标记关闭歌词；无该行时返回 0（由仓库层补空快照行）。 */
    @Query(
        "UPDATE lyrics SET suppressed = 1, updatedAtMs = :updatedAtMs " +
            "WHERE trackUid = :trackUid",
    )
    suspend fun markSuppressed(trackUid: String, updatedAtMs: Long): Int

    @Query("DELETE FROM lyrics WHERE trackUid = :trackUid")
    suspend fun delete(trackUid: String)
}
