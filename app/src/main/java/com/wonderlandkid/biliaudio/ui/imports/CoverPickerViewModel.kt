package com.wonderlandkid.biliaudio.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.CoverCandidate
import com.wonderlandkid.biliaudio.data.repository.AlbumCoverRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 封面选择弹层（[com.wonderlandkid.biliaudio.ui.components.CoverPickerSheet]）的搜索状态。
 *
 * 只负责「网易云专辑封面搜索」：query / loading / results / error。
 * 组件本身不写库，选中的封面通过 `CoverPick` 回调给调用方处理。
 */
class CoverPickerViewModel(
    private val albumCoverRepository: AlbumCoverRepository,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val isSearching: Boolean = false,
        val results: List<CoverCandidate> = emptyList(),
        val error: String? = null,
        /** 是否已经执行过搜索（区分初始态与空结果态） */
        val hasSearched: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /** 打开弹层时重置：预填关键词并清空旧结果，避免上次搜索残留。 */
    fun reset(defaultQuery: String) {
        searchJob?.cancel()
        _uiState.value = UiState(query = defaultQuery.trim())
    }

    fun setQuery(text: String) {
        _uiState.update { it.copy(query = text, error = null) }
    }

    /** 搜索网易云专辑封面；[keywordOverride] 为空时使用输入框当前内容。 */
    fun search(keywordOverride: String? = null) {
        val keyword = (keywordOverride ?: _uiState.value.query).trim()
        if (keyword.isEmpty()) {
            _uiState.update {
                it.copy(error = "请输入搜索关键词（专辑名或艺术家）", hasSearched = true)
            }
            return
        }
        searchJob?.cancel()
        _uiState.update {
            it.copy(isSearching = true, error = null, hasSearched = true, results = emptyList())
        }
        searchJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    albumCoverRepository.searchNetEaseAlbums(keyword, limit = 24)
                }
                _uiState.update { it.copy(isSearching = false, results = results) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = "搜索失败：${t.message?.takeIf { msg -> msg.isNotBlank() } ?: "请检查网络后重试"}",
                    )
                }
            }
        }
    }
}
