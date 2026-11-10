package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.db.AppDatabase
import com.wonderlandkid.biliaudio.data.db.LyricEntity
import com.wonderlandkid.biliaudio.data.db.toModel
import com.wonderlandkid.biliaudio.data.model.LyricSourceType
import com.wonderlandkid.biliaudio.data.model.OnlineLyricCandidate
import com.wonderlandkid.biliaudio.data.model.StoredLyrics
import com.wonderlandkid.biliaudio.data.net.LyricsApiClient
import com.wonderlandkid.biliaudio.data.repository.LyricsRepository

/**
 * 歌词仓库实现：在线搜索委托 [LyricsApiClient]，本机快照存 Room 表 `lyrics`。
 *
 * 保存语义（与 [LyricsRepository.save] 契约一致）：
 * - 自动匹配保存（`isManual = false`）不会覆盖用户手动选择的歌词，也不会把「已关闭」的歌词重新打开；
 * - 用户手动选择（`isManual = true`）覆盖已有快照并清掉 `suppressed`；
 * - 偏移只更新已有快照（[updateDelay]），关闭歌词（[suppress]）在无快照时补一条空快照行，
 *   这样「关闭状态」也能持久化，重进 App 仍显示「已关闭歌词」。
 */
class LyricsRepositoryImpl(
    private val database: AppDatabase,
    private val api: LyricsApiClient,
) : LyricsRepository {

    private val lyricsDao = database.lyricsDao()

    override suspend fun searchOnline(title: String, artist: String): List<OnlineLyricCandidate> =
        api.search(title = title, artist = artist)

    override suspend fun getStored(trackUid: String): StoredLyrics? {
        if (trackUid.isBlank()) return null
        return lyricsDao.getByTrackUid(trackUid)?.toModel()
    }

    override suspend fun save(
        trackUid: String,
        rawLyrics: String,
        sourceType: LyricSourceType,
        sourceName: String,
        delayMs: Long,
        isManual: Boolean,
    ): StoredLyrics {
        if (trackUid.isBlank()) {
            return StoredLyrics(
                trackUid = trackUid,
                rawLyrics = rawLyrics,
                sourceType = sourceType,
                sourceName = sourceName,
                delayMs = delayMs,
                isManual = isManual,
                suppressed = false,
                updatedAtMs = 0L,
            )
        }

        val existing = lyricsDao.getByTrackUid(trackUid)
        // 自动匹配不能覆盖用户手动选择 / 已关闭歌词
        if (!isManual && existing != null && (existing.isManual || existing.suppressed)) {
            return existing.toModel()
        }

        val entity = LyricEntity(
            trackUid = trackUid,
            rawLyrics = rawLyrics,
            sourceType = sourceType.name,
            sourceName = sourceName,
            delayMs = delayMs,
            isManual = isManual,
            // 手动保存会清掉「已关闭」；自动保存路径不会遇到 suppressed 行（上面已回退）
            suppressed = false,
            updatedAtMs = System.currentTimeMillis(),
        )
        lyricsDao.upsert(entity)
        return entity.toModel()
    }

    override suspend fun updateDelay(trackUid: String, delayMs: Long) {
        if (trackUid.isBlank()) return
        // 只更新已有行；没有快照时忽略
        lyricsDao.updateDelay(
            trackUid = trackUid,
            delayMs = delayMs,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    override suspend fun suppress(trackUid: String) {
        if (trackUid.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = lyricsDao.markSuppressed(trackUid, now)
        if (updated > 0) return
        // 没有快照时补一条空快照行，保证「关闭状态」可持久化
        lyricsDao.upsert(
            LyricEntity(
                trackUid = trackUid,
                rawLyrics = "",
                sourceType = LyricSourceType.NONE.name,
                sourceName = "",
                delayMs = 0L,
                isManual = false,
                suppressed = true,
                updatedAtMs = now,
            ),
        )
    }

    override suspend fun clear(trackUid: String) {
        if (trackUid.isBlank()) return
        lyricsDao.delete(trackUid)
    }
}
