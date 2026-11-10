# 哔哩专辑（BiliAudio）设计文档

> B 站专辑资源 → 可搜索、可分曲、可后台播放的音乐播放器。
> Kotlin + Jetpack Compose + Material 3 + Media3。参考 `SPICaMusic_Android` 的扁平 Material 视觉（大标题 / 大圆角卡片 / 封面取色 / 底部迷你播放条），**不使用液态玻璃（liquid glass）模糊材质**。

## 0. 文档状态（v0.4.0 更新，先读这里）

本文档是最初版架构设计，仍有参考价值；但部分细节已被后续迭代更新，开发时以
[AGENTS.md](../AGENTS.md)、[PROJECT_STATUS.md](PROJECT_STATUS.md)、[DECISIONS.md](DECISIONS.md) 为准。
相对初版的主要变化：

- **歌曲模型**：Track 仍是专辑流切片，不设独立音频/艺术家实体（单曲元数据继承专辑，用户已确认）；
- **未分割专辑**：导入未指定分割点 = 0 首，播放时虚拟整张一首；旧版占位曲启动自动清理；
- **播放队列语义**：点歌/专辑播放 = 只播该专辑（分割点展开为该专辑分曲）；「播放全部歌曲」= 全库；
- **导入**：新增分割点文本识别（三种粘贴格式）；音乐库归档 `.biliaudio.json` 统一备份/恢复/分割点文件；
- **歌词（v0.27.0 重做，v0.28.0 并入播放页）**：移除「看歌词视频」（视频播放/视频缓存全部删除），
  改为在线歌词（SPICaMusic 方案：多来源自动匹配 + LRC/YRC/AMLL 解析 + Room 缓存 + 来源/偏移），
  且是「正在播放」页内左右滑动的第二页（封面页 ↔ 歌词页）；
- **离线缓存（v0.27.0）**：只缓存音频、按分 P（多 P 专辑逐 P 缓存）；
- **诊断**：`Diag` 统一日志（Logcat tag `BiliDiag`），设置页可查看/复制/导出。

## 1. 产品目标

B 站上很多 UP 主发布“完整专辑”视频（一个视频包含整张专辑，没有分 P），想当音乐听很麻烦：
- B 站收藏/视频无法按“歌曲”搜索；
- 分曲只能看评论区的时间轴；
- 想要歌词只能看视频，还得手动拖进度。

本 App 把这些视频变成“专辑 → 歌曲”：
1. 登录 B 站（扫码）；
2. 用 BV 号 / 链接 / 文件批量导入专辑，可选自定义分曲时间点；
3. 在 App 里按歌曲播放（后台播放、通知栏控制、播放页内左右滑动看歌词）；
4. 全部专辑/歌曲本地可搜索。

## 2. 核心功能（MVP 范围）

| 模块 | 功能 |
| --- | --- |
| 账号 | B 站扫码登录、Cookie 持久化、账号态校验、退出登录 |
| 导入 | 单条导入（BV 号/链接 → 预览 → 专辑名/艺术家可改）；批量导入（文本/JSON/CSV，含分曲时间点）；搜索 B 站视频后导入（加分项） |
| 专辑 | 专辑详情：封面/标题/艺术家/BV 号/总时长/分曲列表；整张播放、单曲播放；删除；重新编辑元信息 |
| 分曲 | 时间点分割：播放中打点、手动输入时间、重命名、拖拽/批量编辑、保存；多 P 视频可按分 P 自动生成曲目 |
| 播放 | 专辑音频流连续播放；分曲队列（播放列表 = 当前专辑的分割点）；上一首/下一首/随机/循环；播放进度按分曲展示；进度条 seek；后台播放 + 媒体通知 + 锁屏控制；音频焦点/耳机拔出处理 |
| 歌词页 | **v0.27.0 起为在线歌词、v0.28.0 起并入播放页**：播放页左右滑动的第二页，逐行/逐字高亮、Apple Music 式平滑滚动、来源选择、时间偏移、本地导入（原视频方案已删除） |
| 音乐库 | 全部歌曲（按标题/专辑/艺术家搜索）、全部专辑（网格 + 搜索）、最近播放 |
| 设置 | 账号、音质（自动/192K/132K/64K）、深浅色跟随系统/强制、关于 |

后续路线（本轮不做，代码预留）：B 站收藏夹导入、离线缓存、自动静音切分、均衡器、动态取色主题、播放历史、歌单。

## 3. 技术架构

单 `app` 模块 + 包分层（减少 Gradle 复杂度，便于并行开发）：

