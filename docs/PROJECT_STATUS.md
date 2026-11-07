# 项目现状与交接（v0.6.2）

> 最后更新：2026-11-08 · 版本 v0.6.2 · tag `v0.6.2`（release commit 以 `git rev-parse v0.6.2` 为准）；文档维护提交见 `git log docs/`
> 新会话/上下文清空后，先读本文件，再按需看 [DECISIONS.md](DECISIONS.md) / [DEBUGGING.md](DEBUGGING.md)。

## 1. 一句话

把 B 站整张专辑视频导入为「专辑 → 分曲」，支持扫码登录、BV/文本/归档导入、
按专辑/分曲播放（后台 + 通知）、歌词页看原视频（全屏/非全屏）、整库备份恢复。

## 2. 工程与产物

| 项 | 值 |
| --- | --- |
| 仓库 | `/home/wonderlandkid/code/bilibiliPlayer`（本地 git，无 remote） |
| 包名 / applicationId | `com.wonderlandkid.biliaudio` |
| App 名称 | 哔哩专辑 |
| 技术栈 | Kotlin 2.3.21 / Compose BOM 2026.09 / AGP 9.5.0 / Gradle 9.6（腾讯镜像）/ Media3 1.11.1 / Room / OkHttp / Coil / zxing |
| 最低支持 | Android 7.0（API 24），targetSdk 37 |
| 构建 | `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug` |
| 单测 | `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest`（当前 152 个全绿） |
| APK 产物 | `app/build/outputs/apk/debug/app-debug.apk`；发布放置 `dist/biliaudio-vX.Y.Z-debug.apk` |

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
| 归档导入/导出（整库 / 单专辑 / 分割点文件） | ✅ | `.biliaudio.json` v1；合并/仅更新分割点/覆盖全库（自动备份）；内嵌自定义封面 `coverBase64` |
| **自定义专辑封面** | ✅ | 导入预览/专辑详情可「换封面」：相册上传、网易云搜索、恢复 B 站封面；本地文件存储优先 |
| 分曲编辑（手动/播放打点/文本导入/排序/保存） | ✅ | 未分割专辑显示「未分割」空态 |
| 播放（ExoPlayer 分曲裁剪、专辑连续、单曲点播） | ✅ | 点歌/专辑播放=单专辑队列；「播放全部歌曲」=全库 |
| 播放列表（当前专辑分曲展开、跳转） | ✅ | NowPlaying 播放列表面板 |
| 后台播放 + MediaSession 通知 + 锁屏控制 | ✅ | Android 13+ 通知权限已申请 |
| 歌词页原视频（同步、全屏/非全屏、加载状态） | ✅ | 独立第二路 ExoPlayer；清晰度标签；慢加载提示+重试 |
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
| 多 P 视频自动分曲 | ❌ 未做 | 契约 Track 无 cid；当前按整段导入后手动分曲 |
| YAML 只读导入 | ❌ 未做（M3 可选） | 当前只 JSON |
| B 站收藏夹导入 / 离线缓存 / 自动静音切分 / EQ | ❌ 未做 | 路线图 |

## 4. 版本记录（详情见 CHANGELOG）

- **v0.1.0** 首版：登录/导入/分曲/播放/歌词页/音乐库/设置，模拟器+真机走查通过
- **v0.2.0** 音乐库归档：统一 JSON 备份/恢复/分割点文件，覆盖全库自动备份
- **v0.3.0** 文本识别分割点（三种格式）、未分割导入不再生成占位曲、歌词视频清晰度修复（选流）
- **v0.4.0** 诊断日志（Diag + 设置页）、点歌单专辑队列、歌词页加载阶段/重试、720P 上限、旧占位曲清理
- **v0.5.0** 自定义专辑封面（本地上传/网易云搜索/恢复B站/归档内嵌）、歌词页加载体验修复（封面占位、单次延迟提示、校正节流）
- **v0.6.0** 三主页面统一页头/导航转场重做、迷你播放条上/下手势、音乐库三分栏+#A-Z索引+艺术家详情、归档目录形态（顶层/艺术家/专辑/分割）
- **v0.6.1** 音乐库滚动性能修复（播放 tick/索引栏/列表 contentType）、搜索框 UI 统一、Tab 切换闪白修复、维测日志开关
- **v0.6.2** 全局动效按 Material Design 3 Motion 规范重写：BiliMotion token、Fade through/Shared axis X/Y/Fade、M3 弹簧、移除页面内叠加动画；导航壳播放状态订阅隔离

## 5. 最近验证状态（截至 v0.4.0）

- 单元测试 **152 / 0 失败**（wbi、批量/归档解析、导入模式、播放映射、选流、占位曲判定、封面归档、目录归档等）。
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
- **未验证/待用户配合**：
  1. 扫码登录成功后的完整链路（头像/昵称、高清流）——需要真实账号扫码；
  2. 登录后歌词页 720P 画质/加载速度（真机已验证未登录 480P/登录态下 720P 出现）；
  3. 本地相册上传封面在 MIUI 系统相册选择器的完整 UI 走查（代码路径已实现，在线封面/恢复 B 站已真机验证）；
  4. 归档「自定义封面」端到端 UI 走查（数据层单测已覆盖 coverBase64 导出/导入）。

