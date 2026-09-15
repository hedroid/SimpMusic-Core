# 网易云适配踩坑记录（core）

> 面向后续对话/协作者：本文件记录网易云音源接入过程中**真机验证过**的协议坑、KMP 坑与数据层契约，
> 按层组织。改动相关代码前先查这里，能省掉一轮真机调试。
> 主仓侧（UI/架构）的规划见主仓 `docs/netease/NETEASE_UI_PLAN.md`。

## 一、协议层（core/service/netease）

### 端点生死簿（2026-09 实测）

| 端点 | 状态 | 备注 |
|---|---|---|
| `/comment/music` | ❌ 已死 | 评论走 `/v1/resource/comments/R_SO_4_{songId}`；歌单 `R_PL_`、专辑 `R_AL_` |
| `/simi/artist`、`/v1/artist/similar` | ❌ 404 | 相似歌手是 weapi `/discovery/simiArtist`（**无 /v1 前缀**），参数 `artistid` |
| `/api/v6/playlist/detail`（电台详情） | ❌ 已死 | 电台走 eapi `/djradio/get`，参数 `id` |
| `/playlist/catalog` | ❌ | 分类目录是 weapi `/playlist/catalogue`（-ue 结尾） |
| `/djradio/v2/detail` | ❌ | — |
| `/top/playlist` | ❌ | 分类歌单是 weapi `/playlist/list`（cat/order/limit/offset/total） |
| eapi `/v1/search/suggest` | ❌ 404 | 搜索建议走明文 `/api/search/suggest/web`（`{s,limit}`→result.songs/artists）；weapi `/search/suggest` 返回空 `result:{}` 也别用 |
| `/search/suggest/web` 歌曲无封面 | ⚠️ | result.songs 是老形状但 `album.picUrl` 缺失 → 批量 `/v3/song/detail` 一次请求补齐封面 |
| `/playlist/list` 高频 405 | ⚠️ | 连续/并发打多了返回 `code=405`（操作太频繁，HTTP 仍 200、无 playlists 字段→解析成空表），约 1-2 分钟自动解封。**别做逐卡请求**（如空态页 75 张分类卡逐卡取封面=必炸，连带 tag 页 Error）；错误页要留重试入口 |
| `/playlist/list` 的 `order` 参数 | ⚠️ | **只支持 `hot`**；`order=new` 返回 `{"playlists":[],"total":0,"code":200}`（weapi/明文皆然，HTTP 200 不报错）——"最新"维度这个接口没有，别再试 |
| `/v3/discovery/recommend/songs` 的 `afresh` | ⚠️ | `afresh=true` 服务端**每次调用重掷一版**推荐（探针实测两次结果完全不同）；`false` 返回当天已生成的那份（稳定）。调用方须保证 true 每天至多一次（混合页配日缓存），否则"每日推荐"变成"每次刷新推荐" |
| `/playmode/intelligence/list`（心动模式） | ❌ 已死 | 2026-09 探针实测：weapi/eapi(两 host)/明文 api × 参数字符串/数字 × type 四种取值**全部 500**（空 message），`/playmode/intelligence/loop` 404。第三方不可用，红心电台为本地替代方案 |
| `/top/song`（新歌速递） | ❌ 已死 | weapi/eapi/明文全 404；实际走 weapi `/v1/discovery/new/songs`，**参数名必须 `areaId`**（0/7/96/8/16）——传 `type` 或 `area` 服务端静默忽略、返回固定混合列表（探针逐名实测，别再踩）；limit 参数同样被无视（恒 ~100 条，要少只能客户端截断） |
| `/top/artists`、`/api/v1/artist/top`（热门歌手） | ❌ 已死 | 三通道全 404；`/toplist/artist` 三通道 400。真路径是 weapi **`/artist/top`**（无 /api/v1 前缀，探针实测） |
| `/album/new`（新碟上架） | ✅ | weapi；area 取 ALL/ZH/EA/KR/JP，albums 数组标准 album 形状，`toAlbum()` 直解 |

### 参数/响应形状坑

- **eapi 摘要路径**：必须是完整 `/eapi/...` 再替换为 `/api/...`（NeriPlayer 传 encodedPath）。
  只传裸 path 会让摘要缺 `/api` 前缀 → 服务端校验失败。
- **cloudsearch type 语义**：1=单曲、1000=歌单、**100=歌手**——`1004 是 MV`（返回 mvs 数组）。
- **`/song/like/get`**：红心歌曲 ID 数组在**顶层 `ids`**，不在 `data`。
- **云盘 `/v1/cloud/get`**：专辑体在 `dataInfo.data`、封面兜底 `dataInfo.picUrl`、大小字段
  `fileSize`（不是 size）、`bitrate` 单位服务端混用（320000 与 3495 并存），仅展示用。
- **高质量标签 `/playlist/highquality/tags`**：响应 `tags[].category` 分组固定为
  0=语种 1=风格 2=场景 3=情感 4=主题；**组名在 `categories` map 的 value，组号在 key**
  （拿 value 当数字解析会得到空表 → 分区标题变 0/1/2/3/4）。
- **分类目录 `/playlist/catalogue`**：同上 categories 结构。
- **推荐接口 `/v1/discovery/recommend/resource`**：playlist 的 `description` 字段是
  "0"/"1"/"2" 这类**序号**，直接当卡片副标题会渲染成数字 → 需净化（纯数字/空白视为无）。
- **私人雷达（3136952023 等）**：
  - `/v6/playlist/detail`（n=0）的 **trackIds 是 -10000 占位符**，拿去 songDetail 查不到歌；
  - **带 n 的 detail 响应直接内嵌完整 tracks**（NeriPlayer getPlaylistDetail 同款）；
  - `/playlist/track/all` 对这类特殊歌单**间歇性返回空** → 必须 `trackIds/songDetail → 带 n detail` 双路兜底。
