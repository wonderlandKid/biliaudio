package com.wonderlandkid.biliaudio.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditableTrackName(
    val track: Track,
    val title: String,
)

data class MultiPageTrackEditorUiState(
    val isLoading: Boolean = true,
    val album: Album? = null,
    val rows: List<EditableTrackName> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,
) {
    val hasChanges: Boolean
        get() = rows.any { it.title.trim() != it.track.title }

    val canSave: Boolean
        get() = !isLoading && !isSaving && album != null && rows.isNotEmpty() && hasChanges
}

/** 多 P 专辑：编辑每一 P 的曲目名（与单视频的分割点编辑相对应）。 */
class MultiPageTrackEditorViewModel(
    private val albumId: Long,
    private val albumRepository: AlbumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiPageTrackEditorUiState())
    val uiState: StateFlow<MultiPageTrackEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            albumRepository.albumWithTracks(albumId)
                .catch { throwable ->
                    _uiState.update {
                        it.copy(isLoading = false, message = "加载分 P 失败：${throwable.message ?: "未知错误"}")
                    }
                }
                .collect { albumWithTracks ->
                    val tracks = albumWithTracks?.tracks.orEmpty()
                        .sortedBy { track -> track.startMs }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            album = albumWithTracks?.album,
                            rows = tracks.map { track -> EditableTrackName(track, track.title) },
                        )
                    }
                }
        }
    }

    fun updateTitle(trackId: Long, title: String) {
        _uiState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    if (row.track.id == trackId) row.copy(title = title) else row
                },
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            try {
                val updated = state.rows.mapIndexed { index, row ->
                    row.track.copy(
                        // 留空时回退到 P 序号/曲目序号，避免数据库里出现空标题
                        title = row.title.trim().ifEmpty {
                            row.track.sourcePage.takeIf { it > 0 }?.let { "P$it" } ?: "曲目 ${index + 1}"
                        },
                    )
                }
                albumRepository.replaceTracks(albumId, updated)
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (throwable: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, message = "保存失败：${throwable.message ?: "未知错误"}")
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
