# UI 架构与页面模板（v0.12.0）

> 目的：避免“每个页面各写各的”，把页面归类、模板化。新增页面前先读本文。
> 非 Android 开发者也能按“页面类型 → 模板 → 状态约定”来理解。

## 1. 页面分四类

| 类型 | 路由/入口 | 有没有底部导航 | 顶栏 | 转场 | 当前页面 |
| --- | --- | --- | --- | --- | --- |
| **T1 一级 Tab 页** | `HOME/LIBRARY/SETTINGS` | 有 | `BiliPageHeader` 大标题页头 | Tab 瞬时切换 + 内容入场 | 发现 / 音乐库 / 设置 |
| **T2 层级详情页** | `ALBUM/SPLIT_EDITOR/ARTIST/IMPORT/SEARCH/IMPORT_ARCHIVE/DIAGNOSTICS/LOGIN` | 无 | `BiliDetailScaffold`（返回 + 标题 + actions） | 层级 push/pop（右进右出） | 专辑详情 / 编辑分割点 / 艺术家 / 导入 / 诊断 / 登录 |
| **T3 沉浸页** | `PLAYER`（卡片覆盖层）/ 视频覆盖层（非路由） | 无 | 页面自己处理（播放页顶部栏/下拉关闭） | 播放页上下跟手；视频从右盖在播放页上 | 正在播放 / 歌词视频 |
| **T4 弹层/对话框** | ModalBottomSheet / AlertDialog | - | 系统/自定义 | 组件自身 | 播放列表、封面选择、确认框 |

规则：

- **T1 不再自己套 Scaffold**（底部导航/inset 由 `AppNavHost` 的 Scaffold 统一处理）；
  内容用 `BiliPageHeader` + 业务列表；**固定页头/搜索/登录卡等静态元素不做入场**，
  只有列表/内容区用 `Modifier.entrance`（发现逐项、设置逐卡、音乐库内容区）；
- **发现页结构（v0.23.1）**：不重复放搜索栏（搜索统一在音乐库）；四个预览区块按
  「最近播放 → 听歌统计 → 我的歌单 → 随机专辑」排列，只露前 4 条，统一用 `HomeSectionCard`
  （`BiliGroup` + 左右 `Spacing.lg` 外边距 + 右上角操作）；详情：
  - 随机专辑：换一批/导入；单条打开专辑；
  - 我的歌单：查看全部 → Playlists；
  - 听歌统计：预览可切换「专辑时长 / 歌曲次数」（持久化 `AppSettings.homeStatsMode`），
    查看全部 → Stats；
  - 最近播放：查看全部 → RecentAlbums（详情最多 10 张）；
- **T2 一律用 `BiliDetailScaffold`**（或复杂页面至少用 `BiliTopBar`）。禁止在页面里手写一行
  `Row { IconButton(back); Text(title) }` 的顶栏；
- **T3 不使用 T1/T2 模板**：全屏、insets、关闭手势都由页面自己管；
  歌词视频是播放页上方的覆盖层，不是 NavHost 目的地（保证进入/退出时底层始终是播放页）；
- **T4 不要再复制一套 `ModalBottomSheet` 的配置**，优先复用现有组件。

## 2. 层级页面模板

### 2.1 `BiliDetailScaffold`

```kotlin
@Composable
fun BiliDetailScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
)
```

统一保证：

- `Scaffold` 容器色 = `surface`；
- `contentWindowInsets = WindowInsets(0)`（系统 inset 交给 AppNavHost 外层）；
- 顶栏 = `BiliTopBar`：返回键左侧、标题 `titleLarge`、actions 右侧；
- Snackbar 由传入的 `SnackbarHostState` 统一处理；
- 内容接收 `innerPadding`，不要重复系统栏 padding。

### 2.2 `BiliTopBar`

复杂页面如果自己有 Scaffold/特殊内容，可只用 `BiliTopBar(onBack, title, actions)` 统一顶栏。

### 2.3 什么时候不用模板

- 播放页/歌词页：沉浸全屏、自己的 insets 和手势；
- ModalBottomSheet/AlertDialog：属于 T4；
- 页面内嵌的局部卡片：用 `Surface`/`SettingsCard` 等组件，不是页面模板。

## 3. 状态与数据流约定

每个 Screen 对应一个 ViewModel，Screen 只做：

1. `collectAsStateWithLifecycle()` 收集 `@Immutable` 的 UiState；
2. 把用户事件转发到 ViewModel 方法；
3. 按 `isLoading / error / empty / content` 渲染；
4. 一次性消息（message）通过 `LaunchedEffect` 消费（Snackbar），消费后 `consumeMessage()`。

禁止：

- 在 Composable 里直接访问 Repository / 播放引擎（应通过 ViewModel 或 AppContainer 注入）；
- 在 UI 里做排序/分组等重计算（放 ViewModel / 数据层，并缓存）；
- 多个页面各自定义相同的“加载/错误/空态”文案逻辑（新页面优先复用 `LoadingBox`、`EmptyState`）。

## 4. 共享视觉组件

