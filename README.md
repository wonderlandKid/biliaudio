# 哔哩专辑 · BiliAudio

> 把 B 站上的「完整专辑视频」变成真正可搜索、可分曲、可后台播放的音乐播放器。

B 站有很多 UP 主发布整张专辑的完整视频（一个视频 = 一整张专辑），但用 B 站本身听歌很麻烦：收藏的视频不能当歌曲搜、分曲只能翻评论区分割点、想要「歌词」只能看视频。

**哔哩专辑** 把这些视频导入成「专辑 → 歌曲」：

- 🔐 **B 站扫码登录**（Cookie 持久化，登录后可解锁更高音质/画质）
- 🖼️ **自定义专辑封面**：导入预览 / 专辑详情可「换封面」——从相册上传、在线搜索网易云专辑封面、
  或恢复 B 站原始封面；本地封面随归档备份一起保存；
- 📥 **导入专辑**：粘贴 BV 号 / 视频链接解析预览，一键导入；
  旧版本遗留的「整张专辑占位曲」会在启动时自动清理
- 📚 **音乐库三分栏**：歌曲 / 专辑 / 艺术家，右侧 `# A-Z` 快速索引栏（点击/拖动定位，中文按拼音分组）；
  艺术家封面默认取第一张专辑封面，可进入艺术家详情；
- 🧩 **分曲编辑**：输入时间点 + 歌名切分整张专辑；支持「用当前播放位置」打点；
  **粘贴文本自动识别分割点**（兼容「编号+歌名+时间」「时间+歌名」「歌名+时间」三种格式，可混用）
- 🎵 **按歌曲播放**：专辑音频流连续播放，播放列表 = 当前专辑的分割点，点哪首播哪首；
  从音乐库点歌默认**只播放该曲所属专辑**（不是整个音乐库）；后台播放 + 媒体通知 + 锁屏控制；
  底部迷你播放条支持**上滑打开播放页、下滑收起为细条**
- 🎬 **歌词页 = 原视频**：播放 B 站原视频，与音频进度同步，支持全屏 / 非全屏两种模式；
  视频流按「最高可用分辨率」选择（未登录一般 480P，登录后可 720P/1080P）
- 🔎 **可搜索的音乐库**：歌曲 / 专辑搜索，按艺术家、专辑名检索
- 💾 **音乐库归档**：整库备份 / 单专辑分享 / 评论区分割点文件统一为 `.biliaudio.json`，
  导入导出、合并且覆盖前自动备份；旧 CSV/文本块格式继续兼容
- 🎨 **Material 3 扁平设计**：封面大图、大圆角卡片、动态色板，无液态玻璃

## 界面截图

| 发现 | 正在播放 | 歌词视频（原视频） |
| --- | --- | --- |
| ![home](docs/screenshots/final_01_home.png) | ![player](docs/screenshots/real_10_now_playing.png) | ![video](docs/screenshots/real_11_video.png) |

| 分曲编辑 | 搜索导入 | 深色主题 |
| --- | --- | --- |
| ![split](docs/screenshots/real_19_split_editor.png) | ![search](docs/screenshots/final_02_search.png) | ![dark](docs/screenshots/real_35_home_dark.png) |

| 备份与恢复 | 归档导入预览 | 覆盖全库报告 |
| --- | --- | --- |
| ![backup](docs/screenshots/archive_01_settings.png) | ![preview](docs/screenshots/archive_04_preview.png) | ![report](docs/screenshots/archive_09_replace_report.png) |

| 专辑详情（底部迷你播放条） | 歌词页全屏 | 批量导入 |
| --- | --- | --- |
| ![album](docs/screenshots/final_04_album_mini.png) | ![full](docs/screenshots/real_26_video_fullscreen.png) | ![batch](docs/screenshots/real_31_batch_result.png) |

| 分割点文本识别（导入预览） | 文本导入弹窗 | 长专辑 14 首 |
| --- | --- | --- |
| ![textimport](docs/screenshots/v03_09_import_text_preview.png) | ![dialog](docs/screenshots/v03_06_text_import_dialog.png) | ![longalbum](docs/screenshots/v03_11_long_album_detail.png) |

