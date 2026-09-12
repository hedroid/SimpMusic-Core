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

            out.writeText(sb.toString())
            println(sb)
        }
    }
}
