package com.maxrave.netease.crypto

/**
 * 纯 Kotlin SHA-256 + HMAC-SHA256:xeapi 的密钥派生/签名在 commonMain 里做,
 * 不依赖 javax.crypto(与 NeteaseCrypto 手写 MD5/RSA 同一思路)。
 */
internal object Sha256 {
    private val K =
        intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )

    fun digest(message: ByteArray): ByteArray {
        val h =
            intArrayOf(
                0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
            )
        val padded = ((message.size + 9 + 63) / 64) * 64
        val buf = ByteArray(padded)
        message.copyInto(buf)
        buf[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until 8) buf[padded - 1 - i] = (bits ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until padded / 64) {
            val base = block * 64
            for (t in 0 until 16) {
                val o = base + t * 4
                w[t] =
                    ((buf[o].toInt() and 0xff) shl 24) or ((buf[o + 1].toInt() and 0xff) shl 16) or
                        ((buf[o + 2].toInt() and 0xff) shl 8) or (buf[o + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = (w[t - 15] ror 7) xor (w[t - 15] ror 18) xor (w[t - 15] ushr 3)
                val s1 = (w[t - 2] ror 17) xor (w[t - 2] ror 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = (e ror 6) xor (e ror 11) xor (e ror 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = (a ror 2) xor (a ror 13) xor (a ror 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e
                e = d + t1
                d = c; c = b; b = a
                a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    private infix fun Int.ror(n: Int): Int = (this ushr n) or (this shl (32 - n))
}

fun hmacSha256(
    key: ByteArray,
    message: ByteArray,
): ByteArray {
    val blockSize = 64
    var k = key
    if (k.size > blockSize) k = Sha256.digest(k)
    if (k.size < blockSize) k = k + ByteArray(blockSize - k.size)
    val outer = ByteArray(blockSize) { (k[it].toInt() xor 0x5c).toByte() }
    val inner = ByteArray(blockSize) { (k[it].toInt() xor 0x36).toByte() }
    return Sha256.digest(outer + Sha256.digest(inner + message))
}
