package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

/**
 * Body for `playlist/delete`. 端点语义由歌单归属决定:自建歌单=真删除;
 * 收藏的他人歌单=移出资料库(取消收藏)——Metrolist/YTM 网页端同款用法。
 */
@Serializable
data class DeletePlaylistBody(
    val context: Context,
    val playlistId: String,
)
