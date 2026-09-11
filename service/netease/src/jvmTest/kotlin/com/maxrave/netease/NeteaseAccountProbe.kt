package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 真实接口诊断(手动跑,不进 CI):用模拟器 DataStore 导出的 cookie 调 /w/nuser/account/get,
 * 复现"扫码 803 后页面无反应"时这一步的真实行为。
 */
class NeteaseAccountProbe {
    @Test
    fun probeAccountStatus() {
        val cookies =
            File("/tmp/netease_cookie.json").readText().let {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it)
            }
        println("PROBE cookies keys: ${cookies.keys}")
        val client = NeteaseClient()
        kotlinx.coroutines.runBlocking {
            client.seedCookies(cookies)
            val start = System.currentTimeMillis()
            val result = client.getAccountStatus()
            val elapsed = System.currentTimeMillis() - start
            File("/tmp/probe_out.txt").appendText("PROBE account/status in ${elapsed}ms -> $result\n")
            // 也直接探原始 body
            val raw =
                runCatching {
                    val body = client.callWeApi("/w/nuser/account/get", mapOf("noCheckToken" to true))
                    body.keys.joinToString(",")
                }
            File("/tmp/probe_out.txt").appendText("PROBE raw keys: $raw\n")
        }
    }
}
