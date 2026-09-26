package com.maxrave.netease.crypto

/**
 * xeapi 需要的平台原语。X25519 与 HMAC-SHA256 已由 commonMain 纯 Kotlin 实现覆盖
 * (见 [X25519]/hmacSha256),这里只留真正依赖平台 API 的四件:
 * AES-ECB(带 PKCS7)、AES-GCM、gzip 解压、设备标识。
 */
expect object XeapiCipher {
    /** AES-ECB + PKCS7 加密,key 16/32 字节 */
    fun ecbEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    /** AES-ECB + PKCS7 解密 */
    fun ecbDecrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    /** AES-GCM(12 字节 IV,128 位认证 tag,无 AAD),密文尾部自带 tag */
    fun gcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plain: ByteArray,
    ): ByteArray

    fun gzipDecompress(data: ByteArray): ByteArray

    /** 稳定设备 id:同设备每次一致即可,用于设备注册与 x-deviceid 头 */
    fun deviceId(): String

    fun osVersion(): String

    fun deviceModel(): String
}
