# 项目现状与交接（v0.30.0）

> 最后更新：2026-11-11 · 版本 v0.30.0 · tag `v0.30.0`（release commit 以 `git rev-parse v0.30.0` 为准）；文档维护提交见 `git log docs/`
> 新会话/上下文清空后，先读本文件，再按需看 [DECISIONS.md](DECISIONS.md) / [DEBUGGING.md](DEBUGGING.md)。

## 1. 一句话

把 B 站整张专辑视频导入为「专辑 → 分曲」，支持扫码登录、BV/文本/归档导入、
按专辑/分曲播放（后台 + 通知）、在线歌词页（逐行/逐字高亮）、整库备份恢复。

## 2. 工程与产物

| 项 | 值 |
| --- | --- |
| 仓库 | `/home/wonderlandkid/code/bilibiliPlayer`（本地 git，无 remote） |
| 包名 / applicationId | `com.wonderlandkid.biliaudio` |
| App 名称 | 哔哩专辑 |
| 技术栈 | Kotlin 2.3.21 / Compose BOM 2026.09 / AGP 9.5.0 / Gradle 9.6（腾讯镜像）/ Media3 1.11.1 / Room / OkHttp / Coil / zxing |
| 最低支持 | Android 7.0（API 24），targetSdk 37 |
| 构建 | 交付/性能验收：`./gradlew :app:assembleRelease`（R8，debug 签名可安装）；开发调试用 `assembleDebug` |
| 单测 | `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest`（当前 194 个全绿） |
| APK 产物 | `app/build/outputs/apk/release/app-release.apk`（性能验收/交付，发布放置 `dist/biliaudio-vX.Y.Z-release.apk`）；`app-debug.apk` 仅开发调试 |

**环境注意**
- 本机 **GitHub 不可达**（因此 Gradle wrapper 使用 `https://mirrors.cloud.tencent.com/gradle/`；
  B 站 API / 图片 / 媒体 CDN 可直连）。
- Android Studio 曾出现「Run 报 `com.example.bilibiliplayer` Activity 不存在」：是旧包名 Gradle 模型缓存，
  处理见 [DEBUGGING.md](DEBUGGING.md)（Sync Project with Gradle Files / 改 Launch options）。
- 模拟器 AVD：`biliaudio_api36`（headless，`scripts/emulator.sh start`）；截图脚本 `scripts/screenshot.sh`。
- 真机（用户）：Xiaomi 23113RKC6C / Android 16，ADB 串号会变（曾为 `7a7ca1d5`）；真机可登录 B 站账号。

## 3. 功能矩阵

