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

---

## D25 动效节奏放缓与返回位移对称（v0.7.0）

- **日期**：2026-11-08
- **背景**：用户希望整体动画「略微慢一点更优雅」；并反馈详情页进入从右滑入、返回却像「缩小到中间退出」。
- **依据**：
  - M3 Easing and duration 的 recommended pairs：Emphasized decelerate 400ms（进入屏幕）、
    Emphasized accelerate 200ms（离开屏幕）、Emphasized 500ms（起止都在屏幕）；
  - Material Shared axis X 的原 spec 位移为 30dp：对全屏页面来说位移很小，视觉上主要是淡出，
    因此用户感知为「原地缩小/淡出」。这是标准实现与主观体感之间的差异。
- **决策**：
  - 转场总时长 400ms（入场 300ms decelerate + 退场 200ms accelerate，Tap 有 100ms 延迟分级）；
  - 层级页面横向位移改为 **屏幕宽度 24%**，进入/返回严格对称（进入从右、返回向右），
    保持 Shared axis X 的「方向关系」语义，同时提供更明确的空间反馈；
  - 播放页 Y 轴位移 14% 屏高；Fade 250/150ms；以上全部收敛到 BiliMotion token。
- **影响**：动画整体更缓、更有方向感；仍远低于 500ms 上限，不阻塞操作。

## D26 迷你播放条：Haze 毛玻璃 + 跟手拖拽，取消拖拽提示条（v0.7.0）

- **日期**：2026-11-08
- **背景**：用户希望迷你条换成 blur 质感以与列表区分、去掉「可上移」的提示条，并让上移跟手；此前「禁止液态玻璃」的约束调整为**仅允许悬浮迷你条使用毛玻璃**。
- **决策**：
  - 使用 Haze 2.0.1 的 `hazeBlur`（blur radius 22dp，背景半透明 tint + 细边框），与参考项目
    SPICaMusic_Android 的迷你条实现同源，但只取 blur 子集，不引入玻璃折射/高光；
  - HazeState：NavHost 作为 `hazeSource`，悬浮迷你条作为 effect；低版本/软件渲染走 fallback 高不透明度；
  - 移除顶部 handle（拖拽提示条）与亮色进度线（改为低对比度 2dp）；
  - 上滑拖动 = 跟手连续位移（最多 180dp），松手按位移/速度决定打开播放页或弹簧回位；
    下滑收起为细条（细条也支持点击/上滑展开）。
- **影响**：迷你条不再占据 Scaffold bottomBar 高度，改为内容层底部悬浮（列表可滚动到其下方，形成模糊背景）。

---

## D27 全 App 导航统一左右滑动（v0.8.0，替代 D21/D25 的混合转场）

- **日期**：2026-11-08
- **背景**：用户明确反馈「淡入淡出太难用」，要求所有页面导航遵循「从哪来、回哪去」的左右结构滑动；
  只有迷你播放条 ↔ 播放页是上下关系。
- **决策**：
  - 层级页面：进入 = 从右侧滑入（400ms EmphasizedDecelerate），返回 = 向右滑出（400ms EmphasizedAccelerate），
    旧页退出时向左 1/3 视差；**不再使用任何 alpha 淡入淡出**；
  - 三个 Tab 按顺序（发现 0 → 音乐库 1 → 设置 2）决定方向：前往更大序号从右进，返回从左进；
  - 歌词页同样左右滑动进出；迷你条 → 播放页为上下（见 D28）。
- **影响**：所有页面导航方向感一致，操作可预测；不再出现“淡出后再缩放”的观感。

## D28 迷你条 → 播放页：连续跟手展开卡片（v0.8.0）

