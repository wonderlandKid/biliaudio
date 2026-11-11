# 页面 / 组件设计系统清单（v0.24.0）

> 目标：先选“页面种类”，再选“组件种类”，最后只填内容；不再每个页面自己决定卡片/留白/字号。
> 本文是 UI 的**唯一组件目录**；新增页面先查这里，找不到合适组件再新增，不能页面内手写同类实现。
> 一致性由 `app/src/test/java/com/wonderlandkid/biliaudio/ui/UiArchitectureConsistencyTest.kt` 做源码级守卫
> （v0.31.1 起 7 条：空点击 / T2 默认壳 / 发现页标准模板 / 设置组头 / 迷你条留白 / 页面转场唯一来源 / 禁止硬编码颜色）。
> 页面清单、分类与例外登记见 [PAGE_INVENTORY.md](PAGE_INVENTORY.md)。

## 1. 页面种类（先选类型）

| 类型 | 规则 | 适用页面 | 页面壳 |
| --- | --- | --- | --- |
| **T1 一级 Tab** | 无自己的 Scaffold；有底部导航；必须 `BiliPageHeader`；内容按区块/列表组织（v0.31.1 起严格 M3，无例外） | 发现 / 音乐库 / 设置 | `BiliPageHeader` + `BiliSection` / 业务列表 |
| **T2 层级详情页** | 有返回、无底部导航；统一 push/pop；页面内系统 inset 由外层管 | 专辑详情、分曲编辑、艺术家、导入、搜索、归档导入、登录、诊断、缓存管理、我的歌单、歌单详情、听歌统计、最近播放 | `BiliDetailScaffold` |
| **T3 沉浸页** | 页面自管 inset/手势；不套 T2 模板 | 正在播放卡片（页内左右分页：封面/歌词） | `NowPlayingScreen` / `NowPlayingLyricsPane` |
| **T4 弹层/对话框** | 盖在当前页上，不参与导航转场 | 播放列表、封面选择、封面大图、确认框、新建/重命名歌单 | `ModalBottomSheet` / `AlertDialog` / `Dialog` |

### 页面清单

| 页面 | 类型 | 入口 | 页面壳 |
| --- | --- | --- | --- |
| 发现 | T1 | 底部 Tab | `BiliPageHeader` + `BiliSection` ×4 |
| 音乐库 | T1 | 底部 Tab | `BiliPageHeader` + `SearchField` + `PrimaryTabRow` |
| 设置 | T1 | 底部 Tab | `BiliPageHeader` + `BiliGroup` 卡片 |
| 专辑详情 / 分曲编辑 / 分 P 曲名编辑 / 艺术家 / 导入 / 登录 / 诊断 / 缓存管理 | T2 | 各自上游 | `BiliDetailScaffold` |
| 我的歌单 / 歌单详情 / 听歌统计 / 最近播放 | T2 | 发现页四个区块 | `BiliDetailScaffold` |
| 正在播放（封面页 ↔ 歌词页） | T3 | 迷你条上滑 | 自管（页面内左右分页，v0.28.0） |

## 2. 组件种类（再选组件）

### 2.1 页面壳 / 页头

| 组件 | 用途 | 不要再做 |
| --- | --- | --- |
| `BiliPageHeader` | T1 大标题 + 副标题 | 页面内自写大标题 |
| `BiliDetailScaffold` | T2 返回顶栏 + Snackbar + innerPadding | 手写 Row(back+title) |
| `BiliTopBar` | 复杂 T2 页只用顶栏 | 手写返回图标布局 |

### 2.2 区块 / 分组

| 组件 | 用途 | 说明 |
| --- | --- | --- |
| `BiliSection` | **页面区块**：统一左右留白 + 区块标题/操作 + 分组卡片 | 发现页四区块；不要再 `BiliGroup(modifier=Modifier.padding(horizontal=...))` |
| `BiliGroup` | 分组卡片背景/圆角/内边距/整卡点击 | 纯容器，不带页面外边距 |
| `BiliGroupHeader` | 卡片首行：22dp 图标 + bodyLarge 标题 + bodySmall 副标题 + 可选 trailing | 设置卡片、用户数据、统计汇总 |
| `BiliGroupSectionTitle` | 组外小节标题（设置页 SectionTitle） | 不要自写 titleMedium/primary 文本 |
| `BiliGroupDivider` | 组内分割线 | 不要逐条猜间距 |

