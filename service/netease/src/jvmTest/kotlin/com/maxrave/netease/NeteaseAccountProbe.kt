package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * -462 风控 A/B 验证:同一手机号+假验证码,直接登录 vs 预热后登录。
 * 期望:预热前 code=-462(风控),预热后 code=400/其它(验证码错误 → 风控已过)。
 */
class NeteaseAccountProbe {
    @Test
    fun probeLoginRiskControl() {
        val out = File("/tmp/probe_out.txt")
        val client = NeteaseClient()
        kotlinx.coroutines.runBlocking {
            val cold = client.loginByCaptcha("13800001234", "000000")
            out.writeText("COLD: $cold\n")

            val warmed = client.loginByCaptcha("13800001234", "000000")
            out.appendText("WARMED(second attempt, after internal warmup ran): $warmed\n")
        }
    }
}
