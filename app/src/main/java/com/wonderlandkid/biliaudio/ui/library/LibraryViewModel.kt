package com.wonderlandkid.biliaudio.ui.library

import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** 艺术家名为空时的统一展示名（也用于分组）。 */
internal const val UNKNOWN_ARTIST = "未知艺术家"

/** 艺术家名规范化：去空白，空值统一为「未知艺术家」。 */
internal fun String.normalizedArtistName(): String = trim().ifBlank { UNKNOWN_ARTIST }

/** 音乐库 Tab。 */
enum class LibraryTab { SONGS, ALBUMS, ARTISTS }

/**
 * 音乐库「艺术家」Tab 的聚合条目：从专辑列表聚合，封面默认取该艺术家第一张专辑。
 */
data class ArtistEntry(
    val name: String,
    /** 该艺术家在专辑列表中的第一张专辑（封面 / 跳转用） */
    val firstAlbum: Album,
    val albumCount: Int,
    val songCount: Int,
) {
    val subtitle: String get() = "$albumCount 张专辑 · $songCount 首歌曲"
}

/**
 * 播放高亮（列表只需要这两个字段）。
 *
 * 播放位置每 250ms tick 一次，若直接把 `PlaybackController.state` 塞进 combine，
 * 会让整页状态流每 tick 重建（含艺术家聚合、大量对象分配）→ 滚动卡顿。
 * 这里 map + distinctUntilChanged，只在「切歌 / 播放暂停状态变化」时更新 UI。
 */
internal data class PlaybackHighlight(
    val trackId: Long?,
    val isPlaying: Boolean,
)

/** 音乐库 UI 状态。 */
@Immutable
data class LibraryUiState(
    val query: String = "",
    val tab: LibraryTab = LibraryTab.SONGS,
    val isLoading: Boolean = true,
    val songs: List<TrackWithAlbum> = emptyList(),
    val albums: List<Album> = emptyList(),
    /** 从 [albums] 聚合出的艺术家（搜索时基于搜索结果） */
    val artists: List<ArtistEntry> = emptyList(),
    /** 当前播放歌曲 id（用于列表高亮） */
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
) {
    val isSearchActive: Boolean get() = query.isNotBlank()

}

/**
 * 音乐库 ViewModel。
 *
 * 搜索走 [AlbumRepository.searchSongs] / [AlbumRepository.searchAlbums]（Room Flow 实时更新），
 * 并对输入做 ~280ms 防抖，避免逐字触发数据库查询；艺术家由专辑结果聚合而来。
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

    /**
     * 库数据（歌曲 / 专辑 / 艺术家）：只在搜索词变化或数据库变化时构建一次。
     *
     * 艺术家聚合同样在这里完成（AlphabeticIndex 分组不便宜），播放进度 tick 不会触发。
     */
    private data class LibraryData(
        val songs: List<TrackWithAlbum>,
        val albums: List<Album>,
        val artists: List<ArtistEntry>,
        val errorMessage: String? = null,
    )

    private val libraryData: Flow<LibraryData?> = searchResults.map { result ->
        if (result == null) {
            null
        } else {
            LibraryData(
                songs = result.songs,
                albums = result.albums,
                artists = buildArtists(albums = result.albums, songs = result.songs),
                errorMessage = result.errorMessage,
            )
        }
    }

    /** 播放状态 → 列表高亮（每 250ms 的位置 tick 被 distinctUntilChanged 过滤掉）。 */
    private val playbackHighlight: Flow<PlaybackHighlight> = playbackController.state
        .map { playback -> PlaybackHighlight(trackId = playback.currentTrack?.id, isPlaying = playback.isPlaying) }
        .distinctUntilChanged()

    val uiState: StateFlow<LibraryUiState> = combine(
        queryFlow,
        tabFlow,
        libraryData,
        playbackHighlight,
    ) { query, tab, data, highlight ->
        LibraryUiState(
            query = query,
            tab = tab,
            isLoading = data == null,
            songs = data?.songs.orEmpty(),
            albums = data?.albums.orEmpty(),
            artists = data?.artists.orEmpty(),
            currentTrackId = highlight.trackId,
            isPlaying = highlight.isPlaying,
            errorMessage = data?.errorMessage,
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

    /**
     * 从专辑聚合艺术家：
     * - 艺术家名去空白，空 → [UNKNOWN_ARTIST]；
     * - 封面 / 跳转用该艺术家在列表中的**第一张专辑**（保持仓库顺序，不随机）；
     * - 歌曲数用当前歌曲列表（搜索时=搜索结果）统计。
     */
    private fun buildArtists(albums: List<Album>, songs: List<TrackWithAlbum>): List<ArtistEntry> {
        if (albums.isEmpty()) return emptyList()
        val songCountByArtist = HashMap<String, Int>()
        for (song in songs) {
            val name = song.album.artist.normalizedArtistName()
            songCountByArtist[name] = (songCountByArtist[name] ?: 0) + 1
        }
        // groupBy 保持 encounter order：firstAlbum = 该艺术家第一张专辑（不随机）
        return albums.groupBy { album -> album.artist.normalizedArtistName() }
            .map { (name, artistAlbums) ->
                ArtistEntry(
                    name = name,
                    firstAlbum = artistAlbums.first(),
                    albumCount = artistAlbums.size,
                    songCount = songCountByArtist[name] ?: 0,
                )
            }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 280L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