| 点歌只播该专辑（播放列表 14 首） | 歌词页加载状态 | 诊断日志 |
| --- | --- | --- |
| ![albumqueue](docs/screenshots/v04_playlist_album_only.png) | ![loading](docs/screenshots/v04_video_loading.png) | ![diag](docs/screenshots/v04_diagnostics.png) |

| 自定义封面（专辑详情） | 首页卡片同步 | 导入预览换封面 |
| --- | --- | --- |
| ![coversheet](docs/screenshots/v05_cover_custom.png) | ![coverdetail](docs/screenshots/v05_cover_home_sync.png) | ![importcover](docs/screenshots/v05_import_cover.png) |

| 三主页面统一页头（音乐库） | 音乐库三分栏 + A-Z 索引 | 艺术家详情 |
| --- | --- | --- |
| ![library](docs/screenshots/v06_library_songs.png) | ![artists](docs/screenshots/v06_library_artists.png) | ![artist](docs/screenshots/v06_artist_detail.png) |

| 迷你播放条收起态 | 目录归档导入结果 | 统一页头（设置） |
| --- | --- | --- |
| ![mini](docs/screenshots/v06_mini_collapsed.png) | ![dirimport](docs/screenshots/v06_dir_import_result.png) | ![settings](docs/screenshots/v06_settings.png) |

| 搜索框统一（发现/音乐库一致） | 维测日志开关 |
| --- | --- |
| ![search](docs/screenshots/v061_search_unified.png) | ![diag](docs/screenshots/v061_diag_toggle.png) |

更多截图见 [`docs/screenshots/`](docs/screenshots/)。

## 技术栈

- Kotlin + Jetpack Compose（Material 3，material-kolor 动态色板）
- Media3 ExoPlayer + MediaSessionService（后台播放 / 媒体通知 / 第二路视频播放器）
- OkHttp + kotlinx.serialization（B 站 API / wbi 签名 / 扫码登录）
- Room（专辑与分曲持久化）、Coil（封面加载）、Navigation Compose
- 最低 Android 7.0（API 24），targetSdk 37

## 构建

```bash
# 需要 JDK 17+（Android Studio 自带 JBR 即可）
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> Gradle 发行版走腾讯镜像（`gradle/wrapper/gradle-wrapper.properties`），国内网络可直接构建。
> 如果 Android Studio 新建项目后 wrapper 指向 `services.gradle.org`，把它改成
> `https://mirrors.cloud.tencent.com/gradle/gradle-<版本>-bin.zip` 即可。

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

模拟器冒烟测试（可选，用于截图走查）：

```bash
scripts/emulator.sh start   # 启动 headless 模拟器（需预装 AVD）
scripts/screenshot.sh home 8
```

## 使用流程

1. **登录（可选）**：设置 → 去登录，用 B 站 App 扫码。未登录也可以导入和播放（音质/画质受限）。
2. **导入专辑**：发现页 → 导入专辑：
   - **BV 号导入**：粘贴 `BV...` 或视频链接 → 解析预览（封面/标题/UP 主/时长/分 P）→ 可改专辑名/艺术家 → 导入；
   - **批量导入**：粘贴 JSON / CSV / 文本块（格式见下方）→ 开始批量导入；
   - **搜索导入**：关键词搜索 B 站视频 → 预览 → 导入。
3. **分曲**：进入专辑 → 编辑分割点：
   - **粘贴文本自动识别**：点「文本导入」，粘贴整张专辑的曲目时间表即可批量生成分割点，例如：
     ```text
     1.Breakaway00:00
     2.Since U Been Gone03:57
     ...
     ```
     或 `00:00 Tap Out` / ` Come Together00:00` 等常见格式（可混用，带编号/毫秒/全角数字都能识别）；
   - 手动输入时间点（`mm:ss` / `mm:ss.SSS` / `hh:mm:ss` / 秒数）+ 歌名 → 添加；
   - 或先试听专辑，点「用当前位置」自动取当前播放时间（吸附到 0.5s）；
   - 支持编辑/删除/按时间排序，保存后专辑即变成多首歌。
   > 导入时也可以直接在「分曲文本（可选）」里粘贴，导入即完成分曲；不粘贴则导入为未分割专辑
   > （整张按一首播放，不会再生成需要手动删除的占位曲）。