- **日期**：2026-11-08
- **背景**：用户提供参考 App 截图，希望像那些 App 一样：手指慢慢上滑时播放页连续升起，而不是松手后跳转淡入。
- **参考**：SPICaMusic_Android 的 `HomePlayerScaffold`（PlayerSheetState + SubcomposeLayout + 进度驱动的卡片 reveal）。
- **决策（简化实现）**：
  - 播放页改为覆盖层而非导航路由：`playerProgress` 0..1 由拖拽实时控制；
  - 卡片 `translationY = (1-progress) * 屏高`，横向留白 `18dp*(1-progress)`、顶部圆角 `28dp*(1-progress)` 随进度收敛；
  - 松手用 spring（damping 0.9/stiffness 380）收敛：>18% 或向上快滑 → 展开到 1；否则收回 0；
  - 迷你条上滑拖动期间直接驱动进度（跟手），播放页内容全程可见（不再等松手才出现）；
  - 返回键/播放页返回箭头收起卡片（向下滑出）；底部导航在展开时隐藏，迷你条淡出；
  - 从播放页打开歌词视频时卡片让位，视频返回后自动恢复到播放页。
- **已知限制**：播放页卡片目前不响应「在播放页上向下滑关闭」（只能点返回/系统返回），后续可加顶部拖拽区。

---

## D29 左右转场重影修复 + 整页推挤 + 播放页下滑关闭（v0.8.1）

- **日期**：2026-11-08
- **问题**：
  1. 页面左右切换时出现两页文字叠加/重影（用户截图：音乐库与设置标题重叠），并感觉像淡入淡出；
  2. 专辑详情返回像“缩小淡出”；
  3. 迷你条底部有黑边；
  4. 播放页展开后无法下滑收回。
- **根因**：
  1. 各导航页面根节点背景透明，滑动时旧页面从新页面透明区域透出；同时旧页只做了 1/3 视差位移，
     视觉上两页重叠区域大、像混合；
  2. 同一原因（透明 + 视差小）导致返回看起来像原地缩小淡出；
  3. 迷你条底部 2dp 进度线底色在毛玻璃上呈现为一条黑边；
  4. 播放页只注册了返回键收起，没有下拉手势。
- **决策**：
  - 每个 NavHost 目的地包一层不透明 `Page` Surface（background 色），保证两页是实体；
  - 转场改为**完整宽度同步推挤**：进入页整页从右侧滑入，退出页整页向左滑出（退出位移从 -1/3 改为 -1）；
    返回完全反向；Tab 按序号方向；无 alpha；
  - 迷你条移除底部进度线与亮边框，blur radius 22→36dp、底色透明度降低（更深毛玻璃）；
  - 播放页：NowPlaying 顶部栏 + 顶部 48dp 区域均可下拉；松手按速度/进度决定展开或收起。
- **验证**：模拟器截帧确认切换时两页为左右实体（docs/screenshots/v081_push_transition.png）、无重影；
  顶部栏下滑可回到原页面；单测 152 全绿。

---

## D30 导航动画最终统一：Tab 无转场 + 层级页面 push/pop（v0.8.2）

- **日期**：2026-11-08
- **背景**：用户反馈 Tab 左右滑动速度不一致、专辑返回像缩小消失、打开专辑时底层页面往右撤；要求参考
  SPICaMusic_Android 的统一做法或重新设计。
- **参考项目调研结论**：
  - SPICaMusic_Android 的三个一级页（Finder/Music/Library）在同一个 HomeScreen 内用 `when(currentPage)`
    切换，**不进行任何转场动画**；只有真正的页面栈导航（专辑/艺术家/设置子页等）走统一 `iosStyle`：
    push 进入整页从右侧 + 淡入，旧页向左 1/4 + scale 0.95 + 淡出；pop 对称。
- **决策**：
  1. 一级 Tab 完全取消左右滑动/缩放，瞬时切换（只保留 M3 NavigationBar 指示器动画）；
  2. 层级页面统一一套 push/pop（保持方向一致的实体滑动，去掉 alpha/scale，符合用户“不要淡入淡出/缩小”）：
     - push：新页从右侧整页滑入，旧页向左 1/4 视差；
     - pop：当前页向右整页滑出，上一页从左侧 1/4 回位；
  3. 每个目的地外包不透明 Page Surface，防止滑动时内容透视；
  4. Tab 不参与详情页转场（exit/enter 为 None），从而底层页面保持静止，不再往右撤/缩小。
