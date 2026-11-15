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

---

## D37 页面转场拓扑修正 + 深色状态栏/1080P/封面大图（v0.13.0）

- **日期**：2026-11-08
- **问题**：
  1. 设置→诊断日志、发现→专辑只有新页从右侧进入，旧页不动；根因是 Tab 路由固定 `ExitTransition.None`，
     没有区分“Tab 之间切换”和“从 Tab 进入详情页”；
  2. 深色模式状态栏时钟仍是黑色；
  3. 歌词视频封顶 720P，用户期望更高清；
  4. 专辑封面上的“换封面”悬浮按钮遮挡画面。
- **决策**：
  - Tab 路由按目标动态转场：Tab↔Tab = None；Tab→详情 = Tab 整页向左退出；详情→Tab = Tab 从左整页回位；
  - 层级页 push/pop 使用完整宽度（不再 1/4 视差），旧页/上一页都整页滑动；
  - `AppTheme` 用 `WindowCompat.getInsetsController` 按 App 深色设置设置系统栏图标明暗；
  - `StreamSelector` 视频上限 720P→1080P；音频仍独立选择（与视频分辨率无关，192K 档不降为 720p 音频）；
  - 移除封面悬浮按钮；点击封面打开全屏大图，可保存封面/换封面；换封面入口移到右上角更多菜单。
- **影响**：页面拓扑终于一致（Tab↔详情是整页 push/pop），深色模式系统栏可读；歌词视频默认更高清；
  封面交互更干净，保存走 SAF 无需存储权限。

---

## D38 关闭系统预测式返回，统一普通 pop 动画（v0.13.1）

- **日期**：2026-11-08
- **问题**：返回手势预输入时页面会提前退一半；取消有残留；预测式返回与自定义 pop 组合下，
  偶发出现页面反向扫动/封面扫到左边/快速浮现，且从专辑返回时看不到正常的整页 pop 动画。
- **决策**：在 Manifest 关闭 `android:enableOnBackInvokedCallback`（predictive back）。
  返回手势只提交普通 back；NavHost 的 pop 转场（详情向右退出、上一页从左回位）负责动画。
  这样满足用户“真正后退了再后退”，也避免系统预测动画与 Compose 转场互相打架。
- **影响**：失去系统级预测式预览（本就希望不要预演）；返回手势/返回键正常可用；返回动画方向稳定。

---

## D39 动效统一放慢 + 曲线化（v0.14.0）

- **日期**：2026-11-08
- **背景**：页面/列表/迷你条/视频控制层各自使用不同时长和默认曲线；返回手势后动画感觉与滑动速度相关，
  用户希望整体更慢、更优雅，但必须有速度曲线、不能匀速。
- **决策**：
  - 所有自动动画时长集中到 `BiliMotion`，页面转场 500ms；入场 EmphasizedDecelerate、退场 EmphasizedAccelerate；
  - 列表入场 320ms 起、50ms/项、封顶 650ms、交错 45ms；
  - 迷你条/视频控制层/封面占位全部显式曲线；spring 只用于空间归位/交互；
  - 导航类动画固定时长，不跟手势速度联动；播放页拖动属于“跟手”，保持手指驱动，松手用 spring；
  - 新增 docs/MOTION.md，所有新动效必须登记时长/曲线。
- **影响**：整体节奏更慢更统一，避免匀速/线性观感；页面转场 500ms 是刻意取舍，后续要提速只改 token。

---

## D40 转场完全对称 + 三个 Tab 统一动态转场 + 底栏动画（v0.15.0）

- **日期**：2026-11-08
- **问题**：
  1. 发现→专辑旧页左滑，音乐库→专辑旧页不动：LIBRARY/SETTINGS 两个 composable 还残留写死的
     `ExitTransition.None`，只有 HOME 改了动态转场；
  2. 页面进入用 Decelerate、退出用 Accelerate，方向相反且速度曲线不同，不满足“对称”；
  3. 底部导航在 Tab/详情切换时直接出现/消失，太突兀，且要和迷你条底部避让协调。
- **决策**：
  - 三个 Tab 全部使用 `tabEnter/tabExit/tabPopEnter` 动态转场；Tab↔Tab 瞬时，Tab↔详情完整滑；
  - 页面进入/退出统一 `PageTransitionMs=500` + `Emphasized` 同一条曲线，只是方向相反；
  - 底部导航用 `AnimatedVisibility` 做高度 expand/shrink + fade（300/250ms），Scaffold 高度随之动画，
    迷你条避让 spring 跟随，不会跳变；
  - 规范同步到 MOTION/AGENTS。
- **影响**：三个 Tab 进入详情行为一致；同一对手势进出速度完全对称；底栏出现更柔和。

---

## D41 迷你条上一首 + 歌词视频上覆盖层（v0.16.0）

- **日期**：2026-11-08
- **问题**：
  1. 迷你播放条只有播放/下一首，缺少上一首；
  2. 从播放页打开歌词视频时，会先把播放页收起，露出父页面（专辑/库），视频再从右侧进入；
     返回时反向露出父页面，迷你条又从下面滑上来——动画链条断裂且闪烁。
- **决策**：
  - 迷你条增加 `onPrevious`，直接调用 `PlaybackController.previous()`；
  - 歌词视频从 NavHost 目的地改为 **播放页上方的覆盖层**：播放页 `playerProgress` 保持 1，
    `videoOpen` 控制覆盖层；视频层从右滑入/向右滑出，底下始终是播放页；
  - 视频层使用独立 `ViewModelStore`，关闭后 clear（释放视频播放器），播放页状态不重建；
  - 视频页自己的 `BackHandler`：全屏时先退全屏，否则关闭覆盖层；不再依赖 navController.popBackStack。
- **影响**：进入/退出视频不再闪父页面或迷你条；播放页状态连续；视频资源生命周期仍然可控。

---

## D42 专辑音频离线缓存（v0.17.0）

- **日期**：2026-11-08
- **背景**：每次播放都重新拉流，流量消耗大；希望探索离线缓存。
- **结论**：音频缓存技术可行且成本可控；视频缓存暂不做。
- **决策**：
  - 缓存粒度 = 一张专辑的**整段 DASH 音频**（不是每首歌独立文件）；分曲共享同一文件 + Clipping；
  - 手动缓存（专辑更多菜单），不做默认自动下载，避免非预期流量；
  - 文件放应用私有目录 `files/audio_cache`，不进 Room、不进归档 v3、不参与备份；
  - 播放时 `PlaybackEngine.resolveStream()` 优先本地缓存；DataSource 改为 `DefaultDataSource`
    同时支持 http(s)/file://；
  - 下载失败/长度不完整清理临时文件；删除专辑/手动删除时清理缓存。
- **未做**：后台服务/断点续传、Wi-Fi-only、缓存管理页、自动预取、视频缓存。

---

## D43 歌词视频缓存 + 视频页体验修复（v0.18.0）

- **日期**：2026-11-08
- **背景**：音频缓存后，用户希望离线也能看歌词视频；同时 TODO.md 有白边/全屏方向/导入跳转/播放动效等体验问题。
- **决策**：
  - 视频缓存独立于音频：整段 DASH 视频下载到 `files/audio_cache/<bvid>_<cid>_video.mp4`；
    缓存前确认大小风险；视频播放器优先本地文件；音/视频删除互不影响，删专辑一起清理；
  - 视频页覆盖层不再套 Scaffold `innerPadding`，视频页自己 `windowInsetsPadding(systemBars)`，消除白边；
  - 全屏横向：`SENSOR_LANDSCAPE`，退出 `UNSPECIFIED`；Manifest `configChanges` 防旋转重建；
  - TrackRow 当前曲目用动态均衡器（暂停保留静态柱）；播放页暂停封面缩到 92%；
  - 导入预览加「在 B 站打开」链接，便于对照评论区分段时间点。
- **未做**：视频缓存清晰度选择/大小预估、进度条重构、禁用按钮触摸反馈、全局缓存管理。

---

## D44 视频缓存补音频 + 放大封面 + 全局按压反馈（v0.19.0）

- **日期**：2026-11-08
- **问题**：缓存歌词视频后音频无法离线播放。B 站 DASH 把音/视频拆成两条轨道，视频文件是 video-only，
  无法“复用视频里的音频”；用户期望缓存视频后整张专辑可离线。
- **决策**：
  - 「缓存歌词视频（含音频）」在下载视频前先确保音频缓存存在；旧版仅视频缓存提供「补缓存音频」入口；
  - 保留音视频独立删除语义，删除专辑一起清理；
  - 播放页封面放大到可用宽度 96%、左右边距 16dp；暂停仍缩到 92%；
  - `AppTheme` 提供全局自定义 `LocalIndication`：可点击组件按下时自绘水波纹 + 按压底色，
    `LocalRippleConfiguration=null` 关闭 Material 默认 ripple；禁用组件无 interaction，不触发反馈。
- **未做**：水波纹不会真的抬升按钮阴影（Compose indication 只负责绘制），如需“按压阴影”要逐个按钮加 elevation；
  进度栏重构仍待办。

---

## D45 缓存管理入口与信息层级（v0.20.0）

- **日期**：2026-11-08
- **入口选择**：设置 → **存储** → 缓存管理。
  - 不放在音乐库：缓存是设备级维护操作，不是曲库浏览；
  - 不放在播放页：用户不需要在听歌时管理；
  - 与“备份与恢复/诊断与维护”同属设置里的维护类入口，位置稳定、易找。
