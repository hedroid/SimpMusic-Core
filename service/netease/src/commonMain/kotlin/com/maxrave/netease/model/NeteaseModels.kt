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
    /** 码率 bps(999000≈无损/320000=320k),喂 Info 面板比特率 */
    val bitrate: Long?,
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
    val creatorId: Long? = null,
    val creatorNickname: String? = null,
    /** 歌单创建时间(毫秒),歌单页年份列用 */
    val createTimeMs: Long? = null,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long?,
    val description: String?,
    /** 每日推荐/雷达等特殊歌单的标记 */
    val specialType: SpecialType = SpecialType.NORMAL,
    /** 当前登录用户是否已收藏该歌单(/v6/playlist/detail 响应,自建歌单恒 false) */
    val subscribed: Boolean? = null,
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
    /** 主歌手 id(/v1/album 的 album.artists[0].id / artist.id;缺失为 null,专辑页歌手名随之不可点) */
    val artistId: Long? = null,
    val coverUrl: String?,
    val trackCount: Int,
    val publishTimeMs: Long?,
    val description: String?,
    /** 唱片公司(播放页详情卡发行信息行用) */
    val company: String? = null,
    /**
     * 发行类型,/artist/albums 原始词表实测为 "Single"/"专辑"(推断另有 "EP"):
     * 单曲=Single,其余一律归专辑组。albumDetail 等其它端点无此字段时为 null,按专辑处理。
     */
    val type: String? = null,
)

/** DJ 电台(网易云专属能力,YTM 无对应入口) */
data class NeteaseDjRadio(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val programCount: Int,
    val djNickname: String?,
    /** 订阅数/描述/推荐语/分类/当前账号是否已订阅(详情端点才带,列表端点为 null) */
    val subCount: Long? = null,
    val description: String? = null,
    val rcmdtext: String? = null,
    val category: String? = null,
    val subed: Boolean? = null,
)

/** DJ 电台节目。**可播的是 [mainSongId](节目内嵌的主歌 id),节目自身 id 取流无效**(Melodia 真机抓包定论)。
 *  [paid]=付费节目且当前账号未购买(programFeeType!=0 && !buyed)——这类节目取流只回
 *  26KB 级试听片段(几秒),且 privilege 层看不出(mainSong.fee=0),必须节目层判定。 */
data class NeteaseDjProgram(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    /** 毫秒 */
    val durationMs: Long,
    /** 毫秒时间戳 */
    val createTimeMs: Long?,
    /** 期号 */
    val serialNum: Int?,
    /** 播放量 */
    val listenerCount: Long?,
    val mainSongId: Long?,
    val radioId: Long?,
    val radioName: String?,
    val djNickname: String?,
    val paid: Boolean = false,
)

/** 播客电台分类(/djradio/category/get,公开,19 个) */
data class NeteasePodcastCategory(
    val id: Long,
    val name: String,
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

/** 热搜词(/search/hot) */
data class NeteaseHotWord(
    val word: String,
    val score: Long?,
)

/** 搜索建议(/api/search/suggest/web):实体建议(歌曲+歌手),该端点无词联想 */
data class NeteaseSuggest(
    val songs: List<NeteaseSong>,
    val artists: List<NeteaseArtist>,
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

/** 歌手动态信息(api/artist/detail/dynamic:关注状态/视频数;粉丝数走独立 follow/count 端点) */
data class NeteaseArtistDynamic(
    val followed: Boolean? = null,
    val videoCount: Long? = null,
)

/** 歌手百科(weapi /artist/introduction/{id}:分段介绍) */
data class NeteaseArtistIntroduction(
    val briefDesc: String? = null,
    /** (标题, 正文) 分段,如"艺人历程/荣誉成就" */
    val sections: List<Pair<String, String>> = emptyList(),
)

/** 歌曲评论单条(weapi /v1/resource/comments/R_SO_4_) */
data class NeteaseComment(
    val commentId: Long,
    val userId: Long?,
    val nickname: String?,
    val avatarUrl: String?,
    val content: String,
    val timeMs: Long?,
    /** 服务端预格式化的展示日期(如 2014-10-17),官方 app 同款 */
    val timeStr: String?,
    val likedCount: Long?,
    /** IP 归属地(评论展示要求) */
    val location: String?,
    /** 楼中楼回复总数(showFloorComment.replyCount),0=无回复不显示展开入口 */
    val replyCount: Int,
    /** 当前登录用户是否已点赞该评论(登录视角) */
    val liked: Boolean,
    /** 本条是回复时引用的父评论摘要(昵称+内容),官方 app 显示为"回复 @xx: ..." */
    val beRepliedNickname: String?,
    val beRepliedContent: String?,
)

/** 歌曲评论页:热评 + 最新 + 总数 */
data class NeteaseCommentPage(
    val hotComments: List<NeteaseComment>,
    val latestComments: List<NeteaseComment>,
    val totalCount: Int,
    val hasMore: Boolean,
)

/**
 * 评论列表 v2 页(weapi /v2/resource/comments):服务端排序(2=最热,3=最新)+cursor 分页。
 * v1 列表响应已不吐 showFloorComment(恒 null),楼中楼入口计数必须走 v2。
 */
data class NeteaseCommentPageV2(
    val comments: List<NeteaseComment>,
    val totalCount: Int,
    val hasMore: Boolean,
    /** 下一页游标,服务端算好直接透传(最热 normalHot#N / 最新时间戳),null=没有更多 */
    val cursor: String?,
)

/** 楼中楼回复页(weapi /resource/comment/floor/get) */
data class NeteaseCommentFloorPage(
    val comments: List<NeteaseComment>,
    val totalCount: Int,
    val hasMore: Boolean,
    /** 下一页游标(取最后一条的时间戳),null=没有更多 */
    val nextTimeMs: Long?,
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
