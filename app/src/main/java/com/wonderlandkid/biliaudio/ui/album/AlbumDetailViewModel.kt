package com.wonderlandkid.biliaudio.ui.album

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.AlbumCoverRepository
import com.wonderlandkid.biliaudio.data.repository.AudioCacheRepository
import com.wonderlandkid.biliaudio.data.repository.CachedAudio
import com.wonderlandkid.biliaudio.data.repository.CachedVideo
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.BiliRepository
import com.wonderlandkid.biliaudio.data.repository.LibraryArchiveRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import com.wonderlandkid.biliaudio.ui.components.CoverPick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 专辑详情 UI 状态。 */
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    /** 当前播放曲目（用于高亮） */
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    /** 播放器里正在播放的是否就是本专辑 */
    val isThisAlbumPlaying: Boolean = false,
    val isRefreshing: Boolean = false,
    val isDeleting: Boolean = false,
    /** 正在导出专辑文件（菜单项置灰 + 顶栏进度） */
    val isExporting: Boolean = false,
    /** 正在保存/应用新的自定义封面 */
    val isApplyingCover: Boolean = false,
    /** 本专辑已缓存的整段音频（null = 未缓存） */
    val cachedAudio: CachedAudio? = null,
    /** 正在下载缓存 */
    val isCaching: Boolean = false,
    /** 缓存进度 0..1（总大小未知时 null） */
    val cacheProgress: Float? = null,
    /** 本专辑已缓存的歌词视频（null = 未缓存） */
    val cachedVideo: CachedVideo? = null,
    val isCachingVideo: Boolean = false,
    val videoCacheProgress: Float? = null,
    /** 删除成功后由页面收口返回上一页 */
    val deleted: Boolean = false,
    /** 一次性提示（Snackbar） */
    val message: String? = null,
)

/**
 * 专辑详情 ViewModel：订阅 [AlbumRepository.albumWithTracks] 实时数据，并桥接播放状态。
 */
