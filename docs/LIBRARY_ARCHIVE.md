# 音乐库归档 v3：分域 bundle + 单分享文件（已实现 · v0.62.2 起冻结）

> **v3 已实现（v0.10.0），v0.62.2 起冻结**：此后只允许「加可选字段/加可选文件」，
> 改字段/语义必须升 version 并在 §10 记录。整库备份是目录 bundle：
> `manifest.json + library.json + settings.json(可选) + playlists.json(预留) + covers/`。
> 设计取舍见 [ARCHIVE_LAYOUT_V3.md](ARCHIVE_LAYOUT_V3.md)；v2 单文件整库格式已废弃，不兼容。
> `docs/samples/` 下的样例由单测 `ArchiveGoldenSamplesTest` 锁定，改 schema 必须同步样例。

## 1. 两种形态

### 1.1 整库 bundle（目录；zip 只是传输层）

```
<biliaudio-backup>/
├── manifest.json          # 文件清单 + sha256 + 大小
├── library.json           # 专辑 + 分曲（library 域）
├── settings.json          # 可移植设置（可选）
├── playlists.json         # 歌单域（可选；当前版本忽略导入）
└── covers/
    └── <sha256>.<ext>     # 内容寻址封面（jpg/png/webp）
```

- `library.json` 只放歌曲数据；封面只写 hash 引用，不内嵌 base64；
- `settings.json` 只放可移植设置；默认**不覆盖**本地设置（仅在「覆盖全库」恢复时应用）；
- `playlists.json` 已定义 schema，当前版本不导入歌单（报告里提示忽略）；
- 单专辑/分割点仍支持单 JSON 分享（可内嵌封面）。

### 1.2 单分享文件

| 文件 | kind | 说明 |
| --- | --- | --- |
| `album.json` | `album` | 单专辑 + 分曲 +（可选）内嵌封面 |
| `segments.json` | `segments` | bvid/page + 分割点 |

单分享文件仍是 `format=biliaudio-library`、`version=3`，见第 3 节。

## 2. manifest.json

```json
{
  "format": "biliaudio-archive",
  "version": 1,
  "kind": "library",
  "createdAt": "2026-11-08T14:30:00+08:00",
  "app": { "name": "BiliAudio", "version": "0.10.0" },
  "files": [
    { "path": "library.json", "type": "library", "version": 3, "sha256": "...", "bytes": 12345 },
    { "path": "settings.json", "type": "settings", "version": 1, "optional": true, "sha256": "...", "bytes": 200 },
    { "path": "covers/9f3c....jpg", "type": "cover", "sha256": "9f3c...", "bytes": 183421 }
  ]
}
```

导入规则：
- 先校验 manifest 格式/版本、文件存在、大小、sha256；
- required 文件缺失 → 失败；optional 文件缺失 → 忽略；
- 未知文件 type 目前忽略（不阻塞 library 恢复）；
- 只接受 `kind=library` 的整库 bundle。

## 3. library 域（library.json / 单 album / 单 segments）

```json
{
  "format": "biliaudio-library",
  "version": 3,
  "minReaderVersion": 3,
  "kind": "library",
  "archiveId": "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
  "exportedAt": "2026-11-15T18:00:00+08:00",
  "app": { "name": "BiliAudio", "version": "0.62.2" },
  "albums": [
    {
      "albumKey": "bilibili:BV1GJ411x7h7#1",
      "source": "bilibili",
      "bvid": "BV1GJ411x7h7",
      "page": 1,
      "pageCount": 1,
      "cidHint": 137649199,
      "title": "Abbey Road",
      "artist": "The Beatles",
      "durationMs": 213000,
      "updatedAt": 1794051200000,
      "cover": { "hash": "9f3c...", "mime": "image/jpeg", "byteLength": 183421 },
      "segments": [
        { "id": "00000000-0000-4000-8000-000000000001", "startMs": 0, "endMs": 45000, "title": "Come Together" }
      ]
    }
  ]
}
```

