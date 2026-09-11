/*
 * 扫码登录专用会话 —— 照搬 NeriPlayer NeteaseQrLoginClient 的架构(GPL-3.0):
 * https://github.com/cwuom/NeriPlayer — core/api/netease/NeteaseQrLoginClient.kt
 *
 * 与主 [NeteaseClient] 的关键差异(即"扫码成功但无反应"的教训):
 *  1. 独立 HttpClient + 纯内存 cookie —— 整个扫码流程零持久化、零共享状态,
 *     消除"每个响应都回写 DataStore"的挂起面;
 *  2. 803 后按 NeriPlayer 的 verifyConfirmedLogin 三段式:
 *     直接验账号(/w/nuser/account/get,csrf_token 走 query) → 失败则把
 *     x-refresh-token 响应头当 MUSIC_U 兜底再验 → 仍失败保留凭据;
 *  3. 最终一次性交出完整 cookie,由仓库层做唯一一次持久化。
 */
package com.maxrave.netease

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import com.maxrave.netease.model.NeteaseFingerprint
import com.maxrave.netease.model.NeteaseQrSession
import com.maxrave.netease.model.NeteaseQrStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

class NeteaseQrLoginSession {
    /** NeriPlayer 易盾指纹:unikey/check 携带 ydDeviceToken + x-login-chain-id 以通过 -462 风控 */
    private var fingerprint: NeteaseFingerprint? = null
    private var chainId: String = ""

    fun setFingerprint(fp: NeteaseFingerprint?) {
        fingerprint = fp
    }