- **页面设计**：
  - 顶部汇总（专辑数 + 总占用）；
  - 每专辑一张卡：封面/标题/艺术家 + 音频档位/大小 + 视频清晰度/大小 + 合计/更新时间；
  - 单条删除和清空全部都有二次确认；
  - 空状态引导去专辑详情缓存；
  - 列表来自缓存目录元数据，不新增 Room 表；专辑已删除时仍可清理遗留缓存。
- **影响**：缓存从“不可见的磁盘占用”变成可审计、可清理的设置项。

---

## D46 歌单与统计放发现页 + Room v4（v0.21.0）

- **日期**：2026-11-08
- **信息架构**：音乐库 = 纯搜索；所有和“听歌行为”相关的入口集中在发现页（歌单、统计）；
  缓存管理属于设备维护，继续在设置。发现页用两张快捷卡露出，避免把音乐库变成功能大杂烩。
- **歌单**：Room 表 playlists + playlist_entries；条目按 **trackUid** 关联（重导入/编辑分曲后不失效）；
  `(playlistId, trackUid)` 唯一；删除专辑外键级联清理条目。
- **统计**：track_stats 按 trackUid 持久化；播放次数在开始播放时 +1；收听时长按 isPlaying 的实际时间累计，
  每 5 秒批量写入，暂停/切歌/重新入队 flush；未分割专辑使用 `album:<id>` 虚拟 uid。
- **未做**：歌单/统计进入归档备份、拖拽排序、歌单封面、分享；统计只展示 Top 50/100。

---

## D47 用户数据独立备份 + 分组/条目 UI 模板（v0.22.0）

- **日期**：2026-11-08
- **持久化与导出边界**：歌单/统计本来就在 Room v4 持久化；但不应塞进音乐库归档（核心库结构），
  因此在设置新增「用户数据 → 歌单与听歌统计备份」，单独 JSON 导出/导入。
  导出用专辑稳定键 + startMs 定位歌曲；恢复歌单按名称合并、统计按 max 合并，避免重复导入翻倍。
- **UI 一致性**：之前每个新页面自己写 `Surface(shape=22dp, color=surfaceContainerHigh)`，圆角/内边距/
  字体容易漂移。新增 `BiliGroup`（容器）+ `BiliGroupItem`（组内行）+ `BiliGroupSectionTitle`（组外标题），
  统一 background/shape/padding/typography/click clip；`SettingsCard` 也委托 BiliGroup。
  新页面必须使用模板，组内业务内容只能放在容器内，不重新定义卡片外观。
- **影响**：用户数据可单独迁移；设置/缓存/歌单/统计/发现快捷卡视觉一致，后续新页面不用再
  copy-paste 卡片样式。

---

## D48 一致性审计：同类 group/item 行为统一（v0.22.1）

- **日期**：2026-11-08
- **发现的问题**：
  - 统计歌曲行此前 `onClick={}`，有点击水波却没有任何行为；
  - 设置入口/缓存卡/歌单卡使用不同手写卡片和点击区域；
  - 统计歌曲行没有当前播放高亮，歌单详情也没有；
  - 歌单详情/统计页点歌后没有迷你条反馈；
  - 同类卡片标题字号/水平内边距不一致。
- **决策**：
  - `BiliGroup` 增加整卡点击；设置/缓存/歌单/发现快捷卡全部使用 `BiliGroup`/`BiliGroupItem`；
  - 统计歌曲行改为真正播放所属专辑并高亮；统计专辑卡可打开专辑；
  - 歌单详情高亮当前曲；歌单详情/统计页展示迷你播放条；
  - 组内条目水平内边距统一 12dp，统计专辑标题与 group item 统一 bodyLarge；
  - UI_ARCHITECTURE 增加“同类 Group/Item 行为规则”，避免以后出现“同视觉不同行为”的空点击。

---

## D49 发现页四区块化 + 设置卡片首行统一（v0.23.0）

- **日期**：2026-11-08
- **发现页**：
  - 去掉搜索栏（音乐库已有搜索，避免两个入口做同一件事）；
  - 我的专辑网格改为「随机专辑」4 张 + 换一批；导入入口保留；
  - 随机专辑 / 我的歌单 / 听歌统计 / 最近播放统一为最多 4 条的预览卡，详情从右上角进入；
  - 最近播放新增独立详情页，展示 10 张（首页 4 张）；该页也显示迷你播放条；
- **设置页**：
  - 新增（现为共享组件 `BiliGroupHeader`）统一首行；音质、深色模式、整库备份、用户数据、维测日志、关于全部补齐
    图标 + 标题 + 副标题统一首行；账号头像作为身份例外；
  - 统一 22dp 图标、bodyLarge 标题、bodySmall 副标题，减少卡片之间“有的有 icon 有的没有”的割裂；
- **影响**：发现页从“我的专辑网格”变为四个听歌入口，最近播放可通过详情页展开；设置卡片首行一致。

---

## D50 发现页四区块顺序/边距与统计预览口径（v0.23.1）

- **日期**：2026-11-08
- **问题**：发现页四张区块卡片贴边，与设置页卡片左右留白不一致；区块顺序不符合使用频率；
  听歌统计预览只有专辑时长一种口径。
- **决策**：
  - `HomeSectionCard` 统一加 `Spacing.lg` 左右外边距，与设置页 `SettingsPadded` 对齐；
  - 顺序改为：最近播放 → 听歌统计 → 我的歌单 → 随机专辑；
  - 听歌统计预览加 FilterChip 切换「专辑时长 / 歌曲次数」，选择写入 `AppSettings.homeStatsMode`
    （SharedPreferences 持久化），歌曲模式展示 Top4 播放次数；
  - 卡片仍只露前 4 条，详情统一从「查看全部」进入。
- **影响**：发现页左右视觉与设置一致；统计预览可按用户口径展示；选择重启保留。

---

## D51 设计系统目录与源码级一致性守卫（v0.24.0）

- **日期**：2026-11-08
- **背景**：每次新增页面都在“复制卡片/私写 padding/私有 header”之间摇摆，仅靠口头约定会再次漂移。
- **决策**：
  - 建立页面四分类（T1/T2/T3/T4）与组件目录（docs/DESIGN_SYSTEM.md）；
  - 共享组件补全：`BiliSection`（页面区块）、`BiliGroupHeader`（卡片首行）、`BiliAlbumRow`（专辑行）；
  - `BiliLayout` 集中页面/组内/媒体留白与尺寸；新页面不得散写同类数字；
  - 用 `UiArchitectureConsistencyTest` 做源码级守卫：详情页壳、空点击、发现区块、设置首行、迷你条底部 token；
  - 明确豁免：导入/登录/专辑 Hero/播放页/弹层等领域专用视觉，只要求页面壳一致，不强套通用卡片。
- **影响**：以后新增页面先查目录选组件；同类问题会由测试失败而不是靠 review 记忆发现。

---

## D52 多 P BV 导入与无缝播放（v0.25.0）

- **日期**：2026-11-08
- **需求**：有些专辑只找得到多 P 视频；每个分 P 一首，要求连续播放不手动切歌。
- **数据模型**：
  - `Track` 增加 `sourceCid/sourcePage/sourceOffsetMs`（Room v5）：
    - 普通曲目沿用专辑单流，`startMs/endMs` 即源流裁剪区间；
    - 多 P 曲目 `sourceCid` 指向自己的分 P，`startMs/endMs` 只作为专辑累计时间轴，播放从该 P 的 0ms 开始；
  - 归档 `ArchiveSegment` 增加可选分 P 字段，旧归档缺省时仍按单流恢复。
- **导入**：`ImportMode.MULTI_PAGE` 每个分 P 一首；多 P 预览默认该模式，用户仍可切回整段。
- **播放**：
  - 开始播放前一次性解析队列里所有“专辑流/分 P 流”，全部建成 MediaItem 后入队；
  - 多 P 用完整分 P 流且不套 Clipping，ExoPlayer 不暂停在条目末尾，自动预加载下一 P；
  - 重试时按曲目源重新解析，不只刷新专辑首流。
- **限制**：歌词视频/离线音频缓存仍按专辑第一 P；多 P 的按 P 缓存是后续工作。


## D53 移除「看歌词视频」+ 音频缓存按分 P + 在线歌词（v0.27.0）

- **日期**：2026-11-10
- **需求**（用户原话三条）：
  1. 「摈弃看歌词视频这个功能，不要他了；把相应的缓存也只剩下歌曲，删去和视频有关的东西」；
  2. 「分 P 视频缓存的时候是不是只会缓存一个?」——确认：旧实现只缓存首 P，本次一并修复；
  3. 「使用 SPICaMusic 的歌词方案」——用真实在线歌词取代「看原视频歌词」。
- **决策 1：删除视频播放与视频缓存**
  - 「歌词视频」本质是播放 B 站原视频（第二路 ExoPlayer），与音乐播放主线无关且文件巨大；用户明确不要，因此删除全部
    视频播放链路与视频缓存，不做隐藏开关/兼容代码；
  - 保留 B 站视频**元数据/搜索/预览**（导入功能依赖，不属于视频播放）；
  - 旧缓存目录里的 `_video.*` 文件在缓存管理/删除/清空时自动清理，不做一次性迁移任务。
- **决策 2：音频缓存按分 P**
  - 缓存身份从「专辑」改为「分 P」：文件仍为 `<bvid>_<cid>.m4a`，下载/汇总/删除都以
    `{Track.sourceCid > 0 ? sourceCid : album.cid}` 去重后的分 P 集合为单位；
  - 专辑详情状态 = 已缓存分 P / 目标分 P（支持部分缓存 → 补缓存）；缓存管理页按专辑聚合展示；
  - 明细/聚合/删除匹配逻辑抽成纯函数 `AudioCacheLogic` 便于 JVM 单测。
