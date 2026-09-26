package com.maxrave.netease.crypto

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess

actual object XeapiCipher {
    // CommonCrypto 与 Android/JVM 的 javax.crypto 逻辑一致(ECB+PKCS7 一发式)
    private fun crypt(
        mode: Int,
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArray(data.size + 16)
        memScoped {
            val moved = alloc<ULongVar>()
            data.usePinned { pd ->
                key.usePinned { pk ->
                    out.usePinned { po ->
                        val status =
                            CCCrypt(
                                mode,
                                kCCAlgorithmAES,
                                kCCOptionPKCS7Padding or kCCOptionECBMode,
                                pk.addressOf(0),
                                key.size.convert(),
                                null,
                                pd.addressOf(0),
                                data.size.convert(),
                                po.addressOf(0),
                                out.size.convert(),
                                moved.ptr,
                            )
                        check(status == kCCSuccess) { "CCCrypt failed: $status" }
                    }
                }
            }
            return out.copyOf(moved.value.toInt())
        }
    }

    actual fun ecbEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = crypt(kCCEncrypt, key, data)

    actual fun ecbDecrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = crypt(kCCDecrypt, key, data)

    actual fun gcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plain: ByteArray,
    ): ByteArray = throw NotImplementedError("xeapi GCM 暂未接 iOS CryptoKit;发评论功能 iOS 侧不可用")

    actual fun gzipDecompress(data: ByteArray): ByteArray = throw NotImplementedError("xeapi gzip 暂未接 iOS zlib")

    actual fun deviceId(): String = "simpmusic-ios-fixed-device"

    actual fun osVersion(): String = "18"

    actual fun deviceModel(): String = "iOS"
}
