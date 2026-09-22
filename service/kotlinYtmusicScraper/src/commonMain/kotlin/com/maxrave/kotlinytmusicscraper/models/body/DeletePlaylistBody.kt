package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

/**
 * Body for `playlist/delete` — 删除**自建**歌单(playlistId 不带 VL 前缀;带 VL 报 400,
 * 对收藏的他人歌单无 VL 报 403 仅自建可删——收藏歌单移出资料库走 like/removelike)。
 */
@Serializable
data class DeletePlaylistBody(
    val context: Context,
    val playlistId: String,
)
