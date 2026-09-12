/*
 * NetEase business endpoints — each parses the raw JsonObject into the typed DTOs the
 * repository layer maps into SimpMusic's domain entities. Endpoint paths and parameter
 * shapes are cross-checked against NeriPlayer (verified against production) and the
 * community NeteaseCloudMusicApi project.
 */
package com.maxrave.netease

import com.maxrave.netease.model.NeteaseAlbum
import com.maxrave.netease.model.NeteaseArtist
import com.maxrave.netease.model.NeteaseArtistDetail
import com.maxrave.netease.model.NeteaseArtistDynamic
import com.maxrave.netease.model.NeteaseArtistIntroduction
import com.maxrave.netease.model.NeteaseCloudDiskPage
import com.maxrave.netease.model.NeteaseCloudFile
import com.maxrave.netease.model.NeteaseComment
import com.maxrave.netease.model.NeteaseCommentPage
import com.maxrave.netease.model.NeteaseDjRadio
import com.maxrave.netease.model.NeteaseHighQualityTag
import com.maxrave.netease.model.NeteaseLyrics
import com.maxrave.netease.model.NeteasePlaylist
import com.maxrave.netease.model.NeteaseQuality
import com.maxrave.netease.model.NeteaseRadioSession
import com.maxrave.netease.model.NeteaseSearchResult
import com.maxrave.netease.model.NeteaseSong
import com.maxrave.netease.model.NeteaseStreamUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

// 固定歌单 ID(NeriPlayer 验证):雷达系列每日更新、官方榜单
object NeteaseConstants {
    const val RADAR_PRIVATE_PLAYLIST_ID = 3_136_952_023L // 私人雷达
    const val RADAR_FANS_PLAYLIST_ID = 5_327_906_368L // 乐迷雷达(粉丝雷达)
    const val TOPLIST_SOARING_ID = 19_723_756L // 飙升榜
    const val TOPLIST_NEW_ID = 3_779_629L // 新歌榜
    const val TOPLIST_HOT_ID = 37_786_678L // 热歌榜

    /** 雷达歌单全组(NeriPlayer NeteaseRadarPlaylistDefinitions,内容每日更新) */
    val RADAR_PLAYLISTS: List<Pair<Long, String>> =
        listOf(
            5_320_167_908L to "时光雷达",
            5_362_359_247L to "宝藏雷达",
            5_300_458_264L to "新歌雷达",
            RADAR_FANS_PLAYLIST_ID to "乐迷雷达",
            5_341_776_086L to "神秘雷达",
        )
}

suspend fun NeteaseClient.searchSongs(
    keywords: String,
    limit: Int = 30,
    offset: Int = 0,
): Result<NeteaseSearchResult<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/cloudsearch/pc",
                mapOf(
                    "s" to keywords,
                    "type" to 1, // 单曲
                    "limit" to limit,
                    "offset" to offset,
                ),
            )
        val result = body.obj("result")
        NeteaseSearchResult(
            items = result?.array("songs")?.map { it.toSong() } ?: emptyList(),
            totalCount = result?.get("songCount").nInt(),
        )
    }

/** 搜歌单(cloudsearch type=1000),封面走 coverImgUrl */
suspend fun NeteaseClient.searchPlaylists(
    keywords: String,
    limit: Int = 30,
    offset: Int = 0,
): Result<NeteaseSearchResult<NeteasePlaylist>> =
    runCatching {
        val body =
            callWeApi(
                "/cloudsearch/pc",
                mapOf(
                    "s" to keywords,
                    "type" to 1000, // 歌单
                    "limit" to limit,
                    "offset" to offset,
                ),
            )
        val result = body.obj("result")
        NeteaseSearchResult(
            items = result?.array("playlists")?.map { it.toPlaylist() } ?: emptyList(),
            totalCount = result?.get("playlistCount").nInt(),
        )
    }

/** 搜歌手(cloudsearch type=100;注意 1004 是 MV),封面兜底 img1v1Url(NeriPlayer 同款) */
suspend fun NeteaseClient.searchArtists(
    keywords: String,
    limit: Int = 30,
    offset: Int = 0,
): Result<NeteaseSearchResult<NeteaseArtist>> =
    runCatching {
        val body =
            callWeApi(
                "/cloudsearch/pc",
                mapOf(
                    "s" to keywords,
                    "type" to 100, // 歌手
                    "limit" to limit,
                    "offset" to offset,
                ),
            )
        val result = body.obj("result")
        NeteaseSearchResult(
            items =
                result?.array("artists")?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = obj["id"].nLong() ?: return@mapNotNull null
                    NeteaseArtist(
                        id = id,
                        name = obj.str("name").orEmpty(),
                        picUrl = (obj.str("picUrl") ?: obj.str("img1v1Url"))?.toHttpsUrl(),
                        musicSize = obj["musicSize"].nInt(),
                        albumSize = obj["albumSize"].nInt(),
                    )
                } ?: emptyList(),
            totalCount = result?.get("artistCount").nInt(),
        )
    }

