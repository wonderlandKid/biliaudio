# 音乐库归档格式 v2（已实现）

> 状态：**v2 已实现（v0.9.0）**。v1 不再兼容：旧归档请用旧版 App 导出为 v2，或按本文重写。
> 设计背景与取舍见 [ARCHIVE_SCHEMA_V2.md](ARCHIVE_SCHEMA_V2.md)。

## 1. 设计原则

1. **一个格式，三种用法**：整库备份 / 单专辑 / 仅分割点，靠 `kind` 与导入模式区分。
2. **只有配置，没有音频**：播放时按 `bvid/page` 重新解析 B 站流。
3. **稳定身份**：`albumKey = source:bvid#page`；分曲 `id` 为跨设备 UUID，不用自增 id。
4. **可演进**：`version` + `minReaderVersion` + `features`；旧版读到高版本直接拒绝，不部分导入。
5. **不可信输入**：大小、bvid、URL、分曲边界、封面 hash 全部校验。

## 2. 顶层结构

```json
{
  "format": "biliaudio-library",
  "version": 2,
  "minReaderVersion": 2,
  "kind": "library",
  "archiveId": "c0a8...",
  "exportedAt": "2026-11-08T13:47:10+08:00",
  "app": { "name": "BiliAudio", "version": "0.9.0" },
  "features": ["stable-album-key", "stable-track-id", "cover-sha256", "explicit-end-ms"],
  "settings": {
    "version": 1,
    "audioQuality": "AUTO",
    "darkTheme": null,
    "diagnosticLogsEnabled": true
  },
  "albums": []
}
```

- `kind`：
  - `library`：整库（`albums` 可为空）；
  - `album`：单专辑（恰好 1 张）；
  - `segments`：仅分割点（恰好 1 张，可只写定位信息 + `segments`）；
  - `index`：目录归档根索引（`albums` 必须为空；**禁止作为音乐库/专辑直接导入，尤其禁止 REPLACE**）。
- `version`：格式主版本；当前 = 2。读取方支持版本区间，超过当前版本拒绝。
- `minReaderVersion`：写出方要求的最低读取版本；高于当前 App 版本拒绝。
- `features`：读取方不认识的数据特性直接拒绝（避免静默丢字段）。
- `settings.version`：设置对象独立版本，新增设置只升这里。
- `archiveId`：一次导出的唯一 ID（日志/未来增量同步用）。

## 3. 专辑与分曲

```json
{
  "albumKey": "bilibili:BV1GJ411x7h7#p1",
  "source": "bilibili",
  "bvid": "BV1GJ411x7h7",
  "page": 1,
  "pageCount": 13,
  "cidHint": 137649199,
  "title": "Abbey Road",
  "artist": "The Beatles",
  "durationMs": 213000,
  "updatedAt": 1794051200000,
  "createdAt": 1794046700000,
  "lastPlayedAt": 1794051200000,
  "playCount": 12,
  "cover": {
    "mime": "image/jpeg",
    "sha256": "9f3c...64位小写hex",
    "byteLength": 183421,
    "dataBase64": "/9j/4AAQSk..."
  },
  "segments": [
    { "id": "trk_9f3c...", "startMs": 0, "endMs": 45000, "title": "Come Together" },
    { "id": "trk_1a2b...", "startMs": 45000, "endMs": 120000, "title": "Something" }
  ]
}
```

- `albumKey` 导出时写入；导入时校验与 `source/bvid/page` 一致，重复即报错。
- `page` 必填（≥1）；有 `pageCount` 时校验 `page ≤ pageCount`。
- `cidHint` 仅提示，导入时重新解析流，**不信任**。
- 封面对象：MIME 限 `image/jpeg|png|webp`；`dataBase64` 解码后原始字节 ≤2MB；
  `byteLength` 必须一致；`sha256` 必须匹配，不匹配直接报错。
- 分曲：
  - `id` 可选（手写可省略，导入时生成 UUID）；存在时需全局唯一；
  - `startMs` 必填、非负、严格递增；
  - `endMs` 可选；若写出必须 `> startMs`、`≤ durationMs`，且不与下一首重叠；
  - 省略 `endMs` 时按「下一首 startMs / 专辑总时长」推导。

### 3.1 `segments` 空值语义

| 写法 | 新建专辑 | 更新已有专辑 |
| --- | --- | --- |
| 字段省略（解码为 null） | 未分割 | **保留原分曲** |
| `"segments": null` | 未分割 | 保留原分曲 |
| `"segments": []` | 未分割 | **清空分曲（回到未分割）** |
| `"segments": [...]` | 使用给定分曲 | 替换分曲 |

## 4. 目录形态

```
<顶层>/
├── biliaudio-library.json        # kind=index：设置 + 版本；albums=[]
├── <艺术家>/<专辑>/
│   ├── album.json                # kind=album：元信息；segments 字段省略
│   └── segments.json             # kind=segments：定位信息 + segments（未分割 = []）
```

- 大库/大量自定义封面建议用目录归档：单文件归档上限 64MB，目录形态每个文件独立、无全局大小限制；
- 目录名只用于展示与安全化，**身份来自文件里的 albumKey/bvid/page**；
- 导入时每个目录合并 album.json + segments.json，再做一次全局校验（重复 albumKey 会报错）；
- 根索引只取 settings；把根索引当单文件导入会被拒绝；
- 其它文件（README、.DS_Store 等）忽略；
- 路径会做非法字符/控制字符/首尾点空格/长度清理；同名目录追加 ` (2)` 或 BV 号。

## 5. 校验上限

| 项 | 上限 |
| --- | --- |
| 单文件大小 | 64 MB（含内嵌封面；超过请用目录归档） |
| 专辑数 | 2000 |
| 单专辑分曲 | 500 |
| 标题/艺术家 | 200 字符 |
| 分曲 id | 64 字符 |
| 封面原始字节 | 2 MB |
| 封面 MIME | jpeg/png/webp |

源字段：目前只接受 `source=bilibili`；以后新增来源需提升版本或增加 feature。

## 6. 导入模式

| 模式 | 行为 |
| --- | --- |
| **合并**（默认） | 按 albumKey upsert：新建 / 更新元信息 + 分曲（null 保留、[] 清空、数组替换） |
| **仅更新分割点** | 只更新已存在专辑的分曲；不存在跳过；不新建、不改元信息 |
| **覆盖全库** | 先自动备份到 `files/backups/`，清空后导入；`kind=index` 或没有有效专辑时拒绝 |

导入报告：新建 / 更新 / 仅分割点更新 / 跳过 / 失败（含解析错误）。

## 7. 旧格式

- **v1 归档不再支持**：读取时明确提示“旧版归档不再支持，请重新导出”；
- 旧「快捷导入」文本格式（JSON 数组 / CSV / 文本块）仍然支持，会归一化成 v2 归档再走同一导入器
  （这是输入便捷格式，不是 v1 归档兼容）。

## 8. 迁移与数据库

- Room DB v3：`albums` 增加 `source`，唯一索引 `(source,bvid,page)`；`tracks` 增加 `uid`（UUID）唯一索引；
- `MIGRATION_2_3` 会保留已有专辑/分曲并回填 uid；
- 归档里的绝对路径（`coverLocalPath`）永不写出，封面只以字节 + hash 形式保存。
