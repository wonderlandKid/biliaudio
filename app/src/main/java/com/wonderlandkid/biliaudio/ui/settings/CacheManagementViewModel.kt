package com.wonderlandkid.biliaudio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import com.wonderlandkid.biliaudio.data.repository.AudioCacheRepository
import com.wonderlandkid.biliaudio.data.repository.CachedMediaSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CachedItemUi(
    val summary: CachedMediaSummary,
    val album: Album?,
) {
    val key: String get() = "${summary.bvid}_${summary.cid}"
}

data class CacheManagementUiState(
    val isLoading: Boolean = true,
    val items: List<CachedItemUi> = emptyList(),
    val totalBytes: Long = 0L,
    val isClearing: Boolean = false,
    val message: String? = null,
)

/**
 * 缓存管理页：列出应用私有目录里的专辑音频/歌词视频缓存，支持逐条删除与清空。
 *
 * 缓存本身不写数据库；这里通过 AudioCacheRepository 扫目录，再按 albumId 关联专辑标题/封面。
 * 若专辑已被删除但缓存文件还在（历史遗留），仍会展示并允许删除。
 */
class CacheManagementViewModel(
    private val cacheRepository: AudioCacheRepository,
    private val albumRepository: AlbumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CacheManagementUiState())
    val uiState: StateFlow<CacheManagementUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val summaries = cacheRepository.listCached()
                val albums = albumRepository.albums.first().associateBy { album -> album.id }
                val items = summaries.map { summary -> CachedItemUi(summary, albums[summary.albumId]) }
                items to items.sumOf { it.summary.totalBytes }
            }.onSuccess { (items, total) ->
                _uiState.update {
                    it.copy(isLoading = false, items = items, totalBytes = total)
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "读取缓存失败：${throwable.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun delete(item: CachedItemUi) {
        viewModelScope.launch {
            runCatching { cacheRepository.removeCached(item.summary) }
                .onSuccess {
                    _uiState.update { state ->
                        val left = state.items.filterNot { it.key == item.key }
                        state.copy(
                            items = left,
                            totalBytes = left.sumOf { it.summary.totalBytes },
                            message = "已删除缓存",
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(message = "删除失败：${throwable.message ?: "未知错误"}") }
                }
        }
    }

    fun clearAll() {
        if (_uiState.value.isClearing || _uiState.value.items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true) }
            runCatching { cacheRepository.clearAll() }
                .onSuccess {
                    _uiState.update {
                        it.copy(isClearing = false, items = emptyList(), totalBytes = 0L, message = "已清空全部缓存")
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(isClearing = false, message = "清空失败：${throwable.message ?: "未知错误"}")
                    }
                }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