- **决策 3：歌词方案参考 SPICaMusic_Android，按 BiliAudio 模型收敛**
  - **在线源**：直接用参考项目同款多来源歌词 API（`GET /api/lyrics?title=&artist=`），失败静默空列表；
    不传艺术家（BiliAudio 的艺术家是 UP 主），只按曲名搜索；
  - **匹配**：标题清洗（去「01、」等分曲序号）后，用「标题相似度 + 时长接近度」打分，低于阈值宁可不显示，
    避免把无关歌词强行套上；用户可在来源面板手动选；
  - **解析**：移植参考项目的 LRC/YRC/AMLL-TTML 解析器与 `LyricItem` 模型（保留逐字时间、翻译、增强型 LRC、
    错位降级），另加纯文本兜底（`isSynced=false` 静态展示）；
  - **缓存**：Room v6 `lyrics` 表按 `Track.uid` 存原文 + 来源 + 偏移 + 手动/关闭标记；「自动匹配」不覆盖
    「手动选择/已关闭」；歌词不进入归档/用户数据备份（与音频缓存同属设备本地数据）；
  - **UI**：歌词页作为播放页上方覆盖层（沿用旧视频页的覆盖层拓扑与 500ms 转场，不新增导航目的地）；
    只做全屏歌词页，不做迷你条歌词；逐字高亮用 SpanStyle 颜色推进（不引入参考项目的高成本自绘遮罩）；
  - **HTTP 明文**：接口为 HTTP，App 全局仍禁明文，仅 `network_security_config` 放行该 IP 域名。
- **影响**：v0.25.0 记的「多 P 缓存只按第一 P」限制消除；视频页相关测试/文档全部清理；新增歌词文档与 56 个单测。
- **未验证**：逐字 KaraOK（在线源为 LRC 行级时间轴）；多 P 真机断网逐 P 离线播放。

## D54 歌词并入播放页（封面页 ↔ 歌词页左右滑动）+ Apple Music 式滚动（v0.28.0）

- **日期**：2026-11-11
- **需求**（用户原话）：「先用模拟器验证；去掉这个看歌词的按钮；把歌词做成和播放页面一起的页面，
  就是播放页面右边是歌词；然后歌词滚动要平滑一些，有 Apple Music 的感觉」。
  用户确认竖屏形态选 **B：左右滑动切换（封面页 ↔ 歌词页）**，而不是同屏左右分栏。
- **决策**：
  1. **去掉独立歌词覆盖层与「看歌词」按钮**：播放页主体改为一页 HorizontalPager：
     第 0 页 = 大封面 + 曲名/艺术家（点封面也能进歌词），第 1 页 = 歌词；
     底部进度条/时间/三个播放控制**固定在分页之外**，不随页滑动；两页之间放点击可切的圆点指示器；
     返回键在歌词页时先滑回封面页（`BackHandler` 在歌词页启用，优先于 AppNavHost 的收起播放页）。
  2. **歌词滚动 Apple Music 化**：
     - 自动滚动从 300ms 短动画改为 **520ms Emphasized 长缓出**（新增 `BiliMotion.LyricsScrollMs`），
       配合 `collectLatest` 在快速切行时从当前位置重定向，避免跳变；
     - 当前行 1.09 放大 + primary 色 + Bold；非当前行 0.95 缩放 + 已唱/未唱分层透明度；
       过渡 380ms Emphasized（`LyricsActiveMs`）；
     - 歌词区上下边缘用 DstIn 渐变遮罩淡出（用 mask 而不是色块，兼容渐变背景）；
     - 统一 22sp 大字号、翻译行小一号，点行跳转、手动滚动暂停 3s 后恢复等行为保持。
  3. **歌词匹配适配多 P 曲名**：多 P 专辑曲名常为「专辑_01_歌名」；`cleanTitle` 增加该前缀剥离，
     搜索不到可信候选时补搜原始标题合并候选；`LyricsMatcher` 对「标题完全相等」兜底 75 分
     （翻唱/现场版时长差异不再直接判死，时长仍参与排序）。
- **取舍**：
  - 不做同屏左右分栏（411dp 竖屏下两列都过窄）；
  - 不做 Apple Music 的逐行模糊（与项目「扁平 Material 3、不做大面积毛玻璃」的偏好冲突），
    改用缩放/透明度/渐隐达成近似观感；
  - 不做分页联动背景（封面页与歌词页共用同一个播放页渐变背景）；
  - 歌词页不作为导航目的地，不新增路由/转场，避免进入退出时闪父页面。
- **影响**：AppNavHost 删除 `lyricsOpen`/`isImmersive`/歌词覆盖层整段；`LyricsScreen.kt` 改为
  `NowPlayingLyricsPane.kt` 内嵌组件；`NowPlayingScreen` 增加 pager 与页面指示器；单测 194 全绿。


## D56 直接重置回 v0.28.0 基线，放弃 v0.29.0 视觉重设计（v0.30.0）

- **日期**：2026-11-11
- **背景**：v0.29.0 按用户要求做了 Editorial + Spatial + Modern Skeuomorphism 全站重设计；随后用户反馈
  「换新 UI 太大胆冒险了，Material 3 更加稳定」，要求回到换新 UI 之前的样子，同时保留歌词页面功能、
  继续去掉视频歌词方案，并建议「直接回退到那个版本，在那个版本的基础上开发，避免副作用」。
- **决策**：
  1. **直接 `git reset --hard v0.28.0`** 将代码基线重置到 v0.28.0（而非在 v0.29.0 上打 revert 提交），
     避免 revert 补丁可能与后续基线变化产生的冲突/副作用；v0.29.0 保留在 tag 与历史中备查；
  2. 基线 v0.28.0 即「换新 UI 之前」的 Material 3 扁平视觉：动态色板、页面模板、卡片/分组、M3 控件、
     Haze 迷你条；歌词页面（播放页内左右滑动的第二页）在该基线中已具备，无需重复开发；
  3. **视频歌词方案继续保持移除**（v0.27.0 已删除视频缓存/播放）；
  4. 版本号提升为 0.30.0（versionCode 43）覆盖已安装的 v0.29.0，便于识别「回退后的稳定版」；
  5. 视觉偏好写入 AGENTS：默认 Material 3 扁平，不主动做视觉语言级重设计，视觉方向须用户明确批准。
- **影响**：仓库 `main` 直接落在 v0.28.0 基线上再叠加版本/文档提交；v0.29.0 重设计提交不再位于 main 的历史主线。
  歌词页、播放、缓存、归档、歌单、统计等功能与 v0.28.0 完全一致。194 单测 + release 构建 + 模拟器走查通过。


## D57 页面登记制 + 模板自由度分级；发现页采用大胆版式（v0.31.0）

- **日期**：2026-11-11
- **背景**：v0.30 回到 Material 3 稳定基线后，用户希望「简洁实用稳定」，同时要求：
  1）统计并分类现有页面，进一步模板约束，但重新审视模板是否限制过紧；
  2）发现页可以大胆一点，不要一直用列表，认可此前大胆 UI 尝试的排版。
- **决策**：
  1. **新增页面登记制**：`docs/PAGE_INVENTORY.md` 登记全部页面（类型/路由/入口/默认壳/内容结构/例外），
     新页面必须先登记再写代码；T2 细分为内容详情/汇总列表/导入恢复/编辑/系统账号五类；
  2. **模板拆成三层**：L0 全局强制（导航/转场/insets/三态/可点击/颜色/动效/无障碍）、
     L1 默认页面壳（T1 `BiliPageHeader`、T2 `BiliDetailScaffold`、T3 自管、T4 复用弹层；例外登记）、
     L2 页面内部自由（版式/页面专属 Composable/入场动画）；
  3. **放宽过紧约束**：T1 允许自定义刊头（发现页首个例外）；发现页不再强制 `BiliSection`/`BiliAlbumRow`，
     改为「非列表版式 + 共享封面组件」语义守卫；允许页面专属 hero/封面墙等版式与出血；
  4. **收紧关键约束**：新增两条守卫——页面级转场只能在 AppNavHost 定义、页面禁止硬编码颜色；
     T2 默认壳保持强制；页面三态/可点击/动效 token 等写入 checklist；
  5. **发现页重做**：保持 Material 3 基线（动态色板/共享组件/动效 token），采用封面故事 hero、
     横向封面墙、横向榜单卡、歌单卡墙、双列封面网格的「杂志式」版式；不改导航/数据/播放逻辑。
- **取舍**：
  - 不把 v0.29 的全站 Editorial 主题搬回来（用户认为整体太冒险），只把大胆排版局部化到发现页；
  - 发现页私有 Composable 是刻意允许的页面级实现，不做成全局模板；
  - 模板不追求「所有页面长得一样」，而是「骨架一致、页面内部自由、例外可审计」。
- **影响**：新增 `docs/PAGE_INVENTORY.md`；UI_ARCHITECTURE/DESIGN_SYSTEM/AGENTS 同步更新；守卫 5 → 7 条；
  发现页 429 → 约 700 行页面专属版式；单测 202；release v0.31.0。


## D58 回退发现页大胆版式，恢复严格 Material 3 实用风格（v0.31.1）

- **日期**：2026-11-11
- **背景**：v0.31.0 按用户「发现页大胆一点、不要一直用列表」的要求重做了发现页（衬线刊头、封面故事 hero、
  横向封面墙、榜单卡、双列网格）。随后用户反馈：不要改字体、不喜欢胶囊（pill），已决定走严谨实用风格，
  并询问该版式是否违反 Material 3。
