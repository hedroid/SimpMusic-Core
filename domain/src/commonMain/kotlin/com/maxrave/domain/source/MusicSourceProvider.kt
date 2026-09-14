package com.maxrave.domain.source

import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.HomeItem
import kotlinx.coroutines.flow.Flow

/**
 * 在线音源契约。类型全部复用 YTM 既有 domain 实体(SongEntity/HomeItem/PlaylistEntity),
 * 这样新音源"整形成 YTM 形状"后,现有 UI 与仓库逻辑理论上零改动即可渲染 —— simpcore
 * 上游升级时只要实体字段不被删除,适配层不丢逻辑。
 *
 * YTM 侧不直接实现本接口(现有仓库继续工作);网易云侧由 NeteaseRepositoryImpl 实现。
 * 两个实现都存在的意义:让 UI/ViewModel 只面向 source 抽象编程。
 */
enum class MusicSource {
    YOUTUBE_MUSIC,
    NETEASE,
}

/** 歌词契约:网易的 yrc/翻译/罗马音与 YTM 的 Musixmatch JSON 在此归一 */
data class ProviderLyrics(
    val plain: String?,
    val wordTimed: String?,
    val translated: String?,
    val romanized: String?,
)

/** 私人FM/心动模式会话:拉一批歌,播完再来一批 */
data class ProviderRadioSession(
    val songs: List<SongEntity>,
)

interface MusicSourceProvider {
    val source: MusicSource

    /** 登录态(网易云=MUSIC_U cookie 存在;YTM=cookie 存在) */
    val isLoggedIn: Flow<Boolean>

    suspend fun searchSongs(
        query: String,
        limit: Int = 30,
        offset: Int = 0,
    ): Result<List<SongEntity>>

    /**
     * 取流。返回 null 表示拿不到可完整播放的 URL(灰歌/VIP 试听),调用方负责
     * "无版权音乐自动切"(neteaseAutoSwitch 设置)时回退到另一音源搜索同名曲。
     */
    suspend fun getStreamUrl(
        songId: String,
        isDownload: Boolean = false,
    ): Result<String?>

    suspend fun getLyrics(songId: String): Result<ProviderLyrics?>

    /** 主页分区(YTM=首页 shelves;网易=每日推荐/雷达/榜单/分类歌单) */
    suspend fun getHome(force: Boolean = false): Result<List<HomeItem>>

    /** 云端歌单列表(YTM=账号歌单;网易=用户创建+收藏歌单) */
    suspend fun getLibraryPlaylists(): Result<List<PlaylistEntity>>

    suspend fun getPlaylistSongs(playlistId: String): Result<List<SongEntity>>

    // ------------------------------------------------ 网易云特有能力(见能力清单 A/B 档),
    // YTM 侧默认返回 null,接口不因特有能力膨胀。

    /** 每日推荐:30 首歌 + 推荐歌单(每日刷新,需登录) */
    suspend fun getDailyPicks(): Pair<List<SongEntity>, List<PlaylistEntity>>? = null

    /** 雷达歌单系列(私人/粉丝雷达,每日更新,需登录) */
    suspend fun getRadarPlaylists(): List<PlaylistEntity>? = null

    /** 私人FM(需登录);seed 传歌单则为心动模式 */
    suspend fun getPersonalRadio(seed: PlaylistEntity? = null): ProviderRadioSession? = null

    /** 单曲电台:以某首歌为种子的相似歌(网易=simiSong 分页;YTM 走既有 RDAMVM 路径,不实现) */
    suspend fun getSongRadio(
        songId: String,
        limit: Int = 30,
        offset: Int = 0,
    ): ProviderRadioSession? = null

    /** 红心一首歌(网易云=云村红心;YTM=addToLiked,由各自仓库承接) */
    suspend fun likeSong(
        songId: String,
        like: Boolean,
    ): Result<Boolean> = Result.failure(UnsupportedOperationException("$source: likeSong"))

    // ------------------------------------------------ C 档(下个版本),接口位预留
    // TODO(NETEASE_C_TIER): 云盘(cloudDisk)、歌曲评论(songComments)、
    // 歌手详情/百科/相似歌手(artistDetail) —— 实现见 core/service/netease/NeteaseEndpoints.kt 桩。

    // ------------------------------------------------ YTM 有而本源没有的能力(如实留空):
    // 视频MV/播客(网易MV另算)、automix 无限续播、播放行为回传(watchtime)、
    // SponsorBlock(依赖 YT videoId)、Canvas(Spotify 专属)。
}
