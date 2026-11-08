# 版本记录

## v0.23.1（2026-11-08 · tag `v0.23.1`）

**发现页区块顺序/边距修正 + 听歌统计预览口径可切换**

- 发现页四个区块加左右 `Spacing.lg` 边距，与设置页卡片对齐（之前贴边）；
- 区块顺序调整为：最近播放 → 听歌统计 → 我的歌单 → 随机专辑；
- 听歌统计预览支持切换「专辑时长」/「歌曲次数」：
  - 专辑模式显示 Top4 专辑累计收听时长；
  - 歌曲模式显示 Top4 歌曲播放次数；
  - 选择持久化到 `AppSettings.homeStatsMode`，重启仍保留；
- 发现页卡片仍只露前 4 条，详情从右上角「查看全部」进入；
- 单测 126 全绿；模拟器验证间距/顺序/切换与持久化。

## v0.23.0（2026-11-08 · tag `v0.23.0`）

**发现页四区块化 + 随机专辑 + 最近播放详情页 + 设置卡片一致性**

- 发现页移除搜索栏（搜索统一在音乐库）；
- 「我的专辑」改为「随机专辑」：展示 4 张，右上角「换一批」重新随机；保留导入入口；
- 四个区块统一为最近播放式卡片，只露前 4 条：
  - 随机专辑（换一批/导入，专辑行点开详情）
  - 我的歌单（查看全部 → 歌单列表）
  - 听歌统计（查看全部 → 统计页，展示 Top4 专辑时长）
  - 最近播放（查看全部 → 新的最近播放页，详情展示 10 张）
- 新增 `RecentAlbumsScreen`；最近播放详情页也展示迷你播放条；
- 设置页统一卡片首行 `SettingsHeaderRow`（图标 22dp + bodyLarge 标题 + bodySmall 副标题）：
  - 音质、深色模式、整库备份、用户数据、维测日志、关于全部补齐图标/首行结构；
  - 账号头像作为身份例外；其余设置卡片高度/字号/间距统一；
- 单测 126 全绿；模拟器验证四区块、换一批/导入、最近播放详情、设置卡片样式。

## v0.22.1（2026-11-08 · tag `v0.22.1`）

**一致性审计：同类 group/item 行为统一**

- `BiliGroup` 支持整卡点击；`BiliGroupItem` 统一水平内边距（12dp）；
- 设置页「缓存管理」「诊断日志」入口、缓存管理专辑头、歌单列表、发现快捷卡统一使用 `BiliGroup`/`BiliGroupItem`，
  不再手写可点击 Row/卡片；
- 统计页歌曲行从「无反馈 onClick={}」改为真正播放所属专辑，并显示当前播放高亮；
  统计页专辑卡可点击打开专辑详情；
- 歌单详情歌曲行显示当前播放高亮；
- 歌单详情 / 统计页加入迷你播放条（页面底 padding 同步），点歌后有明确反馈；
- 统计专辑标题字号统一为 `bodyLarge`，与 group item 一致；
- UI_ARCHITECTURE 增加“同类 group/item 行为规则”；AGENTS/CHANGELOG/DECISIONS(D48)/PROJECT_STATUS 同步；
- 单测 126 全绿；模拟器复测歌单/统计/缓存/设置页面分组样式与点击行为。

## v0.22.0（2026-11-08 · tag `v0.22.0`）

**用户数据（歌单+统计）独立持久化备份 + 分组/条目 UI 模板统一**

- 歌单/统计仍持久化在 Room v4；新增**独立于音乐库归档**的用户数据备份：
  - 设置 → 用户数据 →「歌单与听歌统计备份」；
  - 导出 JSON 只含歌单/统计，不含专辑/分曲/封面/缓存；
  - 用 `(source,bvid,page) + startMs` 定位歌曲，换机恢复后能重新关联；
  - 歌单按名称合并，统计按 max 合并，避免重复导入翻倍；
- 新增通用分组模板 `BiliGroup` / `BiliGroupItem` / `BiliGroupSectionTitle`：
  - 设置卡片、缓存管理、歌单列表、统计卡片、发现页快捷卡全部迁移；
  - `SettingsCard` 委托 `BiliGroup`，组容器背景/圆角/内边距/字体/点击态只维护一份；
- docs 增加 Group/Item 约定；PLAYLISTS_STATS/AGENTS/PROJECT_STATUS/CHANGELOG/DECISIONS(D47) 同步；
- 单测保持 126 全绿；模拟器验证用户数据导出→删除歌单→导入恢复、歌单/统计入口与分组样式。

## v0.21.0（2026-11-08 · tag `v0.21.0`）

**自建歌单 + 听歌统计（发现页入口）**

- 信息架构调整：音乐库保持纯搜索；歌单/统计等“听歌相关”入口统一放发现页；
  缓存管理继续留在设置；
- 发现页新增「我的歌单」「听歌统计」快捷卡；
- 歌单：
  - 新建/重命名/删除歌单；添加歌曲（底部 Sheet 搜索+多选）、单曲移除、播放全部/点播；
  - Room v4：playlists + playlist_entries，使用稳定 trackUid 关联，唯一防重复；
- 听歌统计：
  - track_stats 按 trackUid 记录播放次数 + 实际收听时长；
  - 播放中每 5 秒批量落库，暂停/切歌 flush；
  - 发现 → 听歌统计：专辑时长榜 + 歌曲次数榜 + 累计汇总；
- 单测保持 126 全绿；模拟器验证歌单创建/添加/播放、统计唱片时长与次数、数据库 v3→v4 迁移。

## v0.20.0（2026-11-08 · tag `v0.20.0`）

