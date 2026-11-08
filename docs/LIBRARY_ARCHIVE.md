# 音乐库归档 v3：分域 bundle + 单分享文件（已实现）

> **v3 已实现（v0.10.0）**。整库备份不再是单个 JSON，而是目录 bundle：
> `manifest.json + library.json + settings.json(可选) + covers/`。
> 设计取舍见 [ARCHIVE_LAYOUT_V3.md](ARCHIVE_LAYOUT_V3.md)；v2 单文件整库格式已废弃，不兼容。

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
        { "id": "trk_...", "startMs": 0, "endMs": 45000, "title": "Come Together" }
      ]
    }
  ]
}
```

- 身份：`albumKey = source:bvid#page`；分曲 `id` 为 UUID；
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
