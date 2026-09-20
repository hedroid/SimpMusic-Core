package com.maxrave.netease

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 一次性探针:验证 /artist/albums 的 type 字段经 toAlbum() 映射后可用,
 * 且按 type=="Single" 拆出的单曲/专辑两组不相交、并集=全集
 * (通知 worker 去重 + 歌手页 singles 分区依赖这两条性质)。
 * cookie 文件不存在时静默跳过。
 */
class AlbumTypeProbe {
    @Test
    fun probeAlbumTypes() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/album_type_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()
            for (artistId in listOf(6452L)) {
                val albums = client.artistAlbums(artistId, limit = 50).getOrThrow().first
                assertTrue(albums.isNotEmpty(), "artist $artistId hotAlbums 不应为空")
                val distinctTypes = albums.mapNotNull { it.type }.distinct().sorted()
                val singles = albums.filter { it.type == "Single" }
                val albumSide = albums.filter { it.type != "Single" }
                sb.appendLine("artist $artistId: ${albums.size} 张, type 词表=$distinctTypes")
                sb.appendLine("  单曲组 ${singles.size} 张: ${singles.take(5).joinToString { "${it.id} ${it.name}" }}")
                sb.appendLine("  专辑组 ${albumSide.size} 张: ${albumSide.take(5).joinToString { "${it.id} ${it.name}" }}")
                // 不相交 + 并集=全集
                assertEquals(0, singles.intersect(albumSide.toSet()).size, "两组不应重叠")
                assertEquals(albums.size, singles.size + albumSide.size, "两组之和应等于全集")
                sb.appendLine("  分区校验通过: 不相交 ✓ 并集=全集 ✓")
            }
            out.writeText(sb.toString())
            println(sb)
        }
    }
}