**缓存管理页（设置 → 存储 → 缓存管理）**

- 入口：设置 → 存储 → 「缓存管理」；
- 页面展示：
  - 已缓存专辑数、总占用空间；
  - 每张专辑一行：封面/标题/艺术家、音频档位+大小、歌词视频清晰度+大小、合计与更新时间；
  - 单条删除（二次确认）、清空全部（二次确认）；
  - 空状态引导去专辑详情缓存；
- 列表从 `files/audio_cache` 元数据扫描，关联本地专辑表；专辑已删但缓存遗留时显示“已删除的专辑”并允许清理；
- 新增 `AudioCacheRepository.listCached()/removeCached()` 与 `CacheManagementViewModel/Screen`；
- OFFLINE_CACHE/TODO/AGENTS/CHANGELOG/DECISIONS(D45)/PROJECT_STATUS 同步；
- 单测 126 全绿；模拟器验证缓存列表、单条删除确认、总大小显示。

## v0.19.0（2026-11-08 · tag `v0.19.0`）

**修复“缓存了视频但音频不能离线”：视频轨不含音频 + 缓存视频自动补音频**

- 根因：B 站 DASH 视频轨是 video-only，视频文件本身没有音频，缓存视频不能当音频缓存用；
- 「缓存歌词视频」现在会自动确保**音频也已缓存**，菜单文案改为「缓存歌词视频（含音频）」；
- 兼容 v0.18 只缓存视频的旧状态：菜单出现「补缓存音频（视频已缓存）」，点一下补齐音频，无需重新下载视频；
- 播放页封面放大：可用宽度 82%→96%，左右边距 24dp→16dp，接近参考播放器的封面比例；
- 全局按压反馈：所有**可点击**组件统一自绘水波纹 + 按压底色（禁用组件不触发）；
- TODO：触摸反馈、封面放大完成；进度栏重构仍保留；
- 单测 126 全绿；模拟器验证补音频、离线音频/视频、封面尺寸。

## v0.18.0（2026-11-08 · tag `v0.18.0`）

**歌词视频缓存 + TODO.md 若干体验修复**

- 歌词视频缓存：专辑更多菜单新增「缓存歌词视频」；确认后下载整段视频到私有目录
  （清晰度跟随解析结果，可能 480P/1080P），视频页优先读本地文件，断网可看歌词；
  音频/视频缓存独立删除，删除专辑一起清理；
- TODO「查看歌词视频页面最上面有白边」：视频覆盖层不再叠加 Scaffold innerPadding，视频页自己处理 systemBars；
- TODO「点击全屏自动横屏，取消恢复竖屏」：全屏时 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，
  退出恢复 `UNSPECIFIED`；Manifest 增加 configChanges，旋转不重建 Activity；
- TODO「导入页跳转 B 站」：BV 预览卡新增「在 B 站打开（看分段/时间点）」；
- TODO「列表正在播放动效不明显」：TrackRow 用三柱均衡器替换静态图标，暂停仍有静态柱高亮；
- TODO「暂停时封面略微缩小」：播放页封面播放时 100%、暂停 92%（Emphasized 曲线）；
- OFFLINE_CACHE 文档升级为音/视频双缓存说明；
- 单测保持 126 全绿；模拟器验证视频缓存后断网播放、全屏自动横屏/退出竖屏。

## v0.17.0（2026-11-08 · tag `v0.17.0`）

**专辑音频离线缓存（第一版）**

- 专辑详情 → 更多菜单新增「缓存整张专辑音频」：
  - 缓存整张专辑对应的完整 DASH 音频一次（分曲共享同一文件，不重复占空间）；
  - 下载完成可「删除音频缓存」，菜单显示当前缓存音质；
  - 删除专辑自动删除缓存。
- 播放引擎优先读取本地缓存：有缓存时离线也能播放，不再消耗音频流量；
  数据源改为 `DefaultDataSource`，同时支持在线 `http(s)` 与缓存 `file://`。
- 下载完整性校验（Content-Length/非空），失败清理临时文件；
- 缓存只存在应用私有目录，**不进入归档/换机备份**；
- 新增 [docs/OFFLINE_CACHE.md](OFFLINE_CACHE.md)：可行性、边界、实现位置、后续规划；
- 单测保持 126 全绿；真机/模拟器验证下载后断网可播放。

## v0.16.0（2026-11-08 · tag `v0.16.0`）

**迷你播放条加“上一首”；歌词视频改为播放页上方覆盖层**

- 迷你播放条：在播放/下一首之间加入「上一首」按钮，接 `PlaybackController.previous()`。
- 歌词视频不再作为独立 NavHost 目的地：
  - 点「看歌词视频」时播放页保持完全展开作为背景，视频层从右侧盖上来；
  - 退出视频时视频层向右滑出，直接露出播放页；不会再闪回专辑/父页面，也不会看到迷你条从下面滑上来；
  - 视频层使用独立 `ViewModelStore`，关闭时 clear，第二路视频播放器及时释放；
  - 关闭视频后播放页保持原状态（进度、滚动、播放列表等不重建）。
- 转场与页面 push/pop 共用 500ms 对称曲线；视频全屏返回逻辑不变。
- 单测保持 126 全绿。

## v0.15.0（2026-11-08 · tag `v0.15.0`）

**修复音乐库→专辑旧页不退出 + 进出完全对称 + 底部导航平滑出现**

- 音乐库/设置两个 Tab 的 `enter/exit/popEnter` 之前还残留写死的 `None`，只有发现生效；
  现在三个 Tab 都按转场目标动态判断：Tab↔Tab 瞬时，Tab→详情 Tab 整页向左退出，详情→Tab 从左侧回位。
