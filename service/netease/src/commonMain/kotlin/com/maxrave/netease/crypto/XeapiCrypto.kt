package com.maxrave.netease.crypto

import io.ktor.http.formUrlEncode
import io.ktor.util.decodeBase64Bytes
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * xeapi 请求组装与响应解密(逆向自官方 App 9.5.61 行为,Melodia 交叉验证)。
 *
 * 请求体三段(B/S/R,均 base64):
 *  - B = AES-128-ECB(dynamicKey, midTransform(AES-256-ECB(staticKey, plaintext)))
 *  - S = 临时公钥(32B) ‖ GCM IV(12B) ‖ AES-128-GCM(deriveKey, iv, "dynamicKeyB64|os|sk")
 *        deriveKey = HKDF 风格:零盐 HMAC-Extract(sharedSecret) → HMAC-Expand(ephemeralPub‖0x01) 前 16 字节
 *  - R = AES-256-ECB(staticKey, "version|")
 * plaintext = {"body":"<base64(form-urlencoded data)>","queryString":"e_r=true"}
 *
 * 响应体:AES-128-ECB(eapiKey) 加密的二进制,解密后可能 gzip,内容为明文 JSON。
 * 动态密钥每次请求随机生成,经 X25519 密钥协商用服务端注册公钥封进 S 段。
 */
object XeapiCrypto {
    // AES-256-ECB 静态密钥:请求体内层 + R 段 + 设备注册响应的加解密
    internal val STATIC_KEY: ByteArray = hexToBytes("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84")

    // HMAC 签名密钥(设备注册取公钥接口),直接用字面量 UTF-8 字节,不能 base64 解码
    private const val SIGN_KEY =
        "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g=="

    // 与 NeteaseCrypto 的 EAPI_KEY 同值:xeapi 响应体解密用
    private const val EAPI_KEY = "e82ckenh8dichen8"

    const val APP_VERSION = "9.5.61"

    data class PublicKeyState(
        val version: String,
        val publicKey: String,
        val sk: String?,
    )

    data class EncryptedForm(
        val b: String,
        val s: String,
        val r: String,
    )

    /** 设备注册取公钥接口的签名:HMAC-SHA256(SIGN_KEY, timestamp+nonce) 的 base64 */
    fun sign(
        timestamp: String,
        nonce: String,
    ): String = hmacSha256(SIGN_KEY.encodeToByteArray(), (timestamp + nonce).encodeToByteArray()).encodeBase64()

    // HKDF 风格派生:零盐 HMAC-Extract → (ephemeralPub‖0x01) HMAC-Expand 取前 16 字节
    internal fun deriveAesKey(
        sharedSecret: ByteArray,
        ephemeralPublicRaw: ByteArray,
    ): ByteArray {
        val prk = hmacSha256(ByteArray(32), sharedSecret)
        return hmacSha256(prk, ephemeralPublicRaw + byteArrayOf(1)).copyOfRange(0, 16)
    }

    /** 自定义混淆:随机掩码 XOR 密文 → base64 → 按掩码首字节循环移位,前置掩码 */
    private fun midTransform(
        ciphertext: ByteArray,
        mask: ByteArray,
    ): ByteArray {
        val xored = ByteArray(ciphertext.size) { i -> (ciphertext[i].toInt() xor mask[i and 0x0f].toInt()).toByte() }
        val b64 = xored.encodeBase64().encodeToByteArray()
        val rotate = if (b64.isNotEmpty()) (mask[0].toInt() and 0x0f) % b64.size else 0
        return mask + b64.copyOfRange(rotate, b64.size) + b64.copyOfRange(0, rotate)
    }

    /** 组装 B/S/R 三段。临时密钥对/动态密钥/掩码/IV 每次真随机 */
    fun assembleRequest(
        uri: String,
        data: LinkedHashMap<String, String>,
        state: PublicKeyState,
        deviceId: String,
        osVersion: String,
    ): EncryptedForm {
        val bodyBase64 = data.toList().formUrlEncode().encodeBase64()
        val plaintext = """{"body":"$bodyBase64","queryString":"e_r=true"}""".encodeToByteArray()
        val dynamicKey = kotlin.random.Random.Default.nextBytes(16)
        val mask = kotlin.random.Random.Default.nextBytes(16)
        val gcmIv = kotlin.random.Random.Default.nextBytes(12)
        val ephemeralSecret = kotlin.random.Random.Default.nextBytes(32)

        val inner = XeapiCipher.ecbEncrypt(STATIC_KEY, plaintext)
        val transformed = midTransform(inner, mask)
        val b = XeapiCipher.ecbEncrypt(dynamicKey, transformed)

        val ephemeralPub = X25519.publicKey(ephemeralSecret)
        val peerPub = state.publicKey.decodeBase64Bytes()
        val sharedSecret = X25519.sharedSecret(ephemeralSecret, peerPub)
        val aesKey = deriveAesKey(sharedSecret, ephemeralPub)
        val gcmPlain = "${dynamicKey.encodeBase64()}|android|${state.sk ?: ""}".encodeToByteArray()
        val s = ephemeralPub + gcmIv + XeapiCipher.gcmEncrypt(aesKey, gcmIv, gcmPlain)

        val r = XeapiCipher.ecbEncrypt(STATIC_KEY, "${state.version}|".encodeToByteArray())

        return EncryptedForm(b = b.encodeBase64(), s = s.encodeBase64(), r = r.encodeBase64())
    }

    /** 解密设备注册接口返回的 encryptedData(AES-256-ECB 静态密钥) */
    fun decryptPublicKeyResponse(encryptedDataBase64: String): PublicKeyState {
        val plain = XeapiCipher.ecbDecrypt(STATIC_KEY, encryptedDataBase64.decodeBase64Bytes())
        val obj = Json.parseToJsonElement(plain.decodeToString()).jsonObject
        return PublicKeyState(
            version = obj["version"]?.jsonPrimitive?.content ?: "",
            publicKey = obj["publicKey"]?.jsonPrimitive?.content ?: "",
            sk = obj["sk"]?.jsonPrimitive?.content,
        )
    }

    /** 解密业务接口响应体:AES-128-ECB(eapiKey) → 按魔数判断是否 gzip → 明文 JSON */
    fun decryptResponseBody(body: ByteArray): String {
        val decrypted = XeapiCipher.ecbDecrypt(EAPI_KEY.encodeToByteArray(), body)
        val isGzip = decrypted.size >= 2 && decrypted[0] == 0x1f.toByte() && decrypted[1] == 0x8b.toByte()
        val plain = if (isGzip) XeapiCipher.gzipDecompress(decrypted) else decrypted
        return plain.decodeToString()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
