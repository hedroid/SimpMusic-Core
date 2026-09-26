package com.maxrave.netease

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.maxrave.netease.crypto.X25519
import kotlin.test.Test
import kotlin.test.assertEquals

/** 隔离诊断:ionspin bignum 语义 + X25519 向量(串行,全量打印) */
class XeapiCryptoTest {
    @Test
    fun bignumSemantics() {
        val x = BigInteger.parseString("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c", 16)
        println("SEM decode-u=" + x.toString(16))
        val a = BigInteger.fromInt(3).multiply(BigInteger.fromInt(256)).plus(BigInteger.fromInt(4))
        println("SEM 3*256+4=" + a.toString(16))
        val neg = BigInteger.fromInt(-5).mod(BigInteger.fromInt(3))
        println("SEM neg mod=" + neg.toString())
        val big1 = BigInteger.parseString("c3da55379de9c6908e94ea4df28d084f", 16)
        val big2 = BigInteger.parseString("3cda55379de9c6908e94ea4df28d084f", 16)
        println("SEM aa-bb=" + (big1 - big2).toString(16))
        println("SEM mod-of-neg-big=" + (big2 - big1).mod(BigInteger.parseString("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)).toString(16))
    }

    @Test
    fun x25519Vector1() {
        val out =
            X25519.sharedSecret(
                scalarFromHex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                scalarFromHex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            )
        println("X25519 OUT: " + out.joinToString("") { "%02x".format(it) })
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", out.joinToString("") { "%02x".format(it) })
    }

    private fun scalarFromHex(hex: String): ByteArray = ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
