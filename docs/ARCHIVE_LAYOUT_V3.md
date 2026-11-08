# 归档/配置布局 v3：按域拆分（提案，未实施）

> 状态：**设计定稿候选**。目标不是“再换一次格式”，而是把**数据域**和**容器**分清，
> 以后加歌单/设置/收藏/同步时不再动 library 的数据结构。
>
> 前置结论：v2 的单文件整库 JSON（library + settings + cover base64 全在一个文件）不作为最终形态；
> **歌曲数据仍可以放在一个 library.json 里**，但歌单、设置、封面、清单必须拆开。

## 0. 一句话建议

**分域拆文件，打包成一个 bundle（目录或 zip）运输；单 JSON 只用于“分享单元”（单专辑/分割点/歌单）。**

## 1. 为什么不能全塞一个 JSON

| 问题 | 全塞一起 | 分域 |
| --- | --- | --- |
| 演进 | 加歌单/设置要升整个归档版本 | 各域独立 version |
| 体积 | 封面 base64 撑爆整库文件 | 封面独立文件，可去重 |
| 恢复 | 恢复音乐库时被迫恢复主题/设置 | 设置可选导入 |
| 合并 | 设置变了也要重写整个库 | 按域 merge/replace |
| 分享 | 分享歌单要带整库 | 只发 playlists/单个 playlist |
| 损坏 | 一个字段错导致整包不可读 | manifest 可定位单文件 |

## 2. 最终布局：bundle + 单分享文件

### 2.1 整库 / 备份 bundle

```
<biliaudio-backup>/
├── manifest.json          # 容器清单：文件列表、sha256、版本
├── library.json           # 专辑 + 分曲（歌曲域，唯一的大 JSON）
├── playlists.json         # 歌单（可选；将来有歌单才写）
├── settings.json          # 可移植设置白名单（可选）
└── covers/
    └── <sha256>.jpg       # 内容寻址封面（去重；可 png/webp）
```

- 目录形态 = 可人工浏览/编辑；同结构 zip = 单文件传输（`.biliaudio.zip`）。
- **library.json 只放歌曲域**：专辑、分曲、封面引用；不再放 settings/playlists。
- 封面不内嵌 JSON：`covers/<sha256>` 内容寻址，多专辑共用同一封面只存一份。
  library.json 里只写 `cover: { hash, mime, byteLength }`。
- 大库不会因为封面变成几十 MB 的单个 JSON；每个文件独立校验与大小限制。

### 2.2 单分享文件（仍然允许单 JSON）

| 文件 | 内容 | 场景 |
| --- | --- | --- |
| `album.json` | 单专辑 + 分曲 +（可选）内嵌封面 base64 | 分享一张专辑 |
| `segments.json` | bvid/page + 分曲时间点 | 评论区分割点 |
| `playlist.json` | 单歌单 + 曲目引用 | 分享歌单 |

单分享文件继续支持内嵌封面（体积可控）；整库 bundle **禁止**内嵌封面 base64。

## 3. 各域职责与 schema

### 3.1 manifest.json（容器层）

```json
{
  "format": "biliaudio-archive",
  "version": 1,
  "kind": "library",
  "createdAt": "2026-11-08T14:20:00+08:00",
  "app": { "name": "BiliAudio", "version": "0.10.0" },
  "files": [
    { "path": "library.json", "type": "library", "version": 3, "sha256": "...", "bytes": 12345 },
    { "path": "playlists.json", "type": "playlists", "version": 1, "optional": true, "sha256": "...", "bytes": 2345 },
    { "path": "settings.json", "type": "settings", "version": 1, "optional": true, "sha256": "...", "bytes": 234 },
    { "path": "covers/abcd.jpg", "type": "cover", "sha256": "abcd...", "bytes": 98765 }
  ]
}
```

- import 先校验 manifest、文件存在、sha256/大小；再按 `type` 分派解析。
- `optional=false/缺省` 的文件缺失 → 失败；`optional=true` → 警告并继续。
- 不认识的文件 type：optional → 忽略并警告；required → 拒绝（避免旧版误吞新语义）。
- 容器 `version` 只描述布局；各域文件有自己的 `version/minReaderVersion`。
- 导出先写临时目录/临时 zip，manifest 最后写，成功后原子替换；失败不留半包。

### 3.2 library.json（歌曲域）