| 功能 | 状态 | 备注 |
| --- | --- | --- |
| B 站扫码登录（Cookie 持久化/校验/退出） | ✅ 实现，成功路径待真机扫码确认 | 二维码生成/轮询/过期刷新已实测 |
| BV 号/链接导入（预览、改专辑名/艺术家） | ✅ | 未分割导入=0 首（不再有占位曲） |
| 导入时粘贴分曲文本自动生成分曲 | ✅ | 三种格式：编号+歌名+时间 / 时间+歌名 / 歌名+时间，可混用 |
| 文件/文本批量导入（旧 JSON数组/CSV/文本块） | ✅ 兼容 | 统一归一化为归档模型入库 |
| 归档导入/导出 | ✅ | **v3 分域 bundle**：manifest + library + settings + covers；合并/仅更新分割点/覆盖全库（自动备份）；单专辑/分割点仍可单 JSON；v1/v2 整库不再兼容 |
| **自定义专辑封面** | ✅ | 导入预览/专辑详情可「换封面」：相册上传、网易云搜索、恢复 B 站封面；本地文件存储优先 |
| 分曲编辑（手动/播放打点/文本导入/排序/保存） | ✅ | 未分割专辑显示「未分割」空态 |
| 播放（ExoPlayer 分曲裁剪、专辑连续、单曲点播） | ✅ | 点歌/专辑播放=单专辑队列；「播放全部歌曲」=全库 |
| 播放列表（当前专辑分曲展开、跳转） | ✅ | NowPlaying 播放列表面板 |
| 后台播放 + MediaSession 通知 + 锁屏控制 | ✅ | Android 13+ 通知权限已申请 |
| **在线歌词（SPICaMusic 方案）** | ✅ | 与播放页合一：封面页 ↔ 歌词页左右滑动（无「看歌词」按钮）；在线多来源匹配 + Room 缓存 + 来源选择/时间偏移 + 逐行/逐字高亮 + Apple Music 式平滑滚动 |
| 专辑音频离线缓存（按分 P） | ✅ | 多 P 按 `sourceCid` 逐 P 缓存/补缓存；缓存管理按专辑聚合；v0.27.0 起只缓存音频（视频缓存已移除） |
| 音乐库歌曲/专辑/艺术家搜索、最近播放 | ✅ | 分曲逐首显示、可搜索 |
| **音乐库三分栏 + A-Z 快速索引** | ✅ | 歌曲/专辑/艺术家；拼音分组、索引拖动定位、拖动气泡 |
| **艺术家详情页** | ✅ | 封面取第一张专辑、专辑网格 + 歌曲列表 |
| **迷你播放条手势** | ✅ | 上滑打开播放页、下滑收起为细条、收起后点击/上滑展开 |
| **统一页头与导航转场** | ✅ | 三 Tab 标题位置一致；详情横向共享轴、播放页底部上滑、Tab 淡入淡出 |
| **归档目录形态（艺术家/专辑/分割）** | ✅ | 设置页导出到文件夹/从文件夹导入；兼容单文件 JSON |
| **维测日志开关** | ✅ | 设置→诊断与维护→「维测日志」Switch；关闭后不再记录/打印，持久化，历史日志可查看 |
| **列表滚动性能优化** | ✅ | 播放 tick 不重建列表；索引栏读状态隔离；Lazy contentType/稳定 key |
| 浅色/深色主题（Material 3 扁平） | ✅ | material-kolor 动态色板；无液态玻璃 |
| 诊断日志（查看/复制/导出/清空） | ✅ | tag `BiliDiag`；设置 → 诊断与维护 |
| 旧版占位曲自动清理 | ✅ | 启动时迁移旧数据 |
| 多 P 视频自动分曲 | ✅ | v0.25.0 起每个分 P 一首；v0.27.0 起音频缓存也按分 P |
| YAML 只读导入 | ❌ 未做（M3 可选） | 当前只 JSON |
| B 站收藏夹导入 / 自动静音切分 / EQ | ❌ 未做 | 路线图 |

## 4. 版本记录（详情见 CHANGELOG）

- **v0.30.0** 直接重置回 v0.28.0 基线（Material 3 扁平 UI），放弃 v0.29.0 视觉重设计；歌词页功能保留、视频歌词保持移除；不采用 revert 补丁以避免副作用
- **v0.28.0** 歌词并入播放页（封面页 ↔ 歌词页左右滑动，去掉「看歌词」按钮）+ Apple Music 式平滑歌词滚动；多 P 曲名清洗 + 歌词候选补搜
- **v0.27.0** 移除「看歌词视频」（视频播放/视频缓存全部删除，缓存只留歌曲并按分 P）；在线歌词（SPICaMusic 方案：多来源 API + LRC/YRC/AMLL + Room 缓存 + 歌词页）；Room v6
- **v0.1.0** 首版：登录/导入/分曲/播放/歌词页/音乐库/设置，模拟器+真机走查通过
- **v0.2.0** 音乐库归档：统一 JSON 备份/恢复/分割点文件，覆盖全库自动备份
- **v0.3.0** 文本识别分割点（三种格式）、未分割导入不再生成占位曲、歌词视频清晰度修复（选流）
- **v0.4.0** 诊断日志（Diag + 设置页）、点歌单专辑队列、歌词页加载阶段/重试、720P 上限、旧占位曲清理
- **v0.5.0** 自定义专辑封面（本地上传/网易云搜索/恢复B站/归档内嵌）、歌词页加载体验修复（封面占位、单次延迟提示、校正节流）
- **v0.6.0** 三主页面统一页头/导航转场重做、迷你播放条上/下手势、音乐库三分栏+#A-Z索引+艺术家详情、归档目录形态（顶层/艺术家/专辑/分割）
- **v0.6.1** 音乐库滚动性能修复（播放 tick/索引栏/列表 contentType）、搜索框 UI 统一、Tab 切换闪白修复、维测日志开关
- **v0.6.2** 全局动效按 Material Design 3 Motion 规范重写：BiliMotion token、Fade through/Shared axis X/Y/Fade、M3 弹簧、移除页面内叠加动画；导航壳播放状态订阅隔离
- **v0.6.3** 列表卡顿根因定位：debug 包无 R8；交付改为 release（R8+debug 签名），关键 UI 状态加 @Immutable；
  真机滚动 90/95/99th 从 53/69/93ms → 8/8/11ms，janky 7.14% → 0.12%