- **判断**：
  - v0.31.0 的发现页**没有违反 M3**：M3 允许自定义字体/版式组合，使用 `FilterChip` 等 M3 组件本身合规；
  - 但它**偏离 M3 默认样式**（衬线字体、超大 hero、替换页头、页面级出血），与用户「严谨实用」的目标不一致，
    因此回退是正确选择。
- **决策**：
  1. 发现页回退到 v0.30 的标准 M3 版式：`BiliPageHeader` + 四个 `BiliSection` + `BiliAlbumRow`/`BiliGroupItem`；
  2. 移除衬线字体、hero/封面墙/榜单卡/双列网格/页面专属入场；不改全局主题字体；
  3. 模板恢复严格：T1 一律 `BiliPageHeader`、T2 一律 `BiliDetailScaffold`，当前无 T1/T2 例外；
  4. 保留 v0.31.0 的骨架资产：PAGE_INVENTORY 页面登记制、L0/L1/L2 分层、两条新增守卫
     （转场唯一来源、禁止硬编码颜色）；发现页恢复「标准模板」守卫；
  5. 胶囊问题：M3 chip 本身就是胶囊；本次只回退新增版式，不在未确认的情况下全局替换 M3 chip。
- **影响**：HomeScreen 恢复 429 行标准版式；PAGE_INVENTORY/UI_ARCHITECTURE/DESIGN_SYSTEM/AGENTS 同步回退；
  202 单测全绿（守卫 7 条）；release v0.31.1。


## D59 容器族扩展：导航树 ≠ 内容树，页面不强制 group+item（v0.32.0）

- **日期**：2026-11-11
- **背景**：用户询问当前模板是否强约束（page 严格拓扑树、page 只能 group、group 只能 item），
  并建议多发展几种 group，避免限制页面设计。
- **澄清**：
  - 导航拓扑确实是严格树（T1/T2/T3/T4；转场只在 AppNavHost；返回/insets 统一），这是稳定性要求；
  - 但页面内容不是「group 包 item」：`BiliGroup`/`BiliSection` 的内容槽是 `ColumnScope`，
    `BiliShelf` 是 `LazyListScope`，都可以放任意 Composable；`BiliGroupItem` 只是常用行 helper；
  - 真正的限制是容器族偏少 + `BiliSection` 总是把内容包成卡片，导致网格/横向墙/编辑式列表只能页面手写。
- **决策**：
  1. `BiliSection` 增加容器模式：`Card`（默认，保持现网视觉）与 `Plain`（内容直接放页面背景）；
  2. 新增通用 `BiliShelf`（标题栏 + 横向滚动内容槽），`AlbumShelf` 复用（视觉不变）；
  3. 文档明确「导航拓扑树 ≠ 页面内容树」；容器族：Section（Card/Plain）/ Shelf / CardGroup / Grid（页面级）/
     Rank/Form（组合）/ Message（EmptyState），容器只负责一致性属性，内容自由；
  4. 不新增强制模板：页面可自由组合容器；T1/T2 默认壳、L0 约束与视觉克制要求不变。
- **影响**：`BiliSection` 向后兼容；新增 `BiliShelf`；`AlbumShelf` 内部复用；DESIGN_SYSTEM/PAGE_INVENTORY/
  UI_ARCHITECTURE/AGENTS 更新；202 单测全绿；release v0.32.0。


## D60 播放器精简 + 播放模式合并 + 迷你条自由拉伸（v0.33.0）

- **日期**：2026-11-11
- **背景**：用户要求：

  1）迷你播放条做成类似椒盐音乐 / 柠檬音乐的「自由拉伸」展开效果；
  2）播放页去掉顶栏（收起箭头/中央「正在播放」/跳转专辑按钮）、去掉左右分页指示条、
  去掉「专辑进度」文字；整体播放与进度条上移，播放按钮接近原进度条位置；
  播放模式合并为一个按钮（随机 / 顺序 / 列表循环 / 单曲循环），右侧第五个按钮改为播放列表；
  3）进度条换成更美观的样式（参考最新 Android 的 M3 Expressive 风格）；
  4）发现/音乐库/设置根页面去掉数量统计、「关于」分区；音乐库去掉字母分隔头。

- **决策**：
  1. **迷你条自由拉伸**：不改现有上下连续展开架构，在 AppNavHost 中把展开进度拆成
     `miniStretch`（0→0.14，迷你条自身长大）与 `openProgress`（0.14→1，播放页卡片展开）；
     `MiniPlayerBar` 接收 `stretchProgress`，连续插值封面大小/内边距/圆角并显示 2dp 进度线。
  2. **播放模式单按钮**：新增 `PlaybackMode`（SHUFFLE/SEQUENTIAL/LOOP_ALL/LOOP_ONE）与
     `PlaybackState.playbackMode` 派生属性；`PlaybackController.cyclePlaybackMode()` 由引擎映射到
     Media3 的 shuffle + repeat 组合；顺序播放 = shuffle 关 + repeat OFF（播完停止）。
  3. **播放页重排**：移除顶栏/分页指示条/专辑进度文字；底部只保留「进度条 + 等宽时间 + 控制行」，
     用底部留白把控制区整体上移；控制行 = 模式 / 上一首 / 播放暂停 / 下一首 / 播放列表；
     顶部保留 48dp 透明下拉关闭热区（无可见箭头）。
  4. **进度条**：不引入新依赖，自绘 M3 Expressive 风格 seekbar（8dp 圆角轨道、突显滑块、
     拖动放大、点击/拖动定位、无障碍进度语义）。
  5. **根页面**：只删信息不改信息架构——发现/音乐库页头去统计；音乐库去字母分隔头但保留排序与索引栏；
     设置删除「关于」分区与页头副标题。

- **影响**：`PlaybackContracts`/`PlaybackEngine`/`NowPlayingViewModel`/`NowPlayingScreen`/`MiniPlayerBar`/
  `AppNavHost`/`HomeScreen`/`LibraryScreen`/`SettingsScreen`/`LetterIndex` 修改；
  新增 `PlaybackModeTest`；204 单测；release v0.33.0。


## D61 迷你条→播放页单一表面连续展开 + 歌词左对齐（v0.34.0）

- **日期**：2026-11-11
- **背景**：用户要求按「单一 surfaces 连续变形（迷你条封面直接放大并移动到播放页封面位置、标题/控制跟着重排）」
  继续优化迷你条展开；同时要求歌词页歌词靠左。
- **决策**：
  1. v0.33 的两段式（先拉伸迷你条、再滑动播放页卡片）改为 **shared-element 式连续展开**：
     - NowPlayingScreen 从进度 >0 起以透明形式组合，上报封面/标题/艺术家在窗口中的 Rect；
     - 新增 PlayerMorphLayer：封面连续插值（位置/大小/圆角/暂停缩放），卡片背景从迷你条尺寸连续长到全屏；
     - 迷你标题在封面右侧、播放页标题在封面下方，路径交叉 → 标题/艺术家在前 12% 快速淡出，
       交给后半段淡入的播放页内容承接，避免叠字；
     - 播放页内容 55%→85% 淡入、morph 层 88%→99.5% 淡出；两端位置对齐；
     - morph 层就绪前迷你条保持完全不透明，避免「迷你条已淡出但 morph 未绘制」的空白帧。
  2. 歌词页（同步/逐字/纯文本/翻译）全部左对齐；顶部信息与来源/偏移布局不变。
- **取舍**：标题/控制没有做全程连续重排（geometry 交叉不可避免），采用「封面连续 + 卡片连续 + 内容交叉淡入」，
  视觉上是一次连续的展开；如后续要做完全体，可在封面飞行基础上再加自定义 Layout 插值所有子元素。
- **影响**：新增 `ui/player/PlayerMorphLayer.kt`；AppNavHost/MiniPlayerBar/NowPlayingScreen 增加窗口位置回调；
  LyricLineRow/NowPlayingLyricsPane 对齐调整；204 单测；release v0.34.0。


## D62 歌词页排版精简 + 播放页顶部标题 + 连续展开支持歌词页 + 音量微调棘轮（v0.35.0）

- **日期**：2026-11-11
- **背景**：用户要求：
  1）歌词左对齐后不要放大当前行，改用主题色填充；
  2）歌词页隐藏上一首/下一首/播放按钮及底部工具栏；
  3）歌词来源/偏移移到右下角（高度接近播放列表入口）；
  4）播放页不再在封面下方显示曲名/作者，改为顶部并适当放大；
  5）连续展开适配歌词页（歌词页收起再拉起仍是歌词页）；
  6）封面右侧增加隐形音量微调棘轮（分挡，1 系统音量步 = 5 小格）。
- **决策**：
  1. 歌词行移除 1.09/0.95 缩放，仅保留颜色/透明度强调；所有歌词左对齐；
  2. 歌词页只在 `pagerState.currentPage == COVER_PAGE` 时显示底部进度/控制；歌词页把「歌词来源/偏移」
     做成右下角 10dp 圆角矩形按钮组（非胶囊），头部标题移交给播放页共用；
  3. 播放页顶部新增共用标题区：曲名 `headlineLarge`（30sp）+ 艺术家 `bodyMedium`（14sp）；
     封面页不再重复；
  4. AppNavHost `rememberSaveable` 保存 `playerPage`；`NowPlayingScreen` 增加 initialPage/onPageChanged；
     morph 层在目标为歌词页时让封面淡出、卡片继续展开/标题飞入；
  5. 音量微调：`PlaybackState.volume` + `PlaybackController.setVolume`；封面右侧 46dp 隐形热区，
     每 18dp/格，格距 = 1/(系统最大音量*5)，拨动时显示半透明 5 段刻度 + 百分比、1.4s 自动隐藏、每格触觉反馈。
