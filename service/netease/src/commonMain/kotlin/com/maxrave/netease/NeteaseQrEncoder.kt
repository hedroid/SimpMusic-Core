/*
 * Minimal byte-mode QR encoder (ECC level M, versions 1-5) — no third-party dependency.
 *
 * Why hand-rolled: the QR libraries published to Maven Central for KMP (qrose, qrcode-kotlin)
 * both ship broken platform artifacts (root .module relocates to submodules that were never
 * uploaded), and the NetEase QR content is a short fixed-shape URL
 * ("https://music.163.com/login?codekey=<32 chars>" ≈ 56 bytes) that always fits v4-M
 * (62-byte capacity). Versions 1-5 cover everything up to 84 bytes; anything longer throws.
 *
 * Spec reference: ISO/IEC 18004; construction follows the well-known Nayuki walkthrough.
 */
package com.maxrave.netease

import kotlin.math.min

object NeteaseQrEncoder {
    /** ECC-M block tables, index = version-1: (blocks, dataCodewordsPerBlock[last], ecPerBlock) */
    private data class VersionSpec(
        val size: Int,
        val dataCodewords: Int,
        val ecPerBlock: Int,
        val blockCount: Int,
        val alignment: IntArray,
    )

    private val VERSIONS =
        listOf(
            VersionSpec(21, 16, 10, 1, IntArray(0)),
            VersionSpec(25, 28, 16, 1, intArrayOf(6, 18)),
            VersionSpec(29, 44, 26, 1, intArrayOf(6, 22)),
            VersionSpec(33, 64, 18, 2, intArrayOf(6, 26)),
            VersionSpec(37, 86, 24, 2, intArrayOf(6, 30)),
        )

