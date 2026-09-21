package com.maxrave.data.parser

import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer

/**
 * YT 库"关注的歌手"(FEmusic_library_corpus_artists)条目解析。
 * 响应有两种已知形状,同一份数据两个入口各自解析:
 * - shelf 形状:musicShelfRenderer.contents[].musicResponsiveListItemRenderer
 * - grid 形状:gridRenderer.items[].musicTwoRowItemRenderer(与库歌单/专辑页同款)
 */
internal data class LibraryArtistItem(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String?,
)

internal fun parseLibraryArtistsFromShelf(items: List<MusicResponsiveListItemRenderer>): List<LibraryArtistItem> =
    items.mapNotNull { renderer ->
        val firstRun =
            renderer.flexColumns
                .firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.firstOrNull()
        val channelId =
            firstRun
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.browseId
                ?: renderer.navigationEndpoint?.browseEndpoint?.browseId
                ?: return@mapNotNull null
        // 只认艺人频道(UC 前缀),防御性滤掉偶发的其它页型
        if (!channelId.startsWith("UC")) return@mapNotNull null
        val name = firstRun?.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LibraryArtistItem(
            channelId = channelId,
            name = name,
            thumbnailUrl = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url,
        )
    }

internal fun parseLibraryArtistsFromGrid(items: List<MusicTwoRowItemRenderer>): List<LibraryArtistItem> =
    items.mapNotNull { renderer ->
        val channelId =
            renderer.navigationEndpoint?.browseEndpoint?.browseId
                ?: return@mapNotNull null
        if (!channelId.startsWith("UC")) return@mapNotNull null
        val name =
            renderer.title?.runs?.firstOrNull()?.text?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
        LibraryArtistItem(
            channelId = channelId,
            name = name,
            thumbnailUrl = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url,
        )
    }
