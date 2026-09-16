package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 新移植端点的真机冒烟(手动探针,非 CI 断言):
 *   adb shell run-as <pkg> sh -c 'cat files/datastore/settings.preferences_pb' > /tmp/pb
 *   从中抽出 netease_cookie 的 JSON 存到 /tmp/netease_cookies.json 再跑本测试。
 * 输出在 /tmp/new_endpoints_out.txt。只打只读端点;addToPlaylist 是写操作,不做真账号冒烟。
 * 文件不存在时静默跳过,保证仓库里不携带真实 cookie。
 */
class NewEndpointsProbe {
    @Test
    fun probeNewEndpoints() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/new_endpoints_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()

            suspend fun section(name: String, block: suspend () -> String) {
                try {
                    sb.appendLine("== $name ==\n${block()}\n")
                } catch (e: Throwable) {
                    sb.appendLine("== $name ==\nFAILED: ${e.message}\n")
                }
            }

            val account = client.getAccountStatus().getOrNull()
            val uid = account?.userId
            sb.appendLine("account: $uid ${account?.nickname} vip=${account?.vipType}")

            section("userStaredAlbums") {
                client.userStaredAlbums(uid ?: 0L).fold(
                    { it.joinToString("\n") { a -> "${a.id} | ${a.name} | ${a.artistName} | ${a.trackCount}首" }.ifEmpty { "(空)" } },
                    { "ERR ${it.message}" },
                )
            }
            section("albumDetail(第一个收藏专辑,没有就用周杰伦范特西 18918)") {
                val albumId = client.userStaredAlbums(uid ?: 0L).getOrNull()?.firstOrNull()?.id ?: 18918L
                client.albumDetail(albumId).fold(
                    { (a, songs) -> "${a.id} ${a.name} / ${a.artistName} / ${a.publishTimeMs} / 曲目${songs.size}: ${songs.take(3).joinToString { s -> s.name }}" },
                    { "ERR ${it.message}" },
                )
            }
            section("likedPlaylistId") {
                client.likedPlaylistId(uid ?: 0L).fold({ "liked playlist = $it" }, { "ERR ${it.message}" })
            }
            section("userLikedSongIds") {
                client.userLikedSongIds(uid ?: 0L).fold(
                    { "count=${it.size} first5=${it.take(5)}" },
                    { "ERR ${it.message}" },
                )
            }
            section("highQualityTags") {
                client.highQualityTags().fold(
                    { it.joinToString(" / ") { t -> t.name }.ifEmpty { "(空)" } },
                    { "ERR ${it.message}" },
                )
            }
            section("userDjRadios") {
                client.userDjRadios(uid ?: 0L).fold(
                    { it.joinToString("\n") { r -> "${r.id} | ${r.name} | ${r.programCount}期" }.ifEmpty { "(未订阅电台)" } },
                    { "ERR ${it.message}" },
                )
            }
            section("relatedPlaylists(热歌榜)") {
                client.relatedPlaylists(3778678L).fold(
                    { "count=${it.size}\n" + it.take(3).joinToString("\n") { p -> "${p.id} | ${p.name}" } },
                    { "ERR ${it.message}" },
                )
            }
            section("djRadioDetail(未订阅时用公开电台 349426053)") {
                val rid = client.userDjRadios(uid ?: 0L).getOrNull()?.firstOrNull()?.id ?: 349426053L
                client.djRadioDetail(rid).fold(
                    { "${it.id} ${it.name} | ${it.programCount}期 | dj=${it.djNickname}" },
                    { "ERR ${it.message}" },
                )
            }
            section("searchPlaylists(周杰伦)") {
                client.searchPlaylists("周杰伦", limit = 3).fold(
                    { r -> "total=${r.totalCount}\n" + r.items.joinToString("\n") { p -> "${p.id} | ${p.name} | ${p.trackCount}首 | ${p.coverUrl?.take(60)}" } },
                    { "ERR ${it.message}" },
                )
            }
            section("searchArtists(周杰伦)") {
                client.searchArtists("周杰伦", limit = 3).fold(
                    { r -> "total=${r.totalCount}\n" + r.items.joinToString("\n") { a -> "${a.id} | ${a.name} | 歌曲${a.musicSize} 专辑${a.albumSize} | ${a.picUrl?.take(50)}" } },
                    { "ERR ${it.message}" },
                )
            }
            section("radarPlaylists(全组)") {
                client.radarPlaylists().fold(
                    { it.joinToString("\n") { p -> "${p.id} | ${p.name} | ${p.trackCount}首 | ${p.specialType}" } },
                    { "ERR ${it.message}" },
                )
            }
            section("linkParser(纯函数)") {
                val cases =
                    listOf(
                        "https://music.163.com/song?id=186016",
                        "https://music.163.com/#/playlist?id=1234567",
                        "听听这个 https://music.163.com/album?id=3410984 好听",
                        "http://163cn.tv/AbCdEf",
                        "https://y.music.163.com/m/artist?id=6452",
                        "https://youtube.com/watch?v=x",
                    )
                cases.joinToString("\n") { c -> "$c -> ${NeteaseLinkParser.recognize(c)}" }
            }
            section("yrcConverter(纯函数)") {
                val sample = "[12580,3470](12580,250,0)难(12830,300,0)以\n[16050,2000](16050,500,0)开"
                val lines = NeteaseLyricsConverter.parseAuto(sample)
                "isYrc=${NeteaseLyricsConverter.isYrc(sample)} lines=${lines.size} " +
                    "first=${lines.firstOrNull()?.let { "${it.text} ${it.startMs}-${it.endMs} words=${it.words.size}" }}\n" +
                    "lrc=\n${NeteaseLyricsConverter.yrcToLrc(sample)}"
            }
            section("artistDetail(周杰伦 6452)") {
                client.artistDetail(6452L).fold(
                    { "id=${it.id} ${it.name} alias=${it.alias} 专辑${it.albumSize} 歌曲${it.musicSize} MV${it.mvSize} 认证=${it.identifyTitle} 简介=${it.briefDesc?.take(60)} 封面=${it.picUrl?.take(50)}" },
                    { "ERR ${it.message}" },
                )
            }
            section("artistDynamic(6452)") {
                client.artistDynamic(6452L).fold(
                    { "followed=${it.followed} videos=${it.videoCount}" },
                    { "ERR ${it.message}" },
                )
            }
            section("artistFollowerCount(6452)") {
                client.artistFollowerCount(6452L).fold(
                    { "fans=$it" },
                    { "ERR ${it.message}" },
                )
            }
            section("artistIntroduction(6452)") {
                client.artistIntroduction(6452L).fold(
                    { "brief=${it.briefDesc?.take(60)} sections=${it.sections.map { s -> s.first + "(" + s.second.take(30) + ")" }}" },
                    { "ERR ${it.message}" },
                )
            }
            section("artistSongs(6452 热门)") {
                client.artistSongs(6452L, limit = 3).fold(
                    { r -> "total=${r.totalCount} first=${r.items.joinToString { s -> s.name }}" },
                    { "ERR ${it.message}" },
                )
            }
            section("artistAlbums(6452)") {
                client.artistAlbums(6452L, limit = 3).fold(
                    { (albums, more) -> "more=$more " + albums.joinToString { a -> "${a.name}(${a.trackCount}首,${a.publishTimeMs})" } },
                    { "ERR ${it.message}" },
                )
            }
            section("similarArtists(6452)") {
                client.similarArtists(6452L).fold(
                    { it.joinToString(" / ") { a -> "${a.name}(${a.id})" }.ifEmpty { "(空)" } },
                    { "ERR ${it.message}" },
                )
            }
            section("songComments(晴天 186016)") {
                client.songComments(186016L, limit = 2).fold(
                    { page -> "total=${page.totalCount} more=${page.hasMore} hot=${page.hotComments.size} latest=${page.latestComments.size}\n" +
                        (page.hotComments.firstOrNull()?.let { c -> "hot#1 [${c.nickname}](${c.location}) 赞${c.likedCount}: ${c.content.take(50)}" } ?: "(无热评)") },
                    { "ERR ${it.message}" },
                )
            }
            section("songRedCount(晴天 186016)") {
                client.songRedCount(186016L).fold(
                    { "likes=$it" },
                    { "ERR ${it.message}" },
                )
            }
            section("cloudDiskFiles") {
                client.cloudDiskFiles(limit = 3).fold(
                    { page -> "total=${page.totalCount} more=${page.hasMore} files=${page.files.size}\n" +
                        page.files.joinToString("\n") { f -> "${f.songId} | ${f.fileName} | ${f.sizeBytes}B ${f.bitrate} | song=${f.song?.name}" }.ifEmpty { "(云盘为空)" } },
                    { "ERR ${it.message}" },
                )
            }

            section("searchSuggest(周杰)") {
                client.searchSuggest("周杰").fold(
                    { s ->
                        "songs=${s.songs.size} artists=${s.artists.size}\n" +
                            s.songs.take(3).joinToString("\n") { sg -> "${sg.id} | ${sg.name} | ${sg.artists} | ${sg.albumName} | ${sg.durationMs}ms | ${sg.coverUrl?.take(50)}" } + "\n" +
                            s.artists.take(3).joinToString("\n") { a -> "${a.id} | ${a.name} | ${a.picUrl?.take(50)}" }
                    },
                    { "ERR ${it.message}" },
                )
            }
            section("searchHot") {
                client.searchHot().fold(
                    { "count=${it.size}\n" + it.take(10).joinToString("\n") { h -> "${h.score} | ${h.word}" } },
                    { "ERR ${it.message}" },
                )
            }

            out.writeText(sb.toString())
            println(sb)
        }
    }
}
