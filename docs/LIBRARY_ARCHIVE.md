# 音乐库归档文件格式（v1，已实现）

> **状态**：M1 + M2 已实现（v0.2.0）：数据层 `LibraryArchiveCodec` / `LibraryArchiveRepositoryImpl`，
> UI 包含导入页「归档导入」Tab、设置页「备份与恢复」、专辑详情「导出专辑文件」；自动备份目录
> `files/backups/`（保留最近 10 份）。M3（自动定时备份 / YAML / 模板下载）按需再排。
>
> 目标：用一个（或若干个）文件承载「导入的专辑 + 分曲时间点」，既能当**整库备份**，
> 也能当**单张专辑分享文件**、**评论区分割点文件**。导入导出共用同一套格式，避免维护两套。

## 1. 设计原则

1. **一个格式，三种用法**：整库备份 / 单专辑 / 只导入分割点，都使用同一个 JSON 结构，靠导入模式区分行为。
2. **文件里只有"配置"，没有音频**：B 站内容不落盘、不重新分发（版权 + 体积）；播放时按 `bvid/page` 重新解析流。
3. **机器可写 + 人可改**：时间点导出用毫秒整数（精确），导入同时接受 `"mm:ss"` 等字符串（方便手写/评论区整理）。
4. **稳定业务键**：专辑用 `bvid + page` 作为跨设备匹配键；数据库自增 id 不写进文件。
5. **向后兼容**：保留现有「快捷导入」格式（JSON 数组 / CSV / 文本块），统一入口自动识别。
6. **可演进**：`format` + `version` 字段，未知高版本明确报错，低版本兼容导入。

## 2. 文件格式（JSON）

扩展名建议：`*.biliaudio.json`；MIME：`application/json`。

### 2.1 完整示例（整库备份）

```json
{
  "format": "biliaudio-library",
  "version": 1,
  "exportedAt": "2026-11-07T22:30:00+08:00",
  "app": { "name": "BiliAudio", "version": "0.1.0" },
  "settings": {
    "audioQuality": "AUTO",
    "darkTheme": null
  },
  "albums": [
    {
      "bvid": "BV1GJ411x7h7",
      "page": 1,
      "cid": 137649199,
      "title": "Never Gonna Give You Up（完整专辑）",
      "artist": "索尼音乐中国",
      "coverUrl": "https://i1.hdslb.com/bfs/archive/5242750857121e05146d5d5b13a47a2a6dd36e98.jpg",
      "durationMs": 213000,
      "createdAt": 1794046700000,
      "lastPlayedAt": 1794051200000,
      "playCount": 12,
      "segments": [
        { "startMs": 0, "title": "Never Gonna Give You Up" },
        { "startMs": 45000, "title": "Whenever You Need Somebody" },
        { "startMs": 120000, "title": "Together Forever" }
      ]
    }
  ]
}
```

### 2.2 单张专辑文件

同一结构，`albums` 只含一项。手动编写时可省略所有可选字段：

```json
{
  "format": "biliaudio-library",
  "version": 1,
  "albums": [
    {
      "bvid": "BV1GJ411x7h7",
      "segments": [
        { "start": "00:00", "name": "Never Gonna Give You Up" },
        { "start": "00:45", "name": "Whenever You Need Somebody" }
      ]
    }
  ]
}
```

- `title/artist/coverUrl/durationMs` 省略时：专辑不存在 → 用 B 站 view 信息补全后创建；专辑已存在 → 保留原值。
- `segments` 省略/为空 → 专辑处于「未分割」状态：播放时整段视频按一首虚拟歌曲播放，
  之后可在分曲编辑页补分割点（不会再生成需要手动删除的占位曲）。

### 2.3 只导入分割点（评论区分割点文件）

同上（最小文件），配合导入模式「**仅更新分割点**」：按 `bvid+page` 匹配已有专辑，
只替换分曲，不新建专辑、不改元数据；匹配不到则记为跳过并提示。

