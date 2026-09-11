/*
 * NetEase private-API HTTP client, ported from NeriPlayer (GPL-3.0)
 * https://github.com/cwuom/NeriPlayer — core/api/netease/NeteaseClient.kt,
 * reworked for Ktor/KMP with cookie persistence delegated to the host app.
 */
package com.maxrave.netease

import com.maxrave.netease.model.NeteaseAccount
import com.maxrave.netease.model.NeteaseQrSession
import com.maxrave.netease.model.NeteaseQrStatus
import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Cookie 持久化交由宿主 App(DataStore),client 只负责:
 *  - 请求时: 持久 cookie(MUSIC_U/__csrf...) + 会话种子(os/appver/NMTID...)合并成 Cookie 头
 *  - 响应时: 解析 Set-Cookie 合并回内存副本,再整体回写 [cookieSaver]
 */
class NeteaseClient(
    private val cookieProvider: suspend () -> Map<String, String> = { emptyMap() },
    private val cookieSaver: suspend (Map<String, String>) -> Unit = { _ -> },
) {
    private val http =
        HttpClient(getEngine()) {
            expectSuccess = false
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId: String = NeteaseCrypto.generateDeviceId()

    private val cookieMutex = Mutex()
    private var sessionCookies: Map<String, String> = emptyMap()

    private val desktopUa =
        "Mozilla/5.0 (Macintosh; Intel Mac 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Safari/537.36"

    fun deviceId(): String = deviceId

    /** 用持久化 cookie 重建会话(App 启动时调用一次即可,后续请求自动带上)。 */
    suspend fun seedCookies(persisted: Map<String, String>) =
        cookieMutex.withLock {
            sessionCookies = persisted
        }

    suspend fun currentCookies(): Map<String, String> = cookieMutex.withLock { sessionCookies }

    suspend fun replaceCookies(cookies: Map<String, String>) {
        cookieMutex.withLock { sessionCookies = cookies }
        cookieSaver(cookies)
    }

    suspend fun logout() = replaceCookies(emptyMap())

    fun hasLoginCookie(cookies: Map<String, String>): Boolean = cookies["MUSIC_U"]?.isNotEmpty() == true

    // ------------------------------------------------------------------ envelopes

    suspend fun callWeApi(
        path: String,
        params: Map<String, Any?>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        val withCsrf =
            if (!params.containsKey("csrf_token") && sessionCsrf().isNotEmpty()) {
                params + ("csrf_token" to sessionCsrf())
            } else {
                params
            }
        return post(
            url = "https://music.163.com/weapi$path",
            form = NeteaseCrypto.weApiEncrypt(withCsrf),
            extraHeaders = extraHeaders,
        )
    }

    suspend fun callEApi(
        path: String,
        params: Map<String, Any?>,
    ): JsonObject = post(
        url = "https://interface.music.163.com/eapi$path",
        form = NeteaseCrypto.eApiEncrypt(path, params),
    )

    private suspend fun post(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        val response =
            http.post(url) {
                header(HttpHeaders.Cookie, buildCookieHeader())
                header(HttpHeaders.Origin, "https://music.163.com")
                header("Referer", "https://music.163.com")
                header(HttpHeaders.UserAgent, desktopUa)
                header("x-os", "web")
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(
                    FormDataContent(
                        Parameters.build {
                            form.forEach { (k, v) -> append(k, v) }
                        },
                    ),
                )
            }
        // 合并 Set-Cookie(登录类接口靠它拿 MUSIC_U)
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        if (setCookies.isNotEmpty()) {
            mergeSetCookies(setCookies)
        }
        val text = response.bodyAsText()
        if (!response.status.value.let { it in 200..299 }) {
            throw ClientRequestException(response, "HTTP ${response.status.value}: ${text.take(200)}")
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private suspend fun buildCookieHeader(): String =
        (cookieProvider() + sessionCookies + sessionSeeds())
            .entries
            .joinToString("; ") { "${it.key}=${it.value}" }

    private fun sessionSeeds(): Map<String, String> =
        mapOf(
            "os" to "pc",
            "appver" to "8.10.35",
            "osver" to "Microsoft Windows 10",
            "deviceId" to deviceId,
            "NMTID" to (sessionCookies["NMTID"] ?: deviceId.replace("-", "").take(24)),
        )

    private suspend fun sessionCsrf(): String = (cookieProvider() + sessionCookies)["__csrf"] ?: ""

    private suspend fun mergeSetCookies(setCookies: List<String>) {
        val merged = cookieMutex.withLock {
            val m = sessionCookies.toMutableMap()
            setCookies.forEach { raw ->
                val nameValue = raw.substringBefore(';').trim()
                val name = nameValue.substringBefore('=').trim()
                val value = nameValue.substringAfter('=', "").trim()
                if (name.isNotEmpty()) m[name] = value
            }
            sessionCookies = m
            m
        }
        cookieSaver(merged)
    }

    // ------------------------------------------------------------------ login flows

    /** 创建扫码登录会话 */
    suspend fun createQrSession(): Result<NeteaseQrSession> =
        runCatching {
            val body =
                callWeApi(
                    "/login/qrcode/unikey",
                    mapOf("type" to 1, "noCheckToken" to true),
                )
            val code = body["code"].primitiveInt() ?: -1
            val key = body["unikey"].primitiveContent().orEmpty()
            check(code == 200 && key.isNotEmpty()) { "QR unikey failed, code=$code" }
            NeteaseQrSession(
                key = key,
                qrContent = "https://music.163.com/login?codekey=$key",
            )
        }

    /**
     * 轮询扫码状态。803 成功时 Set-Cookie 已被 [post] 合并进会话并回写持久层。
     * TODO(NETEASE_RISK_CONTROL): NeriPlayer 的易盾 ydDeviceToken(WebView 设备指纹)+ 登录链 ID
     * 能提高风控通过率,首版不带;若扫码 803 后仍被判定异常登录,在 Android expect/actual 里补
     * headless WebView 方案。
     */
    suspend fun checkQrLogin(key: String): Result<NeteaseQrStatus> =
        runCatching {
            val body =
                callWeApi(
                    "/login/qrcode/client/login",
                    mapOf("type" to 1, "noCheckToken" to true, "key" to key),
                    extraHeaders = mapOf("x-loginmethod" to "QrCode"),
                )
            when (body["code"].primitiveInt() ?: -1) {
                801 -> NeteaseQrStatus.WaitingForScan
                802 -> NeteaseQrStatus.ScannedWaitingForConfirm
                803 -> NeteaseQrStatus.Confirmed(currentCookies())
                else -> NeteaseQrStatus.Expired // 800 及未知码一律按过期重新生成
            }
        }

    /** 手机号+密码登录(密码传明文,内部 md5) */
    suspend fun loginByPhone(
        phone: String,
        password: String,
        countryCode: String = "86",
    ): Result<JsonObject> =
        runCatching {
            callEApi(
                "/w/login/cellphone",
                mapOf(
                    "phone" to phone,
                    "countrycode" to countryCode,
                    "remember" to true.toString(),
                    "password" to NeteaseCrypto.md5Hex(password),
                    "type" to "1",
                ),
            )
        }

    /** 发送短信验证码 */
    suspend fun sendCaptcha(
        phone: String,
        countryCode: String = "86",
    ): Result<JsonObject> =
        runCatching {
            callWeApi(
                "/sms/captcha/sent",
                mapOf("cellphone" to phone, "ctcode" to countryCode),
            )
        }

    /** 手机号+验证码登录 */
    suspend fun loginByCaptcha(
        phone: String,
        captcha: String,
        countryCode: String = "86",
    ): Result<JsonObject> =
        runCatching {
            callEApi(
                "/w/login/cellphone",
                mapOf(
                    "phone" to phone,
                    "countrycode" to countryCode,
                    "remember" to true.toString(),
                    "type" to "1",
                    "captcha" to captcha,
                ),
            )
        }

    /** 校验当前会话并取账号摘要;cookie 无效时返回 null */
    suspend fun getAccountStatus(): Result<NeteaseAccount?> =
        runCatching {
            val body = callWeApi("/w/nuser/account/get", mapOf("noCheckToken" to true))
            val profile = body["profile"] as? JsonObject ?: return@runCatching null
            val account = body["account"] as? JsonObject
            NeteaseAccount(
                userId = profile["userId"].nLong() ?: 0L,
                nickname = profile["nickname"].primitiveContent() ?: "NetEase user",
                avatarUrl = profile["avatarUrl"].primitiveContent(),
                vipType = account?.get("vipType").nInt() ?: 0,
            )
        }

    /** 把浏览器导出的原始 cookie 串("k=v; k2=v2" 或纯 MUSIC_U 值)规范化 */
    fun parseRawCookies(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (!trimmed.contains('=') && trimmed.isNotEmpty()) {
            return mapOf("MUSIC_U" to trimmed)
        }
        return trimmed
            .split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty()) null else name to value
            }.toMap()
    }
}

private fun JsonElement?.primitiveContent(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.primitiveInt(): Int? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