- **v0.7.0** 动效节奏放缓（400ms/24% 对称位移）、迷你条 Haze 毛玻璃 + 跟手拖拽、去掉 handle/亮线；Haze 2.0.1
- **v0.8.0** 全导航改为纯左右滑动（无淡入淡出）、播放页改为迷你条跟手连续展开的覆盖卡片（playerProgress 0→1 + spring）
- **v0.8.1** 修复转场重影（不透明 Page + 整页推挤）、迷你条黑边与更深模糊、播放页顶部栏下滑关闭
- **v0.8.2** 参考 SPICaMusic_Android 统一导航：一级 Tab 瞬时切换（无滑动），层级页面统一 push/pop（右进右出、旧页 1/4 视差、无淡入/缩放）
- **v0.8.3** 一级 Tab 内容入场动画（Entrance.kt）、迷你条底部 spring 过渡；确认分割时间支持 01:22:23（新增单测）
- **v0.8.4** 修复 v0.8.3 闪退（负 padding）、入场动画初始进度 bug、predictive back 默认缩小淡出（全局覆盖返回转场）
- **v0.26.1** 全局底部安全区：迷你条/IME 不再遮挡最后内容
- **v0.26.0** 导入→专辑导航/缓存 IO 修复；Snackbar 避让迷你条；多 P 曲名编辑页
- **v0.25.0** 多 P BV 导入 + Room v5 分 P 来源 + 播放前预解析队列无缝切歌
- **v0.24.0** 设计系统目录 + BiliSection/GroupHeader/AlbumRow + BiliLayout token + 5 个源码级 UI 守卫测试
- **v0.23.1** 发现页区块边距/顺序修正；统计预览支持专辑时长/歌曲次数切换并持久化
- **v0.23.0** 发现页四区块化（无搜索栏/随机专辑/最近播放详情 10 张）+ 设置卡片首行统一
- **v0.22.1** 一致性审计：同类 group/item 点击行为、播放高亮、迷你条反馈、字号/间距统一
- **v0.22.0** 歌单/统计独立 JSON 备份（不混入音乐库归档）；BiliGroup/BiliGroupItem 分组模板统一
- **v0.21.0** 自建歌单 + 听歌统计（发现页入口，音乐库保持纯搜索；Room v4）
- **v0.20.0** 缓存管理页：设置 → 存储；总占用/逐专辑/单条删除/清空全部
- **v0.19.0** 视频缓存自动补音频（修复只缓存视频无法离线听歌）；播放页封面放大；全局按压水波纹
- **v0.18.0** 歌词视频缓存；视频页白边/全屏横屏/导入 B 站链接/播放指示动效/暂停封面缩放（TODO）
- **v0.17.0** 专辑音频离线缓存（手动缓存整张专辑，缓存后离线播放；仅音频，不含视频）
- **v0.16.0** 迷你条上一首；歌词视频改为播放页上方覆盖层（不再闪父页面/迷你条）
- **v0.15.0** 修复音乐库 Tab 详情转场；页面进出完全对称；底部导航平滑出现/消失
- **v0.14.0** 全量动效放慢+曲线化（页面 500ms、列表入场 650ms 封顶），导航动画固定时长不跟手速；新增 MOTION 文档
- **v0.13.1** 关闭系统预测式返回（避免反向扫动/快速浮现），返回动画统一走普通 pop；封面菜单移除重复入口
- **v0.13.0** Tab↔详情完整 push/pop 转场修复；深色状态栏；歌词视频 1080P；封面大图/保存/换封面入口
- **v0.12.1** Tab 入场统一：固定页头/静态元素不动，只列表/内容区入场
- **v0.12.0** UI 页面分四类并模板化（BiliDetailScaffold/BiliTopBar），T2 页面统一迁移；新增 UI_ARCHITECTURE 文档
- **v0.11.0** 预测返回不预演、一级 Tab 内容入场统一、Tab 平级返回 + 连续两次退出
- **v0.10.0** 归档/配置 v3 分域 bundle（manifest/library/settings/covers）；维测日志默认关闭；控制中心用自定义封面
- **v0.9.1** 归档单文件上限 5MB→64MB（不改 schema；大库用目录归档）
- **v0.9.0** 归档数据层 v2：DB v3（source + (source,bvid,page) 唯一键 + tracks.uid）、v2 schema/kind/features/cover hash/空语义；
  v1 归档不再兼容，旧快捷导入文本仍归一化为 v2