- 身份：`albumKey = source:bvid#page`（**严格格式，示例中的 `#p1` 写法已废弃**）；分曲 `id` 为 UUID（track.uid）；
- `page` 必填 ≥1；有 `pageCount` 时校验 `page ≤ pageCount`；
- `cidHint` 不可信，导入时重新解析流；
- 封面对象：bundle 中只有 `hash/mime/byteLength`；单 album 分享可额外带 `dataBase64`（解码后必须与 hash/byteLength 一致；原始字节 ≤2MB）；
- `segments` 空语义：
  - 字段省略 / null：更新已有专辑时**保留**原分曲；
  - `[]`：**清空**（回到未分割）；
  - 数组：替换。

## 4. settings 域（settings.json）

```json
{
  "format": "biliaudio-settings",
  "version": 1,
  "minReaderVersion": 1,
  "audioQuality": "AUTO",
  "darkTheme": null,
  "diagnosticLogsEnabled": false
}
```

- 只包含可移植白名单：音质、深浅色、诊断日志；
- 不导出：登录 Cookie、设备路径、播放队列/进度、上次 Tab、缓存；
- 本地设置仍由 SharedPreferences/DataStore 持有；导入默认不覆盖本地（REPLACE 恢复时除外）。

## 5. playlists 域（预留）

```json
{
  "format": "biliaudio-playlists",
  "version": 1,
  "playlists": [
    { "id": "pl_...", "name": "通勤", "items": [
      { "albumKey": "bilibili:BV...#1", "trackId": "trk_...", "startMs": 0, "title": "Come Together" }
    ] }
  ]
}
```

- `trackId` 为主引用，`albumKey+startMs` 回退定位，`title` 只做展示；
- 当前版本能读写 schema、导出空歌单，但导入会提示“忽略歌单”，等歌单功能接入后启用。

## 6. 校验上限

| 项 | 上限 |
| --- | --- |
| library 文本 | 64 MB |
| 专辑数 | 2000 |
| 单专辑分曲 | 500 |
| 标题/艺术家 | 200 字符 |
| 封面原始字节 | 2 MB |
| 封面 MIME | jpeg/png/webp |
| 单文件路径 | 规范化后不允许 `..` / 重复 |

## 7. 导入/导出行为

| 场景 | 行为 |
| --- | --- |
| 整库备份 | 设置 → 备份与恢复 → 导出整库到文件夹（bundle 目录） |
| 整库恢复 | 从文件夹恢复：校验 manifest/hash 后按模式导入 library；settings 仅在覆盖全库时恢复 |
| 覆盖全库 | 先自动备份为私有 `backups/biliaudio-backup-*` 目录，再清空导入 |
| 单专辑分享 | 专辑详情 → 导出专辑文件（单 JSON，封面内嵌） |
| 分割点分享 | 导入页粘贴/选择 `segments.json`，选「仅更新分割点」 |
| 自动备份 | REPLACE 前写 bundle 目录，保留最近 10 份 |

## 8. 本地数据库与归档的对应

| 本地 | 归档 |
| --- | --- |
| Room `albums`/`tracks` | `library.json` |
| Room `playlists`（将来） | `playlists.json` |
| SharedPreferences 设置 | `settings.json`（白名单） |
| 私有封面文件 | `covers/<sha256>.<ext>` |
| 播放状态/队列 | ❌ 不进归档 |

## 9. 旧格式

- v1/v2 的单文件整库归档不再支持（未发布版本，不做兼容）；
- 旧「快捷导入」文本/CSV 仍支持，会归一化成 v3 library 后走导入器。


## 多 P 分 P 字段（v0.25.0）

`archive.segments[]` 新增可选字段：

```json
{"startMs": 0, "endMs": 344000, "title": "P1", "sourceCid": 275493414, "sourcePage": 1, "sourceOffsetMs": 0}
```

