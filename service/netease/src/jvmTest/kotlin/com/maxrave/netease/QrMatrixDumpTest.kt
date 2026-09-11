package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** dump 全部 8 个 mask 的矩阵到 /tmp/qr_dump_masks.txt,供与 python 参考库比对 */
class QrMatrixDumpTest {
    @Test
    fun dumpAllMasks() {
        val content = "https://music.163.com/login?codekey=abcdefghijklmnopqrstuvwxyz123456"
        val sb = StringBuilder()
        for (mask in 0 until 8) {
            val matrix = NeteaseQrEncoder.encode(content, forceMask = mask)
            sb.appendLine("MASK $mask ${matrix.size}")
            matrix.forEach { row -> sb.appendLine(row.joinToString("") { if (it) "1" else "0" }) }
        }
        File("/tmp/qr_dump_masks.txt").writeText(sb.toString())
    }
}