```json
{
  "format": "biliaudio-library",
  "version": 3,
  "minReaderVersion": 3,
  "kind": "library",
  "albums": [
    {
      "albumKey": "bilibili:BV1GJ411x7h7#p1",
      "source": "bilibili",
      "bvid": "BV1GJ411x7h7",
      "page": 1,
      "pageCount": 1,
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

- 专辑身份仍是 `(source,bvid,page)` ↔ `albumKey`；分曲身份仍是 `track.id`（UUID）。
- `cover` 在 bundle 中用 hash 引用；单 `album.json` 可额外带 `dataBase64`（二选一）。
- 不再包含 `settings`、`playlists`、全局 `features`（features 下沉到各域）。

### 3.3 playlists.json（歌单域，预留）

```json
{
  "format": "biliaudio-playlists",
  "version": 1,
  "minReaderVersion": 1,
  "playlists": [
    {
      "id": "pl_6f1c...",
      "name": "通勤",
      "createdAt": 1794051200000,
      "updatedAt": 1794051200000,
      "items": [
        { "albumKey": "bilibili:BV1GJ411x7h7#p1", "trackId": "trk_...", "startMs": 0, "title": "Come Together" }
      ]
    }
  ]
}
```

- 引用规则：`trackId`（uid）是主键；`albumKey + startMs` 作为回退定位；`title` 仅展示快照。
- 导入时找不到 track → 跳过并报告，不自动创建专辑、不猜时间点。
- 歌单本身独立可分享：`playlist.json`（单歌单）与 bundle 的 playlists 域同 schema。
- 本地持久化：将来加 Room `playlists` / `playlist_items` 表；不把歌单塞进 library 表。

### 3.4 settings.json（设置域）

```json
{
  "format": "biliaudio-settings",
  "version": 1,
  "minReaderVersion": 1,
  "audioQuality": "AUTO",
  "darkTheme": null,
  "diagnosticLogsEnabled": true
}
```

- 只导出**可移植白名单**：音质、深浅色、诊断日志等；
- **不导出**：登录 Cookie、设备路径、窗口状态、缓存、上次 Tab、播放队列/位置（这些是设备态，不是备份）；
- 导入默认**不覆盖**本地设置；UI 提供“同时恢复设置”开关（默认关）。

## 4. 版本与兼容规则

- 每个域文件独立：`format` + `version` + `minReaderVersion`；
- 加歌单 → 只升 `playlists.version`，library 完全不变；
- 加设置项 → 只升 `settings.version`；
- 加 library 字段（如收藏标记）→ `library.version + 1`，旧版拒绝该文件，但 **manifest 其它文件仍可读**；
- 容器 manifest 加新的 optional 文件类型 → 旧版忽略并警告，不阻塞库恢复；
- v2 单文件整库归档**不再支持**（未发布版本；单专辑/分割点 JSON 保留）。

## 5. 导入/导出行为

| 场景 | 行为 |
| --- | --- |
| 整库备份导出 | 目录/zip bundle：manifest + library + playlists(可选) + settings(可选) + covers |
| 整库恢复 | 校验 manifest → 按域导入；library 可选 merge/replace；playlists 可选导入；settings 默认跳过 |
| 单专辑分享 | `album.json`（kind=album，可内嵌封面）；不触碰歌单/设置 |
| 分割点分享 | `segments.json`（kind=segments）；只更新已存在专辑 |
| 歌单分享 | `playlist.json`；导入只加歌单，不建专辑 |
| 自动备份 | 写 bundle（目录/zip），保留最近 N 份；不再用单个 JSON |

## 6. 不需要进归档的东西

- 登录 Cookie / token / 账号隐私；
- B 站流 URL（会过期）、CID 缓存（可重新解析）；
- 播放队列、当前曲目/进度、上次 Tab（设备态；可另存本地 DataStore）；
- 封面绝对路径（只存文件/hash）；
- 诊断日志、崩溃日志；
- 缓存/图片原图（只存用户自定义封面）。

## 7. 与本地存储的分工

| 数据 | 本地持久化 | 进归档 |
| --- | --- | --- |
| 专辑/分曲 | Room | ✅ library.json |
| 歌单 | Room（将来） | ✅ playlists.json |
| 设置 | DataStore/SharedPreferences | ✅ settings.json（白名单） |
| 播放状态/队列 | DataStore（可选） | ❌ |
| 自定义封面 | 私有目录文件 | ✅ covers/ |
| 登录态 | EncryptedSharedPreferences/DataStore | ❌ |

## 8. 实施顺序（代码）

1. **容器层**：`ArchiveManifest` + 目录 bundle 读写（先不做 zip）；
2. **library 域**：现有 v2 codec 拆出 library-only schema（版本 v3），cover 改为 hash/file 引用；
3. **settings 域**：settings.json 独立，导入默认不应用；
4. **covers**：内容寻址文件（sha256 命名），导出时写入/去重；
5. **playlists**：先落 schema/解析（空实现），等歌单功能时加 Room 表/UI；
6. UI：整库导出改为 bundle（目录/zip），单专辑/分割点仍走单 JSON；
7. 自动备份改为 bundle；测试覆盖 manifest 校验、缺文件、hash 不符、跨域版本升级。