- **取舍**：音量微调控制的是 ExoPlayer 音量（与系统媒体音量叠加），不修改系统音量；0/1 边界不越界。
- **影响**：PlayerMorphLayer/NowPlayingScreen/NowPlayingLyricsPane/LyricLineRow/AppNavHost/PlaybackContracts/
  PlaybackEngine/NowPlayingViewModel 修改；204 单测；release v0.35.0。


## D63 音量微调 7 档棘轮 + 波浪式进度条（v0.36.0）

- **日期**：2026-11-11
- **背景**：用户要求：
  1）音量微调直接做 7 档，每档 = 音量（系统一步）的 1/4，默认中间，向上 +3/4、向下 -3/4；
  2）可引入新依赖，尝试全新 Android（M3 Expressive）进度条，最好能随频谱摆动；
  3）未微调时小球居中、与专辑封面最右侧平齐；调整后小球呈玻璃质感并显示 ±数字；拨动跨度单手可及、间隔够大防跳格。
- **决策**：
  1. 音量档位：`PlaybackState.volumeOffsetSteps ∈ [-3,3]`；`PlaybackEngine.setVolumeOffsetSteps` 用
     `AudioManager` 最大档位计算 `detent = 1/(maxSteps*4)`，基础音量留出 +3 档空间（约 -5%）；
     每档 = 系统音量单步的 1/4；
  2. 小球默认位于封面右缘垂直中心（封面宽度 0.94→0.86 留空间）；拨动 34dp/档，移动方向与手指一致；
     未调整显示低调小圆点，调整后玻璃渐变球 + ±数字；
  3. 进度条：采用 M3 Expressive 风格的波浪 active 轨道（自绘，保留拖动 seek 和无障碍）；
     播放时用程序化节拍/频谱包络摆动，暂停 420ms 收平。没有直接依赖实验性
     `LinearWavyProgressIndicator`（material3 1.5-alpha 已有，但不可拖动/无法按档位控制），
     也没有为真实 FFT 引入 `Visualizer`（需要 RECORD_AUDIO 麦克风权限）；
- **取舍**：当前频谱是「与播放状态同步的合成包络」，不是真实 FFT；如需要真实频谱，可加「真实频谱」开关，
  在用户授权麦克风后启用 Visualizer。
- **影响**：PlaybackContracts/PlaybackEngine/NowPlayingViewModel/NowPlayingScreen（音量球、进度条）、文档；204 单测；release v0.36.0。


## D64 缝隙内隐身音量细条 + 真实频谱（Visualizer / RECORD_AUDIO）（v0.37.0）

- **日期**：2026-11-11
- **背景**：用户反馈音量微调控件太大，希望做成很小巧、完全位于专辑封面右侧缝隙内、平时居中隐身、
  拨动才显形并带液态玻璃质感；同时确认「真实频谱需要麦克风权限也可以做」。
- **决策**：
  1. 音量控件改为 26dp 宽、150dp 高的液态玻璃细条，中心落在封面右缘与屏幕右缘的缝隙中点；
     0 档且未触摸时 alpha=0 完全隐身，拖动或非 0 档时显示 7 个刻度与 ±数字；
     每档 20dp（低于 v0.36 的 34dp，更紧凑但仍防跳格）；档位仍是 -3..+3，每档=系统音量单步/4。
  2. 真实频谱：新增 `RealSpectrumAnalyzer`，`Visualizer(audioSessionId)` 读取波形，
     Goertzel 估算 8 频段并做 attack/decay 平滑；UI 首次打开播放页弹一次说明对话，
     同意后申请 `RECORD_AUDIO` 并启用；拒绝/失败/无 audioSession 时回退合成包络；
     Analyzer 仅播放页可见时存在，销毁即 release；不保存/上传任何音频数据。
  3. 未额外引入第三方依赖：M3 1.5-alpha 的实验性 `LinearWavyProgressIndicator` 不可拖动，
     因此保留自绘可交互波浪轨道，仅用真实频谱能量驱动其振幅。
- **取舍**：`RECORD_AUDIO` 属于敏感权限，首次需明确说明；本应用只在播放页用于实时频谱，
  退出播放页/暂停后不会后台采集；用户拒绝则功能降级但不影响播放。
- **影响**：AndroidManifest 增加 RECORD_AUDIO；新增 player/RealSpectrumAnalyzer.kt；
  PlaybackContracts/PlaybackEngine 增加 audioSessionId；NowPlayingScreen 权限/频谱/音量 UI；文档；204 单测；release v0.37.0。


## D65 实时频谱 seekbar + 完美隐藏的贴边音量条（v0.38.0）

- **日期**：2026-11-11
- **背景**：用户觉得 v0.37 的波浪进度条不好看，希望用成熟方案；预想是「播放条本身是频谱，
  未播放部分透明，播放到的地方由小圆点染色；拖动时振幅压缩到 30%」。音量条则希望完美隐藏、
  点一下显形再拖动、用正常模糊、尽量贴屏幕边框少影响封面。
- **调研**：成熟 App 主要是两类进度条：
  1）纯线性进度（Spotify/Apple Music/YouTube Music）；
  2）整曲静态波形 + 已播放染色（SoundCloud、CamelPlayer 等）。
  实时频谱 seekbar 较少见，常见做法是竖条频谱铺满、进度独立渲染（如 Flutter spectrum_seekbar 的 live 模式）。
- **决策**：
  1. 采用 live-spectrum seekbar：整条由竖条频谱组成；已播放竖条用 primary 染色，未播放完全透明；
     小圆点位于边界；播放时优先用真实频谱（v0.37 的 Visualizer 8 频段），否则合成回退；
     拖动 seek 时振幅压缩到 30%，不影响定位手感；不新增第三方依赖。
  2. 音量条：平时 alpha=0（仅 48dp 透明触控区）；点按后在屏幕最右缘显形 24dp×128dp 竖条，
     Haze 背景模糊（API31+，低版本半透明兜底），2s 后自动隐藏；拖动期间保持显形；
     7 档 ±3、20dp/档、触觉反馈不变。
- **取舍**：没有做整曲静态波形（需要解码整段音频/缓存峰值，B 站流式播放不划算）；
  实时频谱只反映当前瞬间，历史位置用已播放染色表达；如需 SoundCloud 式整曲波形，可后续增加
  「缓存音频 → 预先计算 peaks」的离线分析任务。
- **影响**：NowPlayingScreen（频谱 seekbar、贴边 Haze 音量条、Haze source）修改；文档；204 单测；release v0.38.0。


## D66 播放页 UI 拆分与代码整理（v0.39.0）

- **日期**：2026-11-11
- **背景**：经历 v0.34–v0.38 多轮播放页改造后，`NowPlayingScreen.kt` 超过 1000 行，
  同时遗留了 v0.34 之前的迷你条 stretch 死代码、占位页、未使用函数与 import。
- **决策**：
  1. 按职责拆成 `NowPlayingScreen.kt`（页面壳/顶部标题/分页/频谱权限）、`PlaybackSeekBar.kt`
     （实时频谱进度条）、`PlaybackControls.kt`（控制行）、`VolumeFineTune.kt`（贴边音量条）；
  2. 删除不再使用的 `MiniPlayerBar.stretchProgress` 与拉伸布局、`PlaceholderScreens.kt`、
     `LetterIndex.sectionStartIndices()` 等死代码；
  3. 全项目清理未使用 import，统一 import 分组并删除多余空行/重复 import；
  4. README 修复重复「构建」章节、过期技术栈、歌词/播放页操作说明与截图版本。
- **影响**：无行为/UI 变更；204 单测保持全绿；v0.39.0 为整理版发布。

## D68 播放页封面主题 + 不规则整曲波形 + 音量手势/步长修复（v0.41.0）

- **日期**：2026-11-12
- **背景**：用户反馈频谱过于规律（8 段真实频谱插值成全宽条纹）、音量微调没反应、希望播放页用封面主题色；
  同时要求竖条减少以免性能压力，并改善拖动时的播放条衔接与惯性。
- **决策**：
  1. 封面主题：新增 `CoverAccent`（Coil 96×96 + 饱和度/明度加权平均）生成种子色；
     仅播放页局部套 `BiliAudioTheme(seedColor=封面色)`，morph 层也套同一主题，避免展开末尾配色跳变；
     全局品牌粉保持不变（局部跟随当前歌曲，视觉风险更低）。
  2. 频谱：改为按曲目种子生成的**不规则整曲波形包络**（平滑伪随机 + 中段高两端低），
     真实频谱能量只驱动整体起伏；竖条从 ~96 根/2dp 降到上限 64 根/4dp（间距 3dp），降低绘制负担。
     说明：这不是解码出的真实整曲 waveform，而是视觉包络；真正 waveform 需要离线解码音频峰值。
  3. 音量：部分 ROM 的 maxStreamVolume 报 100+，导致每档 <0.5%、几乎无感；改为固定 2.5%/档
     （±3 档约 ±7.5%）。手势由两个 detect* 改为单一 `awaitEachGesture` 循环（点按显形 + 拖动调档），
     修复 tap 吃掉 down 导致拖动不触发的问题。
  4. 拖动衔接：播放页内容按展开进度上浮 56dp + 延迟淡入；松手用预测位置 + 速度决定去留，
     spring 改为临界阻尼、初始速度限幅，降低「突然到顶/闪烁」。