```
com.wonderlandkid.biliaudio
├── BiliAudioApp.kt             Application：容器初始化、Coil 图片加载（带 UA/Referer）
├── MainActivity.kt             Compose 入口
├── di/AppContainer.kt          手写 DI：数据层/播放层实现装配（当前为 Stub）
├── data/
│   ├── model/Models.kt         全部领域模型（冻结契约，Lead 所有）
│   ├── repository/Repositories.kt  仓库接口（冻结契约，Lead 所有）
│   ├── net/                    [Data] wbi 签名、OkHttp、B 站 API、Cookie
│   ├── db/                     [Data] Room 数据库、Entity、DAO
│   └── impl/                   [Data] 仓库实现、媒体流解析
├── player/
│   ├── PlaybackContracts.kt    播放状态/控制器接口（冻结契约，Lead 所有）
│   ├── PlaybackContracts.kt    播放控制接口（冻结契约，Lead 所有）
│   ├── PlaybackEngine.kt       [Player] 单例播放引擎（ExoPlayer + 队列 + 分曲映射）
│   ├── PlaybackService.kt      [Player] MediaSessionService + 通知
│   └── (v0.27.0 起不再有独立视频播放器)
├── ui/
│   ├── theme/                  Lead：Material3 动态色板、字体、圆角、间距
│   ├── components/             Lead：CoverImage/AlbumCard/TrackRow/SearchField/MiniPlayerBar 等
│   ├── navigation/             Lead：NavHost + 路由；集成后替换占位页
│   ├── home/                   [U1] 发现页
│   ├── library/                [U1] 音乐库（歌曲/专辑/搜索）
│   ├── album/                  [U1] 专辑详情 + 分曲编辑
│   ├── imports/                [U2] 导入（单条/批量/搜索）
│   ├── login/                  [U2] 扫码登录
│   ├── settings/               [U2] 设置
│   └── player/                 [P] 正在播放页、播放列表面板、歌词页（lyrics/）
└── util/TimeUtils.kt           时间格式化/解析（共享）
```

### 播放架构（关键决策）

1. **单例播放引擎**：`PlaybackEngine` 在 `AppContainer` 中单例懒加载，持有唯一 `ExoPlayer`，实现 `PlaybackController` 接口，UI 通过 `StateFlow<PlaybackState>` 订阅（不需要 MediaController 的 Future 桥接）。
2. **后台播放**：`PlaybackService : MediaSessionService` 用同一个 ExoPlayer 构建 `MediaSession`，播放时自动前台化、显示媒体通知；UI 触发播放时确保服务已启动。
3. **音频流**：B 站 DASH 音频 URL 是标准 fMP4（AAC），`ProgressiveMediaSource` 直接播放；请求头必须带桌面 UA + Referer（+ 登录 Cookie）；URL 会过期，解析器缓存 ~30 分钟，播放出错时强制刷新重试一次。
4. **分曲实现**：队列元素 = 一首歌，MediaItem 指向该专辑的音频流并用 `ClippingConfiguration(startMs, endMs)` 裁剪；播放进度映射：
   - 曲内进度 = `player.currentPosition`；
   - 专辑绝对进度 = `track.startMs + player.currentPosition`。
5. **专辑连续播放**：队列 = 该专辑全部分曲（首尾相接），上一首/下一首/播放列表面板跳转都在队列内完成；跨专辑的搜索结果播放也能工作（每个条目重新解析所属专辑流）。
6. **歌词页（v0.27.0 已替换）**：初版设计为「独立第二路 ExoPlayer 播放原视频」，v0.27.0 按用户要求整体移除，改为在线歌词方案（见 [LYRICS.md](LYRICS.md) / DECISIONS.md D53）。
7. **多 P 视频**：`Album.cid` 表示当前使用的分 P；导入时若选择“按分 P”，每个 P 生成一首歌（其 `startMs` 语义变为“第几个 P”由实现转换为“P 内播放”？——MVP 简化：多 P 模式每首曲目仍映射到**同一个**播放流，因此导入逻辑将多 P 合并为“单专辑时间轴”是不对的；正确做法是每首歌带自己的 cid。**当前契约 `Track` 无 cid 字段，MVP 先只支持单 P 视频（`SINGLE_VIDEO`）**，多 P 模式在导入时提示“即将支持”，或把多 P 视频按整段视频导入后手动切分。后续迭代再扩展 Track.cid。

### 数据持久化

- Room：`albums` + `tracks`（`startMs/endMs`）+ 最近播放（`albums.lastPlayedAt/playCount`）。
- 设置/Cookie：SharedPreferences（`SettingsRepository` / `AuthRepository` 内部实现）。
- 无云端同步。

## 4. 冻结接口（并行开发的边界）

所有 teammate 只依赖 Lead 已写好的契约文件，不修改它们：

