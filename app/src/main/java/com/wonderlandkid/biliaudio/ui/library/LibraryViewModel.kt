package com.wonderlandkid.biliaudio.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.player.PlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** 音乐库 Tab。 */
enum class LibraryTab { SONGS, ALBUMS }

/** 音乐库 UI 状态。 */
data class LibraryUiState(
    val query: String = "",
    val tab: LibraryTab = LibraryTab.SONGS,
    val isLoading: Boolean = true,
    val songs: List<TrackWithAlbum> = emptyList(),
    val albums: List<Album> = emptyList(),
    /** 当前播放歌曲 id（用于列表高亮） */
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
) {
    val isSearchActive: Boolean get() = query.isNotBlank()

    val songsCountText: String
        get() = if (isSearchActive) "找到 ${songs.size} 首歌曲" else "${songs.size} 首歌曲"

    val albumsCountText: String
        get() = if (isSearchActive) "找到 ${albums.size} 张专辑" else "${albums.size} 张专辑"
}

/**
 * 音乐库 ViewModel。
 *
 * 搜索走 [AlbumRepository.searchSongs] / [AlbumRepository.searchAlbums]（Room Flow 实时更新），
 * 并对输入做 ~280ms 防抖，避免逐字触发数据库查询。
 */
class LibraryViewModel(
    private val albumRepository: AlbumRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private data class SearchResult(
        val query: String,
        val songs: List<TrackWithAlbum>,
        val albums: List<Album>,
        val errorMessage: String? = null,
    )

    private val queryFlow = MutableStateFlow("")
    private val tabFlow = MutableStateFlow(LibraryTab.SONGS)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchResults: Flow<SearchResult?> = queryFlow
        // 防抖：非空查询延迟一小段时间再检索；空查询（全部）立即生效
        .flatMapLatest { query ->
            flow {
                if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
                emit(query)
            }
        }
        .flatMapLatest { query ->
            combine(
                albumRepository.searchSongs(query),
                albumRepository.searchAlbums(query),
            ) { songs, albums ->
                SearchResult(query = query, songs = songs, albums = albums)
            }.catch { throwable ->
                emit(
                    SearchResult(
                        query = query,
                        songs = emptyList(),
                        albums = emptyList(),
                        errorMessage = throwable.message ?: "搜索失败，请稍后重试",
                    ),
                )
            }
        }
        // 映射成可空类型：null = 首次结果还没到，用于展示加载态
        .map<SearchResult, SearchResult?> { result -> result }
        .onStart { emit(null) }

    val uiState: StateFlow<LibraryUiState> = combine(
        queryFlow,
        tabFlow,
        searchResults,
        playbackController.state,
    ) { query, tab, result, playback ->
        LibraryUiState(
            query = query,
            tab = tab,
            isLoading = result == null,
            songs = result?.songs.orEmpty(),
            albums = result?.albums.orEmpty(),
            currentTrackId = playback.currentTrack?.id,
            isPlaying = playback.isPlaying,
            errorMessage = result?.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = LibraryUiState(),
    )

    /** 搜索框输入。 */
    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun clearQuery() {
        queryFlow.value = ""
    }

    fun selectTab(tab: LibraryTab) {
        tabFlow.value = tab
    }

    /**
     * 点击单曲：只播放这首歌**所属的专辑**（队列 = 该专辑分曲，从选中曲开始）。
     *
     * 注意与 [playAllSongs] 区分：点击单曲不会把整个音乐库变成播放列表；
     * 只有显式点「播放全部歌曲」才会以当前歌曲列表作为队列。
     */
    fun playSong(index: Int) {
        val song = uiState.value.songs.getOrNull(index) ?: return
        playbackController.playTrack(albumId = song.album.id, trackId = song.track.id)
    }

    /** 播放当前歌曲列表全部（从第一首开始，显式行为）。 */
    fun playAllSongs() {
        val songs = uiState.value.songs
        if (songs.isNotEmpty()) {
            playbackController.playSongs(songs, 0)
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 280L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