## 5. 最近验证状态（截至 v0.30.0）

- 单元测试 **192 / 0 失败**（wbi、批量/归档解析、导入模式、播放映射、音频缓存逻辑、歌词 LRC/YRC/AMLL 解析与匹配、曲名清洗、UI 架构守卫等）。
- 模拟器端到端已验证：
  - 真实导入 `BV1GJ411x7h7`（3:33）与 `BV1sp4y1B7bH`（59:25，13 分 P）；
  - 用「格式 1」14 首文本导入 → 14 首、播放第 2 首（03:57）专辑进度映射正确；
  - 音乐库歌曲逐首显示（4+14=18 行）；点歌后播放列表面板只显示该专辑 14 首；
  - 归档导出/仅更新分割点/覆盖全库（自动备份+恢复）全流程；
  - 歌词页加载日志：resolve 缓存命中 1ms、首帧 3.4s（未登录 480P，模拟器软解）；
  - 诊断日志页面、复制/导出/清空 UI。
- v0.5.0 新增验证：
  - 真机（Xiaomi）v0.5.0：Room v1→v2 迁移后数据完好；循环开关歌词页 3 次录屏=每次仅「封面占位→视频」一次切换，首帧 289~341ms，快速加载无 spinner/提示；
  - 真机实测自定义封面全链路：网易云搜索 22 条结果 → 选封面 → 下载 140KB/保存 JPEG → 专辑详情/首页卡片同步；
  - 真机导入预览换封面 → 导入（未分割 0 首）→ 封面在导入后异步应用成功；「恢复 B 站原始封面」日志/UI 均生效；
  - 模拟器 v0.4→v0.5 安装升级：18 首/2 专辑数据保留（迁移正常）；
  - 弱网/慢 CDN 场景：覆盖层延迟 500ms 只出现一次，READY 后中途 re-buffer 不再弹提示。
- v0.6.0 模拟器验证（真机本轮断开，未真机回归）：
  - 三主页面标题 bounds 完全一致 `(42,95)-(1038,231)`；
  - 音乐库 3 Tab 正常，字母索引栏 # A-Z/分组/高亮/点击拖动跳转；艺术家 Tab 拼音分组（Fallious→F、索尼音乐中国→S）；
  - 艺术家详情（圆封面/统计/专辑/歌曲）路由与返回正常；
  - 迷你条：上滑→播放页（底部上滑转场）、下滑→收起细条、点击/上滑→展开，截图确认；
  - 专辑详情进入/返回为横向推入/退出，无缩放；
  - 设置页目录导出到 SAF 文件夹成功生成 `biliaudio-library.json` + `Fallious/.../album.json+segments.json` + `索尼音乐中国/...`；从文件夹导入（合并）结果「新建 0 · 更新 2 · 分曲更新 0 · 跳过 0 · 失败 0」。
- v0.8.0 模拟器验证：迷你条慢速上滑时播放页卡片连续升起（录屏 `docs/screenshots/v08_player_sheet_drag.png`），
  松手展开、返回收起；Tab/详情页为纯左右滑动无淡入淡出。
- v0.7.0 模拟器验证：迷你条改为悬浮内容层（位于底部导航之上）、Haze blur fallback 可读、无 handle、上滑跟手拖动（录屏）、
  下滑收起细条、动效 400ms/24% 位移；截图 `docs/screenshots/v07_*.png`。
  （真机本轮被 PIN 锁屏无法操作，待解锁后复测毛玻璃真机 RenderEffect 与手感）
- v0.6.3 真机（Xiaomi 23113RKC6C / Android 16）滚动对比（同一手势脚本、音乐库列表）：
  debug v0.6.2：238 frames / janky 7.14% / 90-95-99th 53-69-93ms / Slow UI 15 / Missed Vsync 9；
  release v0.6.3：838 frames / janky 0.12% / 90-95-99th 8-8-11ms / Slow UI 0 / Missed Vsync 0。
  进一步对齐参考项目：release R8 + 关键模型 @Immutable；baseline profile 暂未引入（作为后续可选优化）。
