@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.maxrave.netease.crypto

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess

actual object AesCipher {
    // CCCrypt one-shot AES with PKCS#7 via option flag. Keys are always 16 bytes (AES-128).
    // size_t typedefs to ULong on 64-bit Apple targets, so ULongVar stands in for size_t*.
    private fun encrypt(
        data: ByteArray,
        key: String,
        iv: String?,
    ): ByteArray {
        val keyBytes = key.encodeToByteArray()
        val ivBytes = iv?.encodeToByteArray()
        // ciphertext = data + one padding block at most (PKCS#7 adds 1..16)
        val out = ByteArray(data.size + 16)
        memScoped {
            val movedPtr = alloc<ULongVar>()
            data.usePinned { pinnedData ->
                keyBytes.usePinned { pinnedKey ->
                    out.usePinned { pinnedOut ->
                        val status =
                            if (ivBytes != null) {
                                ivBytes.usePinned { pinnedIv ->
                                    CCCrypt(
                                        kCCEncrypt,
                                        kCCAlgorithmAES,
                                        kCCOptionPKCS7Padding,
                                        pinnedKey.addressOf(0),
                                        keyBytes.size.convert(),
                                        pinnedIv.addressOf(0),
                                        pinnedData.addressOf(0),
                                        data.size.convert(),
                                        pinnedOut.addressOf(0),
                                        out.size.convert(),
                                        movedPtr.ptr,
                                    )
                                }
                            } else {
                                CCCrypt(
                                    kCCEncrypt,
                                    kCCAlgorithmAES,
                                    kCCOptionPKCS7Padding or kCCOptionECBMode,
                                    pinnedKey.addressOf(0),
                                    keyBytes.size.convert(),
                                    null,
                                    pinnedData.addressOf(0),
                                    data.size.convert(),
                                    pinnedOut.addressOf(0),
                                    out.size.convert(),
                                    movedPtr.ptr,
                                )
                            }
                        check(status == kCCSuccess) { "CCCrypt failed: $status" }
                    }
                }
            }
            return out.copyOf(movedPtr.value.toInt())
        }
    }

    actual fun encryptCbcBase64(
        text: String,
        key: String,
        iv: String,
    ): String = io.ktor.util.encodeBase64(encrypt(text.encodeToByteArray(), key, iv))

    actual fun encryptEcbHex(
        text: String,
        key: String,
    ): String = encrypt(text.encodeToByteArray(), key, null).toHex()

    actual fun encryptEcbHexUppercase(
        text: String,
        key: String,
    ): String = encrypt(text.encodeToByteArray(), key, null).toHex().uppercase()

    private fun ByteArray.toHex(): String = joinToString("") { ((it.toInt() and 0xff).toString(16)).padStart(2, '0') }
}
