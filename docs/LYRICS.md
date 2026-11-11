# 在线歌词（v0.28.0，SPICaMusic 方案）

> 背景：v0.27.0 按用户要求**移除「看歌词视频」功能**（不再播放 B 站原视频），
> 歌词页改用**真实歌词**；方案参考 [SPICaMusic_Android](https://github.com/yangSpica27/SPICaMusic_Android)
> 的多来源歌词实现（在线搜索 → 本地快照 → LRC/YRC/AMLL-TTML 解析 → 逐行/逐字高亮 + 时间偏移）。

## 1. 数据流

```
正在播放的 Track（uid/title）
  │
  ├─ 读 Room `lyrics` 快照（按 trackUid）
  │    ├─ suppressed → 显示「已关闭歌词」
  │    └─ 有原文 → LyricsParser.parse → 歌词页
  │
  └─ 无快照 → 自动在线匹配：
       LyricsText.cleanTitle(标题)            // 去「01、」「专辑_01_」等前缀
       → LyricsApiClient.search(title, "")    // 只按曲名（专辑艺术家是 UP 主，不可靠）
       → 无可信匹配时补搜原始标题，合并候选
       → LyricsMatcher.bestMatch(候选, 标题, 曲目时长)
       → 命中 → save(isManual = false) 落库 → 解析展示
       → 未命中/失败 → 「暂无歌词」+ 手动选源/本地导入
```

## 2. 在线歌词源

- 接口：SPICaMusic 参考项目同款在线歌词服务（多来源聚合，实测返回网易云等来源候选）：
  `GET http://106.54.25.152:4141/api/lyrics?title=<曲名>&artist=<歌手>`
- 响应：`{code, message, data: [{id, name, artist, album, albumArt, duration(秒), lyrics(原文)}]}`
- BiliAudio **只传曲名**、不传艺术家：曲名来自用户对 B 站视频的分曲标题，而专辑艺术家是 UP 主，
  直接传会大量搜不到（实测传 UP 主名会返回无关结果）；
- 接口是 **HTTP 明文**：Manifest 保持 `usesCleartextTraffic="false"`，仅通过
  `res/xml/network_security_config.xml` 放行 `106.54.25.152` 一个域名；
- 网络失败/非 200/JSON 异常统一返回空列表（记录 `Diag` tag `LyricsApi`），不阻塞播放；
- 在线服务是第三方服务，可能失效/变更：失效时自动匹配显示「暂无歌词」，用户仍可本地导入。

## 3. 格式解析（`data/lyrics/LyricsParser.kt`）

自动识别四种格式（`LyricsParser.detectFormat`）：

| 格式 | 识别特征 | 解析器 | 逐字 |
| --- | --- | --- | --- |
| AMLL / TTML | `<?xml` / `<tt` / TTML 命名空间 | `AmllParser`（从 SPICaMusic 移植，含 metadata/ruby/对唱/背景音） | 是 |
| YRC | 行首 `[行开始,行时长](字开始,字时长,空格数)` | `YrcParser` | 是 |
| LRC | `[mm:ss]` / `[mm:ss.xx]` 时间标签 | `LrcParser`（含增强型 `<mm:ss.xx>` 逐字、同时间戳翻译合并、逐字错位降级） | 视源而定 |
| 纯文本 | 无时间标签 | 内置纯文本兜底（每行一句，`isSynced = false`） | 否 |

- 解析结果统一为 `ParsedLyrics(lines: List<LyricItem>, isSynced, parseError)`；
- `LyricItem.NormalLyric` = 整行歌词（可带翻译）；`LyricItem.WordsLyric` = 逐字歌词
  （`WordWithTiming` 每个词有 start/end）；
- 无时间轴（`isSynced = false`）时歌词页静态展示，不高亮、不自动滚动、不可点行跳转。

## 4. 自动匹配（`data/lyrics/LyricsMatcher.kt`）

- 标题归一化：全角转半角、小写、去 `feat./ft.`、去括号后缀（Live/伴奏/版等）、去噪音词、去标点空白；
- 标题得分：完全相等 > 包含（按长度比）> 二元组相似度；
- 时长得分：候选时长与曲目时长差 ≤3s 加满分、3~20s 线性衰减、>20s 扣分；
- **标题完全相等时兜底到 75 分**（不同版本/翻唱时长可能差很多，但歌词通常可用；时长仍参与排序挑最接近的版本）；
- 总分低于阈值（70）返回 `null`，宁缺毋滥（避免「晴天」匹配到无关歌）；
- 匹配失败时歌词面板显示「暂无歌词」并保留候选列表，用户可在「歌词来源」里手动选择。

## 5. 本机缓存（Room v6 `lyrics` 表）

| 列 | 说明 |
| --- | --- |
| `trackUid` | 主键，曲目稳定 uid（与歌单/统计同源） |
| `rawLyrics` | 歌词原文（不解析落库，展示时解析） |
| `sourceType` / `sourceName` | 来源类型（ONLINE / LOCAL_FILE / NONE）与名称（用于来源面板打勾） |
| `delayMs` | 歌词时间偏移（正数 = 歌词提前） |
| `isManual` | 用户手动选择（自动匹配不会覆盖手动选择） |
| `suppressed` | 已关闭歌词（保留快照与偏移，可恢复） |
| `updatedAtMs` | 更新时间 |

- 保存语义：自动匹配保存不会覆盖「手动选择」或「已关闭」；手动选择会覆盖并清掉关闭状态；
- 无外键（曲目删除后残留的歌词不会被展示，也不影响功能）；
- 歌词属于设备本地缓存，**不进入音乐库归档/用户数据备份**。

## 6. UI（v0.28.0：与播放页合一）

歌词就在「正在播放」页里，是页内**左右滑动的第二页**（不再是独立页面/覆盖层，也没有「看歌词」按钮）：

- 第 1 页（封面页）：大封面 + 曲名/艺术家；点封面也可以进入歌词页；
- 第 2 页（歌词页）：紧凑头部（曲名/艺术家 + 歌词来源 + 偏移）→ 歌词区；
- 底部进度条/时间/播放控制固定，不随分页滑动；两页之间用 HorizontalPager 横向滑动切换；
- 两页之间的指示器是两个小圆点（当前页拉长为胶囊），点击也能切换；返回键在歌词页先滑回封面页。

歌词渲染（Apple Music 手感，扁平 Material 3、无毛玻璃）：

- v0.35 起左对齐；统一 22sp 大字号；当前行 primary 色填充（不放大字号），非当前行分层透明度；
- 逐字歌词按词推进颜色（`SpanStyle` 颜色 lerp，不做自绘遮罩）；翻译行小一号展示；
- 自动滚动到当前行：**520ms Emphasized 长缓出**（`BiliMotion.LyricsScrollMs`），切行快速重定向不跳变；
  用户手动滚动后暂停 3 秒再恢复；
- 歌词区上下边缘用 DstIn 渐变遮罩淡出，不出现生硬截断；
- 点击某行跳转到该行时间；
- 状态：加载中「正在匹配歌词…」/ 无歌词（重新搜索、导入本地）/ 已关闭（恢复歌词）/ Snackbar 错误；
- 歌词来源面板（ModalBottomSheet）：在线候选列表（当前使用项打勾）、重新搜索、导入本地歌词文件
  （SAF `OpenDocument`，≤1 MiB，LRC/YRC/TTML/TXT 扩展名 + 内容校验）、关闭歌词、恢复自动匹配；
- 偏移面板（ModalBottomSheet）：`-500 / -100 / +100 / +500 ms` 与重置，调整立即生效并持久化。

## 7. 边界（刻意未做）

- 迷你播放条不显示迷你歌词（`MiniLyric`）——本版先做全屏歌词页；
- 没有逐字扫描/遮罩动画（移动端成本高），逐字用颜色推进；
- 歌词不导出/不随归档迁移（属于设备缓存）；换机后重新在线匹配；
- 在线服务为第三方 HTTP 接口，只放行该域名；接口不可用不影响播放；
- 歌词搜索固定用曲名（不支持自定义关键词），匹配错时手动选源即可。

## 8. 实现位置

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 模型 | `data/model/LyricItem.kt`、`data/model/Lyrics.kt` | 歌词行/候选/快照/解析结果 |
| 解析 | `data/lyrics/LrcParser.kt`、`YrcParser.kt`、`AmllParser.kt`、`LyricsParser.kt` | LRC/YRC/TTML 解析 + 格式识别/本地导入校验 |
| 匹配 | `data/lyrics/LyricsMatcher.kt`、`LyricsText.kt` | 候选打分（标题+时长）、曲名清洗 |
| 网络 | `data/net/LyricsApiClient.kt` | 在线歌词接口（OkHttp + kotlinx.serialization） |
| 存储 | `data/db/LyricEntity.kt`、`LyricsDao.kt`、`AppDatabase.kt`（v6） | Room `lyrics` 表与迁移 |
| 仓库 | `data/repository/LyricsRepository.kt`、`data/impl/LyricsRepositoryImpl.kt` | 在线搜索 + 本机快照读写语义 |
| UI | `ui/player/lyrics/NowPlayingLyricsPane.kt`、`LyricsViewModel.kt`、`LyricsSourceSheet.kt`、`LyricsOffsetSheet.kt`、`LyricLineRow.kt` | 播放页内嵌歌词 + 两个面板 |
| 入口 | `ui/player/NowPlayingScreen.kt` | 封面页 ↔ 歌词页左右分页（HorizontalPager）；点封面或滑动切换 |
| 容器 | `di/AppContainer.kt` | 注入 `lyricsRepository` |

## 9. 后续可做（未排期）

1. 迷你播放条单行歌词（参考 SPICaMusic `MiniLyric`）；
2. 「歌词搜索」自定义关键词/重新匹配（多来源候选手动切换已支持）；
3. 歌词随用户数据备份导出/导入；
4. 在线歌词源 base URL 可配置（服务迁移时无需发版）；
5. 双语/音译行、对唱歌手标签等 AMLL 高级展示。
