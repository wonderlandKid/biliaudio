# 排障手册

> 遇到问题先看这里；诊断日志在 **设置 → 诊断与维护 → 诊断日志**（可复制/导出），
> 真机也可 `adb logcat -s BiliDiag`。

## 1. 诊断日志（Diag）说明

- 实现：`util/Diag.kt`；内存环形（最近 3000 行）+ Logcat tag `BiliDiag`；设置页可查看/复制/导出/清空。
- **开关**：设置 → 诊断与维护 →「维测日志」Switch（默认开启）。关闭后不记录/不打印（立即生效、持久化），
  历史日志仍可查看/导出；排查问题前请确认开关为开启状态。
- 日志格式：`MM-dd HH:mm:ss.SSS +进程运行ms [Tag] 内容 (线程)`。
- Tag 含义：

| Tag | 覆盖内容 |
| --- | --- |
| `App` | 启动、设备/版本信息、旧数据清理 |
| `Api` | B 站接口请求（path、HTTP、耗时、重试/风控） |
| `Resolver` | 播放流解析：缓存命中、音频/视频清晰度/码率、CDN host、耗时 |
| `Play` | 音频播放引擎：playAlbum/Track/Songs、队列与裁剪映射、READY、错误重试、前台服务 |
| `Video` | 歌词页视频：resolve、prepare、BUFFERING/READY、首帧、解码器（硬/软）、分片加载、错误、封面占位/覆盖层显隐、漂移校正 seek |
| `Cover` | 自定义封面：网易云搜索、图片下载/保存、DB 更新（albumId/path） |
| `Import` | 专辑导入（BV/批量/文本）输入、条目数、耗时、失败原因；分割点文本解析统计 |
| `Archive` | 归档导入/导出（模式、条目统计、自动备份路径） |

## 2. 歌词页「转圈很久」

按 `BiliDiag` 里 `[Video]` 时间线判读：

1. `prepare START` → `resolve DONE in Xms`：**X 大** → 数据层 playurl/网络问题；
   先看同时段 `[Resolver]`/`[Api]`（是否 412/风控、是否缓存未命中）。
2. `resolve DONE` 后 → `player prepare ISSUED`：应 <10ms；大则主线程卡顿。
3. `prepare ISSUED` → `BUFFERING`/`READY`/`first frame RENDERED`：**这段大** → 源站/CDN 拉流慢。
   看日志里的 `host=`（CDN 节点）与 `load#N media=...B in ...ms`；可点 UI 的「重试」
   （会 `streamResolver.invalidate` 后重新解析，可能换到别的 CDN 节点）。
4. `READY` 早但 `first frame RENDERED` 晚 → 解码慢；看 `video DECODER=...（硬件/软件解码）`：
   若是软件解码（c2.android.*）或老设备，考虑换更低分辨率/AVC 流。
5. `player ERROR`：日志含 exoplayer 错误码与 host，可重试；持续失败检查 Referer/UA/Cookie 是否带全
   （引擎有默认头，登录态变化会自动清流缓存）。

**已做的优化**（v0.4.0 → v0.5.0）：
- 视频分辨率封顶 720P、同分辨率优先 AVC；`setMediaItem(item, startPosition)` 省 prepare 后 seek；
- 未出首帧用**专辑封面占位**（不再黑屏），加载提示延迟 500ms 且只显示一次；<500ms 快速加载无任何提示；
- 禁用 PlayerView 自带 spinner；
- 音画漂移阈值 800ms / 最小校正间隔 3s / READY 后 1.2s 宽限；READY 后中途 re-buffer 不再弹加载提示；
- 慢加载 >8s 提示可重试（重试会作废旧流地址重新解析，可能换 CDN 节点）。

**v0.5.0 真机基线**（Xiaomi / 硬件解码 / 720P）：`prepare START` → `resolve DONE in 0~2ms` →
`first frame RENDERED after 289~341ms` → `READY` → `coverPlaceholder HIDE`；快速加载**无 overlay SHOW**。
若日志出现 `overlay SHOW ... elapsed=504ms` 说明确实超过 500ms，再按上面 1-5 排查。
若出现 `BUFFERING again after READY (re-buffer)` 且用户觉得顿一下，可调大 `READY_DRIFT_GRACE_MS`
（VideoScreenViewModel，当前 1.2s）。

> 参考数据（模拟器、未登录 480P）：resolve 缓存命中 1ms、首帧 3.4s（模拟器软解）。
> 真机若仍慢，把「诊断日志 → 复制全部」发给开发者，按上面 1-5 定位。

