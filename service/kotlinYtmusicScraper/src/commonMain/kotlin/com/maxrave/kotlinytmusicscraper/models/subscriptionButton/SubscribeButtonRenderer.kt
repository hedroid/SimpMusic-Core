package com.maxrave.kotlinytmusicscraper.models.subscriptionButton

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubscribeButtonRenderer(
    @SerialName("longSubscriberCountText")
    val longSubscriberCountText: LongSubscriberCountText,
    @SerialName("subscribed")
    val subscribed: Boolean? = null,
    /**
     * 频道的 canonical id。订阅列表 API(getLibraryArtists)对部分艺人返回别名 id(实测
     * 陈奕迅:列表 UC2rCyna.../页面 UCnafe6...),订阅关系挂在 canonical 上——用别名
     * 取关必 400 failedPrecondition(2026-09-22 定案)。艺人页一律以此 id 为准。
     */
    @SerialName("channelId")
    val channelId: String? = null,
)
