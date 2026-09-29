package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 最小探针:只打 2 发(/djradio/hot 列表 + /djradio/get 详情),看电台对象原始 JSON 里
 *  有没有评分字段(score/scoreText 之类),供分类页行内展示。输出 /tmp/dj_score_out.txt。 */
class DjRadioScoreProbe {
    @Test
    fun probeDjRadioScore() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/dj_score_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()

            runCatching {
                val body = client.callEApi("/djradio/hot", mapOf("cateId" to 3L, "limit" to 3, "offset" to 0))
                sb.appendLine("== /djradio/hot first radio raw ==\n")
                val first =
                    (body["djRadios"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                        ?: return@runCatching "no djRadios"
                sb.appendLine(first.toString())
                val radioId =
                    ((first as kotlinx.serialization.json.JsonObject)["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                sb.appendLine("\n== /djradio/get id=$radioId raw (截前 3000 字) ==")
                val detail = client.callEApi("/djradio/get", mapOf("id" to radioId?.toLong()))
                sb.appendLine(detail.toString().take(3000))

                // scoreInfoDTO 何时非空:故事FM(大台)+热门榜前几名逐个看
                sb.appendLine("\n== scoreInfoDTO 抽样 ==")
                suspend fun scoreOf(id: Long, tag: String) {
                    val d = runCatching { client.callEApi("/djradio/get", mapOf("id" to id)) }.getOrNull()
                    val radio = d?.obj("djRadio") as? kotlinx.serialization.json.JsonObject
                    sb.appendLine("$tag($id): scoreInfoDTO=${radio?.get("scoreInfoDTO")}")
                }
                scoreOf(350038649L, "故事FM")
                val hot = runCatching { client.callEApi("/djradio/toplist", mapOf("limit" to 5, "offset" to 0, "type" to 1)) }.getOrNull()
                (hot?.array("toplist") ?: hot?.array("djRadios") ?: hot?.array("data"))?.take(5)?.forEach { el ->
                    val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
                    val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
                    scoreOf(id, "toplist:$name")
                }

                // 其它候选端点:v2 详情(Melodia 用)/dj/detail/djradio/detail
                sb.appendLine("\n== 候选端点 ==")
                runCatching {
                    val v2 = client.callEApi("/djradio/v2/get", mapOf("id" to 350038649L))
                    val v2Data = v2.obj("data")
                    val v2Radio = v2Data?.obj("djRadio") ?: v2Data
                    val v2Keys = v2Radio?.keys?.joinToString(",") ?: "no-data"
                    val v2Score = v2Radio?.get("scoreInfoDTO")
                    val v2ScoreTxt = v2Radio?.get("score")
                    sb.appendLine("v2/get radioKeys=$v2Keys scoreInfoDTO=$v2Score score=$v2ScoreTxt")
                }.onFailure { sb.appendLine("v2/get FAILED: ${it.message}") }
                runCatching {
                    val d = client.callWeApi("/dj/detail", mapOf("rid" to 350038649L))
                    val dKeys = d.keys.joinToString(",")
                    val dScore = d["scoreInfoDTO"]
                    val dScore2 = d.obj("data")?.get("scoreInfoDTO")
                    sb.appendLine("dj/detail keys=$dKeys score=$dScore data.score=$dScore2")
                }.onFailure { sb.appendLine("dj/detail FAILED: ${it.message}") }
                runCatching {
                    val d = client.callEApi("/djradio/detail", mapOf("id" to 350038649L))
                    sb.appendLine("djradio/detail keys=${d.keys.joinToString(",")}")
                }.onFailure { sb.appendLine("djradio/detail FAILED: ${it.message}") }
            }.onFailure { sb.appendLine("FAILED: ${it.message}") }

            out.writeText(sb.toString())
            println(out.absolutePath)
        }
    }
}