- **影响**：CoverAccent.kt（新增）、NowPlayingScreen/PlaybackSeekBar/VolumeFineTune/PlaybackEngine/
  AppNavHost；204 单测；release v0.41.0。


## D69 全局封面取色主题 + 频谱高度/时间行调整（v0.42.0）

- **日期**：2026-11-12
- **背景**：用户确认需要全局颜色主题、封面取色；同时希望频谱跳动再大一点，两端时间下移不要挡频谱。
- **决策**：
  1. 主题种子提升到 `AppTheme` 全局：播放状态只订阅「当前封面变化」
     （`produceState` + `map` + `distinctUntilChanged`），避免 250ms 进度 tick 重组整个 App；
     无播放/取色失败回退品牌粉 BiliPink；切歌时保留旧封面色直到新色计算完成，避免闪回；
     CoverAccent 从 player 包移到 `ui/theme`，播放页不再单独套主题。
  2. 频谱：进度条高度 40dp→56dp，最大竖条 28dp→40dp；时间行下移 4dp 并与频谱拉开距离；
     底部留白 48dp→32dp 补偿，控制行位置基本不变。
- **取舍**：全局主题变化是 App 级视觉变化，但用户明确要求；仍保留品牌粉作为无封面回退。
- **影响**：AppCompositionLocals.kt（AppTheme）、CoverAccent.kt 移动、NowPlayingScreen/PlaybackSeekBar/
  AppNavHost；204 单测；release v0.42.0。


## D70 真实 FFT 频谱 + 音量档位加强 + 交互流畅度（v0.43.0）

- **日期**：2026-11-12
- **背景**：用户指出 v0.42 的「整曲波形」是假的，要求真实频谱；-3→+3 音量不够，期望接近 2 个系统音量步；
  设置滚动恢复仍有「补一下上滑」的延迟感；满屏展开仍有卡顿/闪烁。
- **决策**：
  1. 频谱改为真实数据：Visualizer 1024 点 PCM → Hann → 基 2 FFT → 48 个对数频段；
     修正 Visualizer samplingRate 单位 milliHz、captureSize 选择（之前错误缩到 128）；
     高频等响度补偿；UI `withFrameNanos` 60fps 平滑；暂停衰减；未授权低电平占位，不再造假数据。
  2. 音量：每档 5%，居中 0.85，-3→+3 覆盖 0.70..1.00（约 43% 幅度差）。
  3. 滚动恢复：去掉 450ms 延迟，路由回到根 Tab 时立即恢复。
  4. 展开流畅度：p>20% 不再组合 Haze 迷你条；morph 层 p>96% 移除；切 Tab 时收起播放页。
- **取舍**：真实频谱是「实时频率内容」而非整曲 waveform；真正的整曲 waveform 仍需离线解码 peaks。
- **影响**：RealSpectrumAnalyzer.kt、PlaybackSeekBar.kt、PlaybackEngine.kt、AppNavHost.kt；204 单测；release v0.43.0。


## D71 底栏优雅消失（v0.44.0）

- **日期**：2026-11-12
- **背景**：用户反馈 miniplayer 缓慢上滑时底部导航突然消失，设置页出现方角/圆角切换、
  「按钮消失但遮罩还在」的中间态。
- **根因**：底栏 `AnimatedVisibility` 在 playerProgress 2% 就开始高度收缩；拖动刚开始，
  底栏快速 shrink/fade，同时 NavHost 视口高度变化，裁切/inset 中间态暴露。
- **决策**：
  1. 拖动手势期间底栏不收缩高度：alpha 随 progress（0.02→0.45）淡出，并向下位移 0→32dp；
  2. 高度保留到 playerProgress ≥ 0.92（播放页已几乎完全覆盖）才收起，避免看到裁切/方角；
  3. mini 播放条底部内容 inset 不再按 16% 移除，保持到播放页铺满；
  4. 收起/展开与切换 Tab 逻辑不变。
- **影响**：AppNavHost Scaffold bottomBar；204 单测；release v0.44.0。


## D72 播放页展开改为整块滑动裁剪 sheet（v0.45.0）

- **日期**：2026-11-12
- **背景**：用户指出慢速拖动会卡在中间、播放按钮不跟随封面、快速展开/收起闪色；
  要求参考椒盐音乐（全程丝滑、非 SPICa 式先画后加载），并允许参考邻项目代码。
- **根因**：
  1. v0.43 为降 Haze 开销把迷你条在 progress>20% 移出组合，拖拽 pointerInput 随之销毁 → 慢拖中断；
  2. 播放页内容为全屏固定 + alpha 交叉淡入，morph 层单独飞封面/标题，底部控件不在同一表面 → 不同步；
     两层 alpha 交叉导致展开/收起闪色；
  3. 底栏绘制在内容之上，展开中覆盖 sheet。
- **决策**：
  1. 参考 SPICa/椒盐的 sheet 思路：整块播放页 sheet 以 `miniBarTop*(1-progress)` 向上滑动，
     底层锚定 + 顶部圆角裁剪；封面/标题/频谱/进度/播放按钮都在同一 sheet 内，天然一起上移；
  2. sheet 垫不透明 surface + 页面 scrim，移除展开期的 morph 渲染（旧 PlayerMorphLayer 暂留待清理）；
  3. 迷你条始终组合，只淡出 alpha；底栏不再收缩高度，0→18% 淡出/下移；mini 内容 inset 保持稳定；
  4. 对照 SPICaMusic 的 SubcomposeLayout/分级 reveal 思路；椒盐闭源，按其「整块 sheet 全程跟手」原则实现。
- **影响**：AppNavHost（sheet/底栏/迷你条组合）；204 单测；release v0.45.0。


## D73 底栏 overlay + 控制条并入封面页（v0.46.0）

- **日期**：2026-11-12
- **背景**：设置→诊断日志返回后滚动位置仍偏移；播放按钮不随封面页滑动；收起时出现两层文字闪一帧。
- **根因**：底栏参与 Scaffold.bottomBar 测量，进入详情时根页面视口变大导致 LazyColumn clamp；
  控制 footer 在 pager 外，不随封面页横滑；迷你条/sheet 淡出时序重叠。
- **决策**：
  1. 底栏移出 Scaffold.bottomBar，改为固定高度 overlay；根 Tab 显式 padding = 实测底栏高度，
     测高只增不减；返回根 Tab 时底栏直接以最终高度布局（不再 0→全高动画）；
  2. 进度/时间/播放控制移入封面 pager page 内部，随封面横向移动；
  3. sheet 最后 3% 淡出、mini 只在前 5% 淡出；mini 骑 sheet 顶边上移；
  4. 滚动恢复用 requestScrollToItem（两帧后），实测 bounds 完全一致。
- **影响**：AppNavHost、NowPlayingScreen；204 单测；release v0.46.0。


## D74 AppLayerHost 图层架构（v0.47.0）

- **日期**：2026-11-12
- **背景**：底栏/迷你条/播放页/弹层反复出现遮挡、测量、返回顺序问题；需要把持久 chrome 的层级固定下来。
- **决策**：
  1. 采用「单一 AppLayerHost + 显式深度」的应用内 layer 架构：
     Content 0 → BottomBar 100 → MiniPlayer 200 → Player 300 → Modal 400；
  2. 页面切换仍由 Content 层内 NavHost/Pager 负责，不给每个页面单独 z 值；
  3. BottomBar 固定高度 overlay，不参与 Content 测量；MiniPlayer 始终组合；PlayerSheet 统一表面；
  4. 删除停止使用的 PlayerMorphLayer 和 bounds 回调管线；暂未实现真正 mini→full 共享布局 morph。
- **影响**：新增 ui/layers/；AppNavHost 减到约 713 行；204 单测；release v0.47.0。


## D75 PlayerSurface 单一表面（v0.48.0）

- **日期**：2026-11-12
- **背景**：v0.47 收起时仍有「迷你条 + sheet 残影」一帧；用户要求完整展开/收起连续。
- **决策**：
  1. 迷你条和播放页合并为一个 PlayerSurface：p=0 边界=迷你条 rect，p=1 边界=全屏，
     top/bottom/圆角随 progress 插值，播放页内容在 surface 内裁剪；
  2. 迷你条内容只在前 5% 淡出；展开后 surface 层级高于底栏/迷你条；收起最后 6% 停止绘制播放页内容，
     由迷你条接管；
  3. 过渡期迷你条用实色背景，避免 Haze 透出下方播放页残影；
  4. 删除 MiniPlayerLayer/PlayerSheetLayer。
- **仍未做**：surface 内 mini 布局与 full 布局的逐元素插值（真正椒盐式 morph）；当前是同一表面内的
  content 交叉淡化 + 边界连续，已消除跨层残影。
- **影响**：ui/layers/PlayerSurfaceLayer.kt；204 单测；release v0.48.0。


## D76 PlayerSurface 全屏测量 + mask 裁剪（v0.49.0）

- **日期**：2026-11-12
- **背景**：v0.48 的播放页内容放在高度变化的 surface 里，中间帧布局被重新约束，标题/封面错位，
  共享元素 target bounds 不稳定。
- **决策**：
  1. 播放页内容始终 `fillMaxSize()` 按全屏布局；
  2. 用 `GenericShape` mask + `graphicsLayer(translationY=topPx, clip=true)` 裁剪到当前 surface；
  3. surface 背景独立一层，不承载内容；
  4. 共享元素 overlay 暂不启用，等布局稳定后再做。
- **影响**：PlayerSurfaceLayer；204 单测；release v0.49.0。


