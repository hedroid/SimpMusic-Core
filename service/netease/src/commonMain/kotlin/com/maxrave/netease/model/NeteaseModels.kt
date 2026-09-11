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
}

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
    enum class SpecialType { NORMAL, DAILY, RADAR_PRIVATE, RADAR_FANS, TOPLIST, FAVORITE }
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
