package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 心动模式 /playmode/intelligence/list 复探(手动探针,非 CI 断言):
 * PITFALLS 曾记录"weapi/eapi/明文 × 参数形状 × type 全部 500",但当时探针缺
 * startMusicId/count 字段;go-musicfox(活跃维护的第三方客户端)用五参数全字符串
 * 调 weapi 仍在用。本探针按其形状重测,cookie 来自 /tmp/netease_cookies.json
 * (抽取方式同 NewEndpointsProbe),输出 /tmp/heart_mode_out.txt。只读端点。
 */
class HeartModeProbe {
    @Test
    fun probeHeartMode() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/heart_mode_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()

            val account = client.getAccountStatus().getOrNull()
            val uid = account?.userId ?: 0L
            sb.appendLine("account: $uid ${account?.nickname} vip=${account?.vipType}")

            val pid = client.likedPlaylistId(uid).getOrNull()
            sb.appendLine("liked playlist id: $pid")
            val likedIds = client.userLikedSongIds(uid).getOrNull().orEmpty()
            sb.appendLine("liked songs: ${likedIds.size}, first=${likedIds.firstOrNull()}")

            if (pid != null && likedIds.isNotEmpty()) {
                val songId = likedIds.first().toString()
                // go-musicfox playmode_intelligence_list_service.go 原形状:五参数全字符串
                val shapes =
                    mapOf(
                        "weapi五参字符串(gomusicfox)" to mapOf(
                            "songId" to songId,
                            "type" to "fromPlayOne",
                            "playlistId" to pid.toString(),
                            "startMusicId" to songId,
                            "count" to "1",
                        ),
                        "weapi五参数字" to mapOf(
                            "songId" to songId.toLong(),
                            "type" to "fromPlayOne",
                            "playlistId" to pid,
                            "startMusicId" to songId.toLong(),
                            "count" to 1,
                        ),
                        "weapi无count" to mapOf(
                            "songId" to songId,
                            "type" to "fromPlayOne",
                            "playlistId" to pid.toString(),
                            "startMusicId" to songId,
                        ),
                    )
                for ((name, params) in shapes) {
                    sb.appendLine("\n== $name ==")
                    try {
                        val body = client.callWeApi("/playmode/intelligence/list", params)
                        sb.appendLine("HTTP OK, top keys: ${body.keys}")
                        sb.appendLine("code=${body["code"]}")
                        val data = (body["data"] as? kotlinx.serialization.json.JsonArray)
                        sb.appendLine("data count=${data?.size}")
                        data?.firstOrNull()?.let { first ->
                            sb.appendLine("data[0]=$first")
                        }
                    } catch (e: Throwable) {
                        sb.appendLine("FAILED: ${e.message}")
                    }
                }
            }
            out.writeText(sb.toString())
            println(sb)
        }
    }
}