## D77 移除 App 级 Scaffold，自管 WindowInsets（v0.50.0）

- **日期**：2026-11-12
- **背景**：最终架构要求 App 内所有 chrome 为显式图层；Scaffold 的 bottomBar/content inset 测量会引入
  隐式布局依赖，且不利于「迷你条/弹层/播放器」统一排序。
- **决策**：
  1. 删除 App 级 Scaffold；Content 只保留 NavHost；
  2. AppNavHost 用 safeDrawing/ime/navigationBars 统一计算 top/start/end/bottom；
  3. 根 Tab 底部按实测底栏高度留白，详情页按系统导航 inset；IME 可见时取 max(IME, 系统导航)；
  4. BottomBar/PlayerSurface 仍通过 AppLayerHost 绘制，不参与 Content 测量。
- **影响**：AppNavHost；204 单测；release v0.50.0。


## D78 底栏归属根 Tab 页面（v0.51.0）

- **日期**：2026-11-13
- **背景**：全局底栏 overlay 在进入设置子页时先退场，左页仍可见，因此看起来“底栏消失+上一页下移”。
- **决策**：
  1. 底栏移入 `RootTabPage`，与根 Tab 内容同属一个页面；
  2. 转场时底栏随页面一起滑走，不再有独立的 hide 动画；
  3. 全局 Content 只预留系统导航 inset，根 Tab 页面内部预留底栏高度；
  4. 底栏不再跟随 playerProgress 淡出；PlayerSurface 层级在上，自然遮挡；
  5. 迷你条底部位置 = 底栏高度 + 系统导航 inset。
- **影响**：RootTabPage/BiliBottomBar windowInsets；删除 BottomBarLayer；204 单测；release v0.51.0。


## D79 共享元素展开（v0.53.0）

- **日期**：2026-11-13
- **背景**：v0.52 的 PlayerSurface 只有边界动画，封面/标题/播放按钮仍是内容交叉淡化。
- **决策**：
  1. 迷你条和播放页分别上报共享元素 window bounds；
  2. 播放页负责隐藏自身共享元素，PlayerSurfaceLayer overlay 单份绘制并插值；
  3. 封面页目标：封面/标题/艺术家/播放按钮；歌词页目标：只插值标题/艺术家，封面/播放按钮淡出；
  4. 播放按钮目标 bounds 早期不稳定时使用全屏布局兜底位置；
  5. 外层 AppLayerHost/PlayerSurface 层不变。
- **影响**：MiniPlayerBar/NowPlayingScreen/PlaybackControls/PlayerSurfaceLayer；204 单测；release v0.53.0。


## D80 播放器表面生命周期：进场动画按「是否有曲目」触发，拖拽热区常驻（v0.57.0）

- **日期**：2026-11-15
- **背景**：用户反馈两点——(1) 专辑页点播放时迷你条突然弹出没有过渡；(2) 慢速拖动时共享封面
  被拉成长方形，之后有概率整条卡在半展开状态（surface 卡高 309px、迷你条内容 alpha≈0.35，
  反推 progress≈0.059）。
- **决策**：
  1. `PlayerSurfaceLayer` 的进场/退场动画 key 改为「当前是否有曲目」（`Animatable` 初值也随
     `hasTrack`），不再用 `LaunchedEffect(Unit)`（专辑页/根 Tab 上图层一直 visible，动画会在点播放前跑完）；
  2. 迷你条只保留**单一稳定调用点**：删除 `rect == null` 时 early-return 的第二个 `MiniPlayerBar`
     实例（实例切换会打断 `AnimatedVisibility` 进场动画）；
  3. 共享封面的目标 rect 用 `boundsInWindow(clipBounds = false)` 测量：播放页内容整体
     `graphicsLayer{translationY = topPx}` 平移，默认裁剪会把屏幕外的封面裁成动态扁矩形，
     宽高独立插值 → 长方形；不裁剪后 target 恒为 896×896（1080×1920 @2.625）；
  4. 顶部下拉热区从 `progress >= 0.06` 的播放页内容中提出来常驻组合：手势进行中节点被 dispose 时
     draggable 在 detach 状态会丢弃 `onDragStopped`，没有 spring 收敛 → progress 冻在 ≈0.06。
     热区随 surface 平移（`topPx + statusBarInset`），收起态移到屏幕外避免拦截迷你条；
  5. 曲目清空时若 progress 仍在中间值，强制收敛回迷你条（兜底手势节点被销毁的极端情况）；
  6. surface 背景 p=0 时左右按迷你条内边距内缩（此前比迷你条宽 12dp，露出「翅膀」）。
- **原因**：这些都是「生命周期/坐标系」问题，不是视觉设计问题；遵循既有「单一 surface + 封面-only
  共享元素 + 跟手」架构，不引入新动画或新视觉语言。
- **影响**：PlayerSurfaceLayer/MiniPlayerBar 调用点/NowPlayingScreen 封面测量/AppNavHost 兜底；
  204 单测；release v0.57.0。以后改共享元素测量或拖拽热区时必须遵守上述约束。


## D81 维测日志默认开启 + 日志规范（v0.58.0）

- **日期**：2026-11-15
- **背景**：用户反馈「日志太少不方便维测」。检查发现 `AppSettings.diagnosticLogsEnabled` 与
  SharedPreferences 默认都是 false（v0.10.0 的「默认关闭」决策），而排障手册却写默认开启；
  全新安装的用户反馈问题时导出的日志只有设备头，等于没有日志。同时播放器表面/手势、后台服务、
  登录、设置等主链路几乎没有 Diag 日志，v0.57.0 的拖动卡死问题只能靠临时调试代码定位。
- **决策**：
  1. **维测日志默认开启**（老用户显式关闭过的保持关闭）；设置页文案同步；覆盖 v0.10.0「默认关闭」的决策；
  2. 新增/统一 tag：`Shell`（导航壳/手势/展开收起）、`Surface`（播放器表面/共享元素/毛玻璃）、
     `NowPlaying`（播放页内部）、`Play`（播放引擎，原 `PlaybackEngine`）、`Service`（后台播放服务）、
     `Auth`（登录态/扫码）、`Settings`、`Stats`、`Theme`；tag 表以 docs/DEBUGGING.md 为准；
  3. 日志频率规范：只记状态跃迁/阈值跨越/失败，禁止逐帧、禁止 position tick（环形缓冲 3000 行）；
     高频回调一律去重/节流；拖拽相关日志按「每次手势几条」控制；
  4. 隐私规范：禁止记录 Cookie/SESSDATA、二维码 key、mid/uname、完整签名 URL、SAF 绝对路径；
     只记 hasSession 布尔、phase/code、albumId/trackId/index、route pattern 等；
  5. `Diag` 自身必须线程安全且不抛异常：`SimpleDateFormat` → `ThreadLocal`，`log()` 整体 `runCatching`；
  6. 关键异常必须进环形日志（准备播放失败、后台服务失败、登录刷新失败、统计/设置落库失败等），
     不能只写 Logcat；
  7. 回退/验证钩子：`[Surface] cover target ANOMALY`、`[Shell] WARN progress stranded` 是
     v0.57.0 两个已修问题的回归探测器。
- **原因**：日志是问题反馈的唯一现场；默认关闭会让「加日志」对用户无效；同时环形缓冲有限，
  必须靠状态跃迁而不是高频采样控制噪音。
- **影响**：AppSettings/SettingsRepositoryImpl/SettingsScreen/Diag/PlaybackEngine/PlaybackService/
  AuthRepositoryImpl/SettingsViewModel/BiliApi/MediaStreamResolverImpl/MainActivity/MiniPlayerBar/
  PlayerSurfaceLayer/AppNavHost/NowPlayingScreen/AppCompositionLocals/CoverAccent；204 单测；
  release v0.58.0。以后新增状态机/手势/网络/后台链路需同步补 Diag 日志并遵守上述频率/隐私规范。


## D82 恢复上次播放会话 + 静止迷你条不绘制表面背景（v0.59.0）

- **日期**：2026-11-15
- **背景**：用户希望「下次打开还是上次的播放列表和曲目」，并讨论过「迷你条永远常驻 + 无播放时空填充」。
  另反馈静止迷你条（胶囊）外接矩形似乎有一层色块（浅色主题下是白边/白块）。
- **决策**：
  1. **不做「空迷你条常驻」**：空条会在所有页面底部永久占位、没有主操作目标，还要处理空态点击/无障碍/inset，
     反而更复杂；改为**恢复上次播放会话**：
     - `PlaybackSessionStore`（应用私有 SharedPreferences + kotlinx JSON）持久化最小状态：
       专辑 id、曲目 id/startMs/index、曲内进度、随机/循环模式；5 秒节流，暂停/切歌/seek/播完立即保存；
     - 冷启动静默重建队列并恢复为**暂停态**（迷你条显示上次曲目，点播放续播）；启动不解析音频流；
     - 首次播放/切歌/seek/跳队列时才 `session RESUME` 真正 prepare 队列；
     - 兜底：专辑删除则清空会话；曲目 id 变化按 startMs → index 匹配；进度在结尾附近回 0；损坏 JSON 丢弃；
     - 迷你条显示规则保持不变（内容页显示，不在登录/导入/编辑等页面常驻遮控件）。
  2. **静止迷你条（p=0）不绘制 PlayerSurface 背景层**：胶囊自身是 Haze 玻璃，外接矩形必须保持透明；
     表面背景只随展开进度（0→1%）淡入，与迷你条内容淡出、胶囊转为不透明同步。
  3. **表面背景尺寸用真实窗口像素**（`LocalWindowInfo.containerSize`）而不是
     `LocalConfiguration.screenWidthDp`（整数 dp 取整会差 1~2px），否则圆角处会露出 surfaceContainer 色块。