## 6. 待办 / 下一步（按优先级）

1. **P1** 真机 v0.6.1 回归（本轮真机未连接，模拟器验证）：音乐库**播放中**上下滑手感、
   Tab 切换动画（真机 GPU）、维测日志开关持久化、搜索框一致性、目录归档 SAF（MIUI 需「允许访问」确认）。
2. **P1** 本地相册上传封面 + 归档封面恢复的真机 UI 走查（数据层已测；Photo Picker 分支未端到端）。
3. **P1** 真机扫码登录成功路径 QA（头像昵称、音质/画质提升）。
4. **P2** 网易云封面接口为**非官方接口**，未来可能变更/限流；失败时静默降级（只影响在线搜封面），
   可后续加备用源（如 QQ 音乐/豆瓣）或内置占位图选择。
5. **P2** 歌词页 CDN 节点慢的兜底：目前 8s 提示可手动重试（会作废旧流地址重新解析）；可考虑用 playurl 的 backupUrl 自动切换线路。
6. **P2** 音乐库大数据量（数千首）索引滚动性能与分组重算（当前用 remember 缓存，需实测）。
7. **P2** 多 P 视频自动分曲（若做：需 Track 增加 cid，归档/播放映射扩展）。
8. **P3** 路线图：B 站收藏夹导入、离线缓存、自动静音切分、播放历史/歌单、YAML 只读导入、画质设置项。

## 7. 关键设计约束（务必遵守）

- **虚拟歌曲模型**：Track = 专辑音视频流上的 `[startMs, endMs)` 切片，**不做独立音频实体**；
  单曲不设独立艺术家/封面，继承专辑（用户已确认「完全没必要做成实体」）。
- **播放队列语义**：从专辑/音乐库点某曲 → 队列=该曲所属专辑的分曲；「播放全部歌曲」才全库。
- **未分割专辑**：0 首曲目，播放时虚拟整张一首；旧版占位曲启动自动清理。
- **归档格式**：一个 `.biliaudio.json` 承载整库/单专辑/分割点；只 JSON；导入模式 merge/segments-only/replace；
  replace 自动备份到 `files/backups/`（保留 10 份）。
- **歌词页视频**：独立第二路 ExoPlayer 只播视频轨，与音频按「专辑绝对进度」同步；
  视频选流优先最高分辨率 **≤720P**、同分辨率优先 AVC；不要选低码率低分辨率流（会糊）。
  未出首帧用当前专辑封面占位；加载提示延迟 500ms 且只显示一次；音画校正阈值 800ms + 最小间隔 3s。
- **自定义封面**：`Album.coverLocalPath` 优先于远程 `coverUrl`；封面文件存 `files/covers/`（JPEG，长边≤1080）；
  在线来源为网易云专辑搜索（非官方接口）；归档用 `coverBase64` 内嵌封面；删除专辑时清理封面文件。
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
│   ├── net/WbiSigner.kt / StreamSelector.kt / CookieStore.kt
│   ├── db/                             Room（albums/tracks）
│   └── impl/                           仓库实现 + BatchImportParser + SplitTextParser +
│                                       LibraryArchiveCodec / LibraryArchiveRepositoryImpl + Diag 打点
├── player/
│   ├── PlaybackEngine.kt               单 ExoPlayer + ClippingMediaItem 队列（Diag tag Play）
│   ├── PlaybackService.kt              MediaSessionService/通知
│   ├── AlbumVideoPlayer.kt             歌词页第二路视频播放器（Diag tag Video）
│   └── PlaybackMapping.kt              队列/裁剪/进度映射（纯函数 + 单测）
├── ui/
│   ├── theme/ · components/            设计系统与通用组件（集成层）
│   ├── navigation/AppNavHost.kt        导航与底部栏/迷你播放条
│   ├── home/ library/ album/           发现、音乐库、专辑详情、分曲编辑（含文本导入）
│   ├── imports/                        BV/搜索/归档导入、分割点文本识别预览
│   ├── login/ settings/                扫码登录、设置、备份恢复、诊断日志
│   └── player/                         正在播放、播放列表面板、歌词页（加载阶段/重试/清晰度）
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
| 归档样例 | [docs/samples/biliaudio-library-full.sample.json](samples/biliaudio-library-full.sample.json)、[biliaudio-segments.sample.json](samples/biliaudio-segments.sample.json) |
| 模拟器 | `scripts/emulator.sh start` / `scripts/screenshot.sh <name> <wait>` |

## 10. 文档索引

- [AGENTS.md](../AGENTS.md)（根目录入口）
- [DESIGN.md](DESIGN.md)（初版设计，含当前状态更新）
- [DECISIONS.md](DECISIONS.md)（设计决策）
- [DEBUGGING.md](DEBUGGING.md)（排障）
- [CHANGELOG.md](CHANGELOG.md)（版本）
- [API_NOTES.md](API_NOTES.md)（B 站接口）
- [LIBRARY_ARCHIVE.md](LIBRARY_ARCHIVE.md)（归档格式）
- [IMPORT_FORMATS.md](IMPORT_FORMATS.md)（导入/分割点文本格式）