## 2.1 自定义封面相关

- 换封面流程：导入预览/专辑详情 → 换封面 → 相册选择 / 网易云搜索 / 恢复 B 站原始封面。
- 日志（tag `Cover`）：
  - `netease search q='..' results=N`：搜索成功数；0 或报错 → 网易云接口/网络问题（非官方接口，可能变更）；
  - `cover download bytes=N`：图片下载；失败会记录异常；
  - `save cover album=X bytes=N path=..jpg`：解码压缩保存到 `files/covers/`；
  - `update album=X cover=<bilibili>`：恢复 B 站封面（本地文件路径被清空）。
- 现象「换了封面没变」：先看 `Cover` 日志是否有 `update`；若专辑详情一直显示旧图，检查
  `coverLocalPath` 文件是否存在（App 更新/清数据会丢 files 目录，但归档可恢复）；
  `album.coverUrl` 仅作远程兜底，本地文件优先。
- 归档备份封面：导出 JSON 里的 `coverBase64`（原图 ≤2MB 才写入）；导入时自动保存并更新；
  非法/超限 base64 会被忽略、不影响导入（见 LIBRARY_ARCHIVE.md）。

## 3. 导入相关

- **导入后没有曲目**：未填分曲文本时按设计就是「未分割专辑」（0 首，播放时整张播放）；
  在分曲编辑里手动添加或「文本导入」即可。
- **文本识别不到/识别少**：检查格式是否属于三种支持格式（见 IMPORT_FORMATS.md），
  日志 `[Import] splitText parsed=N skipped=M`；无法识别的行会在 UI 提示。
- **导入失败/视频不存在**：B 站 view/playurl 返回非 0 code（视频失效、审核、需登录）；
  UI 会展示中文原因，日志有 `[Api]`/`[Import]` 详情。
- **搜索不到/被风控**：B 站搜索接口风控较严（-412/-352 或返回无 video 分组），
  UI 会给可重试错误；可稍后再试。日志 `[Api] GET /x/web-interface/wbi/search/type ...`。
- **归档导入**：
  - 合并模式：`bvid+page` 匹配，存在则更新元信息+分曲，不存在则新建（新建需要联网补 B 站信息）；
  - 仅更新分割点：只替换已存在专辑的分曲，不会新建；匹配不到会跳过；
  - 覆盖全库：先自动备份到 `files/backups/`（保留 10 份）再清空导入，报告里有备份路径。
- **旧版占位曲**：v0.4.0 启动自动清理（单曲、0→总时长、标题=专辑名）；日志 `[App] legacy placeholder cleaned=N`。
- **数据库升级**：v0.5.0 Room v1→v2 仅 `ALTER TABLE albums ADD COLUMN coverLocalPath TEXT`，
  升级保留原有专辑/分曲（真机与模拟器均已实测）；升级失败日志中会出现 Room 异常，可直接看到 SQL 问题。

## 3.5 归档目录形态（SAF）排障

- 设置 → 备份与恢复 → 目录归档：
  - 「导出到文件夹」用系统文件夹选择器；**选择后还会弹一次「允许访问文件」确认，需点 ALLOW**，
    否则不会写入任何文件；
  - Android 10+ 限制直接使用 Download 根目录（提示 Can't use this folder）→ 新建子文件夹再 USE THIS FOLDER；
  - 导出目录结构：`biliaudio-library.json` + `<艺术家>/<专辑>/album.json` + `segments.json`；
  - 导入时递归读取 `*.json`（深度≤8、≤3000 文件、单文件≤8MB），未知文件忽略；选父目录也可以；
  - 导入模式：合并（默认）/ 仅更新分割点 / 覆盖全库（先自动备份；目录无有效专辑时拒绝清库）；
  - 失败提示：空目录/无 json →「没有找到可导入的归档文件」；坏 JSON 会进 parseErrors 但不中断其它专辑。
- 目录导入导出日志（tag `Archive`）：文件数、模式、耗时、created/updated/failed；UI 结果对话框也有统计。

## 3.6 音乐库索引/艺术家

- 分组基于 `android.icu.text.AlphabeticIndex`（中文拼音桶）；若发现中文分组不对，检查是否误调了
  `setMaxLabelCount()`（ICU 77 会稀疏标签集，导致拼音错位，v0.6.0 已踩坑）；