- 页面进出完全对称：进入/退出使用**同一时长 500ms、同一 Emphasized 曲线**，只有方向相反
  （之前进入 Decelerate、退出 Accelerate，用户会感觉两侧速度不一致）。
- 底部导航出现/消失加入高度扩展 + 淡入淡出（出现 300ms / 消失 250ms，Emphasized 曲线），
  不再“啪”一下出现；Scaffold 高度与迷你条避让随动画同步过渡，迷你条不会跳。
- 新增 `BiliMotion.BottomBarEnterMs/ExitMs`。
- 单测保持 126 全绿。

## v0.14.0（2026-11-08 · tag `v0.14.0`）

**全量动效节奏统一：更慢、全部带速度曲线，导航动画不再跟手速联动**

- 层级页面 push/pop：400ms → **500ms**（EmphasizedDecelerate 入场 / EmphasizedAccelerate 退场）；
  完整宽度滑动，固定时长，与返回手势快慢无关；
- Tab 列表入场：基础 250→**320ms**，每项 +45→**+50ms**，封顶 550→**650ms**，交错 35→**45ms**；
  固定页头/搜索/登录卡仍保持静态；
- 迷你播放条显示 280ms / 隐藏 220ms，显式 Emphasized 曲线；底部避让 spring 放慢（stiffness 400）；
- 播放页松手 spring stiffness 380→**320**，更柔和；
- 歌词视频控制层 260/200ms、封面占位 300/250ms，全部显式 Emphasized 曲线；
- 新增 [docs/MOTION.md](MOTION.md)：动效时长/曲线总表、跟手例外、返回速度说明、调参入口；
- 单测保持 126 全绿。

## v0.13.1（2026-11-08 · tag `v0.13.1`）

**返回动画修复：关闭系统预测式返回，手势提交后才播放正常 pop**

- 关闭 `android:enableOnBackInvokedCallback`（predictive back）：系统不再对返回手势做预测式预演，
  避免“页面反向扫动/封面扫到左边/很快浮现”的预测动画冲突；
- 手势/返回键现在只提交普通 back，由 NavHost 统一播放 pop：详情整页向右退出、上一页从左侧回位；
- 专辑页更多菜单去掉「更换封面」（封面大图里已有该入口）；
- 单测保持 126 全绿。

## v0.13.0（2026-11-08 · tag `v0.13.0`）

**页面转场拓扑修正 + 深色状态栏 + 歌词视频 1080P + 封面大图模式**

- 转场拓扑：修复「从 Tab 进入详情页时旧页不动」的根因——Tab 路由固定 `ExitTransition.None`，
  导致设置→诊断、发现→专辑只有新页从右进入、旧页没有向左退出。
  现在 Tab 路由按目标动态决定：
  - Tab ↔ 详情：Tab 整页向左退出 / 详情向右进入；返回时高度对称（详情向右退出、Tab 从左回位）；
  - Tab ↔ Tab：仍然瞬时（平级，不做方向动画）。
- 层级页面默认 push/pop 改为完整宽度滑动（旧页整页向左，返回整页向右），不再只有 1/4 视差。
- 深色模式系统栏：`AppTheme` 现在按 App 自己的深色设置调用 `WindowCompat` 设置
  `isAppearanceLightStatusBars/NavigationBars`，深色主题下状态栏时钟/图标变浅色（不再黑字黑底）。
- 歌词视频清晰度：`StreamSelector` 视频上限从 720P 提升到 1080P（4K/HDR 仍不选）；
  音频轨仍由独立 `selectAudio` 选择（192K/132K/64K），与视频分辨率无关。
- 专辑封面：
  - 去掉封面上的「换封面」悬浮按钮；
  - 点击封面进入全屏大图欣赏（保存封面 / 换封面）；
  - 「更换封面」移入右上角更多菜单；
  - 保存封面走系统文件选择器（无存储权限问题）。
- 单测 126 全绿。

## v0.12.1（2026-11-08 · tag `v0.12.1`）

**Tab 入场动画再统一：固定页头不动，只有列表/内容入场**

- 发现：`BiliPageHeader`/搜索/登录卡改为静态；最近播放、我的专辑标题、专辑网格逐项入场；
- 设置：`BiliPageHeader` 保持静态；只有 `SettingsPadded` 包裹的卡片/分区逐项入场；
- 音乐库：保持页头/搜索/Tab 静态，内容区入场；
- 音乐库内部 Tab 切换仍用 replayKey 重播内容入场；
- 单测保持 126 全绿。

## v0.12.0（2026-11-08 · tag `v0.12.0`）

**UI 结构整理：页面分类 + 层级页模板，减少页面间实现漂移**

- 新增 `ui/template/BiliPageTemplates.kt`：
  - `BiliTopBar`：统一的返回 + 标题 + actions 顶栏（titleLarge/顶栏背景/insets 约定）；
  - `BiliDetailScaffold`：层级页面统一 Scaffold（surface 容器、Snackbar、顶栏、innerPadding）；
- 所有 T2 层级页面迁移到模板：
  - 专辑详情（顶栏 actions 保留）、编辑分割点、艺术家详情、导入、诊断日志、扫码登录；
  - 删除各页面手写的 Row 顶栏和各自不一致的标题样式（headlineMedium/titleLarge 混用）；
- Tab 页仍统一 `BiliPageHeader`，不自己套 Scaffold；播放/歌词/弹层保持 T3/T4 例外；
- 新增 [docs/UI_ARCHITECTURE.md](UI_ARCHITECTURE.md)：页面四分类、模板 API、状态/UiState 约定、
  共享组件、导航/返回/动效规则、新页 checklist、迁移状态表；
