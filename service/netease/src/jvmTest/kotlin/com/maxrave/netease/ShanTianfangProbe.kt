package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 单田芳评书电台"空内容 vs 风控"形状探针(手动,非 CI):
 * 订阅列表找 id → /djradio/get 详情(programCount?) → /dj/program/byradio 实际节目列表形状。
 * cookie 文件不存在时静默跳过。输出 /tmp/shantianfang_out.txt。
 */
class ShanTianfangProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/shantianfang_out.txt")
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

            fun codeOf(body: kotlinx.serialization.json.JsonObject) =
                (body["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content

            // 1. 订阅列表找单田芳评书
            var radioId: Long? = null
            section("subed /djradio/get/subed") {
                val body = client.callEApi("/djradio/get/subed", mapOf("limit" to 100, "offset" to 0))
                val radios = body.array("djRadios").orEmpty()
                val lines = radios.mapNotNull { el ->
                    val o = el.jsonObjectSafely() ?: return@mapNotNull null
                    val id = o["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
                    val name = o.str("name")
                    val pc = o["programCount"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    if (name?.contains("单田芳") == true) radioId = id
                    "id=$id programCount=$pc name=$name"
                }
                "code=${codeOf(body)} total=${radios.size}\n" + lines.joinToString("\n") + "\nMATCH=${radioId}"
            }

            val id = radioId
            if (id == null) {
                sb.appendLine("!! 单田芳 radio not found in subed list")
            } else {
                // 2. 详情
                section("/djradio/get detail") {
                    val body = client.callEApi("/djradio/get", mapOf("id" to id))
                    val dj = body.obj("djRadio")
                    if (dj == null) {
                        "code=${codeOf(body)} djRadio=ABSENT keys=[${body.keys.joinToString(",")}]"
                    } else {
                        val pc = dj["programCount"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        val name = dj.str("name")
                        val desc = dj.str("desc")?.take(60)
                        "code=${codeOf(body)} name=$name programCount=$pc desc=$desc"
                    }
                }

                // 3. 节目列表(asc=false 首页,与 app 同参)
                section("/dj/program/byradio asc=false") {
                    val body = client.callEApi(
                        "/dj/program/byradio",
                        mapOf("radioId" to id, "limit" to 30, "offset" to 0, "asc" to false),
                    )
                    val programs = body.array("programs").orEmpty()
                    val extra = listOf("total", "count", "more").joinToString(" ") { k ->
                        "$k=${(body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "-"}"
                    }
                    "code=${codeOf(body)} programs=${programs.size} $extra keys=[${body.keys.joinToString(",")}]"
                }

                // 4. 反向再拉一次 asc=true(排除排序参数差异)
                section("/dj/program/byradio asc=true") {
                    val body = client.callEApi(
                        "/dj/program/byradio",
                        mapOf("radioId" to id, "limit" to 30, "offset" to 0, "asc" to true),
                    )
                    val programs = body.array("programs").orEmpty()
                    "code=${codeOf(body)} programs=${programs.size} more=${(body["more"] as? kotlinx.serialization.json.JsonPrimitive)?.content}"
                }

                // 5. 对照组:一个健康电台(byradio 应有数据)
                section("control: toplist first radio byradio") {
                    val top = client.callEApi("/djradio/toplist", mapOf("limit" to 1, "offset" to 0, "type" to 1))
                    val cid = (top.array("toplist")?.firstOrNull()?.jsonObjectSafely()?.get("id"))
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
                    if (cid == null) "no control radio"
                    else {
                        val body = client.callEApi(
                            "/dj/program/byradio",
                            mapOf("radioId" to cid, "limit" to 5, "offset" to 0, "asc" to false),
                        )
                        "controlId=$cid code=${codeOf(body)} programs=${body.array("programs").orEmpty().size}"
                    }
                }
            }

            out.writeText(sb.toString())
            println(out.readText())
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafely(): kotlinx.serialization.json.JsonObject? =
        (this as? kotlinx.serialization.json.JsonObject)
}
