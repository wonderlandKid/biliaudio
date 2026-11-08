package com.wonderlandkid.biliaudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query(
        "SELECT p.id, p.name, p.createdAt, p.updatedAt, " +
            "(SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount " +
            "FROM playlists p ORDER BY p.updatedAt DESC",
    )
    fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observeById(playlistId: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getById(playlistId: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun delete(playlistId: Long)

    @Query("SELECT MAX(position) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND trackUid = :trackUid")
    suspend fun deleteEntry(playlistId: Long, trackUid: String)
}