- AGENTS 的 UI 规范更新为“T1/T2/T3/T4 + 必须用模板/页头”，避免后续页再各写各的。
- 单测保持 126 全绿。

## v0.11.0（2026-11-08 · tag `v0.11.0`）

**预测式返回不预演、一级 Tab 内容入场统一、Tab 平级返回 + 连续两次返回退出**

- 预测式返回（手势预输入）：`predictivePopEnter/ExitTransition` 改为 `None`——手势滑动过程中页面不再先退一半，
  取消手势也不会有残留视觉；只有真正完成返回后才播放普通 pop 转场（整页右滑）。
- 一级 Tab 内容入场统一：修复 `Modifier.entrance` 在 `play=false→true` 时从 1→1 不播放的问题；
  发现/音乐库/设置都使用默认 `play=true` 的入场动画；音乐库内部 Tab 切换继续用 `replayKey` 重播。
- 一级 Tab 改为行为上的平级：在音乐库/设置按返回不再“假装回发现”，而是进入退出流程；
  返回 Toast 提示「再返回一次退出应用」，2 秒内连续第二次才真正退出（键盘弹出时不拦截）。
- 播放页内的播放列表 ModalBottomSheet 优先响应返回（先关弹层，再收播放页）。
- 单测保持 126 全绿。

## v0.10.0（2026-11-08 · tag `v0.10.0`）

**归档/配置 v3：分域 bundle（manifest + library + settings + covers）；维测默认关闭；控制中心自定义封面**

- 归档按域拆分，不再把整库塞进一个 JSON：
  - 整库 = 目录 bundle：`manifest.json` + `library.json` + `settings.json`(可选) + `covers/<sha256>.<ext>`；
  - library 只放专辑/分曲，封面只写 hash 引用；
  - settings 独立文件，默认不覆盖本地，仅覆盖全库恢复时应用；
  - playlists 域 schema 预留，当前导入会提示忽略；
  - 单专辑/分割点仍支持单 JSON 分享（album 可内嵌封面）。
- 数据层：`ArchiveBundleCodec`（manifest/hash/域校验）、`LibraryArchiveCodec` v3（library/album/segments）、
  仓库导出/导入/自动备份/预览全部切到 bundle；单 JSON library 导入明确拒绝。
- UI：设置页“备份与恢复”改为整库 bundle（导出到文件夹 / 从文件夹恢复）+ 导入分享文件入口；
  移除整库单文件导出；自动备份路径改为目录 bundle。
- 维测日志默认关闭：`AppSettings.diagnosticLogsEnabled=false`，SharedPreferences 默认 false；仍可手动开启。
- 控制中心/锁屏封面：播放引擎与歌词视频播放器优先读取 `coverLocalPath` 自定义封面字节作为
  MediaMetadata artworkData，换封面后媒体控制中心随之更新；无自定义封面仍回退 B 站封面。
- 单测 126 全绿（codec/bundle/repository 测试按 v3 重写）。

## v0.9.1（2026-11-08 · tag `v0.9.1`）

**归档单文件大小上限修正（数据 schema 不变）**

- 复核发现：单文件归档上限 5MB，而自定义封面允许 2MB 原图（base64 约 2.7MB），
  多张封面的整库备份会“导出成功但导入被拒绝”；
- 单文件上限改为 **64MB**；大量自定义封面/大库建议使用目录归档（每文件独立）；
- 数据模型/归档字段不变（v2 schema 仍是最终版）。

## v0.9.0（2026-11-08 · tag `v0.9.0`）

**归档数据层 v2：稳定身份 / kind / 新版本策略（不兼容 v1）**

- DB v3：`albums` 增加 `source`，唯一键改为 `(source,bvid,page)`；`tracks` 增加 `uid`（UUID）唯一索引，
  已有数据在 `MIGRATION_2_3` 中保留并回填；
- 归档 v2（`LIBRARY_ARCHIVE_VERSION=2`）：
  - 顶层 `kind`（library/album/segments/index）+ `minReaderVersion` + `features`，旧 v1 明确拒绝；
  - 稳定 `albumKey=source:bvid#page`、分曲 `id`（UUID）、显式 `endMs`；
  - `segments` 空语义：省略/null = 更新时保留；`[]` = 清空回未分割；数组 = 替换；
  - 封面对象 `{mime,sha256,byteLength,dataBase64}`，导入校验 MIME/≤2MB/SHA-256；
  - 新增 `pageCount/updatedAt/source/cidHint`，设置对象独立 version 并包含诊断日志开关；
  - 目录归档根索引 `kind=index` 禁止当单文件导入；合并后做全局校验（重复 albumKey 等）；
- 兼容性：v1 归档不再读取；旧「快捷导入」文本/CSV 仍支持并归一化成 v2；
- 测试：归档 codec/repository 按 v2 重写（v2 往返、kind 门禁、cover hash、空语义、目录往返、REPLACE 备份等），
  单测 126 个全绿。

## v0.8.4（2026-11-08 · tag `v0.8.4`）

**修复 v0.8.3 闪退 / 列表入场不统一 / 系统返回的缩小淡出**

- **闪退根因修复**：进入非一级页面时，迷你条底部 offset 的 spring 动画在过冲时会给出负 padding，
  Android 直接抛 `IllegalArgumentException: Padding must be non-negative`（真机堆栈已确认）。
  修复：`animateDpAsState` 改为无过冲 spring（dampingRatio=1）+ `coerceAtLeast(0.dp)` 兜底；