- v0.6.2 模拟器验证（录屏逐帧，截图 `docs/screenshots/v062_motion_*.png`）：
  - Tab 切换为 Material Fade through（旧页淡出→新页 92%→100% 淡入），无两页不透明叠影；
  - 详情页为 Shared axis X（30dp 平移 + 100/200ms 淡入淡出），无长时间整页空白；
  - 播放页 Shared axis Y、歌词页 Fade；迷你条收起/展开为 M3 spatial 弹簧；
  - 移除 PageEnterAnimation，避免导航转场与页面内动画叠加；AppNavHost 不再订阅播放状态（性能）。
- v0.6.1 模拟器验证：
  - 发现/音乐库搜索框视觉一致（pill 胶囊、同高度/图标/占位，截图对比）；
  - Tab 切换录屏逐帧：旧页+新页重叠过渡，无全白空白帧；PageEnterAnimation 只做上浮不淡入（避免 alpha=0 闪白）；
  - 维测日志开关双向验证：关闭 → 重启后 `logcat -s BiliDiag` 0 行；重新开启 → 启动日志恢复；
  - 列表性能 gfxinfo：90/95/99th 37/44/59ms → 32/35/46ms，Slow UI thread −60%，Missed Vsync 2→0
    （headless 软渲染绝对值仅作相对参考）；根因验证模拟 250ms tick 下 uiState 重建 ≥30 次 → 0 次。
- **真机（用户 Xiaomi）历史验证**：登录、导入、播放、歌词页 HEVC 硬解、通知均正常（v0.2 时代）。
- v0.27.0 真机（Xiaomi 23113RKC6C / Android 16，串号 `7a7ca1d5`）验证：
  - Room v5→v6 自动迁移：原 110 首 / 7 专辑数据完好；release（R8）包安装运行正常；
  - 播放页「看歌词」→ 歌词层从右滑入（播放页保持展开、迷你条隐藏），返回优先关歌词页；
  - 在线自动匹配成功（Beatles「Come Together」按标题+时长选中 04:19 版本）：当前行居中高亮、已唱/未唱分层、
    翻译行、自动滚动、点击行跳转均正常；
  - 偏移面板 +0.1s 立即生效并持久化；来源面板候选/打勾/重新搜索/本地导入/关闭歌词/恢复自动匹配全链路正常；
  - release（R8）下在线歌词搜索与解析正常，HTTP 歌词域名经 `network_security_config` 放行生效；
  - 截屏：`docs/screenshots/v027_00_nowplaying_lyrics_entry.png`、`v027_01..06_lyrics_*.png`。
- v0.28.0 模拟器（biliaudio_api36，1080x1920 @420dpi，headless）验证：
  - 播放页无「看歌词」按钮；左右滑动在封面页 ↔ 歌词页之间切换，底部进度/控制固定；
  - 歌词页自动在线匹配（多 P 曲名清洗后命中）、当前行放大/着色居中、翻译行、上下渐隐遮罩；
  - 逐行平滑滚动（520ms Emphasized）：录屏逐帧看到中间过渡位置，无跳变；
  - 点歌词行跳转、点指示器切换、歌词页按返回先回封面页、来源/偏移面板；
  - 截屏：`docs/screenshots/v028_*.png`。
- v0.30.0 基线回退验证：194 单测全绿；`:app:assembleRelease` 成功；release 包安装到模拟器
  （biliaudio_api36，1080×1920 @420dpi）验证 Light/Dark 首页、专辑详情、播放页封面页、歌词页（右滑）正常，
  播放/切歌/进度拖动/歌词来源均可用。
- **未验证/待用户配合**：
  1. 扫码登录成功后的完整链路（头像/昵称、高清流）——需要真实账号扫码；
  2. 本地相册上传封面在 MIUI 系统相册选择器的完整 UI 走查（代码路径已实现，在线封面/恢复 B 站已真机验证）；
  3. 归档「自定义封面」端到端 UI 走查（数据层单测已覆盖 cover 对象导出/导入）；
  4. 歌词逐字 KaraOK（在线源当前为 LRC 行级时间轴）：需本地导入 YRC/TTML 或逐字歌词源后再真机复验；
  5. 多 P 专辑「逐 P 缓存 + 断网离线播放」端到端（逻辑/单测已覆盖，真机多 P 离线未走查）。

## 6. 待办 / 下一步（按优先级）