- B 站标题「【官方 MV】」等前缀会被跳过再分组；若新增前缀类型导致全落 `#`，在 `LetterIndex` 的装饰符集合里补充；
- 艺术家封面取该艺术家第一张专辑封面（按仓库专辑顺序）；点艺术家进入详情页。

## 3.7 动效（转场）排障

- 所有转场 token 在 `ui/theme/Motion.kt`（BiliMotion），转场实现集中在 `ui/navigation/AppNavHost.kt`；
  页面内不要写散落的 `tween(...)` 进入动画（v0.6.2 已移除 PageEnterAnimation，避免双重动画/闪白）。
- 页面类型选择标准（Material Motion）：顶级 Tab=fade through；有层级关系页面=shared axis X；
  上下展开（播放页）=shared axis Y；沉浸覆盖层=fade；可交互尺寸变化=spring（M3 standard spatial）。
- 若切页出现「两页同时清晰叠影」：通常是旧页 exit 用了 None 或与新页 alpha 未分段/重叠过多；
  若出现整页空白：检查是否只让新页从 alpha=0 淡入而旧页已先移除（v0.6.1 的问题），当前实现保留了 50~60ms 交叉重叠。
- 系统「动画时长缩放/移除动画」由 Compose 平台层处理；若整机动画被关闭，转场会接近瞬时完成，属预期。

## 4. 构建 / IDE

### Android Studio 点 Run 报 `Activity class {com.example.bilibiliplayer/...} does not exist`

原因：项目最初向导包名是 `com.example.bilibiliplayer`，后来改成 `com.wonderlandkid.biliaudio`，
IDE 的 Gradle 模型缓存/运行配置没刷新（自动同步可能被关闭）。

修复顺序：
1. **File → Sync Project with Gradle Files**，完成后再 Run；
2. 仍报旧包名：Run → Edit Configurations… → 选 `app` → General → Launch Options 选 **Default Activity**
   （或 Specified Activity 填 `com.wonderlandkid.biliaudio.MainActivity`）→ Apply；
3. 还不行：File → Invalidate Caches / Restart；或删掉该 Run Configuration 重新创建；
4. 建议开启：Settings → Build, Execution, Deployment → Build Tools → Gradle →
   「Reload project after changes = Any changes」；
5. 命令行绕过：`adb install -r dist/biliaudio-vX.Y.Z-debug.apk && adb shell am start -n com.wonderlandkid.biliaudio/.MainActivity`。

### Gradle 下载发行版超时

`services.gradle.org` 会 307 到 GitHub（本机 GitHub 不可达）。wrapper 已改为腾讯镜像：
`gradle/wrapper/gradle-wrapper.properties` 里 `distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-<ver>-bin.zip`。
新建项目若又指向官方地址，改这一行即可（阿里云备用：`https://mirrors.aliyun.com/macports/distfiles/gradle/`）。

### 模拟器

- AVD：`biliaudio_api36`（API 36 x86_64，KVM）；`scripts/emulator.sh start|stop|status`。
- 截图：`scripts/screenshot.sh <name> <wait>` → `docs/screenshots/<name>.png`。
- 注意：模拟器偶发 SystemUI ANR / 软解慢，真机数据更有参考价值。

## 5. B 站接口/登录

- 接口需带桌面 UA + `Referer: https://www.bilibili.com`；**媒体流不带 Referer 会 403**。
- wbi 签名（`WbiSigner`）：nav 的 `wbi_img` 取 key，mixin 重排后 `w_rid=md5(query+mixinKey)`；
  实现在 `data/net/WbiSigner.kt`，单测覆盖固定向量。
- 登录态校验：带 Cookie 请求 `/x/web-interface/nav`，`code=0 && isLogin=true`；`-101` 未登录。
- 扫码登录：`qrcode/generate` 拿 url 生成二维码 → 轮询 `qrcode/poll`（86101 未扫 / 86090 待确认 /
  86038 过期 / 0 成功，成功时从响应头 Set-Cookie 落盘）；成功路径需真实账号验证。
- 风控：412/-352 会出现「请求被风控」；搜索接口尤其敏感；已有限速/重试/错误提示。
- 播放 URL 约 2 小时有效，缓存 30 分钟；Cookie 变化自动清缓存；播放失败自动 forceRefresh 重试一次。

## 6. 常见检查清单（提交前）

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew clean :app:assembleDebug :app:testDebugUnitTest
# 确认 BUILD SUCCESSFUL、tests 全绿
# 检查 git status 无未跟踪构建物/密钥；dist/ 不提交
```