suspend fun NeteaseClient.songDetail(ids: List<Long>): Result<List<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/v3/song/detail",
                mapOf(
                    // c 必须是 JSON 数组文本(带方括号),缺 [] 服务端解析失败返回空 songs
                    "c" to ids.joinToString(",", prefix = "[", postfix = "]") { "{\"id\":$it}" },
                ),
            )
        body.array("songs")?.map { it.toSong() } ?: emptyList()
    }

suspend fun NeteaseClient.songUrl(
    songId: Long,
    level: NeteaseQuality,
): Result<NeteaseStreamUrl> =
    runCatching {
        val body =
            callWeApi(
                "/song/enhance/player/url/v1",
                mapOf(
                    "ids" to "[$songId]",
                    "level" to level.key,
                    "encodeType" to "flac",
                ),
            )
        val first = body.array("data")?.firstOrNull() ?: error("no url data for $songId")
        val trial = first.jsonObject.obj("freeTrialInfo")
        NeteaseStreamUrl(
            url = first.jsonObject.str("url"),
            sizeBytes = first.jsonObject["size"].nLong(),
            mimeType = first.jsonObject.str("type"),
            level = first.jsonObject.str("level"),
            freeTrialInfo =
                trial?.let {
                    NeteaseStreamUrl.FreeTrial(
                        startTimeMs = it["startTime"].nLong() ?: 0L,
                        endTimeMs = it["endTime"].nLong() ?: 0L,
                    )
                },
        )
    }

suspend fun NeteaseClient.lyric(songId: Long): Result<NeteaseLyrics> =
    runCatching {
        val body =
            callWeApi(
                "/song/lyric/v1",
                mapOf(
                    "id" to songId,
                    "cv" to 0, // 逐行
                    "lv" to -1, // 全量
                    "tv" to -1, // 翻译
                    "rv" to -1, // 罗马音
                ),
            )
        NeteaseLyrics(
            lrc = body.obj("lrc")?.str("lyric"),
            yrc = body.obj("yrc")?.str("lyric"),
            translated = body.obj("tlyric")?.str("lyric"),
            romanized = body.obj("romalrc")?.str("lyric"),
        )
    }

suspend fun NeteaseClient.dailyRecommendPlaylists(): Result<List<NeteasePlaylist>> =
    runCatching {
        val body = callWeApi("/v1/discovery/recommend/resource", emptyMap())
        body.array("recommend")?.map {
            it.toPlaylist().copy(specialType = NeteasePlaylist.SpecialType.DAILY)
        } ?: emptyList()
    }

suspend fun NeteaseClient.dailyRecommendSongs(): Result<List<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/v3/discovery/recommend/songs",
                mapOf("afresh" to true.toString()),
            )
        body.obj("data")?.array("dailySongs")?.map { it.toSong() } ?: emptyList()
    }

suspend fun NeteaseClient.personalizedNewSongs(limit: Int = 30): Result<List<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/personalized/newsong",
                mapOf("limit" to limit),
            )
        // 容器结构: { result: [ { id, name, picUrl, song: {...} } ] }
        body.array("result")?.map { container ->
            val song = container.jsonObject.obj("song") ?: container.jsonObject
            song.toSong().let { s ->
                s.copy(coverUrl = s.coverUrl ?: container.jsonObject.str("picUrl"))
            }
        } ?: emptyList()
    }

/** 分类目录子项(weapi /playlist/catalogue):name+所属组号+是否热门(网页左侧栏同源) */
data class NeteaseCatalogTag(
    val name: String,
    val category: Int,
    val hot: Boolean,
)

/**
 * 全部分类目录(网页版 discover/playlist 的分类栏同源):
 * 返回 (组名, 该组子类列表) 列表,组序 0=语种 1=风格 2=场景 3=情感 4=主题。
 */
suspend fun NeteaseClient.playlistCatalog(): Result<List<Pair<String, List<NeteaseCatalogTag>>>> =
    runCatching {
        val body = callWeApi("/playlist/catalogue", emptyMap())
        // categories 形如 {"0":"语种","1":"风格",...} —— key 是组号,value 是组名
        val groupNames =
            buildMap<Int, String> {
                (body["categories"] as? JsonObject)?.forEach { (k, v) ->
                    k.toIntOrNull()?.let { id ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { name -> put(id, name) }
                    }
                }
            }
        val subs =
            body.array("sub")?.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj.str("name") ?: return@mapNotNull null
                NeteaseCatalogTag(
                    name = name,
                    category = obj["category"].nInt() ?: 0,
                    hot = (obj["hot"] as? JsonPrimitive)?.content == "true",
                )
            } ?: emptyList()
        // 组序固定 0..4,组名缺失时兜底数字
        (0..4).map { catId ->
            val title = groupNames[catId] ?: catId.toString()
            title to subs.filter { it.category == catId }
        }.filter { it.second.isNotEmpty() }
    }

/** 分类歌单分页结果(weapi /playlist/list,网页版分类页同源) */
data class NeteaseCategoryPage(
    val playlists: List<NeteasePlaylist>,
    val total: Int,
    val hasMore: Boolean,
)

