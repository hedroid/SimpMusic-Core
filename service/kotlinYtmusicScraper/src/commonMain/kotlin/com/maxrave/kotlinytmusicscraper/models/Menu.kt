package com.maxrave.kotlinytmusicscraper.models

import com.maxrave.kotlinytmusicscraper.models.response.LikeStatus
import kotlinx.serialization.Serializable

@Serializable
data class Menu(
    val menuRenderer: MenuRenderer,
) {
    @Serializable
    data class MenuRenderer(
        val items: List<Item>,
        val topLevelButtons: List<TopLevelButton>?,
    ) {
        @Serializable
        data class Item(
            val menuNavigationItemRenderer: MenuNavigationItemRenderer?,
            val menuServiceItemRenderer: MenuServiceItemRenderer?,
            // "保存播放列表到媒体库"这类双态项(库歌单 kebab 里收藏他人歌单的专属动作,
            // defaultIcon=BOOKMARK_BORDER/toggledIcon=BOOKMARK,自建歌单没有这一项)
            val toggleMenuServiceItemRenderer: ToggleMenuServiceItemRenderer? = null,
        ) {
            @Serializable
            data class MenuNavigationItemRenderer(
                val text: Runs,
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )

            @Serializable
            data class MenuServiceItemRenderer(
                val text: Runs,
                val icon: Icon,
                val serviceEndpoint: NavigationEndpoint,
            )

            @Serializable
            data class ToggleMenuServiceItemRenderer(
                val defaultText: Runs? = null,
                val toggledText: Runs? = null,
                val defaultIcon: Icon? = null,
                val toggledIcon: Icon? = null,
                val isToggled: Boolean? = null,
            )
        }

        @Serializable
        data class TopLevelButton(
            val buttonRenderer: ButtonRenderer?,
            val likeButtonRenderer: LikeButtonRenderer? = null,
        ) {
            @Serializable
            data class LikeButtonRenderer(
                val likeStatus: String,
                val likesAllowed: Boolean,
            ) {
                fun toLikeStatus(): LikeStatus =
                    if (likesAllowed) {
                        when (likeStatus) {
                            "LIKE" -> LikeStatus.LIKE
                            "DISLIKE" -> LikeStatus.DISLIKE
                            else -> LikeStatus.INDIFFERENT
                        }
                    } else {
                        LikeStatus.INDIFFERENT
                    }
            }

            @Serializable
            data class ButtonRenderer(
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )
        }
    }
}