- **影响**：一级页面切换干脆无方向歧义；所有层级页面共享同一套 push/pop 节奏；详情页返回不再出现缩放/淡出。

---

## D31 一级 Tab 内容入场 + 迷你条底部过渡（v0.8.3）

- **日期**：2026-11-08
- **背景**：Tab 瞬时切换后显得生硬；进入非一级页面时迷你条瞬间掉到屏幕底部；并确认 1 小时以上的分割时间。
- **参考**：SPICaMusic_Android 的 `ui/theme/EntranceModifier.kt`：每个页面内容用 `Modifier.entrance(order, play)`，
  35ms 交错、上浮 28dp、时长 250–550ms、Emphasized decelerate 风格曲线；Tab 本身无转场，靠内容入场消除突兀。
- **决策**：
  - 新增 `ui/theme/Entrance.kt`：`Modifier.entrance(order, play, replayKey)`（淡入 + 24dp 上浮，只写 layer）；
    发现/音乐库/设置的内容出现时播放；音乐库内部 Tab 切换用 replayKey 重播；
  - 迷你条底部 padding 从「底部导航高度」到「系统手势区」用 spring 动画过渡，避免进入详情页时瞬间掉落；
  - 分割时间：`parseTimeToMs`/`SplitTextParser` 已支持 `h:mm:ss`；补充 `01:22:23` 单测（4,943,000ms）。
- **影响**：Tab 切换更柔和；页面层级切换时迷你条位置连续；长专辑（>1h）可直接用 `01:22:23` 格式分割。

---

## D32 修复 v0.8.3 闪退、入场不统一与 predictive back 缩小退出（v0.8.4）

- **日期**：2026-11-08
- **问题**：
  1. 发现→Abbey Road 必闪退（真机堆栈 `IllegalArgumentException: Padding must be non-negative`）；
  2. 发现/音乐库来回切没有列表入场，设置页和音乐库内部 Tab 有（不统一）；
  3. 音乐库→艺术家→The Beatles 等页面退出仍然缩小淡出；需审计其他页面同类老动画。
- **根因**：
  1. v0.8.3 为迷你条底部 offset 加了 `animateDpAsState` + 有回弹 spring，过渡中 padding 可能为负，
     而 Compose 的非负 padding 断言直接崩溃；进入非一级页面正好触发该 offset 变化；
  2. `Modifier.entrance` 在 `play=false` 时把动画初始进度设为 1，之后 `play=true` 时动画从 1→1，
     所以用 `rememberPageEntrancePlay()` 的发现/音乐库没有入场；设置页用默认 `play=true` 所以正常；
  3. navigation-compose 2.10.2 的默认 **predictivePopExitTransition = scaleOut(0.7f)**（Android 实际实现），
     系统返回手势/返回键触发该默认预测动画，表现为页面原地缩小淡出；我们只覆盖了普通 pop 转场。
- **决策**：
  - 迷你条 offset 改无回弹 spring（dampingRatio=1）并 `coerceAtLeast(0.dp)` 兜底；
  - `entrance` 初始进度固定 0，`play` 从 false→true 时仍能完整播放；
  - NavHost 全局覆盖 `predictivePopEnterTransition`/`predictivePopExitTransition` 为整页滑动，
    与普通 pop 一致；这样所有页面（艺术家/专辑/导入/设置子页等）都不再走系统默认的 scaleOut 老动画。
- **影响**：修复闪退；一级 Tab 入场统一；所有页面返回不再缩小淡出。仅视频播放控件的局部 fade 保留（内容层，不是页面转场）。

---

## D33 归档数据层 v2 落地（v0.9.0，不兼容 v1）

- **日期**：2026-11-08
- **背景**：v1 的 `bvid+page` 只存在于文档；DB 唯一键只有 bvid，多 P/多来源无法共存；
  目录根索引与整库备份同 schema，可能被 REPLACE 清库；空 segments 语义矛盾；缺少前向兼容门禁。
