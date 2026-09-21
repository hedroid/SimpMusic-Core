package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer

/**
 * kebab 菜单动作签名(与界面语言无关的 iconType token,2026-09-21 登录账号响应实测):
 * - 自建歌单独有:修改播放列表(EDIT)/删除播放列表(DELETE);
 * - 收藏他人歌单独有:"保存播放列表到媒体库"双态项(toggle,defaultIcon=BOOKMARK_BORDER、
 *   toggledIcon=BOOKMARK,已收藏时也是这两枚,只有文案切换)。
 */
private val OWN_MENU_ICON_TOKENS = setOf("EDIT", "DELETE")
private val SAVED_MENU_ICON_TOKENS = setOf("BOOKMARK", "BOOKMARK_BORDER")

/**
 * FEmusic_liked_playlists 的 grid 把自建/收藏混在一个列表里(YTM App 的"已创建/已保存"
 * 筛选是请求侧 params,不在响应里),菜单动作是唯一稳定的区分信号。
 * 返回 true=收藏的他人歌单、false=自建;token 全都对不上(YT 改名/字段缺席)返回 null,
 * 调用方按自建处理——宁可不分区也不能错分。
 */
internal fun MusicTwoRowItemRenderer.ownSavedByMenu(): Boolean? {
    var own = false
    var saved = false
    menu?.menuRenderer?.items.orEmpty().forEach { item ->
        item.menuNavigationItemRenderer?.icon?.iconType?.let { if (it in OWN_MENU_ICON_TOKENS) own = true }
        item.menuServiceItemRenderer?.icon?.iconType?.let { if (it in OWN_MENU_ICON_TOKENS) own = true }
        item.toggleMenuServiceItemRenderer?.let { toggle ->
            toggle.defaultIcon?.iconType?.let { if (it in SAVED_MENU_ICON_TOKENS) saved = true }
            toggle.toggledIcon?.iconType?.let { if (it in SAVED_MENU_ICON_TOKENS) saved = true }
        }
    }
    return when {
        own && !saved -> false
        saved && !own -> true
        else -> null
    }
}

internal fun parseLibraryPlaylist(input: List<GridRenderer.Item>): List<PlaylistsResult> {
    val list: MutableList<PlaylistsResult> = mutableListOf()
    if (input.isNotEmpty()) {
        for (i in input.indices) {
            input[i].musicTwoRowItemRenderer?.let {
                if (it.navigationEndpoint?.browseEndpoint?.browseId != null) {
                    list.add(
                        PlaylistsResult(
                            author =
                                it.subtitle
                                    ?.runs
                                    ?.get(0)
                                    ?.text ?: "",
                            browseId = it.navigationEndpoint?.browseEndpoint?.browseId ?: "",
                            category = "",
                            itemCount = "",
                            resultType = "",
                            thumbnails =
                                it.thumbnailRenderer
                                    ?.musicThumbnailRenderer
                                    ?.thumbnail
                                    ?.thumbnails
                                    ?.toListThumbnail() ?: listOf(),
                            title =
                                it.title
                                    ?.runs
                                    ?.get(0)
                                    ?.text ?: "",
                        ),
                    )
                }
            }
        }
    }
    return list
}

internal fun parseNextLibraryPlaylist(input: List<MusicTwoRowItemRenderer>): List<PlaylistsResult> =
    input.map {
        PlaylistsResult(
            author =
                it.subtitle
                    ?.runs
                    ?.get(0)
                    ?.text ?: "",
            browseId = it.navigationEndpoint?.browseEndpoint?.browseId ?: "",
            category = "",
            itemCount = "",
            resultType = "",
            thumbnails =
                it.thumbnailRenderer
                    ?.musicThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.toListThumbnail() ?: listOf(),
            title =
                it.title
                    ?.runs
                    ?.get(0)
                    ?.text ?: "",
        )
    }