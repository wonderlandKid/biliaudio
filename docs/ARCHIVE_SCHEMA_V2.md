# 归档数据格式 v2 设计与兼容策略（提案）

> 状态：**已评审、待实施**（v0.8.4 数据层现状盘点）
> 目标：在继续加功能之前，把「身份 / 版本 / 可演进」定死，避免以后导入导出互相破坏。
> 现有 v1 格式见 [LIBRARY_ARCHIVE.md](LIBRARY_ARCHIVE.md)。

## 0. 结论

v1 的方向是对的（只有配置没有音频、JSON 人类可读、`format+version`、未知字段忽略、导入前自动备份），
但有几处**结构性问题**，越晚改成本越高：

1. **身份模型不一致（P0）**：文档/归档把 `bvid+page` 当稳定键，但数据库对 `bvid` 建了唯一索引，
   `getAlbumByBvid` 也不看 page；多 P / 同视频多版本永远无法共存，更新已有专辑时还会把 page 覆盖掉。
2. **`kind` 缺失（P0）**：目录归档的根索引 `biliaudio-library.json` 与「整库备份」是同一个 schema
   （`albums=[]`）。如果用户把根索引当单文件在「覆盖全库」模式导入，会先清库再导入 0 张专辑。
3. **空 segments 语义歧义（P0）**：新建时 `segments=[]` 表示未分割；更新已有专辑时 `segments=[]`
   却被当成“保留原分曲”；文档又写“为空 = 未分割”。同一份文件在不同模式下语义不同，且无法用空数组清空分曲。
4. **没有真正的 v2 演进规则（P1）**：目前只有 `version > current → 报错`；若在 v1 里加字段，
   旧版 App 会忽略字段并在再次导出时丢掉，不能靠“unknown keys ignored”兜底。
5. **内容模型缺关键信息（P1）**：缺 `pageCount`、`updatedAt`、`source`、封面 MIME/哈希/尺寸、
   音轨稳定 ID；这些以后做同步、多来源、播放队列恢复时都会被迫改 schema。
6. **目录树没有 manifest/校验（P2）**：重复 bvid、album.json/segments.json 不一致、根索引 albums 被静默忽略、
   导出中途失败留下半棵树，都没有检查。
7. **数据库 schema 未导出（P2）**：`exportSchema=false`，没有 Room schema JSON 入库，迁移只能靠单测覆盖。

## 1. 身份模型（先定这个，其他都围绕它）

### 1.1 专辑

建议 v2 明确：

```json
"source": "bilibili",
"sourceId": { "bvid": "BV1...", "page": 2 },
"albumKey": "bilibili:BV1...#p2"
```

- `source` 为枚举，v1 隐含 `bilibili`；以后加网易云/本地文件时不用再猜。
- `albumKey` 是跨设备稳定键，也是导入 upsert 的唯一键；**不要用数据库自增 id**。
- 数据库对应改：`albums` 唯一索引从 `bvid` 改为 `(source, bvid, page)`（v1 数据迁移时 source 填 bilibili）；
  DAO/Repository 的 `getAlbumByBvid(bvid)` 改为 `getAlbumBySourceKey(source, bvid, page)`。
- `page` 不再默认 1：有 `bvid` 时必填，并校验 `1 <= page <= pageCount`（pageCount 可缺省，缺省时只校验 >=1）。
  手写分割点文件若省略 page，导入时明确按 1 处理并在报告里提示，而不是静默。

### 1.2 分曲

产品上“单曲不设独立实体”，但归档/未来功能（收藏、播放历史、手动静音某曲）需要稳定引用：

```json
"segments": [
  { "id": "trk_9f3c...", "startMs": 0, "endMs": 45000, "title": "..." }
]
```

- `id`：数据库新增 `tracks.uid`（UUID，生成一次，随分曲导出）；不用自增 id。
- 兼容：手写文件可省略 `id`，导入时按 `albumKey + startMs + 标题` 生成确定性 id；
  以后改标题/移动分割点会视为不同曲目（可接受，语义清晰）。
- `endMs`：v2 显式写出；导入校验 `startMs < endMs <= durationMs`，且相邻分曲不重叠。
  v1 只有 startMs，end 由“下一条/专辑总时长”推导，保持可读。

## 2. 顶层 schema（v2）

