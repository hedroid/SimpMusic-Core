/*
 * NetEase Music private-API crypto, ported from NeriPlayer (GPL-3.0)
 * https://github.com/cwuom/NeriPlayer — core/api/netease/NeteaseCrypto.kt
 * Adapted to Kotlin Multiplatform: java.security/javax.crypto usages were split
 * into pure-Kotlin parts (RSA/MD5 stay here, AES moved to the expect/actual
 * [com.maxrave.netease.crypto.AesCipher]).
 */
package com.maxrave.netease

import com.maxrave.netease.crypto.AesCipher
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 加解密工具 */
object NeteaseCrypto {
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val LINUX_KEY = "rFgB&h#%2?^eDg:Q"
    private const val EAPI_KEY = "e82ckenh8dichen8"

    // Exact strings NeriPlayer verified against production — the separators are the official
    // client's markers, do not "clean them up".
    private const val EAPI_FORMAT = "%s-36cd479b6b5-%s-36cd479b6b5-%s"
    private const val EAPI_SALT = "nobody%suse%smd5forencrypt"

    // The 1024-bit RSA modulus of NetEase's shared public key (e = 65537), extracted from the
    // PEM NeriPlayer ships. Byte-for-byte identical to the official clients'.
    private const val RSA_MODULUS_HEX =
        "E0B509F6259DF8642DBC35662901477DF22677EC152B5FF68ACE615BB7B725152" +
            "B3AB17A876AEA8A5AA76D2E417629EC4EE341F56135FCCF695280104E0312ECBD" +
            "A92557C93870114AF6C9D05C4F7F0C3685B7A46BEE255932575CCE10B424D813C" +
            "FE4875D3E82047B97DDEF52741D546B8E289DC6935B3ECE0462DB0A22B8E7"

    /** kotlin.random is not CSPRNG; the weapi key is transport obfuscation, not a user secret. */
    fun randomKey(): String = buildString {
        repeat(16) { append(BASE62[Random.nextInt(BASE62.length)]) }
    }

    // ---------------------------------------------------------------- MD5 (RFC 1321, pure Kotlin)

    internal fun md5Hex(data: String): String {
        val bytes = data.encodeToByteArray()
        val padded = ByteArray(((bytes.size + 8) / 64 + 1) * 64)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        val bitLength = (bytes.size * 8).toLong()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()
        }
        var a = 0x67452301.toInt()
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476.toInt()
        for (chunkStart in padded.indices step 64) {
            val m = IntArray(16) { i ->
                (padded[chunkStart + i * 4].toInt() and 0xff) or
                    ((padded[chunkStart + i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((padded[chunkStart + i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((padded[chunkStart + i * 4 + 3].toInt() and 0xff) shl 24)
            }
            val aa = a
            val bb = b
            val cc = c
            val dd = d
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    i < 32 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }
                    i < 48 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val tmp = d
                d = c
                c = b
                val sum = a + f + kTable[i] + m[g]
                b = b + ((sum shl shiftTable[i]) or (sum ushr (32 - shiftTable[i])))
                a = tmp
            }
            a += aa
            b += bb
            c += cc
            d += dd
        }
        return buildString {
            listOf(a, b, c, d).forEach { v ->
                for (i in 0 until 4) {
                    append(((v ushr (8 * i)) and 0xff).toString(16).padStart(2, '0'))
                }
            }
        }
    }

    // K[i] = floor(2^32 * |sin(i+1)|) — the standard table, computed rather than hardcoded.
    private val kTable = IntArray(64) { i ->
        val s = sin((i + 1).toDouble())
        ((if (s < 0) -s else s) * 4294967296.0).toLong().toInt()
    }

    private val shiftTable = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    // ---------------------------------------------------------------- RSA (weapi step 2)

    /**
     * Textbook no-padding RSA of the reversed random key with NetEase's shared public key —
     * pure math, no platform crypto involved.
     */
    internal fun rsaEncryptHex(text: String): String {
        val modulus = BigInteger.parseString(RSA_MODULUS_HEX, 16)
        val message = BigInteger.parseString(text.encodeToByteArray().toHexString(), 16)
        return modPow(message, BigInteger(65537), modulus).toString(16).padStart(256, '0')
    }

    /** ionspin bignum 没有内置 modPow,平方乘自实现;e=65537 只有 17 次乘法,开销可忽略 */
    private fun modPow(
        message: BigInteger,
        exponent: BigInteger,
        modulus: BigInteger,
    ): BigInteger {
        var result = BigInteger.ONE
        var base = message.mod(modulus)
        var exp = exponent
        val two = BigInteger(2)
        while (!exp.isZero()) {
            if (!exp.mod(two).isZero()) result = (result * base).mod(modulus)
            base = (base * base).mod(modulus)
            exp = exp.div(two)
        }
        return result
    }

    private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // ---------------------------------------------------------------- envelope builders

    /** weapi: params + encSecKey form fields. [payload] order is preserved as sent. */
    fun weApiEncrypt(payload: Map<String, Any?>): Map<String, String> {
        val json = payload.toJsonElement().toString()
        val secretKey = randomKey()
        val enc1 = AesCipher.encryptCbcBase64(json, PRESET_KEY, IV)
        val params = AesCipher.encryptCbcBase64(enc1, secretKey, IV)
        val encSecKey = rsaEncryptHex(secretKey.reversed())
        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    /** eapi: hex-uppercase params form field (AES-ECB). */
    fun eApiEncrypt(
        url: String,
        payload: Map<String, Any?>,
    ): Map<String, String> {
        val apiPath = url.replace("/eapi", "/api")
        val data = payload.toJsonElement().toString()
        val message =
            "$apiPath-36cd479b6b5-$data-36cd479b6b5-" +
                md5Hex("nobody${apiPath}use${data}md5forencrypt")
        return mapOf("params" to AesCipher.encryptEcbHexUppercase(message, EAPI_KEY))
    }

    /** linux/api: eparams form field. */
    fun linuxApiEncrypt(payload: Map<String, Any?>): Map<String, String> =
        mapOf("eparams" to AesCipher.encryptEcbHex(payload.toJsonElement().toString(), LINUX_KEY))

    /** 游客 anonymous token: XOR + MD5 + base64url, mirrors the official web client. */
    fun anonymous(deviceId: String): String {
        val xorKey = "3go8&$8*3*3h0k(2)2"
        val bytes = deviceId.encodeToByteArray()
        val xored = ByteArray(bytes.size) { (bytes[it].toInt() xor xorKey[it % xorKey.length].code).toByte() }
        // Same deliberate lossy UTF-8 roundtrip the JS client performs before hashing.
        val md5 = md5Hex(xored.decodeToString())
        val md5Bytes = ByteArray(md5.length / 2) { i -> md5.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val content = "$deviceId ${kotlin.io.encoding.Base64.UrlSafe.encode(md5Bytes)}"
        return kotlin.io.encoding.Base64.UrlSafe.encode(content.encodeToByteArray())
    }

    fun generateDeviceId(): String {
        val hex = buildString {
            repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun Map<String, Any?>.toJsonElement(): JsonElement =
        buildJsonObject {
            forEach { (k, v) ->
                put(
                    k,
                    when (v) {
                        null -> JsonNull
                        is String -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        is JsonElement -> v
                        else -> JsonPrimitive(v.toString())
                    },
                )
            }
        }
}
