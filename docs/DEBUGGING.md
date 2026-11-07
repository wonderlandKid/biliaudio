# 排障手册

> 遇到问题先看这里；诊断日志在 **设置 → 诊断与维护 → 诊断日志**（可复制/导出），
> 真机也可 `adb logcat -s BiliDiag`。

## 1. 诊断日志（Diag）说明

- 实现：`util/Diag.kt`；内存环形（最近 3000 行）+ Logcat tag `BiliDiag`；设置页可查看/复制/导出/清空。
- 日志格式：`MM-dd HH:mm:ss.SSS +进程运行ms [Tag] 内容 (线程)`。
- Tag 含义：

| Tag | 覆盖内容 |
| --- | --- |
| `App` | 启动、设备/版本信息、旧数据清理 |
| `Api` | B 站接口请求（path、HTTP、耗时、重试/风控） |
| `Resolver` | 播放流解析：缓存命中、音频/视频清晰度/码率、CDN host、耗时 |
| `Play` | 音频播放引擎：playAlbum/Track/Songs、队列与裁剪映射、READY、错误重试、前台服务 |
| `Video` | 歌词页视频：resolve、prepare、BUFFERING/READY、首帧、解码器（硬/软）、分片加载、错误 |
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

**已做的优化**（v0.4.0）：视频分辨率封顶 720P、同分辨率优先 AVC；直接 `setMediaItem(item, startPosition)`
省去 prepare 后 seek；未 READY 不做漂移校正；加载覆盖层显示阶段与等待秒数，>8s 提示可重试。

> 参考数据（模拟器、未登录 480P）：resolve 缓存命中 1ms、首帧 3.4s（模拟器软解）。
> 真机若仍慢，把「诊断日志 → 复制全部」发给开发者，按上面 1-5 定位。

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
