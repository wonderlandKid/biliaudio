# 哔哩专辑 · BiliAudio

> B 站专辑音乐播放器（Android / Kotlin / Jetpack Compose / Material 3）。
> 把 B 站「一整张专辑装在一个视频里」的内容，转成可搜索、可分曲、可后台播放的音乐库。

## 当前状态（先读这个）

- **版本：v0.30.1**（tag `v0.30.1`，2026-11-11；release commit 以 `git rev-parse v0.30.1` 为准）
- 详细交接信息见 **[docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)**
- 设计决策记录见 **[docs/DECISIONS.md](docs/DECISIONS.md)**
- 排障手册见 **[docs/DEBUGGING.md](docs/DEBUGGING.md)**
- 版本变更见 **[docs/CHANGELOG.md](docs/CHANGELOG.md)**
- 单元测试：**200 个**（v0.30.1；含 5 个 UI 架构守卫）

## 快速开始

```bash
export JAVA_HOME=/opt/android-studio/jbr        # 本机 JDK（Android Studio 自带 JBR 25）
./gradlew :app:assembleDebug                     # 构建 APK
./gradlew :app:testDebugUnitTest                 # 单元测试（v0.30.1: 200 个）
./gradlew :app:assembleRelease                   # 性能/日常安装请用 release（R8）
# 产物：app/build/outputs/apk/debug/app-debug.apk（开发调试，滚动会明显偏慢）
#       app/build/outputs/apk/release/app-release.apk（R8 + debug 签名，可安装）

# 模拟器（AVD: biliaudio_api36，headless）
scripts/emulator.sh start
scripts/screenshot.sh home 8
```

- 包名 / applicationId：`com.wonderlandkid.biliaudio`；App 名称：**哔哩专辑**
- 源码入口：`app/src/main/java/com/wonderlandkid/biliaudio/`
- 本机环境注意：**GitHub 不可达**（Gradle wrapper 已改用腾讯镜像；B 站 API/CDN 可直连）

## 开发约定（重要）

1. **契约文件不要随意改**：`data/model/Models.kt`、`data/repository/Repositories.kt`、
   `player/PlaybackContracts.kt`、`data/model/Lyrics.kt`、`data/repository/LyricsRepository.kt`、
   `di/AppContainer.kt`、
   `ui/navigation/**`、`ui/theme/**`、`ui/components/**`、`AndroidManifest.xml`、`build.gradle.kts`
   属于集成层，改动前先确认影响面（或按 docs/DECISIONS.md 的架构约定来）。
2. **每次发版**：`versionCode +1`、`versionName` 升级 → 全量单测 + **构建 release**（性能验收/交付用；
   debug 包仅开发调试）→ 提交（feat/fix/docs/chore + 详情）
   → annotated tag `vX.Y.Z` → 复制 release 产物到 `dist/biliaudio-vX.Y.Z-release.apk`
   （需要排查时再附 debug）并 `git archive` 生成 `dist/biliaudio-vX.Y.Z-source.zip`
   （`dist/` 被 .gitignore 忽略，不进仓库）。
3. **用户偏好**：中文 UI/中文沟通；Material 3 扁平风格，**不要液态玻璃/大面积毛玻璃**；
   （v0.7.0 起用户明确要求：仅迷你播放条可使用 Haze 毛玻璃，其余页面保持扁平）
   **v0.29.0 曾尝试 Editorial/Spatial/Modern Skeuomorphism 重设计，用户认为过于大胆冒险，v0.30.0 已直接重置回
   v0.28.0 基线（Material 3 扁平 UI）。以后不要主动引入新的视觉语言/设计系统；视觉方向改动必须先获得用户明确批准。**
   单曲不设独立实体，直接继承专辑艺术家；播放列表=当前播放专辑的分曲；追求「点一下就能听」的轻量体验。
4. **信息架构（v0.30.0）**：音乐库只做搜索；发现页不放搜索栏，四个 4 条预览区块按
   最近播放 → 听歌统计 → 我的歌单 → 随机专辑排列（详情从右上角进入；最近播放详情 10 张）；
   听歌统计预览可切专辑时长/歌曲次数，选择持久化到 `AppSettings.homeStatsMode`；
   设置卡片首行用 `BiliGroupHeader` 统一图标/标题/副标题；缓存管理属于设备维护，放设置。歌单用 Room v4 的 playlists/playlist_entries
   （trackUid 关联，唯一防重复）；统计用 track_stats（播放次数+实际收听时长，5 秒批量落库）。
   多 P BV：`ImportMode.MULTI_PAGE` 每个分 P 一首，Room v5 的 `tracks.sourceCid/sourcePage` 记录来源；
   播放前预解析全部分 P 并入队，完整分 P 不裁剪，自动无缝衔接；
   歌单/统计持久化在 Room v4；**不进入音乐库归档**，通过设置 → 用户数据 →「歌单与听歌统计备份」
   独立 JSON 导出/导入（按专辑稳定键 + startMs 关联，统计恢复用 max 合并）；详见 [docs/PLAYLISTS_STATS.md](docs/PLAYLISTS_STATS.md)。
