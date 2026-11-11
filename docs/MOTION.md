# 动效规范（v0.14.0）

> 所有动效统一收口到 `ui/theme/Motion.kt`。目标：**慢一点、有速度曲线、节奏统一**；
> 禁止页面内散写时长，禁止 `LinearEasing` / 匀速。
> 页面分类见 [UI_ARCHITECTURE.md](UI_ARCHITECTURE.md)。

## 1. 总原则

1. **导航类动画固定时长，不跟手势速度联动**：返回手势只提交一次普通 back，
   之后由 NavHost 播放固定时长 pop；快滑/慢滑不会让页面动画变快或变慢。
2. **交互跟手类动画例外**：播放页展开/收起、列表拖拽等由手指位置驱动（这是“跟手”，不是“自动动画”）；
   松手后的归位用 spring。
3. **全部使用曲线**：页面 push/pop 进入与退出使用**同一条 Emphasized 曲线**（只方向相反，保证对称）；
   其他显隐可按进入/退场用 Decelerate/Accelerate；空间归位用 spring；不使用线性插值。
4. 时长集中定义；新动效先加 token，再在页面引用。

## 2. 时长/曲线表

| 动效 | 时长 | 曲线 | 说明 |
| --- | --- | --- | --- |
| 顶层 Tab ↔ Tab | 0ms | - | 平级瞬时切换，只保留底部导航/内容入场 |
| 顶层 Tab 内容入场 | 320ms 起，每项 +50ms，封顶 650ms；交错 45ms | EmphasizedDecelerate | 固定页头/搜索/登录卡不动，只有列表/内容区 |
| Tab ↔ 详情页 push/pop | **500ms** | **进出都用 Emphasized（同一条曲线，仅方向相反）** | 完整宽度：详情从右进/向右出，旧页整页向左/上一页从左回位；完全对称 |
| 详情页 ↔ 详情页 | **500ms** | 同上，进出对称 | 同一套层级页转场 |
| 预测式返回预演 | 0ms | - | Manifest 关闭 predictive back；不预演 |
| 迷你播放条显示 | 280ms | EmphasizedDecelerate | 上滑 + 淡入 |
| 迷你播放条隐藏 | 220ms | EmphasizedAccelerate | 下滑 + 淡出 |
| 迷你条底部避让 | spring | damping 1.0 / stiffness 400 | 无过冲，padding 非负兜底 |
| 底部导航出现 | 300ms | EmphasizedDecelerate | 高度扩展 + 淡入；Scaffold 高度同步 |
| 底部导航消失 | 250ms | EmphasizedAccelerate | 高度收缩 + 淡出；迷你条避让同步 |
| 播放页展开/收起（手指） | 跟手 | 手指位置 | 拖动期间实时；这是交互，不是自动动画 |
| 播放页松手归位 | spring | damping 0.90 / stiffness 320 | 带初速度，有自然回弹但不甩 |
| 播放页左右分页（封面 ↔ 歌词） | 跟手 | pager 默认 fling 曲线 | 水平滑动切换；底部进度/控制固定不跟页（v0.28.0） |
| 歌词行高亮（颜色/透明度） | `BiliMotion.LyricsActiveMs`（380ms） | Emphasized | v0.35 起不再缩放字号：当前行主题色填充，非当前行分层透明度 |
| 歌词自动滚动 | `BiliMotion.LyricsScrollMs`（520ms） | Emphasized | 当前行平滑滚动到视口中心（长缓出，Apple Music 手感）；用户手动滚动后暂停 3s 再恢复 |
| 歌词上下边缘渐隐 | - | 渐变遮罩（DstIn） | 歌词在面板上下边缘淡出，避免生硬截断 |
| 迷你条→播放页连续展开（v0.34） | 跟手（无固定 duration） | 跟手插值；松手 spring(0.90/320) | `PlayerMorphLayer`：封面 Rect 连续插值 + 卡片长到全屏；播放页 alpha 55%→85%、morph alpha 88%→99.5% 交叉 |
| Ripple / 底部导航指示器 / ModalBottomSheet | Material 3 默认 | M3 默认曲线/弹簧 | 系统组件自带非线性动效，不覆盖 |

## 3. 为什么返回“看起来和滑动快慢有关”

- `enableOnBackInvokedCallback=false` 后，返回手势只在**完成时**提交一次 back；
- 真正的页面动画永远是固定 500ms 的 Emphasized 曲线，跟滑动速度无关；
- 快滑时感觉“立刻开始”，慢滑时感觉“等了一下才动”，这是手势识别差异，不是动画速度变化。

## 4. 调参入口

- 页面/局部时长：`BiliMotion.PageTransitionMs / BottomBarEnterMs / MiniPlayerEnterMs / ...`
- 列表入场：`EntranceBaseMs / EntranceStepMs / EntranceMaxMs / EntranceStaggerMs`
- 播放页弹簧：`AppNavHost` 内 `animatePlayerTo`（交互特殊，保留在调用处）
- 任何新增动画必须在本表补一行。