- **列表入场不统一**：`Modifier.entrance` 在 `play=false` 时初始进度是 1，之后 `play=true` 从 1→1 无动画；
  发现/音乐库因此没有入场效果，设置页（默认 play=true）正常。修复为始终从 0 开始，发现/音乐库/设置
  以及音乐库内部 Tab 现在都会播放「淡入 + 上浮」入场；
- **“缩小退出”根因修复**：navigation-compose 2.10.2 的默认 **预测式返回（predictive pop）** 是
  `scaleOut(0.7f)`！系统返回手势/返回键会触发它，导致音乐库→艺术家、专辑等页面退出时缩小淡出。
  已在 NavHost 全局覆盖 `predictivePopEnterTransition` / `predictivePopExitTransition` 为与普通返回一致的
  整页右滑，所有页面统一；代码里不再有页面级 scale/fade 转场（仅视频控件等局部显隐保留 fade）。
- 单测保持 152 全绿。

## v0.8.3（2026-11-08 · tag `v0.8.3`）

**一级 Tab 内容入场动画 + 迷你条底部位置平滑过渡 + 分割时间确认支持 1 小时以上**

- 参考 SPICaMusic_Android 的 `Modifier.entrance`（35ms 交错、28dp 上浮、250–550ms）：
  - 发现/音乐库/设置页面内容出现时统一做「淡入 + 24dp 上浮」入场，Tab 瞬时切换不再显得生硬；
  - 音乐库内部 Tab（歌曲/专辑/艺术家）切换时通过 replayKey 重播内容入场；
  - 动画只写 graphicsLayer，不触发重组，对列表滚动性能无影响。
- 迷你播放条：进入非一级页面（底部三个 Tab 消失）时，迷你条底部 offset 用 spring 平滑过渡到系统手势区，
  不再瞬间掉到屏幕底部。
- 分割时间确认：`TimeUtils.parseTimeToMs` 支持 `h:mm:ss`，`SplitTextParser` 的时间 token 也支持
  `(?:\d{1,2}:)?\d{1,2}:\d{1,2}`；新增单测覆盖 `01:22:23` = 4,943,000ms（1 小时以上可直接用）。
- 单测 152 全绿（新增 01:22:23 断言）。

## v0.8.2（2026-11-08 · tag `v0.8.2`）

**导航动画统一：一级 Tab 不做滑动，层级页面统一 push/pop（参考 SPICaMusic_Android）**

- 参考项目结构：它的三个一级页（发现/音乐/资料库）是 **同一个 HomeScreen 里的 `when(currentPage)` 切换**，
  根本没有转场动画；所有真正的页面前进/返回统一走一套 `iosStyle` push/pop。
- 本项目对齐：
  - **一级 Tab（发现/音乐库/设置）取消左右滑动/缩放**，改为瞬时切换（仅底部导航指示器有动画），
    解决“三个页面平级却方向/速度不一致”的观感；
  - 层级页面统一一套 push/pop：
    - push：新页从右侧整页滑入，旧页向左 **1/4 视差**（不再整页推挤、不再淡入/缩放）；
    - pop：当前页向右整页滑出，上一页从左侧 1/4 回位；
    - 全程无 alpha、无 scale；Tab 不参与转场，所以底层不会“往右撤”或“缩小消失”。
- 修复：从发现/音乐库打开专辑时，底层页面此前因 Tab 的 exitTransition 被误判方向而向右撤；
  返回音乐库时也因 Tab 参与转场出现缩小消失——现在底层页面保持静止，详情页从上方滑入/滑出。
- 单测保持 152 全绿。

## v0.8.1（2026-11-08 · tag `v0.8.1`）

**修复左右转场重影/缩小淡出、迷你条黑边与模糊深度、播放页下滑关闭**

- 左右转场重影根因：各页面根节点背景是透明的，滑动过程中旧页面内容会从新页面下面透出来，
  看起来像两页叠加/淡入淡出。修复：
  - 每个 NavHost 目的地都包一层不透明 `Page` Surface；
  - 转场改为**完整宽度同步推挤**：进入从右侧整页滑入、旧页整页向左滑出（不再只移动 1/3 视差），
    两页像两张实体卡片一起挤压切换；返回方向完全反向，无 alpha 淡入淡出；
  - Tab 也按序号方向整页推挤；专辑等详情页返回同理（不再缩小/淡出）。
- 迷你播放条：
  - blur radius 22dp → 36dp，底色/色调透明度降低，毛玻璃更深；
  - 移除底部 2dp 进度线（用户反馈的“黑边”来源），边框透明度降低；
  - 不支持 RenderEffect 的设备回退更高不透明度，保证可读。
- 播放页下滑关闭：NowPlaying 顶部栏 + 顶部 48dp 区域都可以向下拖动收起；
  速度/进度判定：向下快滑关闭、向上快滑展开，否则过半判定；返回键同样收起。
- 单测保持 152 全绿。

## v0.8.0（2026-11-08 · tag `v0.8.0`）

**连续展开的播放页 + 全部导航改为左右滑动（去掉淡入淡出）**

- **迷你条 → 播放页改为连续跟手展开**（参考 SPICaMusic_Android 的 HomePlayerScaffold 思路，简化实现）：
  - 播放页不再是导航目的地，而是覆盖在内容层上的卡片；`playerProgress 0→1` 由迷你条上的垂直拖拽距离连续驱动，
    卡片随手指上升、横向留白/顶部圆角随进度收缩，松手用弹簧收敛（>18% 或向上快滑展开，否则收回）；
  - 展开时迷你条淡出、底部导航隐藏；返回键/播放页返回箭头收起（卡片向下滑出）；
  - 从播放页进入歌词视频时卡片让位，视频返回后自动恢复播放页；
  - 迷你条不再有“收起细条”状态（上下关系统一为「播放页卡片 ↔ 迷你条」）。