- `data/model/Models.kt`：`Album / Track / AlbumWithTracks / TrackWithAlbum / BiliVideoInfo / ResolvedStreams / AlbumImportRequest / ImportResult / BiliApiException ...`
- `data/repository/Repositories.kt`：`AlbumRepository / BiliRepository / AuthRepository / SettingsRepository / MediaStreamResolver / AuthState / QrLoginState / AppSettings`
- `player/PlaybackContracts.kt`：`PlaybackState / PlaybackController / QueueEntry / RepeatMode`
- `player/PlaybackContracts.kt`：`PlaybackController / PlaybackState`（歌词不再有视频契约）
- `ui/theme/**`、`ui/components/**`：Lead 提供的设计系统与通用组件
- `ui/AppCompositionLocals.kt`：`rememberAppContainer()`、`appViewModel {}` 工厂

若确实需要改契约：先给 Lead 发消息，由 Lead 统一修改，避免多方冲突。

## 5. UI 规范（与参考应用同风格）

- Material 3（materialkolor 动态色板，种子色 B 站粉 `#FB7299`），支持浅色/深色。
- **禁用**：液态玻璃、毛玻璃大面积模糊、iOS 风格玻璃高光。允许：扁平色块、线性渐变、圆角、阴影（克制）。
- 圆角规范：卡片/专辑封面 20–22dp，控制条 22–28dp，搜索框/胶囊 50%。
- 字体：大标题 `headlineLarge/displayMedium` 加粗；页面顶部滚动时标题可保留。
- 页面结构：
  - 发现页：大标题“发现”+ 统计行 + 搜索入口 + 最近播放卡片 + 我的专辑网格 + 导入 FAB。
  - 音乐库：顶部“音乐库”+ 搜索框 + Tab（歌曲/专辑）；歌曲行=封面/标题/艺术家·专辑/时长；专辑=网格卡片。
  - 专辑详情：顶部大封面 + 标题/艺术家/BV 号胶囊 + 总时长/曲目数 + 播放全部 / 编辑分割点；下方分曲列表（当前曲高亮 + 正在播放图标）。
  - 导入页：Tab（BV 号导入 / 批量导入 / 搜索 B 站）；BV 预览卡片（封面/标题/时长/UP 主）；分曲模式选择；导入按钮；批量导入提供格式说明与示例、粘贴文本或选择文件。
  - 正在播放页：左右分页（封面页 / 歌词页）+ 固定底部进度条与播放控制；封面页 = 大封面 + 标题/艺术家；
    歌词页 = 曲名/艺术家 + 来源/偏移 + 居中歌词区（逐行/逐字高亮、Apple Music 式平滑滚动）。
  - 播放列表面板：ModalBottomSheet，点击分曲跳转。
  - 设置：账号卡片（未登录→登录按钮；已登录→头像/昵称/退出）、音质选择、深色模式、关于。
  - 底部迷你播放条：封面 + 标题/艺术家 + 播放/暂停 + 下一首 + 细进度条；位于底部导航之上，点击进入正在播放页。
- 空状态、加载态、错误提示要完整（不要只做 happy path）。

## 6. 并发分工（写入范围互斥）

| 角色 | 负责包 | 交付物 |
| --- | --- | --- |
| Lead | 根构建、manifest、di、data/model、repository 接口、player 契约、ui/theme、ui/components、ui/navigation、集成、QA | 可编译工程、集成、验收 |
| Data | `data/net/**`、`data/db/**`、`data/impl/**`、`app/src/test/java/**/data/**` | 真实 B 站 API + Room + 仓库 + 单元测试（wbi 签名、批量导入解析、时间工具） |
| Player | `player/**`（除契约）、`ui/player/**` | PlaybackEngine、MediaSessionService、通知、正在播放页（封面/歌词分页）/播放列表 UI |
| U1 | `ui/home/**`、`ui/library/**`、`ui/album/**` | 发现页、音乐库、专辑详情、分曲编辑 |
| U2 | `ui/imports/**`、`ui/login/**`、`ui/settings/**` | 导入、扫码登录、设置 |

集成约定：UI 层只依赖冻结接口 + `rememberAppContainer()`；不要修改 `AppContainer`（由 Lead 替换 Stub 为真实实现）；不要修改 `AndroidManifest.xml`（需要权限/组件找 Lead）。

## 7. 验证方式

- `./gradlew :app:compileDebugKotlin`（每个 teammate 完成时自检）
- `./gradlew :app:testDebugUnitTest`（Lead 统一跑）
- `./gradlew :app:assembleDebug`（Lead 统一出 APK）
- 模拟器（API 36，headless，KVM）安装运行 + `adb exec-out screencap` 截图走查主要页面
- 真机使用时需自备 B 站账号扫码登录；未登录也可播放（音频 192K 实测未登录可获取）。
