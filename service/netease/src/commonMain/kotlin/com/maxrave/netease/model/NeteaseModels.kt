package com.maxrave.netease.model

/** QR 扫码登录会话 */
data class NeteaseQrSession(
    val key: String,
    /** 二维码内容,渲染成二维码供网易云 App 扫描 */
    val qrContent: String,
)

/** QR 轮询结果 */
sealed class NeteaseQrStatus {
    /** 801: 等待扫码 */
    data object WaitingForScan : NeteaseQrStatus()

    /** 802: 已扫码,等待手机端确认 */
    data object ScannedWaitingForConfirm : NeteaseQrStatus()

    /** 803: 登录成功 */
    data class Confirmed(
        val cookies: Map<String, String>,
    ) : NeteaseQrStatus()

    /** 800: 二维码过期,需要重新生成 */
    data object Expired : NeteaseQrStatus()

    /** -462: 风控拦截(需设备指纹/滑块),换码无效,建议改用网页登录 */
    data object RiskControl : NeteaseQrStatus()
}

/** 易盾设备指纹快照(NeriPlayer NeteaseYdDeviceSnapshot):token + sDeviceId + WebView 会话 cookie */
data class NeteaseFingerprint(
    val ydToken: String = "",
    val sDeviceId: String = "",
    val cookies: Map<String, String> = emptyMap(),
)

/** 账号信息(/w/nuser/account/get 摘要) */
data class NeteaseAccount(
    val userId: Long,
    val nickname: String?,
    val avatarUrl: String?,
    /** 0=普通用户,11+为黑胶VIP档位 */
    val vipType: Int,
)

/** 取流结果(/song/enhance/player/url/v1 单首歌) */
data class NeteaseStreamUrl(
    val url: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val level: String?,
    /** 非空表示 VIP 试听:只能播 [startTimeMs, endTimeMs] 区间 */
    val freeTrialInfo: FreeTrial?,
) {
    data class FreeTrial(
        val startTimeMs: Long,
        val endTimeMs: Long,
    )
}

/** 网易云音质档位,与官方 level 参数一一对应(降级顺序从高到低) */
enum class NeteaseQuality(
    val key: String,
) {
    JYMASTER("jymaster"), // 超清母带 24bit
    SKY("sky"), // 高清环绕声
    JYEFFECT("jyeffect"), // HD环绕声
    HIRES("hires"), // Hi-Res
    LOSSLESS("lossless"), // 无损 FLAC
    EXHIGH("exhigh"), // 极高 320kbps
    HIGHER("higher"), // 较高 192kbps
    STANDARD("standard"), // 标准 128kbps
    ;

    companion object {
        /** 拿不到所选档位时的依次降级链(NeriPlayer 验证过的顺序) */
        val FALLBACK_ORDER: List<NeteaseQuality> = entries.toList()
    }
}

/** 歌曲原始 DTO(供 repository 映射成 SongEntity) */
data class NeteaseSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val artistIds: List<Long>,
    val albumId: Long?,
    val albumName: String?,
    val durationMs: Long,
    val coverUrl: String?,
    val fee: Int?, // 0免费 1VIP 4购票 8非会员可听低音质
    val hasCopyright: Boolean?,
)

/** 歌单摘要 DTO(供 repository 映射成 PlaylistEntity) */
data class NeteasePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long?,
    val description: String?,
    /** 每日推荐/雷达等特殊歌单的标记 */
    val specialType: SpecialType = SpecialType.NORMAL,
    /** 网易原生 specialType(5=红心歌单"我喜欢的音乐"),仅解析期使用 */
    val rawSpecialType: Int = 0,
) {
    enum class SpecialType { NORMAL, DAILY, RADAR_PRIVATE, RADAR_FANS, RADAR, TOPLIST, FAVORITE }
}

/** 歌词(/song/lyric/v1 全量) */
data class NeteaseLyrics(
    /** 逐行 LRC */
    val lrc: String?,
    /** 逐字 YRC */
    val yrc: String?,
    /** 官方翻译 */
    val translated: String?,
    /** 罗马音 */
    val romanized: String?,
)

/** 私人FM/心动模式会话 */
data class NeteaseRadioSession(
    val songs: List<NeteaseSong>,
    /** 下一次拉取的种子 */
    val popAdjust: Boolean?,
)

/** 专辑 DTO(YTM 有专辑详情/收藏专辑入口,共享形状) */
data class NeteaseAlbum(
    val id: Long,
    val name: String,
    val artistName: String?,
    val coverUrl: String?,
    val trackCount: Int,
    val publishTimeMs: Long?,
    val description: String?,
)

/** DJ 电台(网易云专属能力,YTM 无对应入口) */
data class NeteaseDjRadio(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val programCount: Int,
    val djNickname: String?,
)

/** 高质量歌单分类标签(网易云专属能力) */
data class NeteaseHighQualityTag(
    val id: Int,
    val name: String,
    val category: Int,
)