    // GF(256) over 0x11D
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun gfMul(
        a: Int,
        b: Int,
    ): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    /** 生成 errorLevel=M、自动选 version 与 mask 的二维码模块矩阵;true=深色模块 */
    fun encode(text: String): Array<BooleanArray> {
        val bytes = text.map { (it.code and 0xFF).toByte() } // 内容是 ASCII URL
        require(bytes.size <= 84) { "QR content too long for v5-M: ${bytes.size} bytes" }
        val spec = VERSIONS.first { bytes.size + 2 <= it.dataCodewords } // 2 = 模式4bit + 计数8bit 上取整的保守估计
        val bits = buildBitStream(bytes, spec.dataCodewords)
        val (dataBlocks, ecBlocks) = interleave(bits, spec)
        val total = dataBlocks.sum() + ecBlocks.sum() // 展平后的码字流长度

        var bestMatrix: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val matrix = buildMatrix(spec, dataBlocks + ecBlocks, total, mask)
            val penalty = penalty(matrix)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMatrix = matrix
            }
        }
        return bestMatrix!!
    }

    private fun buildBitStream(
        bytes: List<Byte>,
        dataCodewords: Int,
    ): BitBuffer {
        val bb = BitBuffer()
        bb.append(0x4, 4) // byte mode
        bb.append(bytes.size, 8) // v1-9 用 8bit 计数
        bytes.forEach { bb.append(it.toInt() and 0xFF, 8) }
        // 终止符 + 字节对齐 + 0xEC/0x11 填充
        val capacityBits = dataCodewords * 8
        bb.append(0, min(4, capacityBits - bb.bitCount))
        bb.append(0, (8 - bb.bitCount % 8) % 8)
        val pad = intArrayOf(0xEC, 0x11)
        var i = 0
        while (bb.bitCount < capacityBits) {
            bb.append(pad[i % 2], 8)
            i++
        }
        return bb
    }

    private fun interleave(
        bb: BitBuffer,
        spec: VersionSpec,
    ): Pair<List<Int>, List<Int>> {
        val shortBlock = spec.dataCodewords / spec.blockCount
        val numLong = spec.dataCodewords % spec.blockCount
        val stream = bb.codewords()
        val split = ArrayList<List<Int>>()
        var offset = 0
        for (b in 0 until spec.blockCount) {
            val len = shortBlock + if (b >= spec.blockCount - numLong) 1 else 0
            split.add(stream.subList(offset, offset + len))
            offset += len
        }
        val ecBlocks = split.map { block -> reedSolomon(block, spec.ecPerBlock) }
        // 交错:先 data 后 ec
        val dataOut = ArrayList<Int>()
        for (i in 0 until shortBlock + 1) {
            split.forEach { block -> if (i < block.size) dataOut.add(block[i]) }
        }
        val ecOut = ArrayList<Int>()
        for (i in 0 until spec.ecPerBlock) {
            ecBlocks.forEach { ecOut.add(it[i]) }
        }
        return dataOut to ecOut
    }

    private fun reedSolomon(
        data: List<Int>,
        ecLen: Int,
    ): List<Int> {
        var gen = listOf(1)
        for (i in 0 until ecLen) gen = polyMul(gen, listOf(1, exp[i]))
        val res = MutableList(data.size + ecLen) { 0 }
        data.forEach { b ->
            val factor = b xor res.removeAt(0)
            res.add(0)
            if (factor != 0) {
                for (j in gen.indices) {
                    res[j] = res[j] xor gfMul(gen[j], factor)
                }
            }
        }
        return res.subList(0, ecLen)
    }

    private fun polyMul(
        a: List<Int>,
        b: List<Int>,
    ): List<Int> {
        val out = MutableList(a.size + b.size - 1) { 0 }
        for (i in a.indices) {
            for (j in b.indices) {
                out[i + j] = out[i + j] xor gfMul(a[i], b[j])
            }
        }
        return out
    }

    private fun buildMatrix(
        spec: VersionSpec,
        codewords: List<Int>,
        totalCodewords: Int,
        mask: Int,
    ): Array<BooleanArray> {
        val n = spec.size
        val m = Array(n) { BooleanArray(n) }
        val isFunction = Array(n) { BooleanArray(n) }

        fun setFunction(
            y: Int,
            x: Int,
            dark: Boolean,
        ) {
            m[y][x] = dark
            isFunction[y][x] = true
        }

        // 三个 finder + 分隔带
        fun finder(
            cy: Int,
            cx: Int,
        ) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val y = cy + dy
                    val x = cx + dx
                    if (y in 0 until n && x in 0 until n) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunction(y, x, dist != 2 && dist != 4)
                    }
                }
            }
        }
        finder(3, 3)
        finder(3, n - 4)
        finder(n - 4, 3)

        // timing
        for (i in 8 until n - 8) {
            if (!isFunction[6][i]) setFunction(6, i, i % 2 == 0)
            if (!isFunction[i][6]) setFunction(i, 6, i % 2 == 0)
        }

        // alignment(v2+)
        val al = spec.alignment
        if (al.isNotEmpty()) {
            for (r in al) {
                for (c in al) {
                    if ((r < 8 && c < 8) || (r < 8 && c > n - 9) || (r > n - 9 && c < 8)) continue
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                            setFunction(r + dy, c + dx, dist != 1)
                        }
                    }
                }
            }
        }

        // 预留 format 区域(内容稍后回填)
        for (i in 0 until 9) {
            if (!isFunction[8][i]) setFunction(8, i, false)
            if (!isFunction[i][8]) setFunction(i, 8, false)
        }
        for (i in n - 8 until n) {
            setFunction(8, i, false)
            setFunction(i, 8, false)
        }
        setFunction(n - 8, 8, true) // dark module

        // 数据摆放:右起两列一对,跳过第 6 列(timing),蛇形上下,mask 只作用于数据位
        val bb = BitBuffer()
        codewords.forEach { bb.append(it, 8) }
        var bitIdx = 0
        var upward = true
        var colPair = n - 1
        while (colPair > 0) {
            if (colPair == 6) colPair-- // 跳过 timing 列
            for (i in 0 until n) {
                val row = if (upward) n - 1 - i else i
                for (c in intArrayOf(colPair, colPair - 1)) {
                    if (!isFunction[row][c] && bitIdx < bb.bitCount) {
                        m[row][c] = bb.bitAt(bitIdx) xor maskBit(mask, row, c)
                        bitIdx++
                    }
                }
            }
            upward = !upward
            colPair -= 2
        }

        // format 信息:ECC M(2bit=00) + mask(3bit),BCH(15,5) 再异或 0x5412
        val data = mask // M=0 已隐含在高位
        var v = data shl 10
        while (v.countOneBits() >= 11) {
            v = v xor (0x537 shl (v.countOneBits() - 11))
        }
        val format = ((data shl 10) or v) xor 0x5412
        fun fb(i: Int): Boolean = (format ushr (14 - i)) and 1 == 1
        // 副本一(绕左上 finder):bit0..5 → (8,0..5);bit6 → (8,7);bit7 → (8,8);bit8 → (7,8);bit9..14 → (14-i, 8)
        for (i in 0 until 6) setFunction(8, i, fb(i))
        setFunction(8, 7, fb(6))
        setFunction(8, 8, fb(7))
        setFunction(7, 8, fb(8))
        for (i in 9 until 15) setFunction(14 - i, 8, fb(i))
        // 副本二:bit0..7 → (8, n-1-i);bit8..14 → (n-15+i, 8)
        for (i in 0 until 8) setFunction(8, n - 1 - i, fb(i))
        for (i in 8 until 15) setFunction(n - 15 + i, 8, fb(i))
        return m
    }

    private fun maskBit(
        mask: Int,
        y: Int,
        x: Int,
    ): Boolean =
        when (mask) {
            0 -> (y + x) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (y + x) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> (y * x) % 2 + (y * x) % 3 == 0
            6 -> ((y * x) % 2 + (y * x) % 3) % 2 == 0
            else -> ((y + x) % 2 + (y * x) % 3) % 2 == 0
        }

    /** 简化罚分:长串(N1) + 2x2 同色块(N2),够用即可 */
    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0
        for (y in 0 until n) {
            var run = 1
            for (x in 1 until n) {
                if (m[y][x] == m[y][x - 1]) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score++
                } else {
                    run = 1
                }
            }
        }
        for (x in 0 until n) {
            var run = 1
            for (y in 1 until n) {
                if (m[y][x] == m[y - 1][x]) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score++
                } else {
                    run = 1
                }
            }
        }
        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) score += 3
            }
        }
        return score
    }
}

/** MSB-first 位流 */
private class BitBuffer {
    private val bits = ArrayList<Boolean>()

    val bitCount: Int get() = bits.size

    fun append(
        value: Int,
        len: Int,
    ) {
        for (i in len - 1 downTo 0) {
            bits.add((value ushr i) and 1 == 1)
        }
    }

    fun bitAt(i: Int): Boolean = bits[i]

    fun codewords(): List<Int> {
        require(bits.size % 8 == 0)
        return List(bits.size / 8) { i ->
            var b = 0
            for (j in 0 until 8) {
                b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            }
            b
        }
    }
}