/**
 * 某分类下的歌单列表(普通歌单,order=hot 热度/new 最新),offset 分页 —— 与网页
 * discover/playlist?cat=xxx 同一数据源;精品歌单请用 [highQualityPlaylists]。
 */
suspend fun NeteaseClient.categoryPlaylists(
    cat: String,
    limit: Int = 50,
    offset: Int = 0,
    order: String = "hot",
): Result<NeteaseCategoryPage> =
    runCatching {
        val body =
            callWeApi(
                "/playlist/list",
                mapOf(
                    "cat" to cat,
                    "order" to order,
                    "limit" to limit,
                    "offset" to offset,
                    "total" to true,
                ),
            )
        NeteaseCategoryPage(
            playlists = body.array("playlists")?.map { it.toPlaylist() } ?: emptyList(),
            total = body["total"].nInt() ?: 0,
            hasMore = (body["more"] as? JsonPrimitive)?.content == "true",
        )
    }

/** 便捷封装:分类歌单一次拉 [pages] 页(标签/分类页用) */
suspend fun NeteaseClient.categoryPlaylistsPaged(
    cat: String,
    pages: Int = 2,
    order: String = "hot",
): Result<List<NeteasePlaylist>> =
    runCatching {
        val all = mutableListOf<NeteasePlaylist>()
        repeat(pages) { page ->
            val result = categoryPlaylists(cat = cat, offset = page * 50, order = order).getOrNull() ?: return@repeat
            all += result.playlists
            if (!result.hasMore) return@repeat
        }
        all
    }

/**
 * 高质量歌单分页结果:nextBefore 为下一页游标(响应 lasttime),null 表示没有更多。
 */
data class NeteaseHqPage(
    val playlists: List<NeteasePlaylist>,
    val nextBefore: Long?,
)

/**
 * 高质量歌单,游标分页(每页上限 50;nextBefore 传上页的游标翻页)。
 * cat=null 表示不传分类参数(服务端默认"全部")。
 */
suspend fun NeteaseClient.highQualityPlaylists(
    cat: String? = "全部",
    limit: Int = 50,
    before: Long = 0L,
): Result<NeteaseHqPage> =
    runCatching {
        val body =
            callWeApi(
                "/playlist/highquality/list",
                buildMap<String, Any?> {
                    put("limit", limit)
                    put("lasttime", before)
                    put("total", true)
                    if (cat != null) put("cat", cat)
                },
            )
        val playlists = body.array("playlists")?.map { it.toPlaylist() } ?: emptyList()
        val more = (body["more"] as? JsonPrimitive)?.content == "true"
        val next = body["lasttime"].nLong()
        NeteaseHqPage(
            playlists = playlists,
            nextBefore = if (more && next != null && next > 0) next else null,
        )
    }

/** 便捷封装:一次性拉 [pages] 页(标签/分类页用,内容量对标网易 App) */
suspend fun NeteaseClient.highQualityPlaylistsPaged(
    cat: String? = "全部",
    pages: Int = 2,
): Result<List<NeteasePlaylist>> =
    runCatching {
        val all = mutableListOf<NeteasePlaylist>()
        var cursor = 0L
        repeat(pages) {
            val page = highQualityPlaylists(cat = cat, before = cursor).getOrNull() ?: return@repeat
            all += page.playlists
            cursor = page.nextBefore ?: return@repeat
        }
        all
    }

/**
 * 带 n 的歌单详情直取 playlist.tracks(NeriPlayer getPlaylistDetail 同款)。
 * 雷达这类特殊歌单的 trackIds 是 -10000 占位符,songDetail 查不动;
 * 带 n 请求时响应的 tracks 数组直接是完整歌曲形状。
 */
suspend fun NeteaseClient.playlistTracksViaDetail(
    playlistId: Long,
    limit: Int,
): Result<List<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/v6/playlist/detail",
                mapOf(
                    "id" to playlistId,
                    "n" to limit,
                    "s" to 8,
                ),
            )
        body.obj("playlist")?.array("tracks")?.map { it.toSong() } ?: emptyList()
    }

suspend fun NeteaseClient.toplistPlaylists(): Result<List<NeteasePlaylist>> =
    runCatching {
        val body = callWeApi("/toplist/detail", emptyMap())
        body.array("list")?.map { it.toPlaylist().copy(specialType = NeteasePlaylist.SpecialType.TOPLIST) } ?: emptyList()
    }

suspend fun NeteaseClient.playlistDetail(playlistId: Long): Result<Pair<NeteasePlaylist, List<Long>>> =
    runCatching {
        val body =
            callWeApi(
                "/v6/playlist/detail",
                mapOf(
                    "id" to playlistId,
                    "n" to 0, // 不直接带 tracks,trackIds 足够
                ),
            )
        val pl = body.obj("playlist") ?: error("playlist $playlistId not found")
        val trackIds =
            pl.array("trackIds")?.mapNotNull { item ->
                (item as? JsonObject)?.get("v").nLong() ?: (item as? JsonPrimitive).nLong()
            } ?: emptyList()
        pl.toPlaylist() to trackIds
    }