- **`/playlist/track/all` 已死（2026-09-15 复测）**：weapi 通道对所有歌单返回
  `{"code":404,"message":"接口未找到！"}`（HTTP 200 包 404），不再可用。
  歌单曲目获取一律走 **`/v6/playlist/detail`(n=0) 拿 trackIds → `/v3/song/detail` 按 500/批分片**；
  songDetail 响应顺序与输入一致（探针实测），个别失效 id 缺席需按 slice 顺序回填。
  歌单详情页滚动分页 = continuation 令牌 `NETEASE_PL_PAGE_{offset}`（core/common Config），
  经共享 PlaylistViewModel 的 getContinueTrack 契约续拉（SongRepositoryImpl 顶部前缀路由分支）。
- **`/v6/playlist/detail` 的 trackIds 条目形状 = `{"id":<歌曲id>,"v":<版本号>,...}`**：
  歌曲在 `id` 字段，`v` 是版本号（1/5/7 这类小数字）。2026-09-15 修正前解析误读 `v`
  ——拿到的是版本号，雷达"trackIds=-10000 占位"的老结论其实也混有此解析 bug 的成分。
  修正后 id 优先、v 回退（老形状兼容）。
- **精品歌单 `/playlist/highquality/list`**：游标分页（`lasttime`/`more`），不是 offset；
  每页上限 50。普通分类歌单 `/playlist/list` 才是 offset + order(hot/new)。
- **相似歌单**：无 JSON 接口，抓 `music.163.com/playlist?id=` 页面 HTML 正则解析
  （cver u-cover 块）；KMP 里用 `(?s)` 内联 DOTALL，**不要用 `RegexOption.DOT_MATCHES_ALL`**（JVM 专属）。
- **流地址**：CDN 签发的是 `http://`，Android 禁明文 → ExoPlayer 报 Source error，
  必须升级 `https://`（music.126.net 的 CDN 支持）。

### KMP 语法坑（commonMain）

- `String.format` 不存在 → 手写补零（`appendPad`）。
- `HttpHeaders.Referer` 不存在 → 字符串字面量。
- `RegexOption.DOT_MATCHES_ALL` 不存在 → `(?s)` 内联。
- 接口 override **不能带默认参数值**（默认值留在契约侧）。

## 二、数据/仓库层（core/data）

- **持久化 source 回填**：在映射边界按 ID 特征盖戳——**纯数字 = NETEASE**（YT 的 id 恒含字母），
  `PlaylistBrowse.toPlaylistEntity` / `Track.toSongEntity` 都这么做；**不要**让调用方传"当前源"。
  历史脏数据用 Room 迁移回填（判据 `id NOT GLOB '*[^0-9]*'`，当前为 v28）。
- **Flow 契约：必须至少发射一次**。仓库实现的 Flow 空完成（如 `return@flow` 早退）遇上
  路由层 `.first()` = 主线程 `NoSuchElementException` → **启动崩溃循环**。
  空数据发 `Resource.Error` 或 null，不发空流。
- **`Resource<T>` 不变性**：data 为 null 时不能 `Resource.Success(null)`，
  用 `Resource.Error("empty")` 代替。
- **路由透传**：YT 侧的"缓存先行 + 网络覆盖"是**两次发射**，路由仓库必须 `emitAll` 整体透传，
  不能 `.first()` 截断。
- **Koin**：`createdAtStart` 会级联实例化依赖，注意构造函数别做重活；
  DI 参数多一个 `get()` 记得同步。

## 三、播放链路（core/media + core/data）

- **数字 videoId 路由取流**：`StreamRepositoryImpl.getStream` 开头判 `videoId.toLongOrNull() != null`
  → 走网易取流（音质降级链在 NeteaseRepositoryImpl.getStreamUrl 内）。
- **Mp3Extractor 必须注册**：上游 ExoPlayer 只带 FLAC/Matroska/fMP4/MP4（YT 从不下发 mp3），
  网易流是 mp3/flac → `UnrecognizedInputFormatException`。
- 队列实体经 `Track.toSongEntity()` 回填 source（同 ID 形状规则）。

## 四、风控/登录

- **-462 = 风控**：模拟器 + 代理 + 反复登录极易触发；扫码链路 801→802→803 三段，
  803 后还需 `/w/nuser/account/get` 三段验证（cookie 直验 → csrf → x-refresh-token 兜底）。
  环境性无解时改用网页登录；QR 内容带 chainId（`st/platform/scanlogin?codekey&chainId`）。
- **易盾指纹**（Android WebView 采 createNEFingerprint token）只降风控概率，不根除。
- **MUSIC_U 是会话核心**：`isLoggedIn` 判 `cookie 含 MUSIC_U`；logout 写空串（写 "{}" 会让
  永真）。明文随备份导出——与 YT/Spotify 同策略，产品上接受。

## 五、UI 集成边界（主仓侧，但影响 core 契约设计）

- **不要在独立屏里复用上游 `HomeItem` composable**：它默认参数会实例化上游 HomeViewModel，
  造成账户信息串屏。卡片用纯展示组件（HomeItemContentPlaylist/HomeItemSong/自绘卡）。
- 上游 `accountShow` 的 `LaunchedEffect` 只盯 homeData——账户信息晚到不重算（时序 bug），
  key 加上 accountInfo。
- **DataStore 的 preferences_pb 禁止外部改写**：手工 patch 二进制（哪怕 wire 合法）会导致
  app 内置解析器 `InvalidWireTypeException` 启动崩溃（真机二分定位过）。一切设置变更走 app 自己的 UI。
