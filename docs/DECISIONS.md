# 设计决策记录（ADR）

> 记录已确定、后续开发应遵守的关键决策。每条含背景/决策/原因/影响。
> 新增决策请追加（编号递增、注明日期），推翻旧决策要写「替代 xxx」而不是删掉。

---

## D1 歌曲模型：Track 是「虚拟歌曲」，不做独立实体

- **日期**：2026-11-07
- **背景**：B 站专辑是一个视频（一条音视频流），需要按时间点拆成多首歌播放。
- **决策**：`Track` 只存 `albumId/title/startMs/endMs`，播放时用 `MediaItem + ClippingConfiguration`
  在专辑流上裁剪；每首歌**不存独立音频/封面/艺术家**，艺术家等元数据继承 `Album`。
- **原因**：用户明确表示「完全没必要做成实体，直接继承就好」；独立实体要重构播放/队列/归档，
  且失去「一张专辑连续无缝 + 少请求 + 共用原视频」的优势。
- **影响**：单曲搜索/队列/通知按 Track 处理，但物理上都是专辑流的切片；新增单曲元数据需求时
  只加可选字段（如未来 per-track artist），不改实体关系。

## D2 播放队列语义：点歌/专辑播放 = 单专辑队列

- **日期**：2026-11-07
- **背景**：早期音乐库点歌用 `playSongs(全部歌曲)`，导致播放列表变成整个音乐库。
- **决策**：
  - 从专辑页播放全部/点分曲 → 队列 = 该专辑全部分曲；
  - 从音乐库点某一首 → 队列 = 该曲所属专辑的分曲（从选中曲开始）；
  - 音乐库「播放全部歌曲」是显式行为，播放当前列表全部；
  - NowPlaying 播放列表面板始终展示 `PlaybackState.queue`（专辑播放时即该专辑分割点）。
- **影响**：用户听专辑时不会被切到别的专辑；「播放全部歌曲」保留全库场景。

## D3 未分割导入 = 0 首曲目 + 播放器虚拟整首；旧占位曲自动清理

- **日期**：2026-11-07（v0.3.0 决策 / v0.4.0 补数据迁移）
- **背景**：旧版本未分割导入会生成一条「整张专辑占位曲」（title=专辑名、0→总时长），
  用户要加第一首分曲前必须先手动删除它。
- **决策**：
  - 导入未指定分割点时**不生成任何 Track**（0 首），播放时 `PlaybackEngine` 用虚拟整张曲目
    （id = `-albumId`，0→duration）参与队列；
  - v0.4.0 启动时自动清理旧版本遗留占位曲（单曲、0→总时长、标题=专辑名）；
  - 纯函数 `isLegacyPlaceholder` + 单测覆盖。
- **影响**：未分割专辑在音乐库/专辑页显示「未分割」，点播放仍能整张播放。

## D4 分割点文本识别：三种常见格式，逐行独立解析

- **日期**：2026-11-07
- **背景**：用户从专辑信息/评论区拿到的曲目表格式不统一，手动逐首添加太慢。
- **决策**：`SplitTextParser` 支持（可混用、逐行识别）：
  1. 编号 + 歌名 + 时间（时间在后，中间可无空格）：`1.Breakaway00:00`
  2. 时间 + 歌名（时间在前）：`00:00 Tap Out`
  3. 歌名 + 时间（时间在后，允许前导空格/无空格）：` Come Together00:00` / `Oh! Darling 10:50`
  兼容全角数字/冒号、`hh:mm:ss`、毫秒、编号分隔符 `.、)）`；自动排序、去重、跳过无法识别行并提示。
- **影响**：入口有两处——导入预览的「分曲文本（可选）」与分曲编辑页「文本导入」；
  CSV/文本块等旧格式仍由 `BatchImportParser` 兼容。

## D5 音乐库归档：统一 JSON（.biliaudio.json v1），一个文件三种用途

