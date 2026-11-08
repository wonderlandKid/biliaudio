# 版本记录

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