### 2.4 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `format` | ✅ | 固定 `"biliaudio-library"` |
| `version` | ✅ | 格式版本，当前 `1` |
| `exportedAt` | ❌ | ISO-8601 时间，仅信息用途 |
| `app` | ❌ | 导出方信息（name/version） |
| `settings` | ❌ | 可选偏好（音质/深浅色）；不含登录 Cookie |
| `albums[]` | ✅ | 专辑数组，可为空表示空库（覆盖模式会清空） |
| `albums[].bvid` | ✅ | 支持 BV 号；也接受 `url` 字段（视频链接） |
| `albums[].page` | ❌ | 分 P 序号，默认 1 |
| `albums[].cid` | ❌ | 视频流 id，导入时可从 B 站重新解析，不信任文件值 |
| `albums[].title` | 新建时❌* | 省略则用 B 站视频标题 |
| `albums[].artist` | 新建时❌* | 省略则用 UP 主名 |
| `albums[].coverUrl` | ❌ | 省略则用视频封面；仅接受 http/https |
| `albums[].durationMs` | 新建时❌* | 省略则用 view 接口时长 |
| `albums[].createdAt/lastPlayedAt/playCount` | ❌ | 用户数据，恢复时尽量保留 |
| `albums[].segments[]` | ❌ | 为空 = 未分割（整段按一首虚拟播放）；最多 500 条 |
| `segments[].startMs` 或 `segments[].start` | ✅ | 二选一：毫秒整数，或 `ss`/`mm:ss`/`mm:ss.SSS`/`hh:mm:ss` |
| `segments[].title` 或 `segments[].name` | ❌ | 省略自动命名「曲目 N」 |

\* 标注「新建时❌」的字段：文件里没有且需要新建专辑时，会请求 B 站 view 接口补全；离线则报错而不是写入残缺数据。

### 2.5 校验规则（文件不可信）

- 文件大小上限 5 MB；专辑数 ≤ 2000；单专辑分曲 ≤ 500。
- 分曲 `startMs` 严格递增且 < `durationMs`；越界/重复直接报错并指出第几项。
- `bvid` 必须匹配 `^BV[0-9A-Za-z]{10}$`（或合法 `av`/链接经解析后符合）。
- 标题长度 ≤ 200；URL 仅 http/https。
- 导入全程在 IO 线程；批量请求 B 站时串行 + 限速（复用现有 BiliApi 节流）。

## 3. 导入语义（三种模式）

| 模式 | 适用场景 | 行为 |
| --- | --- | --- |
| **合并导入**（默认） | 整库备份恢复、日常导入 | `bvid+page` 匹配：不存在 → 新建；存在 → 更新元数据与分曲；可选保留 `playCount/lastPlayedAt`（默认保留较大值） |
| **仅更新分割点** | 评论区分割点文件 | 只替换已存在专辑的 `segments`，不新建、不改元数据；匹配不到 → 跳过并报告 |
| **覆盖全库** | 迁移/回滚到备份 | 清空本地库后导入（**先自动导出当前库到私有目录作为回滚点**，UI 二次确认） |

导入完成给出报告：`新建 N 张 / 更新 M 张 / 跳过 K 张 / 失败 L 张`，失败逐条给中文原因。

## 4. 与现有「快捷导入」的关系

统一入口自动识别：

| 文件/文本内容 | 识别为 | 处理 |
| --- | --- | --- |
| JSON 对象且 `format == "biliaudio-library"` | 归档格式 | 走本方案 |
| JSON 数组 | 旧批量格式 | 转成内部归档模型后走同一导入器（不新建解析分支） |
| CSV / 文本块 | 旧批量格式 | 同上，逐行解析 |

即：**旧格式不废弃，最终都归一化成 `LibraryArchive` 再执行导入**，只有一种落库路径。

## 5. UI 方案

### 5.1 导入页（现有「批量导入」Tab 改造）

- 文件选择（SAF `OpenDocument`，支持 `json/txt/yaml` 后缀）或直接粘贴文本；
- 解析后展示**预览卡片**：识别出的专辑数/分曲数、每张专辑是「新建/更新/跳过」；
- 导入模式单选：合并导入（默认）/ 仅更新分割点 / 覆盖全库（红色危险项 + 二次确认）；
- 「导出示例文件」按钮（下载模板，方便手写分割点）；
- 结果报告列表（沿用现有成功/失败 UI）。

### 5.2 设置页 → 新增「备份与恢复」

- 「导出音乐库」：`CreateDocument("biliaudio-library-20261107-2230.json")`，导出全部专辑+设置；
- 「导入/恢复」：跳转统一导入页；
- 显示「上次备份时间」（可后续加自动备份）。

### 5.3 专辑详情 → 更多菜单

- 「导出专辑文件」：单专辑 JSON，方便分享给朋友/另一台设备导入。

## 6. YAML 取舍

**推荐：v1 只做 JSON。** 理由：
- 项目已有 kotlinx.serialization，零新增依赖、序列化/校验可靠；
- 程序生成 + 机器解析为主，JSON 无缩进/类型歧义；可用 `jq`、JSON Schema 校验；
- 手写分曲时我们可以支持「宽松写法」（时间字符串、title/name 别名、可省略元数据），JSON 依然够用；
- YAML 需要引入 kaml（额外依赖），且 YAML 1.1 的 `on/off/yes/no`、时间类型等坑容易造成解析歧义。