- **全 App 导航动画改为纯左右滑动**（用户要求「从哪来、回哪去」，不要淡入淡出）：
  - 所有层级页面：进入从右侧滑入、返回向右滑出；退出时旧页向左 1/3 视差，不再使用任何 alpha 淡入淡出；
  - 三个 Tab 按 Tab 顺序决定方向（发现→音乐库→设置 前进 = 从右进入，反向 = 从左进入）；
  - 播放页展开/收起为上下，符合“迷你条退出是上下关系”。
- 删除 v0.6.x 的 Fade through / Shared axis 混合转场实现；动效 token 仍统一在 BiliMotion（400ms/Emphasized）。
- 单测保持 152 全绿。

## v0.7.0（2026-11-08 · tag `v0.7.0`）

**动效节奏润色 + 迷你播放条毛玻璃/跟手拖拽**

- 动效节奏按 M3 官方「Emphasized decelerate 400ms 入场 / Emphasized accelerate 200ms 退场」建议放缓：
  - Tab Fade through 400ms（旧页 200ms 淡出 → 新页延迟 100ms 以 300ms 淡入 + 94%→100%）；
  - 层级页面 Shared axis X 400ms，位移改为屏幕宽 24%（此前 30dp 太含蓄，返回时像“原地缩小淡出”）；
    进入从右滑入、返回向右滑出，严格对称；
  - 播放页 Shared axis Y 400ms（屏高 14%）；歌词页 Fade 250/150ms。
- 迷你播放条（参考 SPICaMusic_Android 的 Haze 实现）：
  - 新增 Haze 2.0.1 毛玻璃（`haze` + `haze-blur` + `haze-blur-material3`）：
    backdrop blur 22dp + 半透明 surface tint + 细边框；API 31+ 生效，软件渲染/低版本用高不透明度 fallback；
  - 去掉顶部拖拽提示条（handle）；底部进度线改为低对比度（不再一条亮线）；
  - 上滑支持**跟手连续拖动**：整条随手指上移（最高 180dp），松手按阈值/速度决定展开播放页或弹簧回位；
    下滑收起为细条（细条也无 handle），点击/上滑重新展开。
- 布局：迷你播放条改为悬浮在内容层底部（盖住列表），位于底部导航之上；HazeState 由 NavHost 的 hazeSource 提供背景采样。
- 参考项目对比补充：SPICaMusic_Android 的迷你条也使用 Haze 2.0（glass + blur material3），我们采用其中的 blur 子集，不引入其玻璃折射/高光整套。
- 单测保持 152 全绿。

## v0.6.3（2026-11-08 · tag `v0.6.3`）

**列表卡顿最终定位并修复：发布构建改用 R8 release（+ 参考项目做法对齐）**

- 真机（Xiaomi 23113RKC6C / Android 16）用同一滚动手势对比：
  | 指标 | debug v0.6.2 | release v0.6.3 |
  | --- | --- | --- |
  | Total frames | 238 | 838 |
  | Janky frames | 7.14% | **0.12%** |
  | 90th / 95th / 99th | 53 / 69 / 93 ms | **8 / 8 / 11 ms** |
  | Slow UI thread | 15 | **0** |
  | Missed Vsync | 9 | **0** |
- 根因：此前交付/安装的都是 **debug 包（无 R8、可调试）**，Compose 在 debug 下重得多；
  `release { isMinifyEnabled = true; isShrinkResources = true; signingConfig = debug }`，
  ProGuard 补 kotlinx.serialization / Room keep 规则；release APK 仅 3.4MB。
- 对齐参考项目 `SPICaMusic_Android` 的列表性能做法：
  - 使用 release + R8（参考项目 release 开启 minify）；
  - 对 `Album/Track/TrackWithAlbum/QueueEntry/PlaybackState/LibraryUiState/HomeUiState/ArtistDetailUiState`
    加 `@Immutable`，提升 Compose 跳过重组；
  - 参考项目还使用 baseline profile（Macrobenchmark 生成）；本版先不做，若启动/首屏仍需优化可再加。
- 发布产物改为 release APK：`dist/biliaudio-v0.6.3-release.apk`（debug 保留给开发调试）。
- 单测 152 全绿；v0.6.2 的 Material Motion 动效在 release 下同样生效且更顺滑。

## v0.6.2（2026-11-08 · tag `v0.6.2`）

**全局动效重写（依据 Material Design 3 Motion 官方规范）**

- 新增 `ui/theme/Motion.kt` 动效 token：
  - M3 Easing：Emphasized / EmphasizedDecelerate / EmphasizedAccelerate / Standard；
  - M3 Duration：short2=100ms、short4=200ms、medium2=300ms；
  - M3 Expressive motion physics 弹簧 token（按 androidx StandardMotionTokens / ExpressiveMotionTokens
    数值本地实现，material3 1.5 的 MotionScheme API 尚为 internal）：default spatial ⌀0.9/stiffness 700 等。
- 转场按 Material transition patterns 重写：
  - 顶级 Tab = **Fade through**：旧页 100ms 淡出 → 新页延后 100ms 以 200ms 淡入 + 92%→100% 放大（共 300ms），
    严格分段、无两页同时清晰叠影（修复 v0.6.1 的 Tab 叠影/空白问题）；
  - 层级页面（专辑/分曲/艺术家/导入/登录/设置子页）= **Shared axis X**：30dp 平移 + 100ms 淡出 / 200ms 淡入（延迟 100ms）；
  - 播放页 = **Shared axis Y**（从迷你条向上展开，30dp）；歌词页 = **Fade**；
  - 迷你播放条收起/展开 = M3 spatial 弹簧（damping 0.9 / stiffness 700）+ 淡入淡出。