suspend fun NeteaseClient.playlistTracks(
    playlistId: Long,
    limit: Int = 100,
    offset: Int = 0,
): Result<List<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/playlist/track/all",
                mapOf(
                    "id" to playlistId,
                    "limit" to limit,
                    "offset" to offset,
                ),
            )
        body.array("songs")?.map { it.toSong() } ?: emptyList()
    }

suspend fun NeteaseClient.userPlaylists(userId: Long): Result<List<NeteasePlaylist>> =
    runCatching {
        val body =
            callWeApi(
                "/user/playlist",
                mapOf(
                    "uid" to userId,
                    "limit" to 100,
                    "offset" to 0,
                ),
            )
        body.array("playlist")?.mapIndexed { index, element ->
            val pl = element.toPlaylist()
            // specialType==5 是"我喜欢的音乐"红心歌单,网易固定将其排在首位
            if (index == 0 || pl.rawSpecialType == 5) {
                pl.copy(specialType = NeteasePlaylist.SpecialType.FAVORITE)
            } else {
                pl
            }
        } ?: emptyList()
    }

suspend fun NeteaseClient.personalRadio(): Result<NeteaseRadioSession> =
    runCatching {
        val body = callWeApi("/v1/radio/get", emptyMap())
        NeteaseRadioSession(
            songs = body.array("data")?.map { it.toSong() } ?: emptyList(),
            popAdjust = body["popAdjust"]?.let { (it as? JsonPrimitive)?.content == "true" },
        )
    }

suspend fun NeteaseClient.likeSong(
    songId: Long,
    like: Boolean,
): Result<Boolean> =
    runCatching {
        val body =
            callWeApi(
                "/song/like",
                mapOf("trackId" to songId, "like" to like.toString()),
            )
        (body["code"] as? JsonPrimitive)?.content == "200"
    }

/**
 * 雷达歌单组(私人+时光/宝藏/新歌/乐迷/神秘),本质是固定 ID 的普通歌单,内容每日更新。
 * 单个元数据拉取失败时退回 ID+名称的占位(NeriPlayer loadNeteaseRadarPlaylistSummaries 同款容错),
 * 不让一张歌单的失败拖垮整行。
 */
suspend fun NeteaseClient.radarPlaylists(): Result<List<NeteasePlaylist>> =
    runCatching {
        suspend fun radar(
            id: Long,
            fallbackName: String,
            type: NeteasePlaylist.SpecialType,
        ): NeteasePlaylist =
            playlistDetail(id).getOrNull()?.first?.copy(specialType = type)
                ?: NeteasePlaylist(
                    id = id,
                    name = fallbackName,
                    coverUrl = null,
                    trackCount = 0,
                    playCount = null,
                    description = null,
                    specialType = type,
                )

        // 6 张雷达的详情并行拉取(总耗时=最慢一张,而不是六张相加);
        // 单张失败独立兜底为 ID+名称占位,不拖垮整行 —— 原语义保留
        coroutineScope {
            val defs =
                buildList {
                    add(Triple(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID, "私人雷达", NeteasePlaylist.SpecialType.RADAR_PRIVATE))
                    NeteaseConstants.RADAR_PLAYLISTS.forEach { (id, name) ->
                        val type =
                            if (id == NeteaseConstants.RADAR_FANS_PLAYLIST_ID) {
                                NeteasePlaylist.SpecialType.RADAR_FANS
                            } else {
                                NeteasePlaylist.SpecialType.RADAR
                            }
                        add(Triple(id, name, type))
                    }
                }
            defs.map { (id, name, type) ->
                async { radar(id, name, type) }
            }.awaitAll()
        }
    }

/**
 * 主页多来源合流去重(NeriPlayer appendUniqueNeteaseHomeSongs 的 core 版):
 * 以歌曲 ID 为键,合并后截断到 [limit]。UI/仓库组装 feed 时用。
 */
fun appendUniqueSongs(
    current: List<NeteaseSong>,
    next: List<NeteaseSong>,
    limit: Int,
): List<NeteaseSong> {
    if (limit <= 0) return emptyList()
    val merged = ArrayList<NeteaseSong>(limit)
    val seen = HashSet<Long>()
    (current.asSequence() + next.asSequence()).forEach { song ->
        if (merged.size >= limit) return@forEach
        if (song.id > 0L && seen.add(song.id)) {
            merged.add(song)
        }
    }
    return merged
}

// ----------------------------------------------------------------------------
// D 档设置项支撑:关注与网易云同步
// ----------------------------------------------------------------------------

/** 已关注的歌手(/artist/sublist),用于"关注与网易云同步" */
suspend fun NeteaseClient.subscribedArtists(
    limit: Int = 100,
    offset: Int = 0,
): Result<List<Pair<Long, String>>> =
    runCatching {
        val body =
            callWeApi(
                "/artist/sublist",
                mapOf("limit" to limit, "offset" to offset),
            )
        body.array("data")?.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"].nLong() ?: return@mapNotNull null
            id to obj.str("name").orEmpty()
        } ?: emptyList()
    }