- **日期**：2026-11-07
- **背景**：用户希望「分割点能文件导入、专辑也能文件导入，并统一成音乐库备份」。
- **决策**：定义 `biliaudio-library` JSON 格式（见 LIBRARY_ARCHIVE.md）：
  - 同一结构承载整库备份 / 单专辑分享 / 仅分割点文件；
  - 只有 JSON（暂不做 YAML）；`segments` 支持 `startMs` 或 `start` 字符串；省略元数据时新建会用 B 站补全；
  - 三种导入模式：合并（默认）/ 仅更新分割点 / 覆盖全库（先自动备份到 `files/backups/`，保留 10 份）；
  - 旧批量格式（JSON 数组/CSV/文本块）自动归一化到同一模型后导入。
- **影响**：数据层 `LibraryArchiveCodec` 是唯一解析入口；`LibraryArchiveRepository` 是唯一落库入口。

## D6 播放架构：单 ExoPlayer + ClippingMediaItem；歌词页独立第二路视频播放器

- **日期**：2026-11-07（初版决策）
- **决策**：
  - 播放引擎为进程内单例 `PlaybackEngine`，唯一 ExoPlayer；
  - 每首歌 = `MediaItem(专辑音频URL) + ClippingConfiguration(start,end)`，专辑绝对进度 = `track.startMs + playerPosition`；
  - 后台播放用 `MediaSessionService` 复用同一 ExoPlayer；
  - 歌词页用独立的第二路 ExoPlayer 只解码视频轨（静音），按「专辑绝对进度」同步（500ms 检查、>300ms 才 seek），
    离开页面 release。
- **原因**：避免合并音视频流/双解码的复杂度；音乐管线保持干净，视频页可独立失败/重试；
  避免两路播放器互相 seek 干扰。
- **影响**：歌词页打开时额外一份视频流量；音频与视频同步只按专辑绝对时间对齐。

## D7 流选择策略

- **日期**：2026-11-07（v0.3.0 修复清晰度 / v0.4.0 收紧 720P）
- **音频**：按设置偏好（AUTO 最高：30280→30232→30216），排除杜比/Hi-Res；wbi playurl `fnval=16`。
- **视频（歌词页）**：优先最高分辨率、**当前封顶 720P**（兼顾清晰度与首帧/seek 等待），
  同分辨率优先 **AVC**（兼容性最好）再按码率高低；**不要选「≤720P 里码率最低」**
  （曾因此选中 360P HEVC ~82kbps，画面很糊）。
- **原因**：B 站视频 CDN 是 fMP4（含 sidx，可按 Range seek，但仍受码率/节点影响）；
  720P 在手机上足够清晰，同时显著降低大文件加载等待。1080P+ 留作后续「画质设置」。
- **风险**：未登录最高只有 480P；HEVC 设备兼容性差异已用 AVC 优先规避。

## D8 登录：扫码 + Cookie，未登录可用

- **日期**：2026-11-07
- **决策**：B 站扫码登录（generate + poll，Set-Cookie 落盘 SharedPreferences），
  退出清登录态并 `invalidateAll()` 流缓存；未登录也可导入/播放（画质音质受限）。
- **影响**：登录成功路径必须真机扫码 QA；不要在日志/文档中记录 Cookie（Diag 不打印敏感信息）。

## D9 视觉与交互

- **日期**：2026-11-07
- **决策**：Material 3（material-kolor 动态色板），扁平、大圆角、大标题、封面卡片、底部迷你播放条；
  **禁止液态玻璃/毛玻璃**；参考项目 `/home/wonderlandkid/code/SPICaMusic_Android` 的风格但可不同实现。
- **影响**：新页面复用 `ui/theme` 与 `ui/components`，不要另起设计系统。

## D10 维护/诊断日志：统一走 Diag