- 移除 v0.6.0 的 `PageEnterAnimation`（非 Material 规范、与导航转场叠加造成双重动画/闪白）。
- 性能：AppNavHost 不再直接订阅播放状态，`MiniPlayerHost` 内部订阅，250ms 进度 tick 不再触发导航壳/当前页重组。
- 单测保持 152 全绿；文档记录动效规范来源与选择理由。

## v0.6.1（2026-11-08 · tag `v0.6.1`）

**性能/一致性/过渡/日志开关修复**

- **音乐库滚动卡顿修复**：
  - `LibraryViewModel` 把播放状态压成 `PlaybackHighlight(trackId, isPlaying).distinctUntilChanged()` 再 combine，
    播放进度 tick 不再重建 uiState；艺术家聚合只在搜索/数据库变化时计算一次；
  - 索引栏 `activeLabel` 改为 `activeLabelProvider` 在组件内部读取，跨字母不再整列表 section 重组；
  - 三个 Tab 的 Lazy items 增加 `contentType`，key 保持稳定；ArtistDetail 同步优化；
  - 模拟器 gfxinfo 对比：90/95/99th 37/44/59ms → 32/35/46ms，Slow UI thread −60%，Missed Vsync 2→0
    （headless 软渲染绝对值仅作相对参考）。
- **搜索框 UI 统一**：`SearchField` 重做为 pill 样式（无边框胶囊、surfaceVariant 半透明底、搜索图标、清空按钮），
  新增 `SearchBarEntry` 供发现页使用；发现/音乐库/搜索页三处视觉完全一致。
- **Tab 切换过渡修复**：旧页先移除导致新页淡入期间闪白 → Tab 改为「轻微缩放进入 + 旧页无动画退出」，
  并新增 `PageEnterAnimation`（内容 8dp 上浮 220ms），切页不再出现空白帧；加载态标题显示「正在加载音乐库…」。
- **维测日志开关**：设置 → 诊断与维护 →「维测日志」Switch；关闭后 `Diag` 不再记录/打印（立即生效、持久化、
  历史日志仍可查看/导出）；重启后保持关闭状态。真机/模拟器已双向验证。
- 新增 `AppSettings.diagnosticLogsEnabled` 与 `SettingsRepository.setDiagnosticLogsEnabled`；测试 fake 同步更新。
- 单测保持 152 全绿。

## v0.6.0（2026-11-08 · tag `v0.6.0`）

**UI 调整：页头/转场/迷你条手势/音乐库三分栏索引/归档目录结构**

- **统一三主页面布局**：新增 `BiliPageHeader`，发现/音乐库/设置的左上角大标题位置、字号、顶部留白完全一致
  （模拟器实测三页标题 bounds 均为 `(42,95)-(1038,231)`）。
- **导航转场重做**：Tab 切换淡入淡出；详情页（专辑/分曲/导入/登录/设置子页/艺术家）统一横向共享轴：
  进入从右推入、返回向右退出，进出对称；播放页从底部上滑打开、下滑退出（与迷你条手势一致）；歌词页淡入淡出。
  不再出现「进入淡出、返回缩放」的不一致感。
- **迷你播放条手势**：展开态上滑→打开正在播放页；下滑→收起为细条（带拖动指示条 + 当前曲名）；
  收起态点击/上滑→重新展开。收起状态由 `AppNavHost` 保存，Scaffold 底栏高度动画过渡。
- **音乐库改版**：歌曲 / 专辑 / 艺术家三个分类；右侧 `# A-Z` 快速索引栏（点击/拖动定位、拖动大字气泡、
  当前分组高亮，无数据字母自动跳到下一个有分组）；中文用 `android.icu.text.AlphabeticIndex` 按拼音分组排序
  （周杰伦→Z、陈奕迅→C），B 站标题的「【官方 MV】」等前缀会跳过再分组；
  艺术家从专辑聚合（空→未知艺术家，封面取该艺术家**第一张专辑**封面，副标题 N 专辑 · M 歌曲）；
  新增艺术家详情页（封面/名称/统计 + 该艺术家专辑网格 + 歌曲列表，歌曲点击按单专辑队列播放）。
- **归档目录形态（配置目录）**：
  ```
  顶层/
    biliaudio-library.json          # 根索引（版本/导出时间/设置）
    艺术家/
      专辑/
        album.json                  # 专辑元信息（含内嵌封面 coverBase64）
        segments.json               # 专辑分割点
  ```
  设置 → 备份与恢复新增「导出到文件夹」「从文件夹导入」（SAF 选择目录；导入支持合并/仅更新分割点/覆盖全库，
  覆盖前自动备份；路径安全化与重名去重；REPLACE 无有效专辑时拒绝清库）；原单文件 JSON 备份保留兼容。
- 依赖新增 `androidx.documentfile:documentfile:1.1.0`（SAF 目录读写）。
- 单元测试 141 → **152 全绿**（目录归档 11 个新用例）。

## v0.5.0（2026-11-08 · tag `v0.5.0`）

**自定义专辑封面（本地上传 / 网易云在线搜索）/ 歌词页加载体验修复**

- 自定义封面：
  - 导入预览、专辑详情均可「换封面」：从相册选择本地图片；在线搜索网易云专辑封面；
    可恢复 B 站原始封面；
  - `Album.coverLocalPath`（本地文件优先，回退 B 站远程 coverUrl）；Room v1→v2 迁移新增该列；
  - 封面保存时会解码缩放到长边 ≤1080、JPEG 88 后存入 `files/covers/`；更新/删除专辑会清理旧文件；
  - 归档导出内嵌 `coverBase64`（≤2MB 原图），导入恢复自定义封面，备份/恢复/分享可带走封面；
  - 新增 `NetEaseCoverApi`（网易云专辑搜索 + 图片下载，Referer 头，无需登录）。