- **原因**：会话恢复用「上次内容」提供连续性，比空迷你条更符合「点一下就能听」的轻量体验；
  表面背景层本来只是展开过程中的卡片底，静止时属于多余图层，是白边/色块的来源。
- **影响**：PlaybackEngine/PlaybackSessionStore（新增）/PlayerSurfaceLayer；207 单测（新增 3 个会话序列化测试）；
  release v0.59.0。以后改迷你条表面/底色时须守住「p=0 无背景层、真实窗口像素对齐」的约束；
  会话持久化不得写入 Cookie/账号等敏感信息，也不进入归档/备份。


## D83 共享封面交接：overlay 独占绘制 + 固定解码尺寸（v0.59.1）

- **日期**：2026-11-15
- **背景**：用户反馈从播放页完全展开状态刚开始向下拖动时，专辑封面出现「重影」和卡顿感。
- **原因**：
  1. 真实封面在 progress 94%→99.5% 与共享封面 overlay **交叉淡入**，但两者位置/尺寸/圆角不同
     （播放页内容随 surface 整体平移，overlay 目标是页面内坐标）→ 双封面叠加；
  2. overlay 的 `CoverImage` 容器尺寸随拖动逐帧变化，Coil 按动画尺寸反复请求/解码，
     过渡中停留在迷你条的低清位图放大显示 → 发糊，进一步像重影。
- **决策**：
  1. 0<p<1 期间**只由 overlay 绘制封面**（完全不透明）；播放页真实封面 alpha 只在 p≈1 时为 1。
     p=1 时 overlay 与真实封面位置/尺寸/圆角一致（topPx=0、morph=1），单帧切换无感。
  2. `CoverImage` 增加可选 `requestSizePx`：overlay 用固定尺寸解码（播放页封面目标像素，未量到用 1280，
     范围 720~1536），容器尺寸变化时复用同一张高清位图。
- **原因补充**：共享元素的价值是「连续」而不是「双份淡入淡出」；能对齐时用一个绘制者最稳，
  对齐不了就用切换点；解码尺寸属于动画质量的隐形成本，动画中变化的容器必须固定解码尺寸。
- **影响**：`PlayerSurfaceLayer`（交接逻辑）、`CoverImage`（可选参数，普通封面不受影响）；
  207 单测；release v0.59.1。以后调整播放页封面尺寸/圆角时，必须同步 overlay 的 cornerRadius 与 morph，
  否则会在 p=1 切换时露馅；overlay 一律固定解码尺寸。


## D84 根 Tab 内容从迷你条后面滚过（v0.59.2）

- **日期**：2026-11-15
- **背景**：用户反馈静止迷你条「像一个外接矩形色块」：胶囊四角处看不到下面的内容，
  页面上方标题/卡片在胶囊上沿被裁出一条平线，看起来像被矩形遮挡。
- **原因**：页面内容区（`Page`/`RootTabPage`）为迷你条预留了整条高度，内容视口在**胶囊上沿结束**；
  平线以下只剩页面背景色，胶囊玻璃又与页面背景色接近，于是整个外接矩形看上去是一块浅色矩形。
  不是迷你条绘制了矩形。
- **决策**：
  1. 根 Tab（发现/音乐库/设置）页面内容改为**从迷你条后面滚过去**：`tabBottomExtra` 只保留底部导航高度，
     迷你条高度不再裁内容视口；滚到底时通过列表 `contentPadding` 加 `LocalMiniPlayerContentInset`
     保证最后一项停在迷你条上方；
  2. 详情页、登录/导入/编辑等带底部表单按钮的页面保持原样（`Page(applyMiniInset=true)` 仍预留迷你条空间），
     避免悬浮条遮挡按钮；以后若要统一成「全页面滚过」，需要逐页把留白改成列表 contentPadding 后再切。
- **影响**：AppNavHost（`tabBottomExtra`/Local 可见性）、HomeScreen/LibraryScreen/SettingsScreen 列表 padding；
  207 单测；release v0.59.2。改页面底部布局时注意：内容必须能滚到迷你条上方，别让最后一项被玻璃盖住。


## D85 迷你条材质统一为毛玻璃（v0.59.3）

- **日期**：2026-11-15
- **背景**：从播放页收回迷你条时，材质从「不透明底」突然变成「毛玻璃」，最后一刻明显跳变；
  用户建议参考椒盐音乐用渐变过渡。
- **原因**：`MiniPlayerBar.surfaceOpaque` 在过渡期间强制不透明背景（本意是避免透出播放页残影），
  p 降到 0.1% 以下时切回 Haze；同时表面背景层淡出区间只有 0~1%，末端变化过于集中。
- **决策**：
  1. 迷你条**全过程统一为 Haze 毛玻璃**，删除 `surfaceOpaque` 分支；低版本/无 Haze 仍走高不透明度 fallback；
  2. 表面背景层淡入/淡出区间放宽到 `smoothStep(0f, 0.06f, p)`，收起最后 6% 是「卡片渐隐 + 玻璃透出内容」
     的渐变，而不是硬切换；
  3. 过渡中迷你条要么位于播放页表面之下（p>=6%），要么被同形状、同尺寸的表面卡片垫在背后，
     毛玻璃不会透出播放页残影，因此不再需要强制不透明。
- **影响**：MiniPlayerBar（删除参数）、PlayerSurfaceLayer（调用/渐变区间）；207 单测；release v0.59.3。
  以后改迷你条材质时必须保持全程一致，材质切换只能做渐变不能做开关。


## D86 iOS 式肉感动画（v0.60.0）

- **日期**：2026-11-15
- **背景**：希望全项目动画交互带 iPhone 的「肉感」（物理弹性），参考 mobile-design-system skill 的
  「滑动肉感三大铁律」，但只取物理手感，不采用其视觉体系；并明确本轮**不做**三个中高难度项：
  可打断转场、边缘侧滑返回、列表回弹 overscroll（安卓原生拉伸已表达“到底”，不做自定义）。
- **决策**：
  1. 弹簧 token 四档：`springPage`（damping 1.0/320，整页不过冲）、`springSettle`（0.9/360，面板归位）、
     `springSnappy`（0.85/500，按下回弹/列表入场）、`springBouncy`（0.75/340，按钮松手弹回）；
  2. 按压肉感走设计系统包装：`BiliButton/BiliTextButton/BiliOutlinedButton/BiliIconButton/
     BiliFilledIconButton` + `Modifier.biliPressScale/biliClickable`；缩放只用 graphicsLayer
     （不影响热区/布局），按下 90ms 到 0.90~0.97，松手 `springBouncy` 回弹；
  3. 层级页转场：临界阻尼弹簧（320）+ 旧页 1/3 屏宽视差，push/pop 对称；
  4. 其余：列表入场、迷你条出现、播放页 settle（ζ=0.92）、Pager 吸附、播放页完成触觉；
  5. 明确限制：material3 1.5.0-alpha22 的 `MotionScheme` 在 Kotlin 层 internal，暂不能全局注入
     M3 Expressive 弹簧；抽屉/AlertDialog 的内置动画维持默认，后续版本再包。
- **原因**：M3 组件自带 ripple/动画绕过项目 `LocalIndication`，只能包一层才能统一按压反馈；
  弹簧只作用于 transform/alpha，性能可控；整页转场保持临界阻尼避免露出背景/眩晕。
- **影响**：`ui/theme/PressScale.kt`、`ui/components/BiliButtons.kt`、`Motion.kt`、`Entrance.kt`、
  `AppNavHost.kt`、`MiniPlayerBar.kt`、`NowPlayingScreen.kt`、20+ 文件按钮迁移；207 单测；release v0.60.0。
  新代码禁止直接使用 M3 按钮（见 AGENTS §9）。


## D87 material3 升级 1.5.0-rc01 + 全局 MotionScheme.expressive（v0.61.0）

- **日期**：2026-11-15
- **背景**：v0.60.0 想全局启用 M3 弹簧物理，但 project 的 material3（BOM 2026.09.00 → 1.5.0-alpha22）
  中 `MotionScheme` 在 Kotlin 层是 internal、无法调用。用户询问是否有更近的支持版本。
- **调查**：Maven（dl.google.com 可达）上 material3 最高版为 `1.5.0-rc01`；其源码中
  `MotionScheme`、`standard()/expressive()`、`MaterialTheme(motionScheme = …)` 均为 public；
  rc01 POM 依赖 compose foundation/ui/runtime **1.12.0**，与项目 BOM 的 1.12.1 兼容；
  rc01 中消费 motionScheme 的组件覆盖 BottomSheet、Button、IconButton、NavigationBar、Tab、
  Switch、Checkbox、RadioButton、ListItem、AppBar、Menu、SearchBar、SnackbarHost、Ripple 等。
- **决策**：把 `material3` 单独钉到 `1.5.0-rc01`（`libs.versions.toml` 增加 `material3` 版本，
  其余 compose 仍走 BOM），并在 `BiliAudioTheme` 启用 `MotionScheme.expressive()`。
- **影响**：M3 组件内置动画（抽屉开关/吸附、导航栏 indicator、开关、勾选、菜单、波纹等）
  全部弹簧化；AlertDialog/Slider 在 rc01 尚未消费 motionScheme，弹窗肉感需后续自研包装。
  注意 material3 不再跟随 BOM，升级 Compose BOM 时必须同步核对 material3 版本兼容性。