3.5 **换封面**：专辑详情点封面右下角「换封面」→ 从相册选择 / 网易云搜索 / 恢复 B 站原始封面
   （立即生效，列表、迷你播放条、播放页同步）。

4. **播放**：
   - 底部迷你播放条：**上滑 → 打开正在播放页，下滑 → 收起为细条**（点击/上滑展开），播放页从底部上滑进出；
   - 专辑页「播放全部」= 只播放该专辑（若已分曲则展开该专辑全部分曲，按顺序单次播放）；
   - 音乐库点某首歌 = 只播放该曲所属专辑，从选中曲开始（播放列表面板显示该专辑分曲）；
   - 音乐库「播放全部歌曲」= 显式播放当前歌曲列表全部；
   - 底部迷你播放条 → 正在播放页（播放列表面板 = 当前专辑分曲）。
5. **歌词页**：正在播放页 →「看歌词视频」，观看 B 站原视频（与音频同步），右上角可切换全屏；
   加载时会显示「正在获取视频流 / 准备播放器 / 缓冲」和已等待秒数，超过 8 秒会提示并给「重试」按钮；
   视频清晰度标签（如 480P）显示在画面右上角。

### 目录归档（推荐，v0.6.0+）

设置 → 备份与恢复 →「目录归档」：
- 「导出到文件夹」：选择（或新建）一个文件夹，生成
  `biliaudio-library.json`（根索引）与 `艺术家/专辑/album.json`、`艺术家/专辑/segments.json`；
- 「从文件夹导入」：选择导出的顶层目录，按 合并 / 仅更新分割点 / 覆盖全库 导入（覆盖模式先自动备份）；
- 原单文件 JSON 导出/导入保留，旧备份仍可恢复。

### 音乐库归档文件（单文件 JSON，兼容）

一个 `.biliaudio.json` 文件同时承载「整库备份 / 单专辑分享 / 分割点文件」，格式与导入模式见
[docs/LIBRARY_ARCHIVE.md](docs/LIBRARY_ARCHIVE.md)。最小分割点文件示例：

```json
{
  "format": "biliaudio-library",
  "version": 1,
  "albums": [
    {
      "bvid": "BV1GJ411x7h7",
      "segments": [
        { "start": "00:00", "name": "曲目一" },
        { "start": "03:21", "name": "曲目二" }
      ]
    }
  ]
}
```

导入模式：**合并导入**（默认）/ 仅更新分割点 / 覆盖全库（自动备份后可回滚）。
设置页「备份与恢复」可导出整库；专辑详情菜单可导出单张专辑。

### 兼容旧批量导入格式

仍自动识别旧 JSON 数组 / CSV / 文本块，详见 [docs/IMPORT_FORMATS.md](docs/IMPORT_FORMATS.md)。

```text
BV1GJ411x7h7 专辑名
  00:00 曲目一
  03:21 曲目二
  07:45.500 曲目三
```

```csv
BV1GJ411x7h7,专辑名,艺术家,00:00,曲目一
BV1GJ411x7h7,专辑名,艺术家,03:21,曲目二
```

```json
[{"bvid":"BV1GJ411x7h7","title":"专辑名","artist":"艺术家",
  "segments":[{"start":"00:00","name":"曲目一"},{"start":"03:21","name":"曲目二"}]}]
```

> 同一 BV 号重复导入会覆盖更新（保留专辑 id 与播放次数）。

## 已知限制 / 路线图

- **多 P 视频自动分曲**尚未支持（当前按单视频导入后手动分曲）；
- 歌词页视频清晰度：未登录最高 480P，登录后上限 720P（可后续加画质设置项）；全屏为竖屏铺满，横屏全屏待做；
- 播放地址会过期，出错时自动重新解析一次；暂不做离线缓存；
- 搜索接口偶发被 B 站风控（会提示可重试）；
- 目录归档使用系统文件夹选择器（SAF）；Android 限制直接选 Download 根目录，需新建子文件夹；首次选择后要允许访问；
- 在线封面来自**网易云非官方接口**，可能变更/失效（失败只影响在线搜封面，不影响本地/相册上传）；
  自定义封面仅存本机，重装后需靠归档备份恢复（备份文件内嵌封面）；