- 歌词页加载体验：
  - 视频未出首帧时用**当前专辑封面占位**（替代黑屏），READY 后淡出；
  - 加载覆盖层延迟 500ms 才出现、文案固定「正在缓冲…」，快速加载（真机首帧 289~341ms）全程无 spinner/文案；
  - READY 后因音画校正 seek 引起的短暂 re-buffer 不再显示加载提示（`renderedAlbumId` 锁定 + 覆盖层只在首次加载显示）；
  - 音画漂移阈值 300ms→800ms，两次校正最小间隔 3s，READY 后 1.2s 宽限期，避免 seek→缓冲→再 seek 循环。
- 修复归档 `coverBase64` 解析丢失（codec 归一化漏字段）与单测环境 Base64 桩问题（改用 okio）。
- 单元测试 129 → **141 个全绿**。

## v0.4.0（2026-11-07 · tag `v0.4.0` · commit `36a8843`）

**诊断日志 / 单专辑队列 / 歌词页加载优化 / 旧数据清理**

- 新增 `Diag` 诊断日志（内存环形 3000 行 + Logcat `BiliDiag`），打点覆盖 App/Api/Resolver/Play/Video/Import/Archive；
  设置 → 诊断与维护 → 诊断日志（查看/复制/导出/清空，含设备与版本信息）。
- 音乐库点歌改为**只播放该曲所属专辑**（队列=该专辑分曲）；「播放全部歌曲」保留全库显式播放。
- 歌词页加载体验：分阶段文案（获取视频流/准备播放器/缓冲）+ 已等待秒数；>8s 提示慢加载并可重试
  （重试会作废旧流地址重新解析）；画面显示清晰度标签；视频清晰度上限调整为 720P；
  `setMediaItem(item, startPosition)` 省去一次 seek；未 READY 不做音画漂移校正。
- 启动时自动清理旧版本遗留的「整张专辑占位曲」（单曲、0→总时长、标题=专辑名），并有单测覆盖。
- 单元测试 129 个全绿。

## v0.3.0（2026-11-07 · tag `v0.3.0` · commit `edbdf12`）

**文本识别分割点 / 未分割专辑 / 歌词视频清晰度**

- 导入未指定分割点时不再生成「整张专辑占位曲」，改为 0 首（未分割专辑），播放器虚拟整张播放。
- 新增 `SplitTextParser`：支持三种粘贴格式（可混用）：
  `1.Breakaway00:00` / `00:00 Tap Out` / ` Come Together00:00`；兼容全角、毫秒、编号等。
  接入导入预览「分曲文本（可选）」与分曲编辑「文本导入」。
- 修复歌词视频糊：原选流策略选中 360P HEVC 低码率流（~82kbps）；改为最高分辨率、同分辨率 AVC 优先。
- 新增/更新单测（文本识别 12 例等）。

## v0.2.0（2026-11-07 · tag `v0.2.0` · commit `4f8584c`）

**音乐库归档（备份 / 恢复 / 分割点文件）**

- 定义 `biliaudio-library` JSON v1：一个文件承载整库备份、单张专辑、仅分割点（docs/LIBRARY_ARCHIVE.md）。
- 数据层：`LibraryArchiveCodec`（识别/校验/旧格式归一化）、`LibraryArchiveRepositoryImpl`
  （导出整库/单专辑、preview、三种导入模式）。
- 导入模式：合并 / 仅更新分割点 / 覆盖全库；覆盖前自动备份到 `files/backups/`（保留 10 份）。
- UI：导入页「归档导入」Tab（模式选择、解析预览、结果统计）、设置「备份与恢复」、专辑详情「导出专辑文件」。
- 兼容旧 JSON 数组/CSV/文本块批量导入（归一化后入库）；新增 30 个单测。

## v0.1.0（2026-11-07 · tag `v0.1.0` · commit `fedc0b3`）

**第一版：B 站专辑音乐播放器**

- B 站扫码登录（Cookie 持久化、账号态校验、退出清理）。
- 专辑导入：BV 号/链接解析预览；JSON 数组/CSV/文本块批量导入（含分曲时间点）；B 站视频搜索导入。
- 分曲编辑：时间点+歌名、播放中打点、排序、保存校验、逐点试听。
- 播放：Media3 ExoPlayer 分曲裁剪、专辑连续播放、播放列表=当前专辑分曲、后台播放 + 媒体通知、
  随机/循环。
- 歌词页：独立第二路 ExoPlayer 播放 B 站原视频、音画同步、全屏/非全屏。
- 音乐库：歌曲/专辑/艺术家搜索、最近播放、播放全部。
- 设置：音质、深色模式、账号、关于；Material 3 扁平视觉、动态色板、无液态玻璃。
- 技术：wbi 签名、DASH 流、Room、Coil、手写 DI；Gradle 腾讯镜像；79 个单测。

## 文档 / 工具相关提交（非发版）

- `b442721` docs: 音乐库归档文件格式方案 v1
- `162c7ed` docs: Android Studio 运行配置（旧包名缓存）排障说明
- `2ced30e` chore: 收录真机走查截图与 Gradle parallel sync 设置
- `fedc0b3` 之后初始化 git 仓库并完成第一版快照

## 后续计划

见 [PROJECT_STATUS.md](PROJECT_STATUS.md) 第 6 节「待办 / 下一步」。
