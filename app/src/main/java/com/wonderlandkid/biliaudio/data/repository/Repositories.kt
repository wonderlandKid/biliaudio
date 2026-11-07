package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.AppSettings
import com.wonderlandkid.biliaudio.data.model.AlbumImportRequest
import com.wonderlandkid.biliaudio.data.model.AlbumWithTracks
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.model.BatchImportReport
import com.wonderlandkid.biliaudio.data.model.BiliVideoInfo
import com.wonderlandkid.biliaudio.data.model.BiliVideoSearchItem
import com.wonderlandkid.biliaudio.data.model.ImportResult
import com.wonderlandkid.biliaudio.data.model.ResolvedStreams
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 专辑 / 曲目库（Room 持久化）。 */
interface AlbumRepository {
    val albums: Flow<List<Album>>
    val songs: Flow<List<TrackWithAlbum>>
    val recentAlbums: Flow<List<Album>>

    fun albumWithTracks(albumId: Long): Flow<AlbumWithTracks?>

    suspend fun getAlbum(albumId: Long): Album?
    suspend fun getAlbumByBvid(bvid: String): Album?
    suspend fun getTracks(albumId: Long): List<Track>
    suspend fun getTrack(trackId: Long): Track?

    suspend fun saveAlbumWithTracks(album: Album, tracks: List<Track>): Long
    suspend fun updateAlbum(album: Album)
    suspend fun replaceTracks(albumId: Long, tracks: List<Track>)
    suspend fun deleteAlbum(albumId: Long)

    /** 记录播放（最近播放排序、播放次数）。 */
    suspend fun markPlayed(albumId: Long, trackId: Long)

    fun searchSongs(query: String): Flow<List<TrackWithAlbum>>
    fun searchAlbums(query: String): Flow<List<Album>>

    /** 单个专辑导入（BV 号/链接 + 可选自定义分割点）。 */
    suspend fun importAlbum(request: AlbumImportRequest): ImportResult

    /**
     * 批量导入。支持：
     * 1) JSON 数组；
     * 2) 文本行：`BV号[,专辑名[,艺术家]]`，可跟缩进/下划线开头的分割点行 `mm:ss 歌名`；
     * 3) CSV：`BV号,专辑名,艺术家,mm:ss,歌名` 每行一首。
     */
    suspend fun importBatchText(text: String): BatchImportReport
}

/** B 站视频信息 / 搜索。 */
interface BiliRepository {
    suspend fun previewVideo(bvidOrUrl: String): BiliVideoInfo
    suspend fun searchVideos(keyword: String, page: Int = 1): List<BiliVideoSearchItem>
}

sealed interface AuthState {
    data object Unknown : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val profile: UserProfile) : AuthState
}

enum class QrPhase { IDLE, LOADING, WAITING, SCANNED, EXPIRED, ERROR, SUCCESS }

data class QrLoginState(
    val phase: QrPhase = QrPhase.IDLE,
    /** 用于生成二维码图片的 URL */
    val qrUrl: String? = null,
    val message: String? = null,
)

/** B 站账号登录（扫码）。 */
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val qrState: StateFlow<QrLoginState>
    val currentProfile: UserProfile?

    suspend fun refresh()
    suspend fun startQrLogin()
    suspend fun cancelQrLogin()
    suspend fun logout()

    /** OkHttp 请求用：`SESSDATA=..; bili_jct=..; DedeUserID=..`；未登录返回空串。 */
    fun cookieHeader(): String

    /** 未登录时也需要的 buvid 等基础 Cookie。 */
    fun baseCookieHeader(): String
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun setAudioQuality(preference: AudioQualityPreference)
    suspend fun setDarkTheme(dark: Boolean?)
}

/** 解析 B 站播放流（带缓存、按需刷新过期地址）。 */
interface MediaStreamResolver {
    suspend fun resolve(album: Album, forceRefresh: Boolean = false): ResolvedStreams
    fun invalidate(albumId: Long)
}