- `sourceCid/sourcePage`：曲目实际所在分 P；
- `startMs/endMs`：专辑累计时间轴（进度条/歌词）；
- `sourceOffsetMs`：该曲目在分 P 流内的起点（多 P 通常为 0）；
- 旧归档字段缺失时按单流专辑恢复，不影响兼容性。

## 10. 冻结与兼容策略（v0.62.2 起）

> **冻结对象**：manifest v1 / library v3 / settings v1 / playlists v1 / 单分享 album+segments / user-data v1。
> 冻结后按以下规则演进，禁止随意改字段。

允许（不升版本）：
- 新增**可选**字段（旧读者 `ignoreUnknownKeys` 忽略、缺省值回退）；
- 新增 `manifest.files[].type`（旧读者忽略未知 type 文件）；
- 新增域文件（`optional: true`，旧读者忽略）。

必须升版本（`version` + `minReaderVersion`，旧 App 拒绝并提示升级）：
- 改已有字段名 / 类型 / 单位 / 必填性；
- 改 `albumKey` 规则、`segments` 空值语义、时间格式；
- 删除字段（旧读者仍可读，但语义丢失，需升版本说明）。

写作约定（已冻结）：
- 归档元数据时间：ISO-8601 字符串（`exportedAt` / `manifest.createdAt`）；
- 业务时间戳：epoch 毫秒 `Long`（`createdAt/updatedAt/lastPlayedAt`、user-data `exportedAt`、playlist 时间）；
- 导出 JSON 固定 `prettyPrint + 2 空格 + explicitNulls=false`；新增字段按模型声明顺序追加；
- 导出 albums 按 `albumKey` 升序（diff 友好，实现侧保证）。

## 11. 字段速查（冻结版）

| 域 | 字段 | 必填 | 说明 |
| --- | --- | --- | --- |
| 所有域 | `format` / `version` / `minReaderVersion` | ✅ | 版本校验；旧读者遇到更高 version 直接拒绝 |
| library | `kind` | ✅ | `library` / `album` / `segments`（单分享） |
| library | `archiveId` / `exportedAt` / `app` | ❌ | 导出会话元数据，仅用于诊断/展示 |
| album | `albumKey` | ❌* | `source:bvid#page`；有 bvid 时导出必写，导入会校验一致（*仅 `url` 手写场景可缺省） |
| album | `source` / `bvid` / `url` / `page` | 部分 | `source` 目前只支持 `bilibili`；`bvid` 与 `url` 至少一个 |
| album | `pageCount` / `cidHint` | ❌ | 提示字段；`cidHint` 导入时重新解析流，不可信 |
| album | `title/artist/durationMs` | ❌ | 展示/兜底元数据 |
| album | `createdAt/updatedAt/lastPlayedAt/playCount` | ❌ | 本地时间与统计快照；导入按 max 合并（权威统计以 user-data 备份为准） |
| album | `cover` | ❌ | `hash + mime + byteLength`；bundle 中禁止 `dataBase64` |
| segments | `id/startMs/endMs/title` | `startMs` ✅ | `id` = 曲目 UUID；`endMs` 缺省由下一段/专辑时长推导 |
| segments | `sourceCid/sourcePage/sourceOffsetMs` | ❌ | 多 P 字段；缺省按单流专辑处理 |
| segments | 整个数组 | ❌ | 缺省/null = 更新时保留原分曲；`[]` = 清空；数组 = 替换 |
| settings | `audioQuality/darkTheme/diagnosticLogsEnabled` | ❌ | 白名单；默认不覆盖本地，仅 REPLACE 恢复时应用 |
| playlists | item `albumKey + startMs` | ✅ | 定位主键；`trackId/title` 可选辅助 |
| user-data | `format/version/minReaderVersion/exportedAt` | ✅* | *`minReaderVersion` 旧备份缺失时按 1 处理 |
| user-data | playlist `name` | ✅ | 恢复时按 name 合并（重名会并入同一歌单） |
| user-data | stats `(source,bvid,page,startMs,wholeAlbum)` | ✅ | 恢复时 max 合并；`wholeAlbum=true` 表示未分割虚拟曲目 |