```json
{
  "format": "biliaudio-library",
  "version": 2,
  "minReaderVersion": 2,
  "kind": "library",
  "archiveId": "8f0c...",
  "exportedAt": "2026-11-08T13:30:00+08:00",
  "app": { "name": "BiliAudio", "version": "0.9.0" },
  "features": ["stable-track-id", "cover-hash"],
  "settings": { "version": 1, "audioQuality": "AUTO", "darkTheme": null, "diagnosticLogsEnabled": true },
  "albums": [ ... ]
}
```

- `kind`：`library`（整库）/ `album`（单专辑）/ `segments`（仅分割点）/ `index`（目录归档根索引）。
  - `index` 只允许 `albums=[]`，**导入模式为 REPLACE 时直接拒绝并提示“这是目录索引，不是音乐库备份”**；
  - `album` / `segments` 恰好 1 张专辑；
  - `library` 允许 0 张（用户确实可以导出空库，但 REPLACE 需二次确认，沿用现有自动备份）。
- `version`：格式主版本；破坏性/语义变化才 +1。
- `minReaderVersion`：写出方要求的最低读取版本（通常=version）。旧 App 看到 `version > supported` 或
  `minReaderVersion > supported` 都拒绝，并提示升级，而不是部分导入。
- `features`：可选能力列表；读取方不认识某个 feature 时：
  - feature 是“数据性”的（例如新的分曲字段）→ 拒绝，避免丢数据；
  - feature 是“展示性”的 → 可忽略。
  这一条用来替代“偷偷在 v1 加字段”。
- `archiveId`：导出时生成 UUID，便于日志/去重/增量同步（未来）。
- `settings.version`：设置对象单独版本化；新增设置时只改这里，不污染主 version。

## 3. 专辑/封面/分曲字段

```json
{
  "albumKey": "bilibili:BV1GJ411x7h7#p1",
  "source": "bilibili",
  "bvid": "BV1GJ411x7h7",
  "page": 1,
  "pageCount": 13,
  "cid": 137649199,
  "title": "...",
  "artist": "...",
  "durationMs": 3565000,
  "updatedAt": 1794051200000,
  "cover": {
    "mime": "image/jpeg",
    "sha256": "9f3c...",
    "byteLength": 183421,
    "dataBase64": "..."
  },
  "segments": [ ... ]
}
```

- **封面**：v2 用对象，不再用裸 `coverBase64`；读取仍兼容 v1 旧字段。
  建议单文件仍内嵌 base64（保持单文件可分享），目录归档改为 `cover.jpg` + `cover.sha256`，
  album.json 里写 `"coverFile": "cover.jpg"`、`"coverHash"`，避免 JSON 膨胀。
  校验：MIME 限 jpeg/png/webp；原始字节 ≤2MB；解码前限制像素尺寸（防止解压炸弹）。
- **自定义封面路径不写绝对路径**（`coverLocalPath` 只在本地 DB），归档只存图片字节/文件 + hash。
- `cid` 不可信：保留仅作提示，导入时重新解析；或标 `"cidHint"`。
- 新增 `updatedAt`，用于跨设备合并时的 last-writer / 冲突提示；v1 读取时缺省为 exportedAt/now。
- `artist` 保持字符串（产品现状）；未来多艺术家再升 `artists[]`，旧字段保留兼容。

## 4. 空值语义（P0 必须定死）

v2 用“字段存在性”区分：

| 写法 | 新建 | 更新已有 |
| --- | --- | --- |
| `segments` 字段省略 | 未分割（跟随 B 站整段） | **保留原分曲** |
| `"segments": null` | 未分割 | **清空分曲（回到未分割）** |
| `"segments": []` | 未分割 | **清空分曲** |
| `"segments": [ ... ]` | 使用给定分曲 | 替换分曲 |

v1 没有 null 语义：为了向后兼容，v1 读取时把“省略或空数组”按上表“省略”处理（即保留），
并在文档里明确 **v1 不能表达“清空分曲”**；需要清空请用 v2。

## 5. 目录归档 v2

```
<bilibili 音乐库>/
├── biliaudio-library.json      # kind=index：version/minReaderVersion/features/settings + manifest
├── <艺术家>/<专辑>/
│   ├── album.json              # kind=album（coverFile/segmentsFile 引用）
│   ├── segments.json           # kind=segments（可独立分享）
│   ├── cover.jpg               # 可选
│   └── checksum.sha256         # 可选，包含文件清单
```