- 后续：B 站收藏夹批量导入、离线下载、自动静音切分、均衡器、封面取色主题、歌单。

## 项目结构

```
app/src/main/java/com/wonderlandkid/biliaudio
├── data/       # B站 API(wbi签名/扫码登录)、Room、仓库、媒体流解析
├── player/     # 播放引擎(ExoPlayer 分曲裁剪)、后台服务、歌词页视频
├── ui/         # Compose 界面：发现/音乐库/专辑/分曲编辑/导入/登录/设置/播放器/歌词页
├── di/         # 手写 DI 容器
└── util/
docs/           # 设计文档、B站接口笔记、导入/归档格式、截图、示例文件
```

## 文档索引

- [AGENTS.md](AGENTS.md)：项目入口、开发约定、文档导航
- [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)：当前版本、功能矩阵、验证状态、待办与交接
- [docs/DECISIONS.md](docs/DECISIONS.md)：关键设计决策（虚拟歌曲/队列语义/归档格式/选流策略等）
- [docs/DEBUGGING.md](docs/DEBUGGING.md)：诊断日志与排障（转圈、导入、Studio、网络）
- [docs/CHANGELOG.md](docs/CHANGELOG.md)：v0.1.0 → v0.4.0 版本记录
- [docs/DESIGN.md](docs/DESIGN.md)：架构设计（含当前状态说明）
- [docs/API_NOTES.md](docs/API_NOTES.md)：B 站接口实测笔记
- [docs/LIBRARY_ARCHIVE.md](docs/LIBRARY_ARCHIVE.md)：音乐库归档格式 / [docs/IMPORT_FORMATS.md](docs/IMPORT_FORMATS.md)：导入与分割点文本格式
- [docs/samples/](docs/samples/)：归档示例与分割点文本真实样例

## 说明

本项目为个人学习自用工具，仅用于播放用户自己可访问的 B 站内容；B 站内容版权归原作者及平台所有，请勿用于商业用途或大规模抓取。

## 诊断日志

设置 → 诊断与维护 →「维测日志」开关（默认开启；关闭后不再记录/打印，历史日志仍可查看）；
点「诊断日志」可查看 / 复制 / 导出最近的运行日志（播放、视频缓冲、导入、B 站接口耗时等），
反馈问题时把日志发我即可定位；也可在真机用 `adb logcat -s BiliDiag` 查看（日志不含账号 Cookie 等敏感信息）。

## 常见问题

### Android Studio 点 Run 报 `Activity class {com.example.bilibiliplayer/...} does not exist`

原因：这是 Android Studio 的 **Gradle 模型缓存过期**，不是 App 代码问题。项目最初由向导创建时
`applicationId` 是 `com.example.bilibiliplayer`，后来改成了 `com.wonderlandkid.biliaudio`，
而 IDE 的自动同步被关闭，仍用旧包名拼启动命令，于是报「Activity 不存在」（但 APK 部署本身是成功的）。

修复（任选其一）：

1. **File → Sync Project with Gradle Files**（大象图标），等同步完成后再 Run；
2. 若仍报旧包名：Run → Edit Configurations… → 选中 `app` → General → Launch Options
   选 **Default Activity**（或 Specified Activity 填 `com.wonderlandkid.biliaudio.MainActivity`）→ Apply；
3. 还不行：File → Invalidate Caches / Restart…；或删掉该 Run Configuration 重新创建；
4. 建议开启自动同步：Settings → Build, Execution, Deployment → Build Tools → Gradle →
   Auto-import / Reload project after changes 设为 **Any changes**。

命令行验证（不经过 IDE，最直接）：

```bash
adb install -r dist/biliaudio-v0.1.0-debug.apk
adb shell am start -n com.wonderlandkid.biliaudio/.MainActivity
```