### 2.3 行 / 条目

| 组件 | 用途 | 点击行为 |
| --- | --- | --- |
| `BiliGroupItem` | 分组内标准条目（leading/title/subtitle/trailing） | `onClick=null` = 纯展示；有回调 = 整行可点击 |
| `BiliAlbumRow` | 专辑行（封面 48dp + 标题/艺术家 + trailing） | 打开专辑详情 |
| `TrackRow` / `SongRow` | 歌曲行 | 必须真正播放；当前曲高亮 |
| `AlbumCompactRow` | 旧版紧凑专辑行 | 仅历史场景；新页面优先 `BiliAlbumRow` |

### 2.4 状态 / 搜索 / 媒体

| 组件 | 用途 |
| --- | --- |
| `SearchField` / `SearchBarEntry` | 可编辑搜索 / 搜索入口 |
| `LoadingBox` / `EmptyState` | 加载 / 空态 |
| `CoverImage` | 统一封面加载 + 圆角 |
| `MiniPlayerBar` | 唯一允许毛玻璃的组件 |
| `LyricLineRow` / 歌词面板等 | T3 歌词页专用，不参与页面模板 |

## 3. 设计 token

| token | 内容 | 规则 |
| --- | --- | --- |
| `BiliLayout.pageHorizontal` | 页面左右留白 16dp | 所有页面级左右 padding |
| `BiliLayout.sectionGap` / `sectionHeaderVertical` | 区块间距 | `BiliSection` 内部使用 |
| `BiliLayout.groupItemHorizontal/vertical` | 组内条目 padding | `BiliGroupItem` 内部使用 |
| `BiliLayout.leadingIconSize` | 22dp | 组头图标 |
| `BiliLayout.listCoverSize/Corner` | 48dp / 12dp | 专辑行封面 |
| `BiliLayout.contentBottomWithMiniPlayer` | 96dp | 有迷你条的列表底部 |
| `Spacing` / `Dimens` | 基础间距 / 卡片圆角 | 不要散写魔法数字 |
| `BiliMotion` + `Entrance` | 动效时长/曲线 | 见 docs/MOTION.md |

## 4. 新页面选择流程

1. 先定页面类型（T1/T2/T3/T4）；
2. 选页面壳：`BiliPageHeader` / `BiliDetailScaffold` / 自管；
3. 页面内容按区块：`BiliSection` → `BiliGroup` → `BiliGroupItem`/`BiliAlbumRow`/`TrackRow`；
4. 卡片首行用 `BiliGroupHeader`；小节标题用 `BiliGroupSectionTitle`；
5. 状态用 `LoadingBox`/`EmptyState`；搜索用 `SearchField`；
6. 页面级左右 padding 用 `BiliLayout.pageHorizontal`；有迷你条的列表底部用 `BiliLayout.contentBottomWithMiniPlayer`；
7. 不写空点击、不复制卡片、不硬编码卡片圆角/背景。

## 5. 尚未统一的部分（明确豁免）

这些不是“漏改”，而是**领域专用内容**，不属于通用 group/item 类：

- 导入页的预览/格式说明卡片、登录页二维码卡片：内容形态专用；页面壳已经是 T2 模板；
- 专辑详情的 Hero/封面大图、分曲编辑的时间轴行：专用媒体组件；页面壳/顶栏已统一；
- 播放页/歌词页：T3 沉浸页，按设计自管；
- 封面选择/播放列表等 T4 弹层：组件自身状态；
- 设置里带进度/按钮的特殊卡片（整库备份操作行、用户数据按钮区）：使用 `SettingsCard` + `BiliGroupHeader`，
  操作区是专用内容，不强行套 `BiliGroupItem`。

## 6. 自动守卫

`UiArchitectureConsistencyTest` 当前检查（194 个测试中的 5 个）：

- 详情页必须使用 `BiliDetailScaffold`；
- UI 层禁止 `onClick = {}` 空实现；
- 发现页必须用 `BiliSection`/`BiliAlbumRow`，不能恢复私有卡片；
- 设置卡片必须用 `BiliGroupHeader`，不能恢复私有首行；
- 有新迷你条的列表必须用 `BiliLayout.contentBottomWithMiniPlayer`，不能硬编码 96dp。

新增组件时，同步更新本文档与守卫测试；否则视为未完成。