- **日期**：2026-11-07（v0.4.0）
- **决策**：新增 `util/Diag`（内存环形 3000 行 + Logcat tag `BiliDiag` + 设置页查看/复制/导出/清空）。
  Tag 规范：`App / Api / Resolver / Play / Video / Import / Archive`。
  只记录技术链路（耗时、分辨率、码率、CDN host、错误码），**不记录 Cookie/账号敏感信息**。
- **原因**：用户要求「加维护测试日志，方便定位问题」，尤其是歌词页转圈问题需要真机日志。
- **影响**：关键链路（API/解析/播放/视频/导入）应打点；定位流程见 DEBUGGING.md。

## D11 版本与发布流程

- **日期**：2026-11-07
- **决策**：SemVer（0.x 阶段按功能/修复递进）；每次发版 versionCode+1、versionName 升级、全量单测、
  annotated tag `vX.Y.Z`；APK 与源码 zip 放 `dist/`（gitignore，不进仓库），源码以 tag 为准。
- **影响**：见 AGENTS.md「开发约定」。

---

## D12 自定义专辑封面：本地文件优先 + 网易云在线搜索 + 归档内嵌

- **日期**：2026-11-08（v0.5.0）
- **背景**：B 站视频封面常是频道/合集图，不符合专辑听感；用户希望导入专辑时能自己选封面
  （本地上传 + 在线获取两种方式）。
- **决策**：
  - `Album.coverLocalPath` 保存用户封面（本机绝对路径），展示时**本地文件优先，回退 B 站 `coverUrl`**；
  - 封面文件由 `AlbumCoverRepository` 管理：解码 → 长边 ≤1080 → JPEG(88) → `files/covers/`；
    更换/恢复/删除专辑时清理旧文件；
  - 在线来源为**网易云音乐专辑搜索**（`music.163.com/api/search/get?type=10` +
    图片下载，仅带 UA/Referer，无需登录/签名）；接口不稳定时静默降级为空结果；
  - 归档导出把本地封面以 `coverBase64`（≤2MB 原图）内嵌，导入时恢复，保证备份/分享可带走封面；
  - 导入预览支持「待定封面」：导入成功后按 albumId 异步应用，失败不阻塞导入。
- **原因**：不新增音乐实体、不把封面二进制塞进 DB；本地文件路径让 Coil 直接加载、不依赖外链稳定性；
  base64 内嵌是备份可移植性与归档体积的折中（超限不导出）。
- **风险**：网易云接口非官方，可能变更；本地封面不进云同步（仅归档带走）。

## D13 歌词页加载体验：封面占位 + 单次延迟提示 + 同步校正节流

- **日期**：2026-11-08（v0.5.0）
- **背景**：用户反馈（真机 v0.4，日志首帧仅 418ms）点开歌词页「先黑屏卡一下，之后又提示一次缓冲」，
  网络正常时不该出现两次加载观感。录屏逐帧确认有两层原因：
  1) 页面切换时视频区域先黑，随后才出现加载覆盖层；
  2) READY 后约 0.5~2s 音画漂移校正 seek 触发短暂 BUFFERING，phase 回退使加载提示/封面占位再次出现。
- **决策**：
  - 视频未出首帧时用**当前专辑封面占位**（替代黑屏），READY 后 150ms 淡出，且按 `renderedAlbumId`
    锁定「本专辑已出过画面」，后续 re-buffer 不再回退封面；
  - 加载覆盖层**延迟 500ms** 才出现，用户可见文案固定「正在缓冲…」（阶段只进日志）；
    <500ms 快速加载全程无 spinner/文案；>2s 显示等待秒数，>8s 给慢加载提示+重试；
  - 禁用 PlayerView 自带 buffering spinner（避免双 spinner）；
  - 音画漂移阈值 300ms→**800ms**，两次校正最小间隔 **3s**，READY 后 **1.2s 宽限**，
    避免 seek→缓冲→再 seek 循环；中途 re-buffer 不显示整屏加载提示。
- **原因**：正常网络下首帧 <500ms，用户不该看到任何加载 UI；中途短缓冲用「保留画面」体验更好。
- **影响**：慢网络/慢 CDN 仍会显示一次提示并可手动重试；若观看中长缓冲，覆盖层不再出现（后续可加小提示）。

