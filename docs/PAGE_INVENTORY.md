# 页面清单与模板约束（v0.31.0）

> 本文回答三件事：**现在有哪些页面、它们怎么分类、模板该管到什么程度**。
> 它是 [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md)（骨架/转场/insets）与
> [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)（组件/token 目录）之间的「页面登记表」。
>
> 约定：**新页面必须先登记到本文，再写代码**；未登记 = 页面架构不合规。

## 1. 统计总览（v0.31.0）

- **导航目的地 17 个**：3 个一级 Tab + 14 个层级详情路由（12 个 Screen 文件，导入页一个 Screen 承载 3 条路由）。
- **沉浸覆盖层 1 个**：正在播放（不在 NavHost 里；页内左右分页：封面页 / 歌词页）。
- **T4 弹层**：6 个 ModalBottomSheet 使用点（播放队列、封面选择、歌词来源、歌词偏移等）+ 8 个页面的对话框（新建/重命名歌单、删除专辑、封面大图、文本导入、编辑分割点等）。
- **Screen 文件总数 16**：3 个 T1 + 12 个 T2 + 1 个 T3。
- 另有 2 个「 chrome 组件」不是页面：`MiniPlayerBar`（Haze 毛玻璃悬浮条）、`BiliBottomBar`（底部导航）。

## 2. 页面清单

### T1 一级 Tab（3 个）

| 页面 | 路由 | 壳 | 内容结构 | 备注 |
| --- | --- | --- | --- | --- |
| 发现 | `home` | **自定义刊头**（例外，见 §5） | 封面故事 hero + 横向封面墙 + 榜单卡 + 歌单卡 + 双列专辑网格 | v0.31.0 起允许页面专属版式 |
| 音乐库 | `library` | `BiliPageHeader` | 搜索 + 歌曲/专辑/艺术家三分栏 + A-Z 索引 | 默认模板 |
| 设置 | `settings` | `BiliPageHeader` | 分组卡片（`BiliGroup`/`BiliGroupHeader`） | 默认模板 |

### T2 层级详情页（12 个 Screen / 14 条路由）

| 子类 | 页面 | 路由 | 入口 | 内容结构 |
| --- | --- | --- | --- | --- |
| **内容详情** | 专辑详情 | `album/{albumId}` | 发现/音乐库/艺术家/歌单/队列 | 封面 + 信息 + 播放全部 + 曲目列表 + 更多菜单 |
| | 艺术家详情 | `artist/{artistName}` | 音乐库 | 艺术家名 + 专辑列表 + 歌曲列表 |
| | 歌单详情 | `playlist/{playlistId}` | 发现/我的歌单 | 歌单信息 + 曲目列表 |
| **汇总列表** | 我的歌单 | `playlists` | 发现/设置 | 歌单列表 + 新建 |
| | 听歌统计 | `stats` | 发现 | 统计切换 + 排行列表 |
| | 最近播放 | `recent_albums` | 发现 | 最近播放专辑列表（最多 10） |
| **导入/恢复** | 导入专辑（BV / 搜索 / 归档 三个 Tab 共用） | `import` / `search` / `import_archive` | 发现/设置 | Tab 切换 + 输入/预览/封面/分曲文本/确认 |
| **编辑** | 分曲编辑 | `split/{albumId}` | 专辑详情 | 分割点列表 + 工具条 + 保存 |
| | 多 P 曲目标题编辑 | `multi-page-tracks/{albumId}` | 专辑详情（多 P） | 分 P 标题批量编辑 |
| **系统/账号** | 登录 | `login` | 发现/设置/导入 | 扫码登录 + 登录态 |
| | 诊断日志 | `diagnostics` | 设置 | 日志开关 + 日志列表 |
| | 缓存管理 | `cache_management` | 设置 | 缓存统计 + 列表 + 清理 |

### T3 沉浸页（1 个）

| 页面 | 形态 | 壳 | 结构 |
| --- | --- | --- | --- |
| 正在播放 | 覆盖层卡片（不在 NavHost） | 页面自管 insets/手势 | 顶栏 + 左右分页（封面页 / 歌词页）+ 固定进度/控制 + 播放队列 Sheet |

### T4 弹层/对话框

- **ModalBottomSheet（6 个使用点）**：播放队列（`PlaylistPanel`）、封面选择（`CoverPickerSheet`）、歌词来源（`LyricsSourceSheet`）、歌词偏移（`LyricsOffsetSheet`）、以及页面内直接使用的 sheet（播放页队列、导入相关）。
- **Dialog/AlertDialog（8 个页面）**：删除专辑、封面大图、文本导入分割点、编辑分割点、新建/重命名歌单、导入取消确认、诊断清空日志、缓存清空等。
- 约束：优先复用现有组件；**不要每个页面复制一套 sheet/dialog 配置**；弹层不参与导航转场。

## 3. 模板约束分级（v0.31.0）

把「模板」拆成三层，避免一刀切：

### L0 全局强制（不可变，违反即 bug）

1. 导航层级/路由、返回行为、页面转场只在 [AppNavHost](../app/src/main/java/com/wonderlandkid/biliaudio/ui/navigation/AppNavHost.kt) 定义
   （Tab 瞬时、详情 push/pop 500ms、播放页跟手、歌词分页）；
