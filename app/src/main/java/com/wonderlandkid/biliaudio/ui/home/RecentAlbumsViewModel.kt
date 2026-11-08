package com.wonderlandkid.biliaudio.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.repository.AlbumRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 最近播放详情页：发现页只露 4 条，这里展示 10 条。 */
class RecentAlbumsViewModel(
    albumRepository: AlbumRepository,
) : ViewModel() {

    val albums: StateFlow<List<Album>> = albumRepository.recentAlbums
        .map { it.take(10) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )
}