---

## D14 UI 页头与导航转场规范（v0.6.0）

- **日期**：2026-11-08
- **背景**：三个主页面手写标题的顶部留白/边距不一致；转场无显式配置，进入与返回观感不一致（返回像「缩放到最小」）。
- **决策**：
  - 所有 Tab 页使用 `ui/components/PageHeader.kt` 的 `BiliPageHeader(title, subtitle)`，内容容器顶部统一 `Spacing.md`、
    标题左右 `Spacing.lg`；禁止再手写大标题。
  - 转场在 `AppNavHost` 统一定义：Tab 切换淡入淡出（180/140ms）；详情页横向共享轴——进入从右推入（320ms）+淡入，
    返回向右退出 + 淡出，popEnter 从左侧 1/4 回位；播放页从底部上滑（340ms）、返回下滑；
    歌词页 fade；不再使用默认的缩放/不一致动画。
  - 页面内的临时操作（底部弹层、对话框）不参与导航转场。
- **影响**：新页面通过 NavHost `composable` 使用默认横向转场即可；Tab 页需显式 fade 覆盖。

## D15 音乐库信息架构：三分栏 + 字母索引 + 艺术家聚合（v0.6.0）

- **日期**：2026-11-08
- **决策**：
  - 音乐库固定三个分类：歌曲 / 专辑 / 艺术家；
  - 每个分类右侧 `# A-Z` 快速索引栏：点击/拖动跳转、拖动显示气泡、当前分组高亮；数据为空的分组自动跳到下一个；
    搜索激活时隐藏索引栏；
  - 分组与排序使用 `android.icu.text.AlphabeticIndex(ULocale.SIMPLIFIED_CHINESE)`（API 24+）按拼音，
    遇到『【官方 MV】』等装饰前缀先剥离再分组；
  - 艺术家由专辑聚合：空名归「未知艺术家」，封面取该艺术家**第一张专辑**封面（用户明确要求「默认第一个」不随机），
    点击进入艺术家详情（专辑 + 歌曲，歌曲按单专辑队列播放）。
- **原因**：用户需要按联系人式索引快速定位；独立 Artist 实体暂时不引入，避免数据迁移与归属歧义。

## D16 迷你播放条手势与收起（v0.6.0）

- **日期**：2026-11-08
- **决策**：
  - 展开态：上滑 → 打开正在播放页；下滑 → 收起为细条（保留拖动指示条与当前曲名，不直接隐藏）；
  - 收起态：点击或上滑 → 展开；展开态点击 → 打开播放页；
  - 收起状态 `rememberSaveable` 由 `AppNavHost` 持有，底栏高度 `animateContentSize` 过渡；打开播放页时自动展开。
- **原因**：不直接隐藏避免「播放条不见了无法恢复」；细条保留可发现性和拖动提示。

## D17 归档目录形态：顶层 → 艺术家 → 专辑 → 专辑分割（v0.6.0）

- **日期**：2026-11-08
- **决策**：在原有单文件 JSON 之外，新增目录形态归档（用户要求「配置文件目录」分层次）：
  ```
  顶层/biliaudio-library.json        # 根索引：格式/版本/导出时间/设置，albums 为空
  顶层/<艺术家>/<专辑>/album.json     # 单专辑元信息（含内嵌 coverBase64，segments 为空）
  顶层/<艺术家>/<专辑>/segments.json  # 该专辑分割点（bvid/url/page + segments）
  ```
  - 目录路径安全化（非法字符/控制字符/首尾空白点/长度 60 码点；空→未知艺术家/未命名专辑；同父目录重名追加 (2)…(99)→(BV号)）；
  - 导入按目录合并 album.json + segments.json；只有 segments 时按 bvid 匹配更新分曲；根索引 settings 按导入模式应用；
  - 导入模式语义与单文件一致（合并/仅更新分割点/覆盖全库）；REPLACE 且无有效专辑时拒绝清库；
  - UI 通过 SAF `OpenDocumentTree` 读写，需 `documentfile`。