2. 系统栏 insets 由 AppNavHost 外层 Scaffold 统一处理；页面不重复加 statusBar padding；
3. 迷你条可见时列表底部用 `BiliLayout.contentBottomWithMiniPlayer`（或全局 inset），不硬编码避让；
4. 每个页面必须处理 loading / empty / error；音乐内容行点击必须真正播放并高亮；禁止空 `onClick={}`；
5. 颜色只用 `MaterialTheme` 语义色（禁止硬编码 0xAARRGGBB）；动效只用 `BiliMotion` token；
6. 触控目标 ≥48dp、文字对比度按 Material 基线（Light/Dark 都必须可读）。

### L1 默认页面壳（建议，新页面默认用；例外要登记）

| 类型 | 默认壳 |
| --- | --- |
| T1 | `BiliPageHeader` |
| T2 | `BiliDetailScaffold`（返回 + 标题 + actions + Snackbar） |
| T3 | 页面自管（沉浸 insets/手势） |
| T4 | 复用现有 `ModalBottomSheet`/`AlertDialog` 组件 |

### L2 页面内部（自由）

页面可以自由决定：区块/列表/网格/横向封面墙/hero 版式、页面专属入口动画、页面私有 Composable
（例如发现页的刊头、封面故事、榜单卡）。只要满足 L0 与「不复制已有共享组件」。

## 4. 模板限制审查（v0.31.0：放宽了什么、收紧了什么）

### 4.1 原先限制过紧、已放宽

| 原规则 | 问题 | v0.31.0 调整 |
| --- | --- | --- |
| T1 必须 `BiliPageHeader` | 发现页无法做杂志式刊头 | 允许页面自定义刊头（登记为例外），仍不自己套 Scaffold |
| 发现页必须 `BiliSection` + `BiliAlbumRow`（守卫强制） | 强制发现页只能做「标题+列表」，阻止封面墙/网格 | 改为语义守卫：必须有横向/网格等非列表版式 + 使用共享封面组件 |
| 「页面内禁止私有 SectionCard/Header」 | 页面专属 hero/封面故事并不是通用卡片 | 允许页面专属视觉块；禁止复制已有共享组件 |
| T1 「固定页头不做入场，只有内容入场」 | 发现页不能整体做版式入场 | 允许发现页自定义入场（必须用 `BiliMotion` token） |
| `BiliLayout.pageHorizontal` 全站固定 | 封面 story 无法轻微出血 | 允许发现页 hero 右侧出血等页面级视觉例外（登记） |
| T2 「一律 `BiliDetailScaffold`，禁止自定义顶栏」 | 未来详情页可能要做沉浸式头图 | 默认仍用；**允许经批准登记例外**（当前无 T2 例外） |

### 4.2 收紧了什么

1. **页面登记制**：新页面必须先在本文件登记类型/壳/入口/数据/空态/例外；
2. **转场唯一来源**：新增守卫，页面文件出现 `enterTransition`/`popEnterTransition` 直接测试失败；
3. **颜色无硬编码**：新增守卫，`ui/` 下（theme 除外）出现 `Color(0x...)` 直接测试失败；
4. **T2 默认壳仍是强约束**：12 个详情页必须用 `BiliDetailScaffold`（守卫保持）；
5. **发现页必须保持「非纯列表」**：守卫要求 `LazyRow` 或双列网格 + 共享封面组件；
6. **设置卡片首行/迷你条留白**：原守卫保留。

### 4.3 守卫清单（`UiArchitectureConsistencyTest`，v0.31.0 共 7 条）

1. 禁止空 `onClick={}`；
2. 12 个 T2 页面必须使用 `BiliDetailScaffold`；
3. 发现页保持大胆非列表版式（横向/网格 + 共享封面组件）；
4. 设置卡片首行必须 `BiliGroupHeader`；
5. 列表底部必须用 `BiliLayout.contentBottomWithMiniPlayer`；
6. 页面级转场只能在导航层定义；
7. 页面/组件不得硬编码颜色。

## 5. 例外登记表

| 页面 | 例外内容 | 原因 | 登记版本 |
| --- | --- | --- | --- |
| 发现 | 自定义刊头（代替 `BiliPageHeader`）；封面故事 hero、横向封面墙、榜单卡、双列网格；页面专属入场 | 用户明确要求发现页「大胆一点，不要一直用列表」 | v0.31.0 |
| 正在播放 | T3 沉浸页：自管 insets、顶部栏、手势 | 播放器需要全屏沉浸 | 既有 |

> 例外只允许「页面视觉/版式」层面；L0 的导航、转场、insets、播放行为与无障碍约束不允许例外。

## 6. 新页面 checklist

1. 在本文 §2 登记：类型（T1/T2/T3/T4）、路由、入口、页面壳、内容结构、例外（如有）；
2. 用 [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md) 的骨架接线（只在 AppNavHost 加路由/转场）；
3. 默认壳：T1 → `BiliPageHeader`；T2 → `BiliDetailScaffold`；T3 自管；T4 复用现有弹层；
4. 页面内布局自由，但用共享组件（`CoverImage`/`TrackRow`/`BiliGroup`/`EmptyState`…）与 token（`Spacing`/`BiliLayout`/`BiliMotion`）；
5. loading/empty/error 三态齐全；内容行可播放可高亮；
6. 需要新例外 → 先在本文 §5 登记并在 PR/提交说明里写原因；
7. 跑全量单测（含 `UiArchitectureConsistencyTest`），Light/Dark 各走一遍。
