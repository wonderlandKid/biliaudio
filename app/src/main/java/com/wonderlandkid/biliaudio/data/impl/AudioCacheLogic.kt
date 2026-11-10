package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.Track
import com.wonderlandkid.biliaudio.data.repository.CachedAlbumAudio

/** 一个待缓存的分 P（cid + 分 P 序号）。 */
internal data class CachePage(
    val cid: Long,
    val page: Int,
)

/** 缓存文件元数据（properties 的纯数据视图，便于单测聚合逻辑，不依赖 Android）。 */
internal data class CachedAudioMeta(
    val key: String,
    val albumId: Long,
    val bvid: String,
    val cid: Long,
    val page: Int,
    val audioQualityId: Int,
    val audioQualityLabel: String,
    val bytes: Long,
    val cachedAtMs: Long,
)

/**
 * 音频缓存的纯逻辑（无 Android 依赖，可 JVM 单测）。
 *
 * 文件布局：`files/audio_cache/<bvid>_<cid>.m4a` + 同名 `.properties`；
 * 一张专辑可能有多个分 P（多个 cid），对外按专辑（albumId，历史遗留用 bvid）聚合。
 */
internal object AudioCacheLogic {

    /** 缓存文件 key：`<bvid>_<cid>`。 */
    fun keyOf(bvid: String, cid: Long): String = "${bvid}_$cid"

    /**
     * 需要缓存的分 P：取 {track.sourceCid>0 ? sourceCid : album.cid} 去重（保持曲目顺序）。
     * 曲目为空（未分割专辑）时回退为专辑自己的 cid。
     */
    fun targetPages(albumCid: Long, albumPage: Int, tracks: List<Track>): List<CachePage> {
        val ordered = LinkedHashMap<Long, CachePage>()
        tracks.forEach { track ->
            val cid = if (track.sourceCid > 0L) track.sourceCid else albumCid
            if (cid <= 0L) return@forEach
            val page = if (track.sourceCid > 0L) {
                track.sourcePage.takeIf { it > 0 } ?: 1
            } else {
                albumPage.takeIf { it > 0 } ?: 1
            }
            ordered.putIfAbsent(cid, CachePage(cid = cid, page = page))
        }
        if (ordered.isEmpty() && albumCid > 0L) {
            ordered[albumCid] = CachePage(cid = albumCid, page = albumPage.takeIf { it > 0 } ?: 1)
        }
        return ordered.values.toList()
    }

    /**
     * 缓存管理页聚合：albumId>0 时按 albumId 聚合（多 P 合并成一行），
     * 元数据缺失的历史文件按 bvid 兜底；结果按更新时间从新到旧。
     */
    fun groupByAlbum(entries: List<CachedAudioMeta>): List<CachedAlbumAudio> {
        val groups = LinkedHashMap<Pair<Long, String>, MutableList<CachedAudioMeta>>()
        entries.forEach { entry ->
            val groupKey = if (entry.albumId > 0L) entry.albumId to "" else 0L to entry.bvid
            groups.getOrPut(groupKey) { mutableListOf() }.add(entry)
        }
        return groups.map { (groupKey, groupEntries) ->
            val sorted = groupEntries.sortedBy { it.cachedAtMs }
            val latest = sorted.lastOrNull()
            CachedAlbumAudio(
                albumId = groupKey.first,
                bvid = groupKey.second.ifBlank { sorted.first().bvid },
                cids = sorted.map { it.cid }.distinct(),
                bytes = sorted.sumOf { it.bytes },
                audioQualityLabel = latest?.audioQualityLabel?.takeIf { it.isNotBlank() },
                updatedAtMs = sorted.maxOfOrNull { it.cachedAtMs } ?: 0L,
            )
        }.sortedByDescending { it.updatedAtMs }
    }

    /**
     * remove(album) 要删除的 key：优先按元数据 albumId 匹配该专辑所有分 P，
     * 再兼容 bvid+cid 回退（元数据缺失/历史遗留）；最后无条件包含专辑当前分 P 的固定 key。
     */
    fun keysToRemove(
        albumId: Long,
        bvid: String,
        cid: Long,
        entries: List<CachedAudioMeta>,
    ): Set<String> {
        val keys = LinkedHashSet<String>()
        entries.forEach { entry ->
            val matchesAlbum = albumId > 0L && entry.albumId == albumId
            val matchesKey = entry.bvid == bvid && entry.cid == cid
            if (matchesAlbum || matchesKey) keys.add(entry.key)
        }
        if (cid > 0L) keys.add(keyOf(bvid, cid))
        return keys
    }

    /** removeCached(汇总) 要删除的 key：albumId>0 按 albumId 匹配，否则按 bvid；并兜底汇总里的 cids。 */
    fun keysToRemoveForGroup(item: CachedAlbumAudio, entries: List<CachedAudioMeta>): Set<String> {
        val keys = LinkedHashSet<String>()
        entries.forEach { entry ->
            val matches = if (item.albumId > 0L) {
                entry.albumId == item.albumId
            } else {
                entry.bvid == item.bvid
            }
            if (matches) keys.add(entry.key)
        }
        item.cids.forEach { itemCid -> keys.add(keyOf(item.bvid, itemCid)) }
        return keys
    }

    /** 专辑详情「已缓存 x/y 分P」：只保留目标分 P 中真正有缓存的条目并汇总。 */
    fun summarize(
        albumId: Long,
        bvid: String,
        targets: List<CachePage>,
        cached: List<CachedAudioMeta>,
    ): CachedAlbumAudio {
        val cachedByCid = cached.associateBy { it.cid }
        val matched = targets.mapNotNull { target -> cachedByCid[target.cid] }
        val latest = matched.maxByOrNull { it.cachedAtMs }
        return CachedAlbumAudio(
            albumId = albumId,
            bvid = bvid,
            cids = matched.map { it.cid },
            bytes = matched.sumOf { it.bytes },
            audioQualityLabel = latest?.audioQualityLabel?.takeIf { it.isNotBlank() },
            updatedAtMs = matched.maxOfOrNull { it.cachedAtMs } ?: 0L,
        )
    }
}
