package com.maxrave.netease

import java.io.File
import kotlin.test.Test

class QrV6Probe {
    @Test
    fun dumpChainUrl() {
        val key = "abcdefghijklmnopqrstuvwxyz1234567890abcd" // 40 位最坏情况
        val chainId = "a1b2c3d4e5f6"
        val url = "https://music.163.com/st/platform/scanlogin?codekey=$key&chainId=$chainId"
        File("/tmp/qr_probe.txt").writeText("len=${url.length}\n")
        runCatching {
            val m = NeteaseQrEncoder.encode(url)
            File("/tmp/qr_dump.txt").writeText("QRDUMP ${m.size}\n" + m.joinToString("\n") { r -> r.joinToString("") { if (it) "1" else "0" } })
        }.onFailure { File("/tmp/qr_probe.txt").appendText("ENCODE FAILED: $it\n") }
    }
}
