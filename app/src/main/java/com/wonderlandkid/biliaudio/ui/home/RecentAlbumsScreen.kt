package com.wonderlandkid.biliaudio.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.BiliAlbumRow
import com.wonderlandkid.biliaudio.ui.components.BiliGroup
import com.wonderlandkid.biliaudio.ui.components.EmptyState
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.BiliLayout
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/** 最近播放（发现页「查看全部」）：最多 10 张最近听过的专辑。 */
@Composable
fun RecentAlbumsScreen(
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RecentAlbumsViewModel = appViewModel { container ->
        RecentAlbumsViewModel(albumRepository = container.albumRepository)
    }
    val albums by viewModel.albums.collectAsStateWithLifecycle()

    BiliDetailScaffold(
        onBack = onBack,
        title = "最近播放",
        modifier = modifier,
    ) { innerPadding ->
        if (albums.isEmpty()) {
            EmptyState(
                title = "还没有最近播放",
                description = "播放专辑后，这里会显示最近听过的 10 张专辑。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = BiliLayout.pageHorizontal,
                    end = BiliLayout.pageHorizontal,
                    top = Spacing.sm,
                    bottom = BiliLayout.contentBottomWithMiniPlayer,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(albums, key = { it.id }) { album ->
                    BiliGroup(contentPadding = PaddingValues(0.dp)) {
                        BiliAlbumRow(
                            album = album,
                            onClick = { onOpenAlbum(album.id) },
                        )
                    }
                }
            }
        }
    }
}
