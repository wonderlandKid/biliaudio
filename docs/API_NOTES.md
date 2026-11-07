# B 站接口实测笔记（2026-11 验证）

本机网络可直连 B 站 API、图片 CDN、媒体 CDN；**GitHub 不可达**（所以 Gradle 发行版用腾讯镜像，见 wrapper 配置）。

## 0. 通用请求头

所有 API / 媒体请求统一带：

```
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36
Referer: https://www.bilibili.com
```

媒体流（音频/视频）**必须带 Referer + UA**，否则 403（实测无 Referer → 403，有 → 206/200）。
图片 CDN（hdslb）也建议带，Coil 已统一加头。

## 1. wbi 签名（已用 Python 实现验证，Kotlin 直接照搬）

```
GET https://api.bilibili.com/x/web-interface/nav
```

- 未登录时 `code = -101`，但 `data.wbi_img.img_url` / `data.wbi_img.sub_url` **依然存在**，可用于签名。
- 登录成功后 `data.isLogin = true`，`data.uname/face/mid` 为账号信息。

签名算法：

```
imgKey = img_url  最后一段文件名去扩展名       // 例: 7cd084941338484a
subKey = sub_url  最后一段文件名去扩展名       // 例: 4932caff0ff746ea
orig   = imgKey + subKey                      // 64 字符
mixin  = orig[i] for i in MIXIN_KEY_ENC_TAB   // 取前 32 位
params['wts'] = 当前秒级时间戳
query = 按 key 字典序排序后 urlencode（值需 URL 编码）
w_rid = md5(query + mixin) 小写 hex
最终 URL = path + "?" + query + "&w_rid=" + w_rid
```

MIXIN_KEY_ENC_TAB：
`[46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52]`

## 2. 视频信息

```
GET https://api.bilibili.com/x/web-interface/view?bvid=BV1GJ411x7h7
```

实测 `code = 0`，无需登录、无需 wbi。关键字段：

```json
{"data":{
  "aid":80433022,"bvid":"BV1GJ411x7h7",
  "title":"【官方 MV】Never Gonna Give You Up - Rick Astley",
  "pic":"http://i1.hdslb.com/bfs/archive/xxx.jpg",   // 注意 http，使用时转 https
  "duration":213,                                     // 秒
  "videos":1,                                         // 分P数
  "owner":{"mid":486906719,"name":"索尼音乐中国","face":"..."},
  "pages":[{"page":1,"cid":137649199,"part":"...","duration":213}]
}}
```

BV 号解析：输入支持 BV 号、完整链接（`bilibili.com/video/BV...`）、短链 `b23.tv`（需 302 解析，可后置）。

## 3. 播放流

两个端点都实测可用（无登录、无 Cookie）：

- 非 wbi：`GET /x/player/playurl?bvid=BV..&cid=..&fnval=16&fnver=0&fourk=1`
- wbi：`GET /x/player/wbi/playurl?...`（需签名，参数完全相同）

`fnval=16` 返回 DASH。响应结构（节选）：

```json
{"code":0,"data":{
  "dash":{
    "duration":212,
    "audio":[
      {"id":30216,"baseUrl":"https://.../xxx.m4s?...","backupUrl":["..."],"bandwidth":43962,"codecs":"mp4a.40.5","mimeType":"audio/mp4"},
      {"id":30232,...},{"id":30280,...}
    ],
    "video":[
      {"id":16,"baseUrl":"...","width":640,"height":360,"codecs":"avc1...","mimeType":"video/mp4"},
      {"id":32,"baseUrl":"...","width":852,"height":480,"codecs":"hev1.1.6.L120.90"},
      {"id":64,"baseUrl":"..."}
    ]
  }
}}
```

- 音频 id：`30280=192K`、`30232=132K`、`30216=64K`；`30250/30251` 为杜比/Hi-Res（需会员）。
- 视频 quality id：16=360P、32=480P、64=720P、74=720P60、80=1080P、112/116/120/125/126/127 更高。
- 未登录实测：音频可取到 30280（192K），视频最高 480P（id 32）。
- 视频选流策略（歌词页）：优先最高分辨率（**当前封顶 720P**，兼顾清晰度与首帧/seek 等待），
  同分辨率优先 AVC（兼容性最好）再按码率高低；
  **不要选「≤720P 里码率最低」**——那会选到 360P HEVC 低码率流（实测约 82kbps），画面很糊。
  更高画质需要登录（未登录一般最高 480P）。
