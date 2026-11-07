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
import com.wonderlandkid.biliaudio.data.model.CoverCandidate
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

    /**
     * 更新专辑自定义封面：`coverLocalPath = null` 表示恢复 B 站原始封面。
     * @return 是否更新成功（专辑存在）
     */
    suspend fun updateAlbumCover(albumId: Long, coverLocalPath: String?): Boolean

    /** 记录播放（最近播放排序、播放次数）。 */
    suspend fun markPlayed(albumId: Long, trackId: Long)

    /**
     * 清理旧版本遗留的「整张专辑占位曲」：
     * 旧版未分割导入会生成一条 title=专辑名、0→专辑总时长的单曲；
     * 新版未分割专辑应显示 0 首（播放时虚拟整张），这里将其删除。
     *
     * @return 清理掉的占位曲数量
     */
    suspend fun cleanupLegacyPlaceholderTracks(): Int

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

/** 在线封面搜索（网易云音乐）+ 封面文件存取。 */
interface AlbumCoverRepository {
    /**
     * 搜索专辑封面（网易云专辑搜索）。
     * @param query 关键词（通常为专辑名 + 艺术家）
     */
    suspend fun searchNetEaseAlbums(query: String, limit: Int = 20): List<CoverCandidate>

    /** 下载远程封面图片字节；失败返回 null。 */
    suspend fun fetchCoverBytes(url: String): ByteArray?

    /**
     * 保存专辑封面（把图片解码、缩放并压缩为 JPEG 存到应用私有目录）。
     * @return 保存后的本地绝对路径；图片无效返回 null
     */
    suspend fun saveAlbumCover(albumId: Long, bytes: ByteArray): String?

    /** 删除本地封面文件（不修改数据库字段）。 */
    fun deleteAlbumCoverFile(path: String?)
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

    /** 维测日志开关：关闭后不再记录/打印诊断日志。 */
    suspend fun setDiagnosticLogsEnabled(enabled: Boolean)
}

/** 解析 B 站播放流（带缓存、按需刷新过期地址）。 */
interface MediaStreamResolver {
    suspend fun resolve(album: Album, forceRefresh: Boolean = false): ResolvedStreams
    fun invalidate(albumId: Long)
}
