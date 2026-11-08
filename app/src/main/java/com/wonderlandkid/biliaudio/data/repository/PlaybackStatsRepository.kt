package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.AlbumListeningStat
import com.wonderlandkid.biliaudio.data.model.TrackPlayStat
import kotlinx.coroutines.flow.Flow

/**
 * 听歌统计（只记录真实播放行为，不因浏览/导入产生数据）。
 *
 * - 播放次数：歌曲开始播放时 +1；
 * - 收听时长：播放器 isPlaying 时按实际经过的时间累计（暂停/切歌/退出时落库）。
 */
interface PlaybackStatsRepository {
    /** 歌曲开始播放（每次切换/开始播放记一次）。 */
    suspend fun recordTrackStarted(albumId: Long, trackUid: String)

    /** 累加实际收听时长（毫秒），由播放引擎按小批量 flush。 */
    suspend fun addListeningTime(albumId: Long, trackUid: String, deltaMs: Long)

    fun observeAlbumListening(limit: Int = 50): Flow<List<AlbumListeningStat>>
    fun observeTrackPlayCounts(limit: Int = 100): Flow<List<TrackPlayStat>>
}