/** 歌手摘要(搜索/歌曲署名通用形状) */
data class NeteaseArtist(
    val id: Long,
    val name: String,
    val picUrl: String? = null,
    val musicSize: Int? = null,
    val albumSize: Int? = null,
)

/** 搜索分页结果(items + 命中总数,供 UI 分页判断) */
data class NeteaseSearchResult<T>(
    val items: List<T>,
    val totalCount: Int?,
)

/** 主页歌曲 feed 来源目录(NeriPlayer NeteaseHomeSongSource 的 core 版,标题文案归 UI) */
enum class NeteaseSongFeed(val requiresLogin: Boolean) {
    TOP_SOARING(false), // 飙升榜
    PERSONAL_RADAR(false), // 私人雷达(固定歌单,未登录也有基础数据)
    DAILY_RECOMMEND(true), // 每日推荐歌曲
    PRIVATE_FM(true), // 私人FM
    PERSONALIZED_NEW_SONGS(false), // 推荐新歌
    TOP_HOT(false), // 热歌榜
    TOP_NEW(false), // 新歌榜
}

/** 主页歌单 feed 来源目录 */
enum class NeteasePlaylistFeed(val requiresLogin: Boolean) {
    PERSONALIZED(false), // 推荐歌单
    DAILY_RESOURCE(true), // 每日推荐歌单
    HIGH_QUALITY(false), // 高质量歌单
    HOT_PLAYLISTS(false), // 热门歌单
    ACG_PLAYLISTS(false), // ACG 歌单(高质量接口按 cat 过滤)
}

/** 网易链接识别结果(分享文本/URL → 结构化目标) */
sealed class NeteaseLinkTarget {
    data class Song(val id: Long) : NeteaseLinkTarget()

    data class Playlist(val id: Long) : NeteaseLinkTarget()

    data class Artist(val id: Long) : NeteaseLinkTarget()

    data class Album(val id: Long) : NeteaseLinkTarget()

    /** 163cn.tv 短链,需先请求展开再识别 */
    data class ShortLink(val url: String) : NeteaseLinkTarget()
}

/** 逐字歌词行(YRC 解析结果,纯数据;渲染层自行映射) */
data class NeteaseLyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** 逐字/逐词时间轴,空表示整句 */
    val words: List<Word> = emptyList(),
) {
    data class Word(
        val startMs: Long,
        val endMs: Long,
        val charCount: Int,
    )
}

// ----------------------------------------------------------------------------
// C 档:歌手详情/百科/相似、歌曲评论、云盘
// ----------------------------------------------------------------------------

/** 歌手详情(api/artist/head/info/get:头像/简介/统计) */
data class NeteaseArtistDetail(
    val id: Long,
    val name: String,
    val alias: List<String> = emptyList(),
    val picUrl: String? = null,
    val briefDesc: String? = null,
    val albumSize: Int? = null,
    val musicSize: Int? = null,
    val mvSize: Int? = null,
    /** 认证身份(音乐人/歌手等),无认证为 null */
    val identifyTitle: String? = null,
)

/** 歌手动态信息(api/artist/detail/dynamic:关注状态/粉丝数) */
data class NeteaseArtistDynamic(
    val followed: Boolean? = null,
    val followerCount: Long? = null,
    val videoCount: Long? = null,
)

/** 歌手百科(weapi /artist/introduction/{id}:分段介绍) */
data class NeteaseArtistIntroduction(
    val briefDesc: String? = null,
    /** (标题, 正文) 分段,如"艺人历程/荣誉成就" */
    val sections: List<Pair<String, String>> = emptyList(),
)

/** 歌曲评论单条(weapi /comment/music) */
data class NeteaseComment(
    val commentId: Long,
    val userId: Long?,
    val nickname: String?,
    val avatarUrl: String?,
    val content: String,
    val timeMs: Long?,
    val likedCount: Long?,
    /** IP 归属地(评论展示要求) */
    val location: String?,
)

/** 歌曲评论页:热评 + 最新 + 总数 */
data class NeteaseCommentPage(
    val hotComments: List<NeteaseComment>,
    val latestComments: List<NeteaseComment>,
    val totalCount: Int,
    val hasMore: Boolean,
)

/** 云盘单个文件(weapi /v1/cloud/get:simpleSong 是可播放的歌曲形状) */
data class NeteaseCloudFile(
    val songId: Long,
    val fileName: String?,
    val sizeBytes: Long?,
    /** 码率原始值,服务端单位混用(320000 与 3495 并存),仅展示用 */
    val bitrate: Long?,
    val addTimeMs: Long?,
    /** 云盘文件的元数据,缺艺人/封面时为空字段,可按 songId 直接取流播放 */
    val song: NeteaseSong?,
)

/** 云盘文件分页 */
data class NeteaseCloudDiskPage(
    val files: List<NeteaseCloudFile>,
    val totalCount: Int,
    val hasMore: Boolean,
)