/** 关注(t=1)/取关(t=0)歌手,用于同步 SimpMusic 侧的关注操作 */
suspend fun NeteaseClient.subscribeArtist(
    artistId: Long,
    subscribe: Boolean,
): Result<Boolean> =
    runCatching {
        val body =
            callWeApi(
                "/artist/sub",
                mapOf("id" to artistId, "t" to if (subscribe) 1 else 0),
            )
        (body["code"] as? JsonPrimitive)?.content == "200"
    }

// ----------------------------------------------------------------------------
// 专辑 / 红心 / 歌单写操作 —— YTM 有对应入口的走共享形状,红心数据用于库页识别
// ----------------------------------------------------------------------------

/** 专辑详情(weapi /v1/album/{id}),返回专辑元数据+曲目 */
suspend fun NeteaseClient.albumDetail(albumId: Long): Result<Pair<NeteaseAlbum, List<NeteaseSong>>> =
    runCatching {
        val body =
            callWeApi(
                "/v1/album/$albumId",
                mapOf(
                    "n" to 100000,
                    "s" to 8,
                ),
            )
        val album = body.obj("album")?.toAlbum() ?: error("album $albumId not found")
        album to (body.array("songs")?.map { it.toSong() } ?: emptyList())
    }

/** 用户收藏的专辑(eapi mine/rn/resource/list pageType=3,NeriPlayer getUserStaredAlbums) */
suspend fun NeteaseClient.userStaredAlbums(
    userId: Long,
    limit: Int = 1000,
): Result<List<NeteaseAlbum>> =
    runCatching {
        val body =
            callEApi(
                "/mine/rn/resource/list",
                mapOf(
                    "userId" to userId,
                    "offset" to 0,
                    "limit" to limit,
                    "pageType" to "3", // 专辑页签
                    "needRcmd" to "0",
                    "isVistor" to "false",
                    "includeStarPodcast" to "true",
                ),
                host = "https://interface3.music.163.com",
            )
        // 响应条目:{ id:"...", resType:"ALBUM", dataInfo:{ data:{专辑}, picUrl } }
        // 专辑本体在 dataInfo.data,封面兜底在 dataInfo.picUrl(真机冒烟确认)
        body.obj("data")
            ?.obj("mainCollectInfo")
            ?.obj("mineAllTabDto")
            ?.array("dataList")
            ?.mapNotNull { element ->
                val wrapper = element.jsonObject.obj("dataInfo")
                val albumObj = wrapper?.obj("data") ?: return@mapNotNull null
                if (albumObj["id"].nLong() == null) {
                    null
                } else {
                    val album = albumObj.toAlbum()
                    album.copy(coverUrl = album.coverUrl ?: wrapper.str("picUrl"))
                }
            } ?: emptyList()
    }

/** "我喜欢的音乐"红心歌单 ID:网易固定把它排在用户歌单首位(specialType==5) */
suspend fun NeteaseClient.likedPlaylistId(userId: Long): Result<Long?> =
    runCatching {
        userPlaylists(userId).getOrThrow()
            .firstOrNull { pl -> pl.rawSpecialType == 5 || pl.specialType == NeteasePlaylist.SpecialType.FAVORITE }
            ?.id
    }

/** 用户红心歌曲 ID 全量(weapi /song/like/get,数组在顶层 ids),用于库页红心歌单的曲目填充 */
suspend fun NeteaseClient.userLikedSongIds(userId: Long): Result<List<Long>> =
    runCatching {
        val body = callWeApi("/song/like/get", mapOf("uid" to userId))
        body.array("ids")?.mapNotNull { it.nLong() } ?: emptyList()
    }

/** 添加歌曲到自己的歌单(weapi /playlist/manipulate/tracks,NeriPlayer addSongsToPlaylist) */
suspend fun NeteaseClient.addToPlaylist(
    playlistId: Long,
    songIds: List<Long>,
): Result<Boolean> =
    runCatching {
        require(playlistId > 0L) { "playlistId must be positive" }
        val ids = songIds.filter { it > 0L }.distinct()
        require(ids.isNotEmpty()) { "songIds must contain a positive id" }
        val body =
            callWeApi(
                "/playlist/manipulate/tracks",
                mapOf(
                    "op" to "add",
                    "pid" to playlistId,
                    "id" to playlistId,
                    "tracks" to ids.joinToString(","),
                    "trackIds" to ids.joinToString(",", prefix = "[", postfix = "]"),
                    "imme" to "true",
                ),
            )
        (body["code"] as? JsonPrimitive)?.content == "200"
    }

// ----------------------------------------------------------------------------
// 网易云专属浏览能力(YTM 无对应入口):相似歌单、高质量分类标签、DJ 电台
// ----------------------------------------------------------------------------

/**
 * 相似歌单:网易没有 JSON 接口,NeriPlayer 的做法是抓 playlist 页 HTML 正则解析
 * (cver u-cover 块:封面/歌单链接/歌单名/创建者),这里原样移植。
 */
