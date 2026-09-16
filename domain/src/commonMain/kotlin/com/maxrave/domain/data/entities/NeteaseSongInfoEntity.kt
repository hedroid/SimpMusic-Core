package com.maxrave.domain.data.entities

/**
 * 网易歌的播放页元数据,与 [SongInfoEntity](YT 形状)平行:Spotify 主题的艺人卡/说明卡
 * 和播放器"歌曲信息"面板按源二选一渲染,不把网易数据伪装进 YT 字段(评论数≠播放量)。
 * 各字段独立拉取、独立降级,缺哪项哪项留空。
 */
data class NeteaseSongInfoEntity(
    val artistId: String? = null,
    val artistName: String? = null,
    val artistAvatar: String? = null,
    /** 粉丝数(artist/follow/count/get),拿不到为 null */
    val artistFans: Long? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    /** 专辑发行日 yyyy-MM-dd,拿不到为 null */
    val albumPublishDate: String? = null,
    /** 专辑简介 */
    val albumDescription: String? = null,
    val albumTrackCount: Int? = null,
    /** 唱片公司 */
    val albumCompany: String? = null,
    /** 艺人简介(详情卡第三行,优先于专辑简介展示) */
    val artistBriefDesc: String? = null,
    /** 歌曲红心总数(song/red/count),拿不到为 null */
    val likeCount: Long? = null,
    val commentCount: Int = 0,
    val hotComments: List<HotComment> = emptyList(),
) {
    data class HotComment(
        val nickname: String? = null,
        val avatarUrl: String? = null,
        val content: String,
        val likedCount: Long? = null,
        /** IP 归属地(评论展示要求) */
        val location: String? = null,
    )
}
