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
            section("榜单电台 programCount vs byradio 实际节目数") {
                val tops = client.djRadioToplist().getOrNull().orEmpty()
                tops.take(8).map { r ->
                    val actual = client.djRadioPrograms(r.id, limit = 5, offset = 0).getOrNull()?.first?.size ?: -1
                    "${r.name.take(18)}(id=${r.id}): declared=${r.programCount}, actual=$actual"
                }.joinToString("\n")
            }
            section("音乐合集型电台:songs 字段验证") {
                val body = client.callEApi("/dj/program/byradio", mapOf("radioId" to 793942484L, "limit" to 2, "offset" to 0, "asc" to false))
                val cnt = body["count"].nInt()
                val progs = body.array("programs")?.size
                val raw = body.toString()
                val songsIdx = raw.indexOf("\"songs\"")
                val songsSnippet = if (songsIdx >= 0) raw.substring(songsIdx, minOf(songsIdx + 400, raw.length)) else "NO songs field"
                "count=$cnt programs=$progs\nsongs snippet: $songsSnippet"
            }
            section("电台当歌单拉:v6/v3 playlist detail") {
                val sb4 = StringBuilder()
                val b6 = runCatching { client.callApi("/v6/playlist/detail", mapOf("id" to 793942484L, "n" to 5, "s" to 0)) }.getOrNull()
                sb4.appendLine("v6 code=${b6?.get("code")?.nLong()} keys=${b6?.keys?.joinToString(",").toString().take(120)}")
                val tracks6 = b6?.array("playlist") // playlist 是对象不是数组
                val pl = b6?.obj("playlist")
                sb4.appendLine("v6 playlist.trackCount=${pl?.get("trackCount").nInt()} tracks=${pl?.array("tracks")?.size} trackIds=${pl?.array("trackIds")?.size}")
                val byAsc = client.callEApi("/dj/program/byradio", mapOf("radioId" to 793942484L, "limit" to 3, "offset" to 0, "asc" to true))
                sb4.appendLine("byradio asc=true: code=${byAsc["code"].nLong()} msg=${byAsc.str("msg")}/${byAsc.str("message")} programs=${byAsc.array("programs")?.size}")
                // 对照:能返回节目的电台(清音悦耳 972022490)
                val ok2 = client.callEApi("/dj/program/byradio", mapOf("radioId" to 972022490L, "limit" to 2, "offset" to 0, "asc" to false))
                sb4.appendLine("byradio 清音悦耳: programs=${ok2.array("programs")?.size} count=${ok2["count"].nInt()}")
                sb4.toString().trim()
            }
            section("移植候选端点验证") {
                val sb5 = StringBuilder()
                fun kotlinx.serialization.json.JsonObject?.brief(name: String) {
                    val o = this
                    sb5.appendLine("$name: code=${o?.get("code").nLong()} keys=${o?.keys?.joinToString(",").toString().take(100)}")
                }
                // 1.分类下电台(bycat):官方"分类浏览"的正主
                runCatching { client.callEApi("/djradio/get/bycat", mapOf("cateId" to 3L, "limit" to 5, "offset" to 0, "asid" to "")) }.getOrNull().brief("bycat cateId=3")
                runCatching { client.callWeApi("/djradio/get/bycat", mapOf("cateId" to 3L, "limit" to 5, "offset" to 0)) }.getOrNull().brief("bycat weapi")
                // 2.新晋电台榜(type=0)
                val t0 = client.djRadioToplist(limit = 5, type = 0).getOrNull()
                sb5.appendLine("新晋榜(type=0): size=${t0?.size} first=${t0?.firstOrNull()?.name?.take(10)}")
                // 3.节目榜
                runCatching { client.callEApi("/program/toplist", mapOf("limit" to 5, "offset" to 0)) }.getOrNull().brief("program/toplist eapi")
                runCatching { client.callWeApi("/dj/program/toplist", mapOf("limit" to 5, "offset" to 0)) }.getOrNull().brief("dj/program/toplist weapi")
                // 4.相似电台
                runCatching { client.callEApi("/djradio/similar", mapOf("radioId" to 792734685L)) }.getOrNull().brief("djradio/similar")
                runCatching { client.callWeApi("/djradio/similarity", mapOf("radioId" to 792734685L)) }.getOrNull().brief("djradio/similarity")
                sb5.toString().trim()
            }
            section("bycat 路径变体穷举") {
                val sb6 = StringBuilder()
                suspend fun tryCall(name: String, path: String, params: Map<String, Any?>, eapi: Boolean = true) {
                    val body = runCatching { if (eapi) client.callEApi(path, params) else client.callWeApi(path, params) }.getOrNull()
                    val arr = body?.array("djRadios") ?: body?.array("data") ?: body?.array("radios")
                    sb6.appendLine("$name: code=${body?.get("code").nLong()} keys=${body?.keys?.joinToString(",").toString().take(60)} arr=${arr?.size}")
                }
                tryCall("eapi /djradio/category/recommend", "/djradio/category/recommend", mapOf("cateId" to 3L, "limit" to 5, "offset" to 0))
                tryCall("eapi /djradio/hot", "/djradio/hot", mapOf("cateId" to 3L, "limit" to 5, "offset" to 0))
                tryCall("weapi /djradio/bycat", "/djradio/bycat", mapOf("cateId" to 3L, "limit" to 5, "offset" to 0), eapi = false)
                tryCall("eapi /dj/program/bycat", "/dj/program/bycat", mapOf("cateId" to 3L, "limit" to 5, "offset" to 0))
                sb6.toString().trim()
            }
            section("program/toplist 元素形状") {
                val body = client.callEApi("/program/toplist", mapOf("limit" to 2, "offset" to 0))
                val arr = body.array("toplist")
                val first = (arr?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.toString() ?: "none"
                first.take(900)
            }
            section("全部分类+逐分类电台数") {
                val cats = client.podcastCategories().getOrNull().orEmpty()
                val sb7 = StringBuilder()
                sb7.appendLine("categories(${cats.size}): " + cats.joinToString("/") { "${it.id}:${it.name}" })
                cats.forEach { c ->
                    val r = client.djRadiosByCategory(c.id, limit = 5, offset = 0).getOrNull()
                    val info = r?.let { "n=${it.first.size},more=${it.second}" } ?: "FAILED"
                    sb7.appendLine("  ${c.name}(${c.id}): $info")
                }
                sb7.toString().trim()
            }
            section("byradio asc 参数验证") {
                val rid = client.userDjRadios().getOrNull()?.firstOrNull()?.id
                    ?: client.djRadioToplist().getOrNull()?.firstOrNull()?.id
                    ?: return@section "no radio"
                val a = client.djRadioPrograms(rid, limit = 3, offset = 0, asc = false).getOrNull()?.first
                val b = client.djRadioPrograms(rid, limit = 3, offset = 0, asc = true).getOrNull()?.first
                "radio=$rid\nfalse: " + (a?.joinToString("/") { "${it.name.take(6)}@${it.serialNum}" } ?: "null") +
                    "\ntrue:  " + (b?.joinToString("/") { "${it.name.take(6)}@${it.serialNum}" } ?: "null")
            }
            section("sub/unsub 真实响应形状(测试后还原)") {
                val sb12 = StringBuilder()
                // 找一个当前未订阅的电台(榜单里挑)
                val target = client.djRadioToplist(limit = 20, type = 1).getOrNull()
                    ?.firstOrNull { it.subed != true } ?: return@section "no unsubscribed target"
                sb12.appendLine("target: ${target.id} ${target.name.take(10)} subed=${target.subed}")
                // sub
                val subBody = runCatching { client.callEApi("/djradio/sub", mapOf("id" to target.id)) }.getOrNull()
                sb12.appendLine("SUB: code=${subBody?.get("code").nLong()} keys=${subBody?.keys?.joinToString(",").toString().take(70)}")
                // 验证订阅生效
                val subed1 = client.userDjRadios().getOrNull()?.any { it.id == target.id }
                sb12.appendLine("subscribed now: $subed1")
                // unsub 还原
                val unsubBody = runCatching { client.callEApi("/djradio/unsub", mapOf("id" to target.id)) }.getOrNull()
                sb12.appendLine("UNSUB: code=${unsubBody?.get("code").nLong()}")
                val subed2 = client.userDjRadios().getOrNull()?.any { it.id == target.id }
                sb12.appendLine("subscribed after unsub: $subed2")
                sb12.toString().trim()
            }
            section("优秀新电台四轮:toplist分类版type档") {
                val sb11 = StringBuilder()
                for (t in listOf(0, 1, 2, 3)) {
                    val b = runCatching { client.callEApi("/djradio/toplist", mapOf("cateId" to 3L, "limit" to 6, "offset" to 0, "type" to t)) }.getOrNull()
                    val arr = b?.array("toplist")
                    val names = arr?.take(3)?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.str("name")?.take(6) }?.joinToString("/")
                    sb11.appendLine("toplist cateId=3 type=$t: code=${b?.get("code").nLong()} n=${arr?.size} [$names]")
                }
                for (path in listOf("/djradio/category/rec", "/djradio/featured")) {
                    val b = runCatching { client.callEApi(path, mapOf("cateId" to 3L, "limit" to 6)) }.getOrNull()
                    sb11.appendLine("$path: code=${b?.get("code").nLong()}")
                }
                sb11.toString().trim()
            }
            section("优秀新电台端点三轮") {
                val sb10 = StringBuilder()
                // 社区 dj_radio 模块(分类推荐电台)用的路径
                val byasidW = runCatching { client.callWeApi("/djradio/get/byasid", mapOf("asid" to "", "cateId" to 3L, "limit" to 6, "offset" to 0)) }.getOrNull()
                sb10.appendLine("byasid weapi: code=${byasidW?.get("code").nLong()} keys=${byasidW?.keys?.joinToString(",").toString().take(60)}")
                val byasidE = runCatching { client.callEApi("/djradio/get/byasid", mapOf("asid" to "", "cateId" to 3L, "limit" to 6, "offset" to 0)) }.getOrNull()
                sb10.appendLine("byasid eapi: code=${byasidE?.get("code").nLong()} keys=${byasidE?.keys?.joinToString(",").toString().take(60)}")
                val arr = byasidW?.array("djRadios") ?: byasidE?.array("djRadios")
                if (arr != null && arr.isNotEmpty()) {
                    sb10.appendLine("首条: " + ((arr.first() as? kotlinx.serialization.json.JsonObject)?.str("name") ?: "?"))
                }
                sb10.toString().trim()
            }
            section("分类页端点二轮") {
                val sb9 = StringBuilder()
                suspend fun names(path: String, params: Map<String, Any?>, arrKey: String = "djRadios"): String {
                    val b = runCatching { client.callEApi(path, params) }.getOrNull() ?: return "FAIL"
                    val arr = b.array(arrKey) ?: return "code=${b.get("code").nLong()} noArr"
                    return arr.take(4).mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.str("name")?.take(8) }.joinToString("/")
                }
                sb9.appendLine("hot默认: " + names("/djradio/hot", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0)))
                sb9.appendLine("hot type=1: " + names("/djradio/hot", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0, "type" to 1)))
                val tl = runCatching { client.callEApi("/djradio/toplist", mapOf("cateId" to 3L, "limit" to 4, "type" to 1)) }.getOrNull()
                sb9.appendLine("toplist+cateId: code=${tl?.get("code").nLong()} n=${tl?.array("toplist")?.size}")
                for (path in listOf("/djradio/highquality", "/djradio/get/highquality", "/dj/hot")) {
                    val b = runCatching { client.callEApi(path, mapOf("cateId" to 3L, "limit" to 4)) }.getOrNull()
                    sb9.appendLine("$path: code=${b?.get("code").nLong()} keys=${b?.keys?.joinToString(",").toString().take(50)}")
                }
                sb9.toString().trim()
            }
            section("分类页官方布局端点探测") {
                val sb8 = StringBuilder()
                suspend fun tryHot(name: String, params: Map<String, Any?>) {
                    val b = runCatching { client.callEApi("/djradio/hot", params) }.getOrNull()
                    val arr = b?.array("djRadios")
                    sb8.appendLine("$name: code=${b?.get("code").nLong()} n=${arr?.size} keys=${b?.keys?.joinToString(",").toString().take(50)}")
                }
                // type 变体(上升最快/最热?)
                tryHot("hot cateId=3 无type", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0))
                tryHot("hot type=0", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0, "type" to 0))
                tryHot("hot type=1", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0, "type" to 1))
                // orderBy 变体
                tryHot("hot orderBy=hot", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0, "orderBy" to "hot"))
                tryHot("hot orderBy=rise", mapOf("cateId" to 3L, "limit" to 4, "offset" to 0, "orderBy" to "rise"))
                // 优秀新电台候选
                for ((n, path) in listOf("djradio/new" to "/djradio/new", "djradio/get/new" to "/djradio/get/new", "djradio/recommend/new" to "/djradio/recommend/new")) {
                    val b = runCatching { client.callEApi(path, mapOf("cateId" to 3L, "limit" to 4)) }.getOrNull()
                    sb8.appendLine("$n: code=${b?.get("code").nLong()} keys=${b?.keys?.joinToString(",").toString().take(60)}")
                }
                sb8.toString().trim()
            }
            out.writeText(sb.toString())
            println(sb.toString())
        }
    }
}
