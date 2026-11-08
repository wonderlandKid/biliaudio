# 哔哩专辑 · BiliAudio

> B 站专辑音乐播放器（Android / Kotlin / Jetpack Compose / Material 3）。
> 把 B 站「一整张专辑装在一个视频里」的内容，转成可搜索、可分曲、可后台播放的音乐库。

## 当前状态（先读这个）

- **版本：v0.8.0**（tag `v0.8.0`，2026-11-08；release commit 以 `git rev-parse v0.8.0` 为准）
- 详细交接信息见 **[docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)**
- 设计决策记录见 **[docs/DECISIONS.md](docs/DECISIONS.md)**
- 排障手册见 **[docs/DEBUGGING.md](docs/DEBUGGING.md)**
- 版本变更见 **[docs/CHANGELOG.md](docs/CHANGELOG.md)**
- 单元测试：**152 个**（v0.8.0）

## 快速开始

```bash
export JAVA_HOME=/opt/android-studio/jbr        # 本机 JDK（Android Studio 自带 JBR 25）
./gradlew :app:assembleDebug                     # 构建 APK
./gradlew :app:testDebugUnitTest                 # 单元测试（v0.8.0: 152 个）
./gradlew :app:assembleRelease                   # 性能/日常安装请用 release（R8）
# 产物：app/build/outputs/apk/debug/app-debug.apk（开发调试，滚动会明显偏慢）
#       app/build/outputs/apk/release/app-release.apk（R8 + debug 签名，可安装）

# 模拟器（AVD: biliaudio_api36，headless）
scripts/emulator.sh start
scripts/screenshot.sh home 8
```

- 包名 / applicationId：`com.wonderlandkid.biliaudio`；App 名称：**哔哩专辑**
- 源码入口：`app/src/main/java/com/wonderlandkid/biliaudio/`
- 本机环境注意：**GitHub 不可达**（Gradle wrapper 已改用腾讯镜像；B 站 API/CDN 可直连）

## 开发约定（重要）

1. **契约文件不要随意改**：`data/model/Models.kt`、`data/repository/Repositories.kt`、
   `player/PlaybackContracts.kt`、`player/VideoContracts.kt`、`di/AppContainer.kt`、
   `ui/navigation/**`、`ui/theme/**`、`ui/components/**`、`AndroidManifest.xml`、`build.gradle.kts`
   属于集成层，改动前先确认影响面（或按 docs/DECISIONS.md 的架构约定来）。
2. **每次发版**：`versionCode +1`、`versionName` 升级 → 全量单测 + **构建 release**（性能验收/交付用；
   debug 包仅开发调试）→ 提交（feat/fix/docs/chore + 详情）
   → annotated tag `vX.Y.Z` → 复制 release 产物到 `dist/biliaudio-vX.Y.Z-release.apk`
   （需要排查时再附 debug）并 `git archive` 生成 `dist/biliaudio-vX.Y.Z-source.zip`
   （`dist/` 被 .gitignore 忽略，不进仓库）。
3. **用户偏好**：中文 UI/中文沟通；Material 3 扁平风格，**不要液态玻璃/大面积毛玻璃**；
   （v0.7.0 起用户明确要求：仅迷你播放条可使用 Haze 毛玻璃，其余页面保持扁平）
   单曲不设独立实体，直接继承专辑艺术家；播放列表=当前播放专辑的分曲；追求「点一下就能听」的轻量体验。
4. **问题排查**：先看 `Diag` 诊断日志（设置 → 诊断与维护 → 诊断日志；Logcat tag `BiliDiag`），
   再按 docs/DEBUGGING.md 的流程判断。
5. **UI 规范**：Tab 页页头必须用 `ui/components/PageHeader.kt` 的 `BiliPageHeader`（标题位置统一）；
   搜索框统一用 `SearchField`（可编辑）/`SearchBarEntry`（入口）。
6. **动效规范（v0.8.0 起，用户明确要求）**：全部动效 token 在 `ui/theme/Motion.kt`（BiliMotion）；
   **导航一律左右滑动、从哪来回到哪去，禁止 alpha 淡入淡出**：层级页面进入从右滑入/返回向右滑出（400ms，
   旧页向左 1/3 视差）；三个 Tab 按序号决定左右方向；歌词页同样左右滑动。
   迷你条 ↔ 播放页是**上下关系**：播放页为进度驱动的连续展开卡片（playerProgress 0→1 + spring 收敛），
   不是导航路由。
7. **迷你条**：Haze blur（dev.chrisbanes.haze 2.0.1，API31+；低版本 fallback 半透明）+ 跟手拖拽上滑打开播放页；
   不再有拖拽提示条；只允许迷你条使用毛玻璃，其余页面保持 Material 3 扁平（无大面积玻璃）。
8. **列表性能规范**：ViewModel 里不要直接 combine `playbackController.state`（会随进度 tick 重建）；
   要 map 成 `(trackId,isPlaying)` 后 distinctUntilChanged；重计算（分组/排序）放数据变化阶段；
   Lazy 列表加 contentType + 稳定 key；索引栏高亮用 provider 在组件内部读取，父级不要读滚动派生状态。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | 当前版本、功能矩阵、验证状态、待办、交接说明 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 关键设计决策与原因（虚拟歌曲、队列语义、归档格式、视频选流等） |
| [docs/DEBUGGING.md](docs/DEBUGGING.md) | 诊断日志、常见故障排查（转圈、导入、Studio、网络） |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | v0.1.0 → v0.8.0 版本记录 |
| [docs/DESIGN.md](docs/DESIGN.md) | 初版架构设计（含当前状态更新说明） |
| [docs/API_NOTES.md](docs/API_NOTES.md) | B 站接口实测笔记（wbi、登录、播放流、选流策略） |
| [docs/LIBRARY_ARCHIVE.md](docs/LIBRARY_ARCHIVE.md) | 音乐库归档 JSON 格式规范（备份/恢复/分割点文件） |
| [docs/IMPORT_FORMATS.md](docs/IMPORT_FORMATS.md) | 批量导入与分割点文本格式（三种粘贴格式） |
| [docs/samples/](docs/samples/) | 归档示例、分割点文本真实样例 |
| [docs/screenshots/](docs/screenshots/) | 模拟器/真机走查截图 |