suspend fun NeteaseClient.relatedPlaylists(playlistId: Long): Result<List<NeteasePlaylist>> =
    runCatching {
        val html = getText("/playlist", mapOf("id" to playlistId.toString()))
        // (?s) 内联 DOTALL —— common 的 RegexOption 没有 DOT_MATCHES_ALL(那是 JVM 专属)
        val regex =
            Regex(
                pattern = """(?s)<div class="cver u-cover u-cover-3">.*?<img src="([^"]+)">.*?<a class="sname f-fs1 s-fc0" href="([^"]+)"[^>]*>([^<]+?)</a>.*?<a class="nm nm f-thide s-fc3" href="([^"]+)"[^>]*>([^<]+?)</a>""",
                options = setOf(RegexOption.IGNORE_CASE),
            )
        regex.findAll(html).mapNotNull { m ->
            val id = m.groupValues[2].removePrefix("/playlist?id=").toLongOrNull() ?: return@mapNotNull null
            NeteasePlaylist(
                id = id,
                name = m.groupValues[3],
                // 封面 URL 末尾的 ?param=WxH 是尺寸参数,去掉拿原图
                coverUrl = m.groupValues[1].replace(Regex("""\?param=\d+y\d+$"""), ""),
                trackCount = 0,
                playCount = null,
                description = null,
            )
        }.toList()
    }

/** 高质量歌单分类标签(weapi /playlist/highquality/tags),配合 [highQualityPlaylists] 的 cat 参数 */
suspend fun NeteaseClient.highQualityTags(): Result<List<NeteaseHighQualityTag>> =
    runCatching {
        val body = callWeApi("/playlist/highquality/tags", emptyMap())
        body.array("tags")?.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj.str("name") ?: return@mapNotNull null
            NeteaseHighQualityTag(
                id = obj["id"].nInt() ?: 0,
                name = name,
                category = obj["category"].nInt() ?: 0,
            )
        } ?: emptyList()
    }

/** 用户订阅的 DJ 电台(weapi /user/djradio/get/subed) */
suspend fun NeteaseClient.userDjRadios(
    userId: Long,
    limit: Int = 100,
    offset: Int = 0,
): Result<List<NeteaseDjRadio>> =
    runCatching {
        val body =
            callWeApi(
                "/user/djradio/get/subed",
                mapOf(
                    "uid" to userId,
                    "limit" to limit,
                    "offset" to offset,
                ),
            )
        body.array("djRadios")?.mapNotNull { it.toDjRadioOrNull() } ?: emptyList()
    }

/** 电台详情(eapi /djradio/get,响应 djRadio 对象;NeriPlayer 走的 api/v6/playlist/detail 已失效,真机冒烟验证过此端点) */
suspend fun NeteaseClient.djRadioDetail(radioId: Long): Result<NeteaseDjRadio> =
    runCatching {
        val body = callEApi("/djradio/get", mapOf("id" to radioId))
        body.obj("djRadio")?.toDjRadioOrNull() ?: error("dj radio $radioId not found")
    }

// ----------------------------------------------------------------------------
// C 档:歌手详情/百科/相似歌手、歌曲评论、云盘 —— 端点真实现,UI 入口下个需求接
// ----------------------------------------------------------------------------

/** 歌手详情(NeriPlayer getArtistDetail:明文 api/artist/head/info/get) */
suspend fun NeteaseClient.artistDetail(artistId: Long): Result<NeteaseArtistDetail> =
    runCatching {
        val body = callApi("/artist/head/info/get", mapOf("id" to artistId))
        val artist =
            body.obj("data")?.obj("artist")
                ?: body.obj("artist")
                ?: error("artist $artistId not found")
        NeteaseArtistDetail(
            id = artist["id"].nLong() ?: artistId,
            name = artist.str("name").orEmpty(),
            alias = artist.array("alias")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            // head/info/get 的头像字段是 avatar(cover 是对象,只做兜底)
            picUrl = (artist.str("avatar") ?: artist.str("picUrl"))?.toHttpsUrl(),
            briefDesc = artist.str("briefDesc"),
            albumSize = artist["albumSize"].nInt(),
            musicSize = artist["musicSize"].nInt(),
            mvSize = artist["mvSize"].nInt(),
            identifyTitle = body.obj("data")?.obj("identify")?.str("imageDesc") ?: body.obj("identify")?.str("imageDesc"),
        )
    }

/** 歌手动态(NeriPlayer getArtistDynamic:明文 api/artist/detail/dynamic,关注状态/视频数) */
suspend fun NeteaseClient.artistDynamic(artistId: Long): Result<NeteaseArtistDynamic> =
    runCatching {
        val body = callApi("/artist/detail/dynamic", mapOf("id" to artistId))
        NeteaseArtistDynamic(
            followed = (body["followed"] as? JsonPrimitive)?.content == "true",
            // videoNum 是 [{cat,num}] 分组数组,求和
            videoCount = body.array("videoNum")?.sumOf { (it as? JsonObject)?.get("num").nLong() ?: 0L },
        )
    }