- **原因**：目录结构可浏览、可手动修改/分享，比单一巨型 JSON 更符合「配置文件」直觉；保留单文件 JSON 兼容旧备份。

## D18 诊断日志随功能扩展（v0.6.0）

- 新增 tag：`Library`（索引/分组异常崩溃日志）、`Cover`/`Archive` 已有；目录导入导出在 `Archive` tag 记录文件数/模式/耗时。
- 转场/手势不额外打日志（纯 UI），避免噪音。

---

## D19 列表滚动性能规范（v0.6.1）

- **日期**：2026-11-08
- **背景**：音乐库歌曲列表上下滑明显卡顿；定位到两类问题：
  1) `LibraryViewModel.uiState` 直接 combine `playbackController.state`，播放进度每 250ms tick 都会重建 uiState，
     重新执行艺术家聚合（AlphabeticIndex 分组/排序/大量对象分配）——滚动时主线程抖动；
  2) `LibraryScreen` 父作用域读取 `activeLabel`（derivedStateOf 首可见 item），跨字母时整个列表 section 重组。
- **决策**：
  - 播放状态一律先 `map { PlaybackHighlight(trackId, isPlaying) }.distinctUntilChanged()` 再参与 combine；
    重计算（分组/排序/聚合）只在数据变化阶段做（`searchResults.map { LibraryData(...) }`）；
  - 派生滚动状态（索引栏高亮）用 provider/State 在组件内部读取，父级不订阅；
  - Lazy 列表项加 `contentType`（header/song/album/artist），key 使用稳定业务 id；回调 lambda 用 remember 稳定引用。
- **验证**：gfxinfo 90/95/99th 37/44/59→32/35/46ms，Slow UI thread −60%，Missed Vsync 2→0；
  模拟 250ms tick 下 uiState 重建 ≥30 → 0 次（headless 软渲染绝对值仅作相对参考）。

## D20 搜索框视觉统一（v0.6.1）

- **决策**：全 App 搜索输入统一为 `SearchField`（pill 胶囊、无边框、surfaceVariant 半透明底、搜索图标、清空按钮、高度 52dp）；
  只作入口的场景用 `SearchBarEntry`（同一视觉，点击跳转）；发现页/音乐库/搜索页三处必须一致，
  不要再手写 `OutlinedTextField` 或自绘搜索条。

## D21 Tab 切换过渡：禁止 alpha=0 淡入（v0.6.1）

- **背景**：v0.6.0 的 Tab 淡入出现「切页瞬间整页空白」——旧页先被移除，新页 alpha=0 淡入期间只剩白色背景。
- **决策**：Tab 路由 `exitTransition = ExitTransition.None`（旧页保持），新页 `scaleIn(0.97→1)` **不淡入**；
  `PageEnterAnimation` 只做 8dp 上浮（不改变 alpha）；加载态文案显示「正在加载音乐库…」而不是 0 数量。
  详情页仍用横向共享轴、播放页底部上滑、歌词页淡入。

## D22 维测日志开关（v0.6.1）

- **决策**：`AppSettings.diagnosticLogsEnabled`（默认 true）持久化到 SharedPreferences；
  `SettingsRepository.setDiagnosticLogsEnabled` 更新 Flow；App 启动时同步 `Diag.enabled`，
  设置页切换立即生效（关闭前记最后一条，重新开启时记一条）；关闭后 `Diag.log` 直接返回，不写内存/Logcat；
  历史日志保留可查看/导出。设置 → 诊断与维护 →「维测日志」Switch 控制。

---

## D23 全局动效系统按 Material Design 3 Motion 规范重写（v0.6.2）