5. **离线缓存（v0.27.0 只缓存音频、按分 P）**：`AudioCacheRepository` 按 `Track.sourceCid` 逐 P
   把整段 DASH 音频缓存到应用私有目录 `files/audio_cache`（同一 P 的分曲共享同一文件）；
   缓存**不进入 Room/归档/备份**；播放引擎优先读缓存（`bvid+cid` key），删除专辑一起清理；
   多 P 专辑「缓存整张专辑音频」会缓存所有分 P，支持补缓存（已缓存 x/y 分P）；
   **歌词视频缓存及其视频播放功能已在 v0.27.0 整体移除**（旧 `_video` 文件自动清理）；
   设置 → 存储 → **缓存管理** 可查看总量、逐条删除、清空全部；
   详见 [docs/OFFLINE_CACHE.md](docs/OFFLINE_CACHE.md)。
6. **歌词（v0.28.0：与播放页合一，参考 SPICaMusic_Android 方案）**：在线多来源歌词 API（按曲名搜索，
   不传 UP 主艺术家；多 P 曲名会去掉「专辑_01_」前缀，匹配不到时补搜原始曲名）→ 候选按标题+时长自动匹配 →
   Room v6 `lyrics` 表按 `Track.uid` 缓存原文 → `LyricsParser` 解析 LRC / YRC / AMLL-TTML / 纯文本。
   **歌词是「正在播放」页内左右滑动的第二页**（第 1 页封面/曲名，第 2 页歌词；底部进度/控制固定），
   不是独立路由、也不是覆盖层；返回键在歌词页先回封面页。滚动用 `BiliMotion.LyricsScrollMs` 长缓出 +
   当前行放大/着色 + 上下渐隐遮罩（Apple Music 手感）；支持来源选择、本地导入、时间偏移、逐行/逐字高亮。
   歌词是设备本地缓存，**不进入归档/备份**；在线接口是 HTTP，仅通过 `network_security_config` 放行该域名；
   详见 [docs/LYRICS.md](docs/LYRICS.md)。
7. **问题排查**：先看 `Diag` 诊断日志（设置 → 诊断与维护 → 诊断日志；Logcat tag `BiliDiag`），
   再按 docs/DEBUGGING.md 的流程判断。
8. **UI 规范（v0.12.0 页面模板化）**：
   - 页面分四类：T1 一级 Tab（`BiliPageHeader`，不自己套 Scaffold）、T2 层级详情页（必须用
     `ui/template/BiliPageTemplates.kt` 的 `BiliDetailScaffold`/`BiliTopBar`）、T3 沉浸页（播放/歌词，页面自管）、
     T4 弹层/对话框；禁止再手写“Row + 返回 + 标题”式顶栏；
   - 搜索框统一用 `SearchField`（可编辑）/`SearchBarEntry`（入口）；
   - **先查 [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) 选页面类型/组件**：
     区块用 `BiliSection`，卡片用 `BiliGroup`，卡片首行用 `BiliGroupHeader`，组内行用
     `BiliGroupItem`/`BiliAlbumRow`，专辑/歌曲行用 `BiliAlbumRow`/`TrackRow`；
   - 禁止页面内手写大圆角 Surface 卡片、私有 SectionCard/Header、空 `onClick={}`；
     音乐内容行点击必须真正播放并高亮；
   - 页面级留白/迷你条底部留白用 `BiliLayout` token；新增规则要同步 `UiArchitectureConsistencyTest`；
   - 详见 [docs/UI_ARCHITECTURE.md](docs/UI_ARCHITECTURE.md) / [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md)。
9. **动效规范（v0.8.2 起，参考 SPICaMusic_Android 统一）**：
   - **一级 Tab（发现/音乐库/设置）是平级页面，禁止滑动/缩放转场**：瞬时切换（EnterTransition.None），
     只保留底部导航指示器动画（参考项目就是同一个 HomeScreen 内 `when` 切换）；
   - **层级页面统一 push/pop（完整宽度）**：push = 新页从右侧整页滑入、旧页同时整页向左滑出；
     pop = 当前页整页向右滑出、上一页从左侧整页回位；**固定 500ms、进出同一条 Emphasized 曲线**
     （只方向相反，保证对称），不跟手势速度联动；无 alpha、无 scale；Tab↔详情同理，Tab↔Tab 才瞬时；
   - **预测式返回不预演**：Manifest `enableOnBackInvokedCallback=false`，系统不做预测式预演；
     手势只提交普通 back，之后走 NavHost 普通 pop（方向稳定，避免反向扫动/快速浮现）；
   - **一级 Tab 行为平级**：音乐库/设置返回不回到发现，进入退出流程；根 Tab 返回需 2 秒内连续两次
     （第一次 Toast 提示「再返回一次退出应用」）；键盘弹出时 BackHandler 让位给键盘；
   - 播放页内部是**左右分页**（v0.28.0）：第 1 页封面/曲名、第 2 页歌词；横向滑动切换（跟手 + pager 默认 fling），
     底部进度/控制固定不跟页；歌词页按返回先滑回封面页；迷你条 ↔ 播放页仍是上下关系（连续跟手展开，顶部栏可下滑关闭）；
   - 一级 Tab 入场规则：**固定页头/搜索/登录卡等静态元素不动；只有列表/内容区播放「淡入 + 上浮」**
     （320ms 起、50ms/项、封顶 650ms、交错 45ms；音乐库内部 Tab 用 replayKey 重播；Tab 切换本身无转场）；
   - 底部导航出现/消失做高度展开/收缩 + 淡入淡出（300/250ms），Scaffold 高度和迷你条避让同步；
   - **所有自动动画必须有速度曲线**（Emphasized 或 spring），禁止 LinearEasing/匀速；时长统一用
     `BiliMotion` token，详见 [docs/MOTION.md](docs/MOTION.md)；
   - **按压反馈全局统一**：`AppTheme` 提供自绘 `LocalIndication`（水波纹 + 按压底色），
     Material 默认 ripple 关闭；只对 enabled 的可点击组件生效。
