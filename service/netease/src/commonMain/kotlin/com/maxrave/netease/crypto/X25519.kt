package com.maxrave.netease.crypto

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * 纯 Kotlin X25519(RFC 7748):xeapi 的临时密钥协商。用 ionspin bignum 做 255 位模运算,
 * 平台无关(不依赖 JCA 的 XDH——Android 12 以下/老 JVM 没有该 provider);
 * 单次标量乘 ~10ms 量级,发评论这种用户触发场景无感。实现以 RFC 7748 §5.2 向量做单测校验。
 */
internal object X25519 {
    // p = 2^255 - 19
    private val P = BigInteger.parseString("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    private val TWO = BigInteger.fromInt(2)
    private val A24 = BigInteger.fromInt(121665)
    private val B256 = BigInteger.fromInt(256)
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    fun publicKey(secret: ByteArray): ByteArray = scalarMult(secret, BASE_POINT)

    fun sharedSecret(secret: ByteArray, peerPublicKey: ByteArray): ByteArray = scalarMult(secret, peerPublicKey)

    private fun clamp(k: ByteArray): ByteArray {
        val r = k.copyOf()
        r[0] = (r[0].toInt() and 248).toByte()
        r[31] = (r[31].toInt() and 127).toByte()
        r[31] = (r[31].toInt() or 64).toByte()
        return r
    }

    private fun decode(bytes: ByteArray): BigInteger {
        var x = BigInteger.ZERO
        for (i in 31 downTo 0) {
            x = x * 256 + (bytes[i].toInt() and 0xff)
        }
        return x
    }

    private fun encode(v0: BigInteger): ByteArray {
        val out = ByteArray(32)
        var v = v0
        for (i in 0 until 32) {
            out[i] = v.mod(B256).intValue().toByte()
            v = v / B256
        }
        return out
    }

    private fun modPow(base: BigInteger, exp: BigInteger): BigInteger {
        var result = BigInteger.ONE
        var b = base.mod(P)
        var e = exp
        while (e.signum() > 0) {
            val qr = e.divrem(TWO)
            if (qr.remainder.signum() != 0) result = (result * b).mod(P)
            b = (b * b).mod(P)
            e = qr.quotient
        }
        return result
    }

    private fun scalarMult(scalarRaw: ByteArray, uRaw: ByteArray): ByteArray {
        val k = clamp(scalarRaw)
        val uMasked = uRaw.copyOf().also { if (it.size == 32) it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = decode(uMasked)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val kt = ((k[t / 8].toInt() shr (t and 7)) and 1) == 1
            if (swap != kt) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt
            val a = (x2 + z2).mod(P)
            val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P)
            val cb = (c * b).mod(P)
            val x3n = (da + cb).mod(P)
            x3 = (x3n * x3n).mod(P)
            val z3n = (da - cb).mod(P)
            z3 = (x1 * z3n.mod(P) * z3n.mod(P)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + (A24 * e).mod(P)).mod(P)).mod(P)
        }
        if (swap) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        // z2^(p-2) mod p = 乘法逆元(费马小定理)
        val inv = modPow(z2, P.subtract(BigInteger.fromInt(2)))
        return encode((x2 * inv).mod(P))
    }
}
