# 项目现状与交接（v0.4.0）

> 最后更新：2026-11-07 · 版本 v0.4.0 · tag `v0.4.0`（release commit `36a8843`）；文档维护提交见 `git log docs/`
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
| 单测 | `JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest`（当前 129 个全绿） |
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
| 归档导入/导出（整库 / 单专辑 / 分割点文件） | ✅ | `.biliaudio.json` v1；合并/仅更新分割点/覆盖全库（自动备份） |
| 分曲编辑（手动/播放打点/文本导入/排序/保存） | ✅ | 未分割专辑显示「未分割」空态 |
| 播放（ExoPlayer 分曲裁剪、专辑连续、单曲点播） | ✅ | 点歌/专辑播放=单专辑队列；「播放全部歌曲」=全库 |
| 播放列表（当前专辑分曲展开、跳转） | ✅ | NowPlaying 播放列表面板 |
| 后台播放 + MediaSession 通知 + 锁屏控制 | ✅ | Android 13+ 通知权限已申请 |
| 歌词页原视频（同步、全屏/非全屏、加载状态） | ✅ | 独立第二路 ExoPlayer；清晰度标签；慢加载提示+重试 |
| 音乐库歌曲/专辑/艺术家搜索、最近播放 | ✅ | 分曲逐首显示、可搜索 |
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

## 5. 最近验证状态（截至 v0.4.0）

- 单元测试 **129 / 0 失败**（wbi、批量/归档解析、导入模式、播放映射、选流、占位曲判定等）。
- 模拟器端到端已验证：
  - 真实导入 `BV1GJ411x7h7`（3:33）与 `BV1sp4y1B7bH`（59:25，13 分 P）；
  - 用「格式 1」14 首文本导入 → 14 首、播放第 2 首（03:57）专辑进度映射正确；
  - 音乐库歌曲逐首显示（4+14=18 行）；点歌后播放列表面板只显示该专辑 14 首；
  - 归档导出/仅更新分割点/覆盖全库（自动备份+恢复）全流程；
  - 歌词页加载日志：resolve 缓存命中 1ms、首帧 3.4s（未登录 480P，模拟器软解）；
  - 诊断日志页面、复制/导出/清空 UI。
- **真机（用户 Xiaomi）历史验证**：登录、导入、播放、歌词页 HEVC 硬解、通知均正常（v0.2 时代）。
- **未验证/待用户配合**：
  1. v0.4 在真机上的「歌词视频转圈」实际耗时与 `[Video]` 日志（用户反馈 v0.3 前版本转圈较久，本轮已加日志与优化）；
  2. 扫码登录成功后的完整链路（头像/昵称、高清流）——需要真实账号扫码；
  3. 登录后歌词页 720P（当前上限）画质/加载速度；
  4. 旧真机数据安装 v0.4 后占位曲自动清理效果。

## 6. 待办 / 下一步（按优先级）

1. **P0** 用户安装 v0.4 后复现视频转圈，导出诊断日志（设置→诊断与维护→诊断日志→复制全部）发回，
   按 [DEBUGGING.md](DEBUGGING.md) 判读 `[Video]` 时间线（resolve / prepare / buffering / first frame / 解码器 / CDN host），
   再决定是否：换 CDN 节点重试、降清晰度、ABR 或缓存策略。
2. **P1** 真机扫码登录成功路径 QA（头像昵称、音质/画质提升）。
3. **P1** 真机 v0.4 全流程回归（安装覆盖旧数据 → 旧占位曲清理 → 文本导入分曲 → 播放队列 → 归档备份恢复）。
4. **P2** 搜索接口风控体验（当前：失败给可重试错误 + 空结果「重新搜索」；可考虑更长退避/本地缓存）。
5. **P2** 多 P 视频自动分曲（若做：需 Track 增加 cid，归档/播放映射扩展）。
6. **P3** 路线图：B 站收藏夹导入、离线缓存、自动静音切分、播放历史/歌单、YAML 只读导入、画质设置项。

## 7. 关键设计约束（务必遵守）

- **虚拟歌曲模型**：Track = 专辑音视频流上的 `[startMs, endMs)` 切片，**不做独立音频实体**；
  单曲不设独立艺术家/封面，继承专辑（用户已确认「完全没必要做成实体」）。
- **播放队列语义**：从专辑/音乐库点某曲 → 队列=该曲所属专辑的分曲；「播放全部歌曲」才全库。
- **未分割专辑**：0 首曲目，播放时虚拟整张一首；旧版占位曲启动自动清理。
- **归档格式**：一个 `.biliaudio.json` 承载整库/单专辑/分割点；只 JSON；导入模式 merge/segments-only/replace；
  replace 自动备份到 `files/backups/`（保留 10 份）。
- **歌词页视频**：独立第二路 ExoPlayer 只播视频轨，与音频按「专辑绝对进度」同步；
  视频选流优先最高分辨率 **≤720P**、同分辨率优先 AVC；不要选低码率低分辨率流（会糊）。
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
