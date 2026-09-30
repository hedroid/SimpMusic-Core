package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.entities.SongEntity

/**
 * 文件式下载(第二代)的转存内容收集器:下载引擎在把缓存流转存成真实音乐文件前,
 * 通过它尽力收集 tag 字段(流派/语言/词曲/版权/音轨号)与歌词文本、封面字节。
 *
 * 实现方契约:**任何失败都必须吞掉返回空字段**(tag 失败绝不阻塞下载),因此引擎侧
 * 不需要 runCatching 包裹——但调用方仍建议包一层防御,实现 bug 不该炸掉转存协程。
 *
 * 放在 domain 是因为下载引擎(media3)只能依赖 domain;实现在 core/data
 * (那里才有 netease/lastfm/aiService/lyricsService 的依赖),Koin 绑定。
 */
interface DownloadEnricher {
    suspend fun enrich(
        song: SongEntity,
        artworkUrl: String?,
    ): EnrichedDownloadContent
}

/**
 * 全部字段可空=尽力而为:数据源拿不到就留空,写入端跳过空字段。
 * [lyricist]/[composer]/[copyright] 仅网易歌可能非空;[genre] 优先 Last.fm toptags、
 * AI 兜底;[language] 歌词字符判别+AI;[lrcText] 是"原文时间轴 LRC 文本"(翻译/罗马音不写)。
 * 封面不在这里:data 模块不带 HTTP 客户端,由引擎侧(media3,okhttp 在依赖里)按
 * [SongEntity.thumbnails] 升档 1080 后自行下载。
 */
data class EnrichedDownloadContent(
    val genre: String? = null,
    val language: String? = null,
    val lyricist: String? = null,
    val composer: String? = null,
    val copyright: String? = null,
    val trackNumber: Int? = null,
    val discNumber: String? = null,
    val year: String? = null,
    val albumArtist: String? = null,
    val lrcText: String? = null,
)
