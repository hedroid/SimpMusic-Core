package com.maxrave.netease.crypto

import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object XeapiCipher {
    private fun cipher(
        mode: Int,
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    actual fun ecbEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, data)

    actual fun ecbDecrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = cipher(Cipher.DECRYPT_MODE, key, data)

    actual fun gcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(plain)
    }

    actual fun gzipDecompress(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }

    private val hostId: String by lazy {
        val raw = "SimpMusic_${System.getProperty("user.name") ?: "user"}_${System.getProperty("os.name") ?: "jvm"}"
        MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    actual fun deviceId(): String = hostId

    actual fun osVersion(): String = System.getProperty("os.version") ?: "12"

    actual fun deviceModel(): String = System.getProperty("os.name") ?: "JVM"
}