10. **迷你条**：Haze blur（dev.chrisbanes.haze 2.0.1，API31+；低版本 fallback 半透明）+ 跟手拖拽上滑打开播放页；
   不再有拖拽提示条；只允许迷你条使用毛玻璃，其余页面保持 Material 3 扁平（无大面积玻璃）；
   底部导航消失/出现时，迷你条底部位置用 spring 平滑过渡（`animateDpAsState`，**必须无过冲 + 非负兜底**，
   否则会抛 `Padding must be non-negative` 闪退）。
11. **列表性能规范**：ViewModel 里不要直接 combine `playbackController.state`（会随进度 tick 重建）；
   要 map 成 `(trackId,isPlaying)` 后 distinctUntilChanged；重计算（分组/排序）放数据变化阶段；
   Lazy 列表加 contentType + 稳定 key；索引栏高亮用 provider 在组件内部读取，父级不要读滚动派生状态。

12. **页面底部安全区**：迷你条可见时由 `AppNavHost` 全局抬升 NavHost 内容区，页面最后一项不会被 Haze 遮住；
    键盘打开时全局 `imePadding()` 接管，并撤销迷你条 inset，避免重复叠加；页面不要再单独写 Snackbar 迷你条避让。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | 当前版本、功能矩阵、验证状态、待办、交接说明 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 关键设计决策与原因（虚拟歌曲、队列语义、归档格式、缓存/歌词等） |
| [docs/DEBUGGING.md](docs/DEBUGGING.md) | 诊断日志、常见故障排查（转圈、导入、Studio、网络） |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | v0.1.0 → v0.30.1 版本记录 |
| [docs/DESIGN.md](docs/DESIGN.md) | 初版架构设计（含当前状态更新说明） |
| [docs/API_NOTES.md](docs/API_NOTES.md) | B 站接口实测笔记（wbi、登录、播放流、选流策略） |
| [docs/LIBRARY_ARCHIVE.md](docs/LIBRARY_ARCHIVE.md) | **归档 v3 格式规范（分域 bundle + 单分享文件）** |
| [docs/ARCHIVE_SCHEMA_V2.md](docs/ARCHIVE_SCHEMA_V2.md) | 归档 v2 历史设计（已被 v3 分域布局取代） |
| [docs/ARCHIVE_LAYOUT_V3.md](docs/ARCHIVE_LAYOUT_V3.md) | 归档/配置布局 v3（已实施：library/playlists/settings/covers + manifest bundle） |
| [docs/UI_ARCHITECTURE.md](docs/UI_ARCHITECTURE.md) | **页面分四类 + 模板/状态/导航/动画规范（新增页面前必读）** |
| [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) | **页面种类 / 组件目录 / token / 选型流程 / 豁免清单（新页面唯一入口）** |
| [docs/MOTION.md](docs/MOTION.md) | **动效时长/曲线总表、跟手例外、调参入口（新增动效前必读）** |
| [docs/PLAYLISTS_STATS.md](docs/PLAYLISTS_STATS.md) | **歌单与听歌统计：信息架构、Room v4、记录规则、边界** |
| [docs/OFFLINE_CACHE.md](docs/OFFLINE_CACHE.md) | **专辑音频离线缓存：按分 P 缓存、多 P 专辑、缓存管理、边界** |
| [docs/LYRICS.md](docs/LYRICS.md) | **在线歌词（SPICaMusic 方案）：数据流、格式解析、自动匹配、Room 缓存、歌词页边界** |
| [docs/IMPORT_FORMATS.md](docs/IMPORT_FORMATS.md) | 批量导入与分割点文本格式（三种粘贴格式） |
| [docs/samples/](docs/samples/) | 归档示例、分割点文本真实样例 |
| [docs/screenshots/](docs/screenshots/) | 模拟器/真机走查截图 |