- 根索引 `manifest`：`[{ "path": "...", "albumKey": "...", "sha256": "..." }]`，导入时校验完整性；
- 目录名不作为身份，只作展示；同一 albumKey 出现多份 → 报错（v1 现在静默按顺序更新）；
- 导出先写临时目录/文件、完整成功后原子替换；失败不留下半棵树；
- 目录归档导入时，**所有合并完成的专辑必须经过一次全局 `validateAndNormalize`**（v1 现在逐文件校验，
  合并后没有再做全局重复/越界检查）。

## 6. 兼容与迁移策略

- **读取方**：支持读取 `version ∈ [1, CURRENT]`；`minReaderVersion` 高于支持版本 → 报错；
  `kind=index` 不允许进“覆盖全库”流程。
- **写出方**：默认写最新版本；设置里提供“导出兼容 v1 格式（可能丢失新字段）”选项（明确提示）。
- **版本规则**：
  - 纯展示性、可丢失的新增字段：可同版本添加，但必须写进 `features` 且读取方可忽略；
  - 数据性/语义性变化：**version +1**，并提供 v(n-1) 读取适配；
  - 不允许复用字段名/改变既有字段含义；废弃字段保留读取，不再写出。
- **数据库**：`exportSchema=true`，把 Room schema JSON 提交到 `app/schemas/`；归档 v2 落地时一并做
  DB v3 迁移（`source`、`page` 索引、`tracks.uid`、`updatedAt`），用 Room migration test 覆盖从 v1/v2 升级。
- **黄金文件测试**：`docs/samples/` 下放 v1、v2、目录归档样例；单测覆盖：
  - v1 读取 → v2 写出 → v2 读取字段一致；
  - 旧版本（模拟 version 过高）拒绝；
  - kind=index 在 REPLACE 下被拒绝；
  - 空/缺省/null segments 三种语义；
  - 重复 albumKey、cover hash 不匹配、manifest 缺文件、路径穿越、超大图片；
  - 导入导出幂等（同文件导入两次结果一致）。

## 7. 建议的实施顺序

| 优先级 | 改动 | 风险 | 说明 |
| --- | --- | --- | --- |
| P0-1 | DB `albums` 唯一键改 `(source,bvid,page)` + DAO/Repository 匹配 page | 中 | 需 DB migration v3；当前 multi-P 未支持，但越早改越便宜 |
| P0-2 | 归档增加 `kind` 字段；`index` 禁止 REPLACE；根索引/整库区分 | 低 | 纯读取校验，可先兼容 v1 |
| P0-3 | 明确 segments 省略/空/null 语义并修文档与实现 | 低 | v1 只读兼容，v2 写全新语义 |
| P1-1 | v2 schema（version=2, minReaderVersion, features, albumKey, track uid, cover 对象） | 中 | 写 v2，读 v1+v2；设置里保留 v1 导出 |
| P1-2 | DB tracks.uid + albums.updatedAt/pageCount/source；exportSchema | 中 | 与 P0-1 同一个 migration 做掉 |
| P2 | 目录 manifest/checksum、zip 打包、原子导出、JSON Schema 校验 | 低-中 | 分享/备份可靠性提升 |

## 8. 现有 v1 需要立刻修的小问题（不依赖 v2）

1. `LibraryArchiveCodec.MAX_COVER_BASE64_LENGTH = 8MB` 与导出侧 `MAX_INLINE_COVER_BYTES = 2MB`、
   文档“原始字节 ≤2MB”不一致；导入应按“解码后原始字节 ≤2MB”校验，避免 6MB 垃圾内嵌图占内存。
2. `nowIso()` 输出 `+0800`（无冒号），文档示例是 `+08:00`；改成 `java.time.OffsetDateTime` 或明确接受两种。
3. 目录导入合并后缺少全局校验（重复 albumKey、页数越界）。
4. 根索引 `albums=[]` 与“空库备份”同形，UI 在 REPLACE 时至少二次确认并提示“0 张专辑会清空音乐库”。
5. `updateExisting` 不看 page（当前 DB 限制），至少加注释/校验，避免未来误改。
6. 封面 base64 解码前不限制像素尺寸，建议解码时用 `BitmapFactory.Options.inJustDecodeBounds` 限制（防解压炸弹）。
