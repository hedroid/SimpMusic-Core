/*
 * 网易云分享链接识别 —— NeriPlayer ExploreLinkRecognizer 的网易分支,降层到 core。
 * 纯字符串解析(common 无 java.net.URI):支持 music.163.com 的 path 与 #/fragment 两种
 * 形态、163cn.tv 短链,以及分享文本里夹带的 URL。
 */
package com.maxrave.netease

import com.maxrave.netease.model.NeteaseLinkTarget

object NeteaseLinkParser {
    private val HTTP_URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val HOST_REGEX = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)

    private val TRAILING_PUNCTUATION = charArrayOf('。', '，', ',', '.', '）', ')', '】', ']', '}', '》', '>')

    /** 从任意文本(分享语、剪贴板)里识别网易链接;不是网易链接返回 null */
    fun recognize(input: String): NeteaseLinkTarget? {
        val url = extractHttpUrl(input) ?: return null
        val host = hostOf(url) ?: return null
        return when {
            host.endsWith("music.163.com") -> recognizeNetease(url)
            host == "163cn.tv" -> NeteaseLinkTarget.ShortLink(url)
            else -> null
        }
    }

    /** 展开 163cn.tv 短链后的再识别(短链本身不含 id) */
    fun recognizeExpanded(finalUrl: String): NeteaseLinkTarget? {
        val host = hostOf(finalUrl) ?: return null
        return if (host.endsWith("music.163.com")) recognizeNetease(finalUrl) else null
    }

    // music.163.com/song?id=1、music.163.com/#/playlist?id=2、m.music.163.com 都要认:
    // 老网页把路由放在 #fragment 里,fragment 里还可能带自己的 ?query。
    private fun recognizeNetease(url: String): NeteaseLinkTarget? {
        val afterScheme = url.substringAfter("://", url)
        val withoutHost = afterScheme.substringAfter('/', missingDelimiterValue = "")
        val pathPart = withoutHost.substringBefore('#')
        val fragment = withoutHost.substringAfter('#', missingDelimiterValue = "")
        val fragmentPath = fragment.substringBefore('?')
        val targetPath = "/$pathPart/$fragmentPath".lowercase()

        val params =
            queryParameters(queryOf(url)) + queryParameters(
                fragment.substringAfter('?', missingDelimiterValue = ""),
            )
        val id = params["id"]?.toLongOrNull() ?: return null

        return when {
            targetPath.contains("/song") -> NeteaseLinkTarget.Song(id)
            targetPath.contains("/playlist") -> NeteaseLinkTarget.Playlist(id)
            targetPath.contains("/artist") -> NeteaseLinkTarget.Artist(id)
            targetPath.contains("/album") -> NeteaseLinkTarget.Album(id)
            else -> null
        }
    }

    private fun extractHttpUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        return HTTP_URL_REGEX.find(trimmed)
            ?.value
            ?.trimEnd(*TRAILING_PUNCTUATION)
            ?.takeIf { it.isNotBlank() }
            ?: trimmed.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun hostOf(url: String): String? = HOST_REGEX.find(url)?.groupValues?.get(1)?.lowercase()

    private fun queryOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val noFragment = afterScheme.substringBefore('#')
        return noFragment.substringAfter('?', missingDelimiterValue = "")
    }

    private fun queryParameters(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=').urlDecode().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                key to part.substringAfter('=', missingDelimiterValue = "").urlDecode()
            }.toMap()
    }

    // %XX 解码(common 无 URLDecoder;失败原样返回)
    private fun String.urlDecode(): String {
        if (!contains('%')) return this
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '%' && i + 2 < length) {
                val hex = substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
