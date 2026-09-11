/*
 * NetEase business endpoints — each parses the raw JsonObject into the typed DTOs the
 * repository layer maps into SimpMusic's domain entities. Endpoint paths and parameter
 * shapes are cross-checked against NeriPlayer (verified against production) and the
 * community NeteaseCloudMusicApi project.
 */
package com.maxrave.netease

import com.maxrave.netease.model.NeteaseLyrics
import com.maxrave.netease.model.NeteasePlaylist
import com.maxrave.netease.model.NeteaseQuality
import com.maxrave.netease.model.NeteaseRadioSession
import com.maxrave.netease.model.NeteaseSong
import com.maxrave.netease.model.NeteaseStreamUrl
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
    const val RADAR_FANS_PLAYLIST_ID = 5_327_906_368L // 粉丝雷达
    const val TOPLIST_SOARING_ID = 19_723_756L // 飙升榜
    const val TOPLIST_NEW_ID = 3_779_629L // 新歌榜
    const val TOPLIST_HOT_ID = 37_786_678L // 热歌榜
}

suspend fun NeteaseClient.searchSongs(
    keywords: String,
    limit: Int = 30,
    offset: Int = 0,
): Result<List<NeteaseSong>> =
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
        body.obj("result")?.array("songs")?.map { it.toSong() } ?: emptyList()
    }

suspend fun NeteaseClient.songDetail(ids: List<Long>): Result<List<NeteaseSong>> =
    runCatching {
        val body =
            callWeApi(
                "/v3/song/detail",
                mapOf(
                    "c" to ids.joinToString(",") { "{\"id\":$it}" },
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

suspend fun NeteaseClient.highQualityPlaylists(
    cat: String? = "全部",
    limit: Int = 30,
): Result<List<NeteasePlaylist>> =
    runCatching {
        val body =
            callWeApi(
                "/playlist/highquality/list",
                buildMap<String, Any?> {
                    put("limit", limit)
                    if (cat != null) put("cat", cat)
                },
            )
        body.array("playlists")?.map { it.toPlaylist() } ?: emptyList()
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

/** 雷达歌单(私人/粉丝),本质是固定 ID 的普通歌单,内容每日更新 */
suspend fun NeteaseClient.radarPlaylists(): Result<List<NeteasePlaylist>> =
    runCatching {
        listOf(
            NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID,
            NeteaseConstants.RADAR_FANS_PLAYLIST_ID,
        ).mapNotNull { id ->
            playlistDetail(id).getOrNull()?.first?.copy(
                specialType =
                    if (id == NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID) {
                        NeteasePlaylist.SpecialType.RADAR_PRIVATE
                    } else {
                        NeteasePlaylist.SpecialType.RADAR_FANS
                    },
            )
        }
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
// C 档能力(下个版本实现):云盘、歌曲评论/热评、歌手详情/百科/相似歌手。
// 接口位保留在这里,实现为显式 TODO 桩,避免下个版本翻 git 历史找端点。
// ----------------------------------------------------------------------------

/**
 * TODO(NETEASE_C_TIER): 云盘 — /cloud/vip/info 额度、/cloud/get 文件列表、
 * /cloud/upload 上传本地歌曲(VIP 最高 60GB),播放走云盘专属 songId 取流。
 */
suspend fun NeteaseClient.cloudDiskStub(): Result<Unit> = Result.failure(UnsupportedOperationException("cloud disk: next release"))

/**
 * TODO(NETEASE_C_TIER): 歌曲评论 — /comment/music (hotComments/newComments),
 * 播放页新增评论区入口。
 */
suspend fun NeteaseClient.songCommentsStub(songId: Long): Result<Unit> = Result.failure(UnsupportedOperationException("song comments: next release"))

/**
 * TODO(NETEASE_C_TIER): 歌手详情 — /artist/detail(百科+封面)、/artist/similar(相似歌手)、
 * /artist/songs(热门50)。打通后 SongEntity.artistId 填网易歌手 ID,播放页/艺人页可跳转。
 */
suspend fun NeteaseClient.artistDetailStub(artistId: Long): Result<Unit> = Result.failure(UnsupportedOperationException("artist detail: next release"))


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
        coverUrl = al?.str("picUrl") ?: obj.str("picUrl"),
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
        coverUrl = obj.str("coverImgUrl") ?: obj.str("picUrl"),
        trackCount = obj["trackCount"].nInt() ?: 0,
        playCount = obj["playCount"].nLong() ?: obj["playcount"].nLong(),
        description = obj.str("description"),
        specialType = NeteasePlaylist.SpecialType.NORMAL,
        rawSpecialType = obj["specialType"].nInt() ?: 0,
    )
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** 空串归一为 null 的字符串读取 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.ifEmpty { null }

/** kotlinx 的 longOrNull/intOrNull 只挂在 JsonPrimitive 上,这里包成可空接收者的安全读取 */
internal fun JsonElement?.nLong(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

internal fun JsonElement?.nInt(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