| 组件 | 位置 | 用途 |
| --- | --- | --- |
| `BiliPageHeader` | `ui/components/PageHeader.kt` | T1 页大标题 + 副标题 |
| `BiliDetailScaffold` / `BiliTopBar` | `ui/template/BiliPageTemplates.kt` | T2 统一容器/顶栏 |
| `BiliGroup` / `BiliGroupItem` | `ui/components/BiliGroup.kt` | 分组卡片与组内条目的统一背景/圆角/内边距/字体/点击态 |
| `BiliGroupSectionTitle` | `ui/components/BiliGroup.kt` | 分组外的小节标题（设置/缓存/用户数据统一） |
| `SearchField` / `SearchBarEntry` | `ui/components/Common.kt` | 胶囊搜索框/入口 |
| `TrackRow` / `AlbumCard` / `CoverImage` | `ui/components/` | 列表行/卡片/封面 |
| `EmptyState` / `LoadingBox` | `ui/components/Common.kt` | 空态/加载态 |
| `SettingsCard` 等 | 页面内私有 | 设置分类卡片（如后续多页复用再上提） |

尺寸、圆角、间距统一用 `ui/theme/Tokens.kt`（`Spacing`/`Dimens`）；动效统一用
`ui/theme/Motion.kt` 的 `BiliMotion` + `Entrance.kt`，不要页面内散写时长/曲线。

### Group / Item 怎么保证一致

- 分组容器统一 `BiliGroup`：`Dimens.cardCorner` 圆角 + `surfaceContainerHigh` + 标准内边距；
- 组内行统一 `BiliGroupItem`：leading/标题/副标题/trailing 的间距、字体、点击裁剪与点击态只维护一份；
- `SettingsCard` 已委托给 `BiliGroup`；设置、缓存管理、歌单列表、统计卡片、发现页快捷卡全部使用同一模板；
- 组内自定义内容（如统计时长条、缓存音频/视频 chips）只允许在 **BiliGroup 容器内部**做业务排版，
  不重新定义卡片背景/圆角/阴影；
- 列表行统一复用 `TrackRow`/`SongRow`；页面壳统一 T2 模板；空态/加载态统一 `EmptyState`/`LoadingBox`。

### 设置卡片首行模板

设置卡片统一用 `SettingsHeaderRow`：22dp 图标 + `bodyLarge` 标题 + `bodySmall` 副标题，
右侧可放 Switch/操作；账号头像属于身份例外。分组容器仍用 `BiliGroup`。

### 同类 Group/Item 的行为规则（v0.22.1 审计）

- **容器**：一律 `BiliGroup`；需要整卡点击时用 `BiliGroup(onClick=...)`，不要给内部 Row 再加一个不同命中区域；
- **组内普通行**：一律 `BiliGroupItem`；`onClick=null` 表示纯展示，右侧按钮承担明确动作；
- **音乐内容行**：一律 `TrackRow`/`SongRow`，点击必须真的播放（不能 `onClick={}` 空实现）；
  - 音乐库/统计歌曲行 → 播放所属专辑并从该曲开始；
  - 歌单详情 → 从歌单队列中该曲开始播放；
  - 当前曲目必须有 highlight（`isCurrent`/`isPlaying`）；
- **管理类卡片**（缓存/诊断等）只提供明确动作（删除/进入），不让整卡点击承载模糊行为；
- 同类卡片/行如果视觉一致但行为不同，必须在设计上能解释（例如“统计专辑卡可打开专辑，缓存卡只做管理”），
  并在新增页面 checklist 里写明。

## 5. 导航/返回/动画规则（摘要）

- 一级 Tab：瞬时切换，内容入场；三个 Tab 行为平级；
- 层级页：push 新页从右整页进入、旧页整页向左退出；pop 当前页整页向右退出、上一页从左整页回位；
  Tab↔详情也走这条；Tab↔Tab 才瞬时；**固定 500ms**；无 alpha/scale；
- 所有动画时长/曲线见 [MOTION.md](MOTION.md)；禁止匀速/LinearEasing，导航动画不跟手速联动；
- 预测式返回：Manifest 关闭 predictive back（`enableOnBackInvokedCallback=false`），
  手势只提交普通 back，由 NavHost 播放正常 pop；不做手势预演；
- 深色模式系统栏：`AppTheme` 根据 App 深色设置调整状态栏/导航栏图标明暗；
- 一级 Tab 根返回：第一次 Toast“再返回一次退出应用”，2 秒内第二次才退出；
- 播放页：迷你条 ↔ 播放页上下跟手；播放页内弹层返回优先关弹层。

## 6. 新增页面 checklist

1. **先定类型**：T1 / T2 / T3 / T4，并在路由处选对应转场；
2. **T2 用 `BiliDetailScaffold`**（T1 用 `BiliPageHeader`；T1 页头静态、列表内容入场）；
3. ViewModel 定义 `@Immutable` UiState，Screen 只收集 + 转发事件；
4. loading/error/empty 复用公共组件；一次性提示走 Snackbar + consume；
5. 列表加稳定 key + contentType；重计算放数据层/ViewModel；
6. 不新增页面级手写动画；需要新 token 先加到 `BiliMotion`；
7. 更新本文档的“当前页面”表 + docs/CHANGELOG（如涉及规范）。

## 7. 当前迁移状态（v0.12.0）

| 页面 | 类型 | 模板 |
| --- | --- | --- |
| 发现/音乐库/设置 | T1 | `BiliPageHeader` |
| 专辑详情 | T2 | `BiliDetailScaffold` + actions |
| 编辑分割点 | T2 | `BiliDetailScaffold` + actions |
| 艺术家详情 | T2 | `BiliDetailScaffold` |
| 导入 / 搜索 / 归档导入 | T2 | `BiliDetailScaffold` |
| 诊断日志 | T2 | `BiliDetailScaffold` |
| 扫码登录 | T2 | `BiliDetailScaffold` |
| 正在播放 | T3 | 页面自管 |
| 歌词视频 | T3 | 页面自管 |
| 播放列表/封面选择/确认 | T4 | ModalBottomSheet/AlertDialog |