如果确实想要 YAML：建议 M3 阶段加 **只读 YAML 导入**（kaml → 同一 `LibraryArchive` 模型），导出仍默认 JSON；
可选的 "友好 YAML 导出" 仅当用户明确需要再做。

## 7. 代码改动清单（实施时）

**数据层（新增）**
- `data/model/LibraryArchive.kt`：`@Serializable` 的 `LibraryArchive / ArchiveAlbum / ArchiveSegment / ArchiveSettings` + `ArchiveImportMode` + `ArchiveImportReport`。
- `data/impl/LibraryArchiveCodec.kt`：解析（JSON/旧格式归一化）、校验、序列化（prettyPrint）、格式识别、版本检查。
- `data/impl/LibraryArchiveRepository.kt`：`exportAll()` / `exportAlbum(albumId)` / `importArchive(text, mode)`；复用 `AlbumRepository` 落库，保证幂等。
- `AlbumRepository` 接口新增（Lead 统一改契约）：`suspend fun snapshotAll(): List<AlbumWithTracks>`、`suspend fun findByBvidPage(bvid, page): Album?`、`suspend fun applySegments(albumId, segments)`。
- `BatchImportParser`：保留，输出统一为 `LibraryArchive`。

**UI**
- `ui/imports/ImportViewModel.kt`、`ImportScreen.kt`：批量 Tab 改为归档导入（模式选择/预览/示例下载/结果报告）。
- `ui/settings/SettingsScreen.kt` + ViewModel：备份与恢复入口（导出）。
- `ui/album/AlbumDetailScreen.kt`：更多菜单加「导出专辑文件」。

**依赖**：无新增；导出用 `ActivityResultContracts.CreateDocument`，读取已有 `OpenDocument`。

**测试**
- Codec：完整往返（导出→导入字段一致）、旧格式归一化、时间字符串/毫秒双写法、未知版本拒绝、损坏 JSON 报错。
- 导入模式：merge（新建/更新/保留播放次数）、segments-only（跳过未匹配）、replace（清空+导入，先备份）。
- 校验：非法 bvid、分曲越界/乱序、超大文件、封面非 http、空库导出导入。
- 使用现有 79 个单测基础，新增约 25–35 个用例。

## 8. 分阶段实施

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| M1 | 归档模型 + Codec + 导出全库/单专辑 + 导入合并模式（含旧格式归一化） | 导出→删库→导入完整还原；单测通过 |
| M2 | 「仅更新分割点」+「覆盖全库」（自动备份+二次确认）+ 预览报告 | 评论区分割点文件可单独套用；覆盖模式可回滚 |
| M3（可选） | 自动备份（每次修改写 `files/backups/`，保留近 5 份）、YAML 只读导入、导出模板按钮 | 需求确认后再做 |

## 9. 附录：JSON Schema（v1，摘要）

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "BiliAudio Library Archive v1",
  "type": "object",
  "required": ["format", "version", "albums"],
  "properties": {
    "format": { "const": "biliaudio-library" },
    "version": { "const": 1 },
    "exportedAt": { "type": "string" },
    "app": { "type": "object", "additionalProperties": true },
    "settings": {
      "type": "object",
      "properties": {
        "audioQuality": { "enum": ["AUTO", "HIGH", "MEDIUM", "LOW"] },
        "darkTheme": { "type": ["boolean", "null"] }
      },
      "additionalProperties": true
    },
    "albums": {
      "type": "array",
      "maxItems": 2000,
      "items": {
        "type": "object",
        "required": ["bvid"],
        "properties": {
          "bvid": { "type": "string", "pattern": "^BV[0-9A-Za-z]{10}$" },
          "url": { "type": "string" },
          "page": { "type": "integer", "minimum": 1 },
          "cid": { "type": "integer", "minimum": 1 },
          "title": { "type": "string", "maxLength": 200 },
          "artist": { "type": "string", "maxLength": 200 },
          "coverUrl": { "type": "string", "pattern": "^https?://" },
          "durationMs": { "type": "integer", "minimum": 1 },
          "createdAt": { "type": "integer" },
          "lastPlayedAt": { "type": "integer" },
          "playCount": { "type": "integer", "minimum": 0 },
          "segments": {
            "type": "array",
            "maxItems": 500,
            "items": {
              "type": "object",
              "properties": {
                "startMs": { "type": "integer", "minimum": 0 },
                "start": { "type": "string" },
                "title": { "type": "string", "maxLength": 200 },
                "name": { "type": "string", "maxLength": 200 }
              },
              "oneOf": [
                { "required": ["startMs"] },
                { "required": ["start"] }
              ]
            }
          }
        }
      }
    }
  }
}
```