- `baseUrl` 是**完整 fMP4 文件**（ffprobe 验证：AAC 48kHz stereo，时长与视频一致 / HEVC 852x480），可直接作为 ExoPlayer `ProgressiveMediaSource` URI；`backupUrl` 可在主 URL 失败时降级。
- URL 带 `e=`/`deadline=` 等过期参数，普通场景约 2 小时有效；缓存建议 ≤30 分钟，播放失败自动强制刷新一次。

## 4. 扫码登录

1. 生成二维码：
   ```
   GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate
   → {"code":0,"data":{"url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=xxx&from=","qrcode_key":"xxx"}}
   ```
   用 `data.url` 生成二维码图片（zxing core → Bitmap），轮询间隔 ~2s。

2. 轮询：
   ```
   GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=xxx
   ```
   业务码：
   - `0` 登录成功：**响应头 Set-Cookie 中拿到** `SESSDATA`、`bili_jct`、`DedeUserID`、`DedeUserID__ckMd5`、`sid` 等；`data.url` 是跨域登录 URL。需用 OkHttp 从响应头解析所有 Set-Cookie 并持久化。
   - `86101` 未扫码
   - `86090` 已扫码待确认
   - `86038` 二维码已失效
3. 登录态校验：带 Cookie 请求 `/x/web-interface/nav`，`code == 0 && data.isLogin == true` 即已登录；`-101` 为未登录。
4. 其他基础 Cookie（buvid3/buvid4 等，可请求 `GET /x/frontend/finger/spi` 获取）建议也持久化，减少风控。未登录也能正常调用 view/playurl 即可。

## 5. 搜索视频（导入时发现资源，加分项）

实测（未登录）可用：

```
GET https://api.bilibili.com/x/web-interface/search/all/v2?keyword=周杰伦+专辑
或
GET https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=...
```

返回 `data.result`（type=video 的分组里 `data[]`），字段：`bvid`、`title`（含 `<em class="keyword">` 高亮标签，需去除）、`pic`（`//i2.hdslb.com/...` 需补 `https:`）、`duration`（"3:55"）、`author`、`play`、`video_review`、`description`、`pubdate` 等。

## 6. 已知坑

- `pic` 字段是 `http://`，**必须转 https**，否则 Android 默认禁止明文。
- 媒体 URL 403 的首因就是缺 `Referer`；退出登录后旧解析缓存要 invalidate。
- Cookie 变更（登录/退出）后，`MediaStreamResolver` 缓存要清空（否则还是旧画质/Cookie 的流）。
- 搜索接口返回 title 含 HTML 标签与实体（`&amp;` 等），需清洗。
- 用户上传内容可能被 UP 主删除/设为私密 → view/playurl 返回非 0 code，导入和播放都要给明确错误提示（如 "视频不存在或已失效"）。
- 请求过快可能触发风控（412 / -352），给接口加统一 1 次重试 + 限速（如 300ms 间隔）即可。

## 网易云音乐封面搜索（自定义封面用，非 B 站接口）

参考 `~/Music/pipeline/lib/netease.py` 的可用端点；v0.5.0 实测可用，均为**非官方接口**：

| 用途 | 请求 |
| --- | --- |
| 搜索专辑 | `GET https://music.163.com/api/search/get?s=<关键词>&type=10&limit=20` |
| 专辑详情（备用） | `GET https://interface3.music.163.com/api/v1/album/<albumId>` |
| 图片 | 搜索结果 `picUrl` 直接 GET（`http` 升级为 `https`；可加 `?param=300y300` 取缩略图） |

- 请求头：桌面 UA + `Referer: https://music.163.com` 即可，无需登录/Cookie/签名。
- 搜索响应：`result.albums[]`，字段 `id / name / picUrl / artists[].name`。
- 注意：接口没有稳定性承诺；失败时 App 静默降级为空结果（只影响在线搜封面）；
  不要下载超大图（App 限制 8MB，保存时长边缩到 ≤1080 再 JPEG 压缩）。
