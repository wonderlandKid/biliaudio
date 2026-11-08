package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.Playlist
import com.wonderlandkid.biliaudio.data.model.PlaylistWithTracks
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import kotlinx.coroutines.flow.Flow

/** 用户自建歌单（Room 持久化；歌曲用稳定 trackUid 关联）。 */
interface PlaylistRepository {
    val playlists: Flow<List<Playlist>>

    fun playlistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?>

    suspend fun createPlaylist(name: String): Long
    suspend fun renamePlaylist(playlistId: Long, name: String)
    suspend fun deletePlaylist(playlistId: Long)

    /** 追加歌曲；已存在的 trackUid 会忽略，不产生重复。 */
    suspend fun addSongs(playlistId: Long, songs: List<TrackWithAlbum>)
    suspend fun removeSong(playlistId: Long, trackUid: String)
}
