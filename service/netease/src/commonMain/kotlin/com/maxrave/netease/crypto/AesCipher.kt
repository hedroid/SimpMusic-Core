package com.maxrave.netease.crypto

/**
 * The only platform-crypto primitive the NetEase protocol needs. All call sites go through
 * the friendly wrappers below; actuals only implement raw AES with PKCS#7 (= PKCS#5 for AES)
 * padding. Keys and IVs are exactly 16 ASCII chars, so they map straight onto bytes.
 */
expect object AesCipher {
    /** AES-CBC encrypt, PKCS#7 padded, Base64 output. */
    fun encryptCbcBase64(
        text: String,
        key: String,
        iv: String,
    ): String

    /** AES-ECB encrypt, PKCS#7 padded, lowercase-hex output. */
    fun encryptEcbHex(
        text: String,
        key: String,
    ): String

    /** Same as [encryptEcbHex] but uppercase — the eapi envelope is hex-uppercase. */
    fun encryptEcbHexUppercase(
        text: String,
        key: String,
    ): String
}