/** 歌手百科(weapi /artist/introduction,分段字段为 ti/txt) */
suspend fun NeteaseClient.artistIntroduction(artistId: Long): Result<NeteaseArtistIntroduction> =
    runCatching {
        val body = callWeApi("/artist/introduction", mapOf("id" to artistId))
        NeteaseArtistIntroduction(
            briefDesc = body.str("briefDesc"),
            sections =
                body.array("introduction")?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val text = obj.str("txt") ?: obj.str("text") ?: return@mapNotNull null
                    obj.str("ti").orEmpty() to text
                } ?: emptyList(),
        )
    }

/** 歌手热门歌曲(NeriPlayer getArtistSongs:明文 api/v1/artist/songs,order=hot 默认热门50) */
suspend fun NeteaseClient.artistSongs(
    artistId: Long,
    order: String = "hot",
    limit: Int = 50,
    offset: Int = 0,
): Result<NeteaseSearchResult<NeteaseSong>> =
    runCatching {
        val body =
            callApi(
                "/v1/artist/songs",
                mapOf(
                    "id" to artistId,
                    "private_cloud" to "true",
                    "work_type" to "1",
                    "order" to order,
                    "offset" to offset,
                    "limit" to limit,
                ),
            )
        NeteaseSearchResult(
            items = body.array("songs")?.map { it.toSong() } ?: emptyList(),
            totalCount = body["total"].nInt(),
        )
    }

/** 歌手专辑(NeriPlayer getArtistAlbums:明文 api/artist/albums/{id}) */
suspend fun NeteaseClient.artistAlbums(
    artistId: Long,
    limit: Int = 30,
    offset: Int = 0,
): Result<Pair<List<NeteaseAlbum>, Boolean>> =
    runCatching {
        val body =
            callApi(
                "/artist/albums/$artistId",
                mapOf(
                    "limit" to limit,
                    "offset" to offset,
                    "total" to "true",
                ),
            )
        (body.array("hotAlbums")?.map { it.toAlbum() } ?: emptyList()) to
            ((body["more"] as? JsonPrimitive)?.content == "true")
    }

/** 相似歌手(weapi /discovery/simiArtist,NCA 验证的路径,注意没有 /v1 前缀) */
suspend fun NeteaseClient.similarArtists(artistId: Long): Result<List<NeteaseArtist>> =
    runCatching {
        val body = callWeApi("/discovery/simiArtist", mapOf("artistid" to artistId))
        body.array("artists")?.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"].nLong() ?: return@mapNotNull null
            NeteaseArtist(
                id = id,
                name = obj.str("name").orEmpty(),
                picUrl = (obj.str("picUrl") ?: obj.str("img1v1Url"))?.toHttpsUrl(),
                musicSize = obj["musicSize"].nInt(),
                albumSize = obj["albumSize"].nInt(),
            )
        } ?: emptyList()
    }

/** 歌曲评论(weapi /v1/resource/comments/R_SO_4_{id}):热评 + 最新评论 + 总数 */
suspend fun NeteaseClient.songComments(
    songId: Long,
    limit: Int = 20,
    offset: Int = 0,
): Result<NeteaseCommentPage> =
    runCatching {
        val body =
            callWeApi(
                // R_SO_4_ 前缀 = 单曲资源(歌单 R_PL_、专辑 R_AL_)
                "/v1/resource/comments/R_SO_4_$songId",
                mapOf(
                    "limit" to limit,
                    "offset" to offset,
                    "beforeTime" to "",
                ),
            )
        fun parse(array: JsonArray?): List<NeteaseComment> =
            array?.mapNotNull { element ->
                val obj = element.jsonObject
                NeteaseComment(
                    commentId = obj["commentId"].nLong() ?: return@mapNotNull null,
                    userId = obj.obj("user")?.get("userId").nLong(),
                    nickname = obj.obj("user")?.str("nickname"),
                    avatarUrl = obj.obj("user")?.str("avatarUrl")?.toHttpsUrl(),
                    content = obj.str("content").orEmpty(),
                    timeMs = obj["time"].nLong(),
                    likedCount = obj["likedCount"].nLong(),
                    location = obj.obj("ipLocation")?.str("location") ?: obj.str("ipLocation"),
                )
            } ?: emptyList()
        NeteaseCommentPage(
            hotComments = parse(body.array("hotComments")),
            latestComments = parse(body.array("comments")),
            totalCount = body["total"].nInt() ?: 0,
            hasMore = (body["more"] as? JsonPrimitive)?.content == "true",
        )
    }

/**
 * 云盘文件列表(weapi /v1/cloud/get)。simpleSong 即可播形状,songId(云端分配的大数值 ID)
 * 直接走取流端点播放;本地文件上传(/cloud/upload 二进制流)留到 UI 接入时按需做。
 */
