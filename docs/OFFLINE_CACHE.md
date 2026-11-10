# 专辑音频离线缓存（v0.27.0，方案与边界）

> 结论：**只缓存音频，且按「分 P」缓存**；不需要改归档格式。
> 这不是“把每首歌分别下载”，而是把「一张专辑 = 一个 B 站视频/分 P」的**整段 DASH 音频**缓存一次：
> 分曲只是同一音频流上的时间裁剪，所以缓存一次，离线时该 P 的所有分曲都能播放。
> 多 P 专辑会按曲目的 `Track.sourceCid` 逐 P 缓存，整张专辑离线可用。
>
> v0.27.0 起**歌词视频缓存已整体移除**（用户明确不要「看歌词视频」功能），
> 旧版本遗留的 `<bvid>_<cid>_video.*` 文件会在打开缓存管理页/删除/清空时自动清理。

## 1. 为什么可行

- 播放模型是「专辑音频流 + `ClippingConfiguration` 切分曲」，不是每首歌一个独立文件；
- B 站 `playurl` 返回完整 DASH 音频流，音频下载一次即可；
- 缓存到应用私有目录后，播放 URL 失效/断网都不影响本地文件播放；
- 不需要改 Room schema、不需要改归档 v3（缓存是设备本地数据，不进入备份）。

## 2. 当前行为（v0.27.0）

入口：专辑详情 → 右上角更多 → **缓存整张专辑音频（N 个分P）**。

- 缓存粒度 = 分 P：单 P 专辑就是整张专辑；多 P 专辑按曲目的 `sourceCid` 去重后逐 P 下载，
  每个分 P 下载一次（同一 P 的多首分曲共用同一音频文件）；
- 下载当前音质档位的完整音频文件；进度按全部分 P 字节汇总（`缓存中 42%…`）；
- 已缓存的分 P 直接跳过，因此支持**补缓存**：菜单显示「补缓存音频（已缓存 x/y 分P）」；
- 全部缓存后菜单变为「删除专辑音频缓存（192K · x 个分P）」，点击删除该专辑所有分 P；
- 之后播放该专辑自动使用本地缓存，离线也能播放，不再消耗音频流量；
- 播放引擎对多 P 曲目用 `album.copy(cid = track.sourceCid, page = track.sourcePage)` 查
  `bvid + cid` 缓存，命中后直接播本地文件；
- 删除专辑时一起清理该专辑所有分 P 缓存；
- 缓存文件：`files/audio_cache/<bvid>_<cid>.m4a` + 同名 `.properties` 元数据
  （含 albumId/bvid/cid/page/qualityId/qualityLabel/cachedAtMs）；
- 下载失败/文件不完整会清理临时 `.part` 并提示（错误信息带“第 k/N 个分P失败、已缓存 x/N”）；
  文件长度与 Content-Length 不一致视为失败。

## 3. 多 P 专辑（v0.27.0 修复）

v0.25.0 多 P 导入后，旧的缓存实现仍以「专辑 = 一个 cid」为单位（取 `album.cid`，即首 P），
导致多 P 专辑只缓存首 P、其他分 P 离线仍需联网，且 UI 的“已缓存”只反映首 P。

v0.27.0 起：

- `AudioCacheRepository.cache(album, tracks, onProgress)` 对
  `{Track.sourceCid > 0 ? sourceCid : album.cid}` 去重后逐 P 缓存；
- 专辑详情缓存状态用 `getCachedSummary(album, tracks)` 汇总「已缓存 x/y 分P + 总大小」；
- 缓存管理页按专辑聚合：已缓存分 P 数、总大小、音质档位、更新时间。

## 4. 缓存管理（v0.20.0 起，v0.27.0 只保留音频）

入口：**设置 → 存储 → 缓存管理**。

- 顶部：已缓存专辑数 + 总占用空间；
- 每张专辑一行：封面/标题/艺术家、音频档位与大小、分 P 数、更新时间；
- 单条删除 / 清空全部都有二次确认；
- 列表从缓存目录元数据扫描，不需要 Room schema；专辑已删除但缓存遗留时会显示“已删除的专辑”供清理；
- 空状态提示去专辑详情缓存。

## 5. 边界（刻意未做）

- **手动缓存**，不做“点播放就偷偷下载”，避免用户没决定就消耗流量；
- 没有后台下载服务/断点续传：目前离开专辑页会取消下载，失败需重试；
- 缓存固定为下载时的音质档位；之后改音质设置不会自动重下（菜单会显示缓存档位）；
- 多 P 混合档位缓存时，汇总展示取最近一次缓存分 P 的档位；
- 缓存不进入归档/换机备份；清除 App 数据会一起删除；
- 不自动清理孤立缓存（删除专辑/手动删除已覆盖主要路径）。

## 6. 实现位置

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 接口 | `data/repository/AudioCacheRepository.kt` | 音频缓存查询/汇总/下载/删除的契约 |
| 纯逻辑 | `data/impl/AudioCacheLogic.kt` | key 生成、分 P 去重、按专辑聚合、删除匹配（可单测） |
| 实现 | `data/impl/AudioCacheRepositoryImpl.kt` | 解析流 → OkHttp 下载 → 私有文件 + 元数据 |
| 音频播放 | `player/PlaybackEngine.kt` | `resolveStream()` 先查音频缓存；多 P 用 `bvid+cid` 命中 |
| 专辑详情 | `ui/album/AlbumDetailViewModel.kt` / `AlbumDetailScreen.kt` | 菜单入口、进度、补缓存/删除、Snackbar |
| 缓存管理 | `ui/settings/CacheManagementScreen.kt` / `CacheManagementViewModel.kt` | 设置入口、按专辑列表、单条/清空 |
| 容器 | `di/AppContainer.kt` | 注入 `audioCacheRepository` |

## 7. 后续可做（按优先级）

1. Wi-Fi-only 开关 + 后台/前台服务下载 + 断点续传；
2. 自动缓存当前播放的下一张专辑（需明确流量策略）；
3. 缓存过期/版本校验（音频地址失效时自动重下）；
4. 缓存命中时在专辑/曲目 UI 显示离线标记；
5. 缓存管理页显示按分 P 的明细（当前按专辑聚合）。