1. **P1** 真机 v0.6.3 回归（release 包）：滑动/动画/归档 SAF/维测日志开关；**以后性能结论一律用 release 包**。
2. **P2** 可选：引入 baseline profile（参考 SPICaMusic_Android 的 Macrobenchmark 模块）进一步优化启动/首屏。
3. **P1** 本地相册上传封面 + 归档封面恢复的真机 UI 走查（数据层已测；Photo Picker 分支未端到端）。
4. **P1** 真机扫码登录成功路径 QA（头像昵称、音质/画质提升）。
5. **P2** 网易云封面接口为**非官方接口**，未来可能变更/限流；失败时静默降级（只影响在线搜封面），
   可后续加备用源（如 QQ 音乐/豆瓣）或内置占位图选择。
6. **P2** 在线歌词第三方 HTTP 服务可用性：base URL 可配置 + 失败降级（本地导入/重试）；接口失效不影响播放。
7. **P2** 音乐库大数据量（数千首）索引滚动性能与分组重算（当前用 remember 缓存，需实测）。
8. **P2** 歌词增强：迷你条单行歌词（MiniLyric）、逐字 YRC/TTML 真机复验、自定义搜索关键词。
9. **P3** 路线图：B 站收藏夹导入、后台缓存下载/断点续传/Wi-Fi-only、自动静音切分、YAML 只读导入、画质设置项。

## 7. 关键设计约束（务必遵守）

- **虚拟歌曲模型**：Track = 专辑音频流上的 `[startMs, endMs)` 切片，**不做独立音频实体**；
  单曲不设独立艺术家/封面，继承专辑（用户已确认「完全没必要做成实体」）。
- **播放队列语义**：从专辑/音乐库点某曲 → 队列=该曲所属专辑的分曲；「播放全部歌曲」才全库。
- **未分割专辑**：0 首曲目，播放时虚拟整张一首；旧版占位曲启动自动清理。
- **归档格式**：一个 `.biliaudio.json` 承载整库/单专辑/分割点；只 JSON；导入模式 merge/segments-only/replace；
  replace 自动备份到 `files/backups/`（保留 10 份）。
- **歌词（v0.28.0）**：在线多来源歌词 API（按曲名搜索，不传 UP 主艺术家；多 P 曲名去「专辑_01_」前缀，
  匹配不到补搜原始标题）→ 候选按标题+时长匹配 → Room v6 `lyrics` 按 `Track.uid` 存原文；解析 LRC/YRC/AMLL-TTML/纯文本。
  歌词是「正在播放」页内左右滑动的第二页（封面页 ↔ 歌词页，非路由/非覆盖层），底部进度/控制固定；
  歌词是设备本地缓存，不进归档/备份；在线接口为 HTTP，仅放行该域名（network_security_config）。见 [LYRICS.md](LYRICS.md)。
- **离线缓存（v0.27.0）**：只缓存音频，按分 P（`bvid+cid` key）；多 P 专辑逐 P 缓存/补缓存；视频缓存/视频播放已移除。
- **自定义封面**：`Album.coverLocalPath` 优先于远程 `coverUrl`；封面文件存 `files/covers/`（JPEG，长边≤1080）；
  在线来源为网易云专辑搜索（非官方接口）；归档用 `cover` 对象（MIME/SHA-256/字节）内嵌封面；删除专辑时清理封面文件。
- **UI 规范**：Tab 页统一 `BiliPageHeader`；转场在 `AppNavHost` 统一定义（Tab fade、详情横向共享轴、播放页底部上滑）；
  迷你条上滑打开播放页、下滑收起为细条。
- **列表性能**：播放状态必须 `map{PlaybackHighlight}.distinctUntilChanged()` 后再 combine；
  Lazy 列表用 contentType + 稳定 key；索引栏高亮在组件内部读取（provider），父级不读滚动派生状态。
- **搜索框**：统一 `SearchField`（pill 胶囊）；发现页用 `SearchBarEntry`（同一视觉、点击跳转）。
- **动效（v0.6.2）**：全部 token 在 `ui/theme/Motion.kt`；顶级 Tab = Fade through、层级页面 = Shared axis X、
  播放页 = Shared axis Y、沉浸页 = Fade；尺寸变化用 M3 spatial 弹簧；禁止页面内叠加自定义进入动画。
- **归档双形态**：单文件 `.biliaudio.json`（分享/旧备份）与目录树（顶层/艺术家/专辑/album.json+segments.json，
  可浏览可编辑；REPLACE 有防误删保护）。
