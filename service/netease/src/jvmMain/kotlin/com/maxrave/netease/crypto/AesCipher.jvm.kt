package com.maxrave.netease.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object AesCipher {
    private fun cipherAes(
        text: String,
        key: String,
        iv: String?,
    ): ByteArray {
        val secretKey = SecretKeySpec(key.encodeToByteArray(), "AES")
        val cipher =
            if (iv != null) {
                Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv.encodeToByteArray()))
                }
            } else {
                Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                    init(Cipher.ENCRYPT_MODE, secretKey)
                }
            }
        return cipher.doFinal(text.encodeToByteArray())
    }

    actual fun encryptCbcBase64(
        text: String,
        key: String,
        iv: String,
    ): String = java.util.Base64.getEncoder().encodeToString(cipherAes(text, key, iv))

    actual fun encryptEcbHex(
        text: String,
        key: String,
    ): String = cipherAes(text, key, null).toHex()

    actual fun encryptEcbHexUppercase(
        text: String,
        key: String,
    ): String = cipherAes(text, key, null).toHex().uppercase()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