    private val http =
        HttpClient(getEngine()) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = LinkedHashMap<String, String>()
    private val nmtid = buildString { repeat(24) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) } }

    fun reset() = cookies.clear()

    fun currentCookies(): Map<String, String> = LinkedHashMap(cookies)

    suspend fun createSession(): Result<NeteaseQrSession> =
        runCatching {
            val fp = fingerprint
            // WebView 指纹 cookie 先入会话(真实浏览器环境的 NMTID/__csrf/sDeviceId)
            fp?.cookies?.forEach { (k, v) -> if (v.isNotBlank()) cookies[k] = v }
            // chainId 总长压在 ~12 字符:chainId 绑定版扫码 URL 已 93 字节,v6-M 上限 106
            chainId =
                fp?.sDeviceId?.take(12)?.ifBlank { null }
                    ?: kotlin.random.Random.nextLong(1_000_000_000_000L, 9_000_000_000_000L).toString()
            val body = weApiPost("/login/qrcode/unikey", mapOf("type" to 1, "noCheckToken" to true)).text.toJson()
            val code = body.nInt("code") ?: -1
            val key = body.nStr("unikey").orEmpty()
            check(code == 200 && key.isNotEmpty()) { "QR unikey failed, code=$code" }
            // NeriPlayer buildScanLoginUrl:chainId 绑定进扫码链接
            NeteaseQrSession(
                key = key,
                qrContent = "https://music.163.com/st/platform/scanlogin?codekey=$key&chainId=$chainId",
            )
        }

    suspend fun checkLogin(key: String): Result<NeteaseQrStatus> =
        runCatching {
            val ydToken = fingerprint?.ydToken.orEmpty()
            val params =
                buildMap<String, Any?> {
                    put("type", 1)
                    put("noCheckToken", true)
                    put("key", key)
                    if (ydToken.isNotBlank()) put("ydDeviceToken", ydToken)
                }
            val headers =
                buildMap<String, String> {
                    put("x-loginmethod", "QrCode")
                    if (chainId.isNotBlank()) put("x-login-chain-id", chainId)
                }
            val result =
                weApiPost(
                    "/login/qrcode/client/login",
                    params,
                    extraHeaders = headers,
                )
            val body = result.text.toJson()
            val codeValue = body.nInt("code") ?: -1
            com.maxrave.logger.Logger.d(TAG, "poll code=$codeValue")
            when (codeValue) {
                801 -> NeteaseQrStatus.WaitingForScan
                802 -> NeteaseQrStatus.ScannedWaitingForConfirm
                -462 -> {
                    Logger.d(TAG, "poll risk-controlled (-462)")
                    NeteaseQrStatus.RiskControl
                }
                803 -> {
                    mergeBodyCookies(body)
                    val verified = verifyConfirmedLogin(result.refreshToken)
                    val final = verified.ifEmpty { currentCookies() }
                    Logger.d(TAG, "QR 803: finalCookies=${final.keys} verified=${verified.isNotEmpty()}")
                    NeteaseQrStatus.Confirmed(final)
                }
                else -> NeteaseQrStatus.Expired
            }
        }

    // ---------------------------------------------------------------- NeriPlayer 三段式验证

    private suspend fun verifyConfirmedLogin(refreshToken: String): Map<String, String> {
        val direct = verifyAccountIfPossible()
        if (direct.isNotEmpty()) return direct
        // x-refresh-token 响应头即 MUSIC_U 凭据,Set-Cookie 缺失时的官方兜底
        if (cookies["MUSIC_U"].isNullOrBlank() && refreshToken.isNotBlank()) {
            cookies["MUSIC_U"] = refreshToken
        }
        if (cookies["MUSIC_U"].isNullOrBlank()) return emptyMap()
        val refreshed = verifyAccountIfPossible()
        if (refreshed.isNotEmpty()) return refreshed
        return emptyMap() // 凭据存疑,交给调用方按 currentCookies 兜底
    }

    private suspend fun verifyAccountIfPossible(): Map<String, String> =
        runCatching {
            val csrf = cookies["__csrf"].orEmpty()
            // NeriPlayer applyCsrfIfNeeded:account/get 的 csrf_token 必须出现在 query 上
            val params =
                buildMap<String, Any?> {
                    put("noCheckToken", true)
                    if (csrf.isNotBlank()) put("csrf_token", csrf)
                }
            val result = weApiPost("/w/nuser/account/get", params)
            val body = result.text.toJson()
            val ok = body.nInt("code") == 200 && (body["profile"] != null || body["account"] != null)
            Logger.d(TAG, "verifyAccount ok=$ok cookieKeys=${cookies.keys}")
            if (ok) currentCookies() else emptyMap()
        }.getOrDefault(emptyMap())

    // ---------------------------------------------------------------- transport

    private data class HttpResult(
        val text: String,
        val refreshToken: String,
    )

    private suspend fun weApiPost(
        path: String,
        params: Map<String, Any?>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult {
        val withCsrf =
            if (!params.containsKey("csrf_token") && cookies["__csrf"]?.isNotEmpty() == true) {
                params + ("csrf_token" to cookies["__csrf"])
            } else {
                params
            }
        val response =
            http.post("https://music.163.com/weapi$path") {
                header(HttpHeaders.Cookie, cookieHeader())
                header(HttpHeaders.Origin, "https://music.163.com")
                header("Referer", "https://music.163.com")
                header(HttpHeaders.UserAgent, DESKTOP_UA)
                header("Accept", "*/*")
                header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                header("Cache-Control", "no-cache")
                header("Pragma", "no-cache")
                header("x-os", "web")
                header("x-channelsource", "undefined")
                header("nm-gcore-status", "1")
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(
                    FormDataContent(
                        Parameters.build {
                            NeteaseCrypto.weApiEncrypt(withCsrf).forEach { (k, v) -> append(k, v) }
                        },
                    ),
                )
            }
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { raw ->
            val nameValue = raw.substringBefore(';').trim()
            val name = nameValue.substringBefore('=').trim()
            val value = nameValue.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotBlank()) cookies[name] = value
        }
        Logger.d(TAG, "POST $path -> ${response.status.value} cookies=${cookies.keys}")
        return HttpResult(
            text = response.bodyAsText(),
            refreshToken = response.headers["x-refresh-token"].orEmpty(),
        )
    }

    /** 803 响应体里的 cookie 串("k=v; k2=v2")兜底合并 —— 引擎可能不透传全部 Set-Cookie */
    private fun mergeBodyCookies(body: JsonObject) {
        val raw = (body["cookie"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        raw.split(";")
            .forEach { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotBlank()) cookies[name] = value
            }
    }

    private fun cookieHeader(): String {
        val seeds =
            mapOf(
                "os" to "pc",
                "appver" to "8.10.35",
                "osver" to "Microsoft Windows 10",
                "NMTID" to nmtid,
            )
        return (cookies + seeds).entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun String.toJson(): JsonObject = json.parseToJsonElement(this).jsonObject


    private fun JsonObject.nInt(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonObject.nStr(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private companion object {
        const val TAG = "NeteaseQrSession"
        val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/129.0.0.0 Safari/537.36"
    }
}