suspend fun NeteaseClient.cloudDiskFiles(
    limit: Int = 30,
    offset: Int = 0,
): Result<NeteaseCloudDiskPage> =
    runCatching {
        val body =
            callWeApi(
                "/v1/cloud/get",
                mapOf(
                    "limit" to limit,
                    "offset" to offset,
                ),
            )
        NeteaseCloudDiskPage(
            files =
                body.array("data")?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val songId = obj["songId"].nLong() ?: return@mapNotNull null
                    NeteaseCloudFile(
                        songId = songId,
                        fileName = obj.str("fileName"),
                        sizeBytes = obj["fileSize"].nLong(),
                        bitrate = obj["bitrate"].nLong(),
                        addTimeMs = obj["addTime"].nLong(),
                        song = obj.obj("simpleSong")?.takeIf { it["id"].nLong() != null }?.toSong(),
                    )
                } ?: emptyList(),
            totalCount = body["count"].nInt() ?: 0,
            hasMore = (body["hasMore"] as? JsonPrimitive)?.content == "true",
        )
    }


// ----------------------------------------------------------------------------
// JSON tree helpers — short unique names so they never clash with kotlinx's
// JsonPrimitive.intOrNull/longOrNull extensions imported above.
// ----------------------------------------------------------------------------

/** 从 song 形状(ar/al/dt/fee/privilege)映射 */
internal fun JsonElement.toSong(): NeteaseSong {
    val obj = jsonObject
    val ar = obj.array("ar") ?: obj.array("artists")
    val al = obj.obj("al") ?: obj.obj("album")
    return NeteaseSong(
        id = obj["id"].nLong() ?: 0L,
        name = obj.str("name").orEmpty(),
        artists = ar?.mapNotNull { it.jsonObject.str("name") } ?: emptyList(),
        artistIds = ar?.mapNotNull { it.jsonObject["id"].nLong() } ?: emptyList(),
        albumId = al?.get("id").nLong(),
        albumName = al?.str("name"),
        durationMs = obj["dt"].nLong() ?: obj["duration"].nLong() ?: 0L,
        coverUrl =
            (al?.str("picUrl") ?: al?.str("picUrl_str") ?: obj.str("picUrl"))?.toHttpsUrl(),
        fee = obj["fee"].nInt(),
        hasCopyright = obj["privilege"]?.let { (it as? JsonObject)?.get("st").nInt() == 0 },
    )
}

/** 从 playlist 形状映射;rawSpecialType 为网易原生 specialType(5=红心歌单) */
internal fun JsonElement.toPlaylist(): NeteasePlaylist {
    val obj = jsonObject
    return NeteasePlaylist(
        id = obj["id"].nLong() ?: 0L,
        name = obj.str("name").orEmpty(),
        creatorId = obj.obj("creator")?.get("userId").nLong(),
        creatorNickname = obj.obj("creator")?.str("nickname"),
        coverUrl = (obj.str("coverImgUrl") ?: obj.str("picUrl") ?: obj.str("coverUrl"))?.toHttpsUrl(),
        trackCount = obj["trackCount"].nInt() ?: obj["songCount"].nInt() ?: 0,
        playCount = obj["playCount"].nLong() ?: obj["playcount"].nLong(),
        description = obj.str("description"),
        specialType = NeteasePlaylist.SpecialType.NORMAL,
        rawSpecialType = obj["specialType"].nInt() ?: 0,
    )
}

/** 从 album 形状映射(artists 数组或 artist 对象两种来源) */
internal fun JsonElement.toAlbum(): NeteaseAlbum {
    val obj = jsonObject
    return NeteaseAlbum(
        id = obj["id"].nLong() ?: 0L,
        name = obj.str("name").orEmpty(),
        artistName =
            obj.array("artists")?.firstOrNull()?.jsonObject?.str("name")
                ?: obj.obj("artist")?.str("name"),
        coverUrl = (obj.str("picUrl") ?: obj.str("coverImgUrl"))?.toHttpsUrl(),
        trackCount = obj["size"].nInt() ?: obj["trackCount"].nInt() ?: 0,
        publishTimeMs = obj["publishTime"].nLong(),
        description = obj.str("description"),
    )
}

/** 从 djRadio/电台形状映射;没有 id 返回 null(调用方 mapNotNull 过滤) */
internal fun JsonElement.toDjRadioOrNull(): NeteaseDjRadio? {
    val obj = jsonObject
    val id = obj["id"].nLong() ?: return null
    return NeteaseDjRadio(
        id = id,
        name = obj.str("name").orEmpty(),
        coverUrl = (obj.str("coverUrl") ?: obj.str("coverImgUrl") ?: obj.str("picUrl"))?.toHttpsUrl(),
        programCount = obj["programCount"].nInt() ?: obj["trackCount"].nInt() ?: 0,
        djNickname = obj.obj("dj")?.str("nickname"),
    )
}

/** 图片 URL 统一 https(NeriPlayer toHttps:pic CDN 的 http 链接在部分网络下被拦) */
internal fun String.toHttpsUrl(): String = replaceFirst("http://", "https://")

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** 空串归一为 null 的字符串读取 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.ifEmpty { null }

/** kotlinx 的 longOrNull/intOrNull 只挂在 JsonPrimitive 上,这里包成可空接收者的安全读取 */
internal fun JsonElement?.nLong(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

internal fun JsonElement?.nInt(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