- **决策**：
  - DB v3：`albums.source` + 唯一键 `(source,bvid,page)`；`tracks.uid` UUID；MIGRATION_2_3 保留数据并回填；
  - 归档只支持 v2：`kind`/`minReaderVersion`/`features`；`albumKey=source:bvid#page`；
    分曲 `id` + `endMs`；封面 `{mime,sha256,byteLength,dataBase64}`；segments 省略/null 保留、[] 清空；
  - 目录归档根索引 `kind=index`；单文件导入拒绝；合并后全量校验；
  - v1 归档明确拒绝，不做读取兼容；旧“快捷导入”文本格式保留但归一化为 v2；
  - 封面内嵌 base64 暂保留（目录/单文件一致）；manifest/zip/独立 cover 文件列为 P2，不阻塞 v2 语义。
- **影响**：数据层身份/版本/内容模型一次到位；后续做收藏/播放历史/多 P/同步时不再反复改归档主版本。

---

## D34 归档 v3 分域落地（v0.10.0）

- **日期**：2026-11-08
- **决策**：按 ARCHIVE_LAYOUT_V3 实施：整库为目录 bundle（manifest/library/settings/covers），
  library 只放专辑/分曲，封面内容寻址；settings 独立且默认不覆盖本地；playlists schema 预留；
  单专辑/分割点保留单 JSON 分享。整库单 JSON 不再支持。
- **配套**：维测日志默认关闭；控制中心/锁屏优先使用自定义封面 artworkData。
- **影响**：加歌单/设置/收藏等未来域时，各自升域版本，不再改 library 主结构。

---

## D35 预测返回不预演 + 一级 Tab 平级返回/连续两次退出（v0.11.0）

- **日期**：2026-11-08
- **问题**：
  1. 预测式返回手势一开始就把页面退了一半，取消手势有残留观感；希望“真正后退了再后退”；
  2. 发现/音乐库/设置只有设置页有入场动画，另外两个没有；
  3. 发现被当作根 Tab：音乐库/设置返回会回到发现，但三个 Tab 应是平级；
  4. 根页面返回直接退出太容易误触。
- **决策**：
  - `predictivePopEnterTransition/ExitTransition = None`：手势预输入期间不做视觉预演；真正 pop 后才走正常转场；
  - 修复 `Modifier.entrance` 的 false→true 进度 bug；三个一级 Tab 都用默认 `play=true` 入场，音乐库内部 Tab 用 replayKey；
  - 一级 Tab 行为平级：在音乐库/设置按返回不再回发现；所有一级 Tab 根返回进入“连续两次退出”流程：
    第一次 Toast「再返回一次退出应用」，2 秒内第二次才 finish Activity；键盘弹出时不拦截；
  - 播放页内 ModalBottomSheet 的 BackHandler 晚于播放页收起注册，优先关闭弹层。
- **影响**：返回叙事一致（层级页返回上一页，一级 Tab 返回=退出），减少误退；Tab 内容入场统一。

---

## D36 UI 页面分类与模板化（v0.12.0）

- **日期**：2026-11-08
- **背景**：项目由多轮快速实现积累，页面各写各的顶栏/Scaffold/标题样式，维护者需要先理解每个页面
  才能改一致性。需要一套低成本的页面分类与模板，而不是继续复制粘贴。
- **决策**：
  - 页面分四类：T1 一级 Tab（`BiliPageHeader`，外層 AppNavHost Scaffold）、T2 层级详情页
    （`BiliDetailScaffold`/`BiliTopBar`）、T3 沉浸页（播放/歌词，页面自管）、T4 弹层/对话框；
  - 所有 T2 页面迁移到统一模板；禁止手写“Row + 返回 + 标题”顶栏；
  - Tab 页不再各自套 Scaffold；insets 统一由 AppNavHost 外层 Scaffold 处理；
  - 状态约定：Screen 只收集 `@Immutable` UiState + 转发事件；loading/error/empty 复用公共组件；
    一次性消息 Snackbar + consume；
  - 规范写入 docs/UI_ARCHITECTURE.md，并同步 AGENTS 的 UI 条款与新页面 checklist。
- **非目标**：不重写 ViewModel/数据流，不改变页面视觉信息结构；本次只收敛页面外壳与共享视觉约定。

