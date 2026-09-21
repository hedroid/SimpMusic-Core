package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer

/** kebab 菜单里只有歌单主人可见的动作(编辑/删除播放列表),与界面语言无关的 iconType token */
private val OWN_MENU_ICON_TOKENS = setOf("PLAYLIST_EDIT", "DELETE")

/** kebab 菜单里只有收藏他人歌单才有的动作(从媒体库移除) */
private val SAVED_MENU_ICON_TOKENS = setOf("LIBRARY_REMOVE", "REMOVE_FROM_LIBRARY")

/**
 * 用菜单动作签名判断这条库歌单是不是"收藏的他人歌单"。
 * FEmusic_liked_playlists 的 grid 把自建/收藏混在一个列表里(YTM App 的"已创建/已保存"
 * 筛选是请求侧 params,不在响应里),菜单动作是唯一稳定的区分信号:
 * - 自建歌单菜单有 编辑播放列表/删除播放列表;
 * - 收藏的他人歌单菜单只有 从媒体库移除。
 * token 若对不上(YT 改名)则返回 false 归"自建"侧,行为退化为不分区——不会错分。
 */
internal fun MusicTwoRowItemRenderer.savedByMenuSignature(): Boolean {
    val tokens =
        menu?.menuRenderer?.items.orEmpty().mapNotNull { item ->
            item.menuNavigationItemRenderer?.icon?.iconType
                ?: item.menuServiceItemRenderer?.icon?.iconType
        }
    val own = tokens.any { it in OWN_MENU_ICON_TOKENS }
    val saved = tokens.any { it in SAVED_MENU_ICON_TOKENS }
    return saved && !own
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