- **日期**：2026-11-08
- **背景**：此前转场为手写拼凑（Tab 用 ExitTransition.None + scale，详情页用自定义 slide），
  出现 Tab 两页不透明叠影、切页空白/闪白、动画风格不统一等问题。用户要求「先查标准再重写」。
- **调研（官方规范）**：
  - Material Design 3 – Easing and duration tokens
    （https://m3.material.io/styles/motion/easing-and-duration/tokens-specs）：
    Emphasized / EmphasizedDecelerate / EmphasizedAccelerate 曲线；duration short2=100、short4=200、medium2=300、long2=500；
  - Material Design 3 – Transition patterns
    （https://m3.material.io/styles/motion/transitions/transition-patterns）：
    Top level = **Fade through**（旧页先淡出、新页再淡入，用于无强关联的顶级目的地）；
    有层级/方向关系的页面 = **Shared axis**；
  - Material Design 3 – Motion physics overview/specs
    （https://m3.material.io/styles/motion/overview/how-it-works、/overview/specs）：
    M3 Expressive 引入弹簧 token（spatial/effects，standard/expressive 两套方案），
    推荐可交互尺寸/位置动画用 spring；本机 androidx material3 1.5 源码 `StandardMotionTokens`：
    default spatial damping=0.9 / stiffness=700，effects damping=1.0 / stiffness=1600；
  - Material Motion – Fade through / Shared axis 具体时序
    （https://m2.material.io/design/motion/choreography.html、
    https://blog.stylingandroid.com/material-motion-shared-axis/）：
    fade through：旧页 100ms 淡出，新页 200ms 淡入（延迟 100ms）并 92%→100%；
    shared axis：两页同向平移 30dp，旧页淡出 100ms、新页淡入 200ms（延迟 100ms），共 300ms。
- **决策**：
  - 所有动效 token 收敛到 `ui/theme/Motion.kt`（`BiliMotion`），页面不得再写散落时长/曲线；
  - 顶级 Tab 用 Fade through；层级页面用 Shared axis X；播放页 Shared axis Y；沉浸覆盖层用 Fade；
  - 禁止「页面内再叠一层进入淡入动画」（删除 PageEnterAnimation），一次导航只表达一次转场；
  - 可交互尺寸变化（迷你条收起/展开）用 M3 spatial 弹簧；
  - 动画仅使用 alpha/translation/scale（GPU 友好），并依赖 Compose 对系统「动画时长缩放/移除动画」设置的处理。
- **验证**：模拟器录屏逐帧检查 Tab/详情/播放页转场无叠影、无长时间空白；单测 152 全绿。

---

## D24 列表性能与交付构建：日常/验收必须用 release（R8），debug 只用于开发（v0.6.3）

- **日期**：2026-11-08
- **背景**：用户多次反馈「列表仍然很卡」，检查发现此前安装/交付的都是 debug APK（无 R8、可调试），
  真机同滚动脚本对比：debug 90th=53ms / janky 7.14%，release 90th=8ms / janky 0.12%。
- **参考项目做法**（/home/wonderlandkid/code/SPICaMusic_Android）：
  - `release { minifyEnabled = true }`，日常使用/发布走 release；
  - 自定义 `baselineprofile` 模块（Macrobenchmark）生成 baseline profile；
  - 实体与 UI 状态大量使用 `@Immutable`（以及 `ImmutableList`）稳定 Compose 参数。
- **决策**：
  - 本项目 release 开启 `isMinifyEnabled`/`isShrinkResources`，用 debug 签名便于个人安装；
    ProGuard 补充 kotlinx.serialization、Room 规则；
  - 交付/性能验收统一用 release APK（`dist/biliaudio-vX.Y.Z-release.apk`）；
  - 关键 UI 模型/状态加 `@Immutable`；列表继续遵守 D19；
  - baseline profile 暂不引入（当前 release 滚动已达标），作为后续启动/首屏优化项。
- **影响**：以后不能再拿 debug 包判断流畅度；Android Studio 开发时可用 debug，但性能结论必须用 release。