class AlbumDetailViewModel(
    private val albumId: Long,
    private val albumRepository: AlbumRepository,
    private val biliRepository: BiliRepository,
    private val playbackController: PlaybackController,
    private val libraryArchiveRepository: LibraryArchiveRepository,
    /** 应用级 Context，仅用于读写用户通过 SAF 选择的文件 */
    private val appContext: Context,
    private val albumCoverRepository: AlbumCoverRepository,
    private val audioCacheRepository: AudioCacheRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        observeAlbum()
        observePlayback()
    }

    private fun observeAlbum() {
        viewModelScope.launch {
            albumRepository.albumWithTracks(albumId)
                .catch { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "加载专辑失败：${throwable.message ?: "未知错误"}",
                        )
                    }
                }
                .collect { albumWithTracks ->
                    val cachedAudio = albumWithTracks?.album?.let(audioCacheRepository::getCached)
                    val cachedVideo = albumWithTracks?.album?.let(audioCacheRepository::getCachedVideo)
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            album = albumWithTracks?.album,
                            tracks = albumWithTracks?.tracks.orEmpty().sortedBy { track -> track.startMs },
                            cachedAudio = cachedAudio,
                            cachedVideo = cachedVideo,
                        )
                    }
                }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playbackController.state.collect { playback ->
                _uiState.update {
                    it.copy(
                        currentTrackId = playback.currentTrack?.id,
                        isPlaying = playback.isPlaying,
                        isThisAlbumPlaying = playback.currentAlbumId == albumId && playback.isPlaying,
                    )
                }
            }
        }
    }

    /** 播放全部（队列 = 该专辑全部分曲）。 */
    fun playAll() {
        playbackController.playAlbum(albumId)
    }

    /** 播放专辑内某一首。 */
    fun playTrack(trackId: Long) {
        playbackController.playTrack(albumId, trackId)
    }

    /**
     * 更多菜单 → 缓存/删除整张专辑音频。
     *
     * 手动触发，避免用户没决定就消耗流量；缓存的是当前音质档位的完整 DASH 音频。
     */
    fun toggleAudioCache() {
        val album = _uiState.value.album ?: return
        if (_uiState.value.isCaching) return
        viewModelScope.launch {
            if (_uiState.value.cachedAudio != null) {
                audioCacheRepository.remove(album)
                _uiState.update { it.copy(cachedAudio = null, message = "已删除音频缓存") }
                return@launch
            }
            _uiState.update { it.copy(isCaching = true, cacheProgress = 0f) }
            try {
                val cached = audioCacheRepository.cache(album) { downloaded, total ->
                    val progress = if (total != null && total > 0L) {
                        downloaded.toFloat() / total.toFloat()
                    } else {
                        null
                    }
                    _uiState.update { it.copy(cacheProgress = progress) }
                }
                val size = android.text.format.Formatter.formatShortFileSize(appContext, cached.bytes)
                _uiState.update {
                    it.copy(
                        isCaching = false,
                        cacheProgress = null,
                        cachedAudio = cached,
                        message = "已缓存整张专辑音频（$size）",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isCaching = false,
                        cacheProgress = null,
                        message = "缓存失败：${t.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /**
     * 更多菜单 → 缓存/删除歌词视频。
     *
     * 视频缓存的是整段原始视频（不是按曲目切段），文件可能很大；UI 层负责先弹确认。
     */
    fun toggleVideoCache() {
        val album = _uiState.value.album ?: return
        if (_uiState.value.isCachingVideo) return
        viewModelScope.launch {
            if (_uiState.value.cachedVideo != null) {
                audioCacheRepository.removeVideo(album)
                _uiState.update { it.copy(cachedVideo = null, message = "已删除歌词视频缓存") }
                return@launch
            }
            _uiState.update { it.copy(isCachingVideo = true, videoCacheProgress = 0f) }
            try {
                val cached = audioCacheRepository.cacheVideo(album) { downloaded, total ->
                    val progress = if (total != null && total > 0L) {
                        downloaded.toFloat() / total.toFloat()
                    } else {
                        null
                    }
                    _uiState.update { it.copy(videoCacheProgress = progress) }
                }
                val size = android.text.format.Formatter.formatShortFileSize(appContext, cached.bytes)
                _uiState.update {
                    it.copy(
                        isCachingVideo = false,
                        videoCacheProgress = null,
                        cachedVideo = cached,
                        message = "已缓存歌词视频（${cached.videoHeight?.let { "${it}P" } ?: "视频"} · $size）",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isCachingVideo = false,
                        videoCacheProgress = null,
                        message = "视频缓存失败：${t.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** 更多菜单 → 刷新视频信息（标题/艺术家/封面/时长/分 P 信息）。 */
    fun refreshVideoInfo() {
        val album = _uiState.value.album ?: return
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val info = biliRepository.previewVideo(album.bvid)
                albumRepository.updateAlbum(
                    album.copy(
                        title = info.title,
                        artist = info.ownerName,
                        coverUrl = info.coverUrl,
                        durationMs = info.durationMs,
                        cid = info.pages.firstOrNull()?.cid ?: album.cid,
                        page = info.pages.firstOrNull()?.page ?: album.page,
                        pageCount = info.pages.size,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                _uiState.update { it.copy(isRefreshing = false, message = "视频信息已刷新") }
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        message = "刷新失败：${throwable.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** 更多菜单 → 删除专辑（确认弹窗由页面负责）。 */
    fun deleteAlbum() {
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                _uiState.value.album?.let { album ->
                    audioCacheRepository.remove(album)
                    audioCacheRepository.removeVideo(album)
                }
                albumRepository.deleteAlbum(albumId)
                _uiState.update { it.copy(isDeleting = false, deleted = true) }
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        message = "删除失败：${throwable.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 自定义封面
    // ------------------------------------------------------------------

    /**
     * 应用用户选择的封面（相册本地图片 / 网易云在线封面 / 恢复 B 站原始封面）。
     *
     * - 文件保存、下载、写库都在 IO 线程执行；
     * - 成功后 Room Flow 会自动刷新页面封面，UI 无需手动改状态；
     * - 结果通过一次性 [AlbumDetailUiState.message] 走 Snackbar。
     */
    fun applyCover(albumId: Long, pick: CoverPick) {
        if (_uiState.value.isApplyingCover) return
        // 恢复 B 站封面时用于删除旧的本地封面文件
        val oldLocalPath = _uiState.value.album?.coverLocalPath
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingCover = true) }
            val errorMessage = try {
                withContext(Dispatchers.IO) { applyCoverInternal(albumId, oldLocalPath, pick) }
                null
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                throwable.message?.takeIf { it.isNotBlank() } ?: "未知错误"
            }
            _uiState.update {
                it.copy(
                    isApplyingCover = false,
                    message = errorMessage?.let { reason -> "封面更新失败：$reason" } ?: "封面已更新",
                )
            }
        }
    }

    /** 真正执行封面保存/写库；失败抛异常，message 为可直接展示的中文提示。 */
    private suspend fun applyCoverInternal(albumId: Long, oldLocalPath: String?, pick: CoverPick) {
        when (pick) {
            is CoverPick.LocalImage -> {
                if (pick.bytes.isEmpty()) throw IllegalStateException("读取到的图片为空")
                val path = albumCoverRepository.saveAlbumCover(albumId, pick.bytes)
                    ?: throw IllegalStateException("封面保存失败（图片格式不支持或存储空间不足）")
                if (!albumRepository.updateAlbumCover(albumId, path)) {
                    albumCoverRepository.deleteAlbumCoverFile(path)
                    throw IllegalStateException("封面写入数据库失败")
                }
            }

            is CoverPick.NetEase -> {
                val bytes = albumCoverRepository.fetchCoverBytes(pick.candidate.fullUrl)
                    ?: throw IllegalStateException("封面下载失败，请检查网络后重试")
                val path = albumCoverRepository.saveAlbumCover(albumId, bytes)
                    ?: throw IllegalStateException("封面保存失败（图片格式不支持或存储空间不足）")
                if (!albumRepository.updateAlbumCover(albumId, path)) {
                    albumCoverRepository.deleteAlbumCoverFile(path)
                    throw IllegalStateException("封面写入数据库失败")
                }
            }

            CoverPick.RestoreBilibili -> {
                if (!albumRepository.updateAlbumCover(albumId, null)) {
                    throw IllegalStateException("恢复 B 站封面失败")
                }
                // 写库成功后再删旧文件，避免旧封面丢失但数据库还没恢复
                albumCoverRepository.deleteAlbumCoverFile(oldLocalPath)
            }
        }
    }

    /**
     * 封面大图模式 → 保存封面到用户通过 SAF 选择的文件。
     *
     * 优先读用户自定义封面的本地文件；没有自定义封面时下载 B 站封面。
     * 读文件/下载/写目标文件都在 IO 线程，结果走一次性 Snackbar。
     */
    fun saveCoverTo(uri: android.net.Uri) {
        val album = _uiState.value.album ?: return
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val bytes = album.coverLocalPath
                        ?.let { path -> File(path).takeIf { it.isFile }?.readBytes() }
                        ?: album.coverUrl
                            .takeIf { it.isNotBlank() }
                            ?.let { albumCoverRepository.fetchCoverBytes(it) }
                        ?: throw IllegalStateException("没有可保存的封面")
                    val output = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开目标文件")
                    output.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    }
                }
                "封面已保存"
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "保存封面失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
            }
            _uiState.update { it.copy(message = message) }
        }
    }

    /**
     * 更多菜单 → 导出专辑文件：把单专辑 JSON 写入系统文件选择器（SAF）返回的 [uri]。
     *
     * 读库 / 序列化 / 写文件都在 IO 线程完成；结果通过一次性 [AlbumDetailUiState.message]
     * 走现有 Snackbar 通道提示。
     */
    fun exportAlbumTo(uri: Uri) {
        if (_uiState.value.isExporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val resultMessage = try {
                withContext(Dispatchers.IO) {
                    // 1) 生成单专辑归档 JSON（专辑不存在返回 null）
                    val json = libraryArchiveRepository.exportAlbumJson(albumId)
                        ?: return@withContext "导出失败：专辑不存在"
                    // 2) 写入用户选中的文件（UTF-8）
                    val output = appContext.contentResolver.openOutputStream(uri)
                        ?: return@withContext "导出失败：无法写入所选文件"
                    output.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    }
                    "已导出专辑文件"
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (security: SecurityException) {
                "导出失败：没有文件写入权限"
            } catch (io: IOException) {
                "导出失败：无法写入所选文件（存储空间不足或没有权限）"
            } catch (throwable: Throwable) {
                "导出失败：${throwable.message?.takeIf { it.isNotBlank() } ?: "写入文件时出错"}"
            }
            _uiState.update { it.copy(isExporting = false, message = resultMessage) }
        }
    }

    /** Snackbar 展示过后清空一次性提示。 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
