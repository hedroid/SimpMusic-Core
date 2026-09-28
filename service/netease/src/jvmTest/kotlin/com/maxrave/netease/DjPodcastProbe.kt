package com.maxrave.netease

import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.booleanOrNull

/**
 * 播客端点形状探针(手动,非 CI):验证发现侧四个端点的响应外层形状
 * (精选=djRadios?/榜单=toplist?/猜你喜欢=data?)与订阅列表路径
 * (/user/djradio/get/subed vs /djradio/get/subed)。输出 /tmp/dj_podcast_out.txt。
 * cookie 文件不存在时静默跳过。
 */
class DjPodcastProbe {
    @Test
    fun probeDjPodcast() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/dj_podcast_out.txt")
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

            fun keysSummary(body: kotlinx.serialization.json.JsonObject): String {
                val keys = body.keys.joinToString(",")
                val code = (body["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                fun arr(name: String): String =
                    (body[name] as? kotlinx.serialization.json.JsonArray)?.let { "size=${it.size}" } ?: "absent"
                return "code=$code keys=[$keys] djRadios=${arr("djRadios")} toplist=${arr("toplist")} data=${arr("data")} programs=${arr("programs")} categories=${arr("categories")}"
            }

            section("recommend/v1") { keysSummary(client.callEApi("/djradio/recommend/v1", emptyMap())) }
            section("toplist") { keysSummary(client.callEApi("/djradio/toplist", mapOf("limit" to 20, "offset" to 0, "type" to 1))) }
            section("personalize/rcmd") { keysSummary(client.callEApi("/djradio/personalize/rcmd", mapOf("limit" to 10))) }
            section("program/recommend/v1") { keysSummary(client.callEApi("/program/recommend/v1", mapOf("limit" to 5, "offset" to 0))) }
            section("category/get") { keysSummary(client.callEApi("/djradio/category/get", emptyMap())) }
            section("subed weapi /user/djradio/get/subed") {
                val body = client.callWeApi("/user/djradio/get/subed", mapOf("limit" to 5, "offset" to 0))
                keysSummary(body)
            }
            section("subed weapi /djradio/get/subed") {
                val body = client.callWeApi("/djradio/get/subed", mapOf("limit" to 5, "offset" to 0))
                keysSummary(body)
            }
            section("subed eapi /djradio/get/subed") {
                val body = client.callEApi("/djradio/get/subed", mapOf("limit" to 5, "offset" to 0))
                keysSummary(body)
            }
            // 真实封装函数(与 app 同代码路径)的解析结果
            section("fn recommendDjRadios") {
                val r = client.recommendDjRadios()
                r.fold(onSuccess = { list -> "size=${list.size} first=${list.firstOrNull()?.let { "${it.id}|${it.name}|${it.djNickname}" }}" }, onFailure = { "FAILED ${it.message}" })
            }
            section("fn djRadioToplist") {
                val r = client.djRadioToplist()
                r.fold(onSuccess = { list -> "size=${list.size} first=${list.firstOrNull()?.let { "${it.id}|${it.name}|${it.djNickname}" }}" }, onFailure = { "FAILED ${it.message}" })
            }
            section("fn personalizedDjRadios") {
                val r = client.personalizedDjRadios()
                r.fold(onSuccess = { list -> "size=${list.size} first=${list.firstOrNull()?.let { "${it.id}|${it.name}|${it.djNickname}" }}" }, onFailure = { "FAILED ${it.message}" })
            }
            // 首条电台原始 JSON(查 toDjRadioOrNull 是否可能全 null)
            section("raw first recommend item") {
                val body = client.callEApi("/djradio/recommend/v1", emptyMap())
                val first = (body["djRadios"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                first?.toString()?.take(500) ?: "no item"
            }
            // 首个电台的节目列表形状
            section("program/byradio (radio 349426053)") {
                keysSummary(client.callEApi("/dj/program/byradio", mapOf("radioId" to 349426053L, "limit" to 5, "offset" to 0, "asc" to false)))
            }
            section("fn programs cateId=3(情感)") {
                val r = client.recommendPodcastPrograms(3L, limit = 5, offset = 0)
                r.fold(onSuccess = { (list, more) -> "size=${list.size} more=$more titles=" + list.take(3).joinToString("/") { it.name.take(12) } }, onFailure = { "FAILED ${it.message}" })
            }
            section("fn programs cateId=null") {
                val r = client.recommendPodcastPrograms(null, limit = 5, offset = 0)
                r.fold(onSuccess = { (list, more) -> "size=${list.size} more=$more titles=" + list.take(3).joinToString("/") { it.name.take(12) } }, onFailure = { "FAILED ${it.message}" })
            }
            section("fn programs listenerCount sample") {
                val r = client.recommendPodcastPrograms(null, limit = 3, offset = 0)
                r.fold(onSuccess = { (list, _) -> list.joinToString("/") { "${it.listenerCount}" } }, onFailure = { "FAILED ${it.message}" })
            }
            section("fn programs cateId=3 limit30") {
                val r = client.recommendPodcastPrograms(3L, limit = 30, offset = 0)
                r.fold(onSuccess = { (list, more) -> "size=${list.size} more=$more titles=" + list.take(3).joinToString("/") { it.name.take(12) } }, onFailure = { "FAILED ${it.message}" })
            }
            section("fn programs null limit30") {
                val r = client.recommendPodcastPrograms(null, limit = 30, offset = 0)
                r.fold(onSuccess = { (list, more) -> "size=${list.size} more=$more titles=" + list.take(3).joinToString("/") { it.name.take(12) } }, onFailure = { "FAILED ${it.message}" })
            }
            section("variant categoryId=3") {
                val body = client.callEApi("/program/recommend/v1", mapOf("categoryId" to 3, "limit" to 10, "offset" to 0))
                val arr = body.array("programs")
                "size=${arr?.size} first=${(arr?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.str("name")?.take(16)}"
            }
            section("variant cateId string") {
                val body = client.callEApi("/program/recommend/v1", mapOf("cateId" to "3", "limit" to 10, "offset" to 0))
                val arr = body.array("programs")
                "size=${arr?.size} first=${(arr?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.str("name")?.take(16)}"
            }
            section("program embedded radio.categoryId sample") {
                val r = client.recommendPodcastPrograms(null, limit = 3, offset = 0)
                r.fold(onSuccess = { (list, _) -> list.joinToString("/") { p -> p.radioId.toString() } }, onFailure = { "FAILED ${it.message}" })
            }
            section("短剧诊断:找电台+节目+取流") {
                val sb2 = StringBuilder()
                // 榜单里找"有声短剧"
                val all = (client.djRadioToplist().getOrNull().orEmpty()) +
                    (client.recommendDjRadios().getOrNull().orEmpty()) +
                    (client.personalizedDjRadios().getOrNull().orEmpty())
                sb2.appendLine("candidates=" + all.joinToString("/") { it.name.take(10) })
                val radio = all.firstOrNull { it.name.contains("短剧") || it.name.contains("有声") }
                if (radio != null) {
                    val progs = client.djRadioPrograms(radio.id, limit = 3, offset = 0).getOrNull()?.first ?: emptyList()
                    sb2.appendLine("programs=${progs.size}")
                    progs.take(2).forEach { p ->
                        sb2.appendLine("program id=${p.id} name=${p.name.take(20)} durMs=${p.durationMs} mainSongId=${p.mainSongId}")
                        if (p.mainSongId != null) {
                            val url = client.songUrl(p.mainSongId, com.maxrave.netease.model.NeteaseQuality.LOSSLESS).getOrNull()
                            sb2.appendLine("  songUrl: url=${url?.url?.take(60)} size=${url?.sizeBytes} br=${url?.bitrate} level=${url?.level} trial=${url?.freeTrialInfo}")
                        }
                    }
                }
                sb2.toString().trim()
            }
            section("短剧 songDetail privilege") {
                val songs = client.songDetail(listOf(2724359175L)).getOrNull()
                songs?.firstOrNull()?.let { sd ->
                    "name=${sd.name.take(16)} hasCopyright=${sd.hasCopyright} fee=${sd.fee}"
                } ?: "songDetail empty"
            }
            section("短剧节目原始字段") {
                val all2 = (client.djRadioToplist().getOrNull().orEmpty()) +
                    (client.recommendDjRadios().getOrNull().orEmpty())
                val radio2 = all2.firstOrNull { it.name.contains("短剧") } ?: return@section "no radio in ${all2.size}"
                val body = client.callEApi("/dj/program/byradio", mapOf("radioId" to radio2.id, "limit" to 1, "offset" to 0, "asc" to false))
                val raw = (body.array("programs")?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.toString() ?: "none"
                // 只留关键字段:过滤出费相关+时长相关
                raw.substring(0, raw.length.coerceAtMost(30000))
            }
            section("programFeeType 对照:推荐节目vs普通电台") {
                val rec = client.recommendPodcastPrograms(null, limit = 5, offset = 0).getOrNull()?.first.orEmpty()
                val recInfo = rec.joinToString("/") { "${it.name.take(6)}" }
                // 原始字段对照
                val rawRec = client.callEApi("/program/recommend/v1", mapOf("limit" to 3, "offset" to 0))
                val feeTypes = (rawRec.array("programs") ?: kotlinx.serialization.json.JsonArray(emptyList())).mapNotNull { p ->
                    (p as? kotlinx.serialization.json.JsonObject)?.let {
                        val fee = it["programFeeType"].nInt()
                        val buyed = (it["buyed"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
                        "${it.str("name")?.take(6)}:feeType=$fee,buyed=$buyed"
                    }
                }
                "rec=$recInfo\nfeeTypes=" + feeTypes.joinToString(" | ")
            }
            section("女王电台 paid 标志分布") {
                val all3 = (client.djRadioToplist().getOrNull().orEmpty()) +
                    (client.recommendDjRadios().getOrNull().orEmpty())
                val r3 = all3.firstOrNull { it.name.contains("女王归来") } ?: return@section "no radio"
                val body3 = client.callEApi("/dj/program/byradio", mapOf("radioId" to r3.id, "limit" to 30, "offset" to 0, "asc" to false))
                val arr = body3.array("programs") ?: kotlinx.serialization.json.JsonArray(emptyList())
                arr.mapNotNull { p ->
                    (p as? kotlinx.serialization.json.JsonObject)?.let {
                        "${it.str("name")?.take(8)}:feeType=${it["programFeeType"].nInt()},buyed=${(it["buyed"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull}"
                    }
                }.joinToString(" | ")
            }
            section("取流原始响应对照:试听vs正常") {
                val sb3 = StringBuilder()
                listOf(2724359175L, 2724348872L).forEach { sid ->
                    val body = client.callWeApi("/song/enhance/player/url/v1", mapOf("ids" to "[$sid]", "level" to "lossless", "encodeType" to "flac"))
                    val first = (body.array("data")?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.toString() ?: "none"
                    sb3.appendLine("TRIAL $sid -> $first")
                }
                // 正常对照:用推荐节目里免费节目 mainSong
                val freeProg = client.recommendPodcastPrograms(null, limit = 3, offset = 0).getOrNull()?.first?.firstOrNull { it.mainSongId != null }
                freeProg?.mainSongId?.let { fid ->
                    val body = client.callWeApi("/song/enhance/player/url/v1", mapOf("ids" to "[$fid]", "level" to "lossless", "encodeType" to "flac"))
                    val first = (body.array("data")?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.toString() ?: "none"
                    sb3.appendLine("FREE $fid(${freeProg.name.take(8)}) -> $first")
                }
                sb3.toString().trim()
            }
            out.writeText(sb.toString())
            println(sb.toString())
        }
    }
}