- **视觉**：Material 3 扁平、大圆角、动态色板；**禁止液态玻璃/毛玻璃**。
- **日志**：技术链路打点统一走 `util/Diag`（不要打印 Cookie 等敏感信息）。

## 8. 代码地图（快速定位）

```
app/src/main/java/com/wonderlandkid/biliaudio
├── BiliAudioApp.kt / MainActivity.kt
├── di/AppContainer.kt                 手写 DI（所有仓库/播放器装配）
├── data/
│   ├── model/Models.kt                 Album/Track/...（契约）
│   ├── model/LibraryArchive.kt         归档格式模型
│   ├── repository/Repositories.kt      仓库接口（契约）
│   ├── repository/LibraryArchiveRepository.kt
│   ├── net/BiliApi.kt                  B站 API、wbi、扫码、playurl、搜索
│   ├── net/WbiSigner.kt / StreamSelector.kt / CookieStore.kt / LyricsApiClient.kt
│   ├── lyrics/                         歌词解析（LRC/YRC/AMLL）、格式识别、候选匹配、曲名清洗
│   ├── db/                             Room（albums/tracks/playlists/track_stats/lyrics，v6）
│   └── impl/                           仓库实现 + BatchImportParser + SplitTextParser +
│                                       LibraryArchiveCodec / LibraryArchiveRepositoryImpl + AudioCacheLogic + Diag 打点
├── player/
│   ├── PlaybackEngine.kt               单 ExoPlayer + ClippingMediaItem 队列（Diag tag Play）
│   ├── PlaybackService.kt              MediaSessionService/通知
│   └── PlaybackMapping.kt              队列/裁剪/进度映射（纯函数 + 单测）
├── ui/
│   ├── theme/ · components/            设计系统与通用组件（集成层）
│   ├── navigation/AppNavHost.kt        导航与底部栏/迷你播放条
│   ├── home/ library/ album/           发现、音乐库、专辑详情、分曲编辑（含文本导入）
│   ├── imports/                        BV/搜索/归档导入、分割点文本识别预览
│   ├── login/ settings/                扫码登录、设置、备份恢复、诊断日志
│   └── player/                         正在播放（封面/歌词左右分页）、播放列表面板、歌词组件（lyrics/）
└── util/Diag.kt                       诊断日志（内存环形 + Logcat BiliDiag）
```

## 9. 验证用测试数据（便于复现）

| 用途 | 数据 |
| --- | --- |
| 短专辑（3:33，1 个分 P） | `BV1GJ411x7h7`（Never Gonna Give You Up） |
| 长专辑（59:25，13 个分 P，用于分曲/长进度） | `BV1sp4y1B7bH`（【黑胶试听合輯】周杰伦_葉惠美） |
| 长专辑（47:13，用于导入换封面） | `BV1Hj411t7Zn`（The Beatles - Abbey Road 整轨） |
| v0.6 索引测试 | 音乐库现有 2 专辑/18 分曲（Fallious→F、索尼音乐中国→S 验证拼音分组） |
| 分曲文本样例 | [docs/samples/tracklist-formats.sample.txt](samples/tracklist-formats.sample.txt)（三种格式真实样例） |
| 归档样例 | [docs/samples/biliaudio-library-full.sample.json](samples/biliaudio-library-full.sample.json)（library.json）、[biliaudio-segments.sample.json](samples/biliaudio-segments.sample.json)、[biliaudio-settings.sample.json](samples/biliaudio-settings.sample.json)、[biliaudio-archive-manifest.sample.json](samples/biliaudio-archive-manifest.sample.json) |
| 模拟器 | `scripts/emulator.sh start` / `scripts/screenshot.sh <name> <wait>` |

## 10. 文档索引

- [AGENTS.md](../AGENTS.md)（根目录入口）
- [DESIGN.md](DESIGN.md)（初版设计，含当前状态更新）
- [DECISIONS.md](DECISIONS.md)（设计决策）
- [DEBUGGING.md](DEBUGGING.md)（排障）
- [CHANGELOG.md](CHANGELOG.md)（版本）
- [API_NOTES.md](API_NOTES.md)（B 站接口）
- [LIBRARY_ARCHIVE.md](LIBRARY_ARCHIVE.md)（归档格式）
- [OFFLINE_CACHE.md](OFFLINE_CACHE.md)（音频缓存）
- [LYRICS.md](LYRICS.md)（在线歌词方案）
- [IMPORT_FORMATS.md](IMPORT_FORMATS.md)（导入/分割点文本格式）
