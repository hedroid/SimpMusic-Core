package com.maxrave.data.repository

import DatabaseDao
import com.maxrave.domain.data.entities.NeteaseAccountEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Artists
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.home.chart.ChartItemPlaylist
import com.maxrave.domain.data.model.browse.artist.ResultPlaylist
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.domain.data.model.mood.MoodSection
import com.maxrave.domain.data.model.mood.moodmoments.Content as MoodContent
import com.maxrave.domain.data.model.mood.moodmoments.Item as MoodItemShelf
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import com.maxrave.domain.source.MusicSource
import com.maxrave.logger.Logger
import com.maxrave.domain.source.MusicSourceProvider
import com.maxrave.domain.source.ProviderLyrics
import com.maxrave.domain.source.ProviderRadioSession
import com.maxrave.netease.NeteaseClient
import com.maxrave.netease.dailyRecommendPlaylists
import com.maxrave.netease.dailyRecommendSongs
import com.maxrave.netease.highQualityPlaylists
import com.maxrave.netease.highQualityPlaylistsPaged
import com.maxrave.netease.highQualityTags
import com.maxrave.netease.likeSong
import com.maxrave.netease.lyric
import com.maxrave.netease.model.NeteaseAccount
import com.maxrave.netease.model.NeteaseHighQualityTag
import com.maxrave.netease.model.NeteasePlaylist
import com.maxrave.netease.model.NeteaseQuality
import com.maxrave.netease.model.NeteaseSong
import com.maxrave.netease.personalRadio
import com.maxrave.netease.personalizedNewSongs
import com.maxrave.netease.playlistDetail
import com.maxrave.netease.playlistTracks
import com.maxrave.netease.radarPlaylists
import com.maxrave.netease.searchSongs
import com.maxrave.netease.songUrl
import com.maxrave.netease.toplistPlaylists
import com.maxrave.netease.userPlaylists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 网易云音源适配层:把 NeteaseClient 的 DTO "整形成 YTM 形状"的 domain 实体,实现统一
 * 契约 [MusicSourceProvider]。Cookie 持久化在 DataStoreManager,client 通过钩子读写。
 *
 * ID 约定:SongEntity.videoId = 网易歌曲数字 ID 原文;PlaylistEntity.id = 歌单数字 ID 原文;
 * 区分来源一律查 [SongEntity.source] / [PlaylistEntity.source](方案B,不用前缀)。
 */
class NeteaseRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val dao: DatabaseDao,
) : MusicSourceProvider,
    HomeRepository {
    override val source: MusicSource = MusicSource.NETEASE
    /**
     * 排行榜去重缓存:feed 的"排行榜"行与图表区块共用同一份榜单数据
     * (原本两个槽各打一次接口)。Mutex 保证并发下的第一对请求只发一次网络;
     * 10 分钟 TTL,榜单按天更新,会话内刷新也能拿到新数据。
     */
    private val toplistMutex = Mutex()
    private var toplistCache: Pair<List<NeteasePlaylist>, kotlin.time.TimeMark>? = null

    @OptIn(ExperimentalTime::class)
    private suspend fun toplistCached(): List<NeteasePlaylist>? =
        toplistMutex.withLock {
            toplistCache?.let { (list, mark) ->
                if (mark.elapsedNow() < 10.minutes) return list
            }
            client.toplistPlaylists().getOrNull()?.also { list ->
                toplistCache = list to TimeSource.Monotonic.markNow()
            }
        }


    private companion object {
        const val TAG = "NeteaseRepo"
    }

    private val json = Json { ignoreUnknownKeys = true }

    val client =
        NeteaseClient(
            cookieProvider = { loadPersistedCookies() },
            cookieSaver = { cookies -> persistCookies(cookies) },
        )

    override val isLoggedIn: Flow<Boolean> =
        dataStoreManager.neteaseCookie.map { it.isNotEmpty() && it != "{}" && it.contains("MUSIC_U") }

    val accountName: Flow<String> = dataStoreManager.neteaseAccountName
    val accountThumbUrl: Flow<String> = dataStoreManager.neteaseAccountThumbUrl

    // ---------------------------------------------------------------- login session

    /** 任意登录方式拿到 cookie 后走这里:校验 + 落盘 + 缓存账号摘要 */
    suspend fun saveLoginCookies(cookies: Map<String, String>): Result<NeteaseAccount> {
        val withUser = cookies.filterValues { it.isNotBlank() }
        Logger.d(TAG, "saveLoginCookies: keys=${withUser.keys} hasMusicU=${client.hasLoginCookie(withUser)}")
        if (!client.hasLoginCookie(withUser)) {
            return Result.failure(IllegalArgumentException("cookie 缺少 MUSIC_U"))
        }
        client.seedCookies(withUser) // 先只进内存会话,验证通过才落盘
        Logger.d(TAG, "saveLoginCookies: seeded, verifying account…")
        return client.getAccountStatus().mapCatching { account ->
            Logger.d(TAG, "saveLoginCookies: account=$account")
            val valid =
                account ?: throw IllegalStateException("MUSIC_U 无效或已过期")
            persistCookies(withUser)
            dataStoreManager.setNeteaseAccountName(valid.nickname ?: "NetEase user")
            dataStoreManager.setNeteaseAccountThumbUrl(valid.avatarUrl ?: "")
            // 多账户:按 userId 入表并标记当前使用(GoogleAccountEntity 同构)
            dao.getAllNeteaseAccount().forEach { dao.updateNeteaseAccountUsed(false, it.userId) }
            dao.insertNeteaseAccount(
                NeteaseAccountEntity(
                    userId = valid.userId,
                    nickname = valid.nickname ?: "NetEase user",
                    avatarUrl = valid.avatarUrl ?: "",
                    cookies = json.encodeToString(withUser),
                    isUsed = true,
                ),
            )
            valid
        }.onFailure {
            // 校验失败则清掉,不留半登录态
            Logger.e(TAG, "saveLoginCookies failed", it)
            logout()
        }
    }


    private suspend fun loadPersistedCookies(): Map<String, String> =
        runCatching {
            json.decodeFromString<Map<String, String>>(dataStoreManager.neteaseCookie.first())
        }.getOrDefault(emptyMap())

    suspend fun logout() {
        client.logout()
        dataStoreManager.setNeteaseCookie("")
        dataStoreManager.setNeteaseAccountName("")
        dataStoreManager.setNeteaseAccountThumbUrl("")
        dao.deleteAllNeteaseAccount()
    }

    private suspend fun persistCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) {
            dataStoreManager.setNeteaseCookie("")
            return
        }
        // NonCancellable:登录收尾协程若被取消,写盘也必须完成(现场日志显示挂起发生在
        // 这条链路上,取消风暴下最稳妥的是不让 DataStore 写入参与取消)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            dataStoreManager.setNeteaseCookie(json.encodeToString(cookies))
        }
    }

    // ---------------------------------------------------------------- 多账户(YTM 同构)

    fun getNeteaseAccounts(): Flow<List<NeteaseAccountEntity>> =
        flow {
            emit(dao.getAllNeteaseAccount())
        }.flowOn(Dispatchers.IO)

    /** 访客模式:清当前会话但保留账户表(区别于 logout 清光),随时可点回账户切换回来 */
    suspend fun useGuest() {
        client.logout()
        dataStoreManager.setNeteaseCookie("")
        dataStoreManager.setNeteaseAccountName("")
        dataStoreManager.setNeteaseAccountThumbUrl("")
        dao.getAllNeteaseAccount().forEach { dao.updateNeteaseAccountUsed(isUsed = false, userId = it.userId) }
    }

    /** 切换账户:换 DataStore cookie + 内存会话 + 账户名/头像 */
    suspend fun setUsedNeteaseAccount(userId: Long) {
        val accounts = dao.getAllNeteaseAccount()
        val target = accounts.firstOrNull { it.userId == userId } ?: return
        accounts.forEach { dao.updateNeteaseAccountUsed(isUsed = it.userId == userId, userId = it.userId) }
        dataStoreManager.setNeteaseCookie(target.cookies)
        dataStoreManager.setNeteaseAccountName(target.nickname)
        dataStoreManager.setNeteaseAccountThumbUrl(target.avatarUrl)
        runCatching { json.decodeFromString<Map<String, String>>(target.cookies) }
            .getOrDefault(emptyMap())
            .let { client.seedCookies(it) }
    }

    // ---------------------------------------------------------------- MusicSourceProvider

    override suspend fun searchSongs(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<List<SongEntity>> =
        client.searchSongs(query, limit, offset).map { result ->
            result.items.map(NeteaseSong::toSongEntity)
        }

    override suspend fun getStreamUrl(
        songId: String,
        isDownload: Boolean,
    ): Result<String?> {
        val id = songId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("netease songId 非数字: $songId"))
        val levelName =
            (
                if (isDownload) {
                    dataStoreManager.neteaseDownloadQuality
                } else {
                    dataStoreManager.neteaseQuality
                }
            ).first()
        val wanted = NeteaseQuality.entries.firstOrNull { it.name == levelName } ?: NeteaseQuality.EXHIGH
        // 从所选档位向下走降级链;只剩试听(freeTrialInfo)视为不可完整播放 → null,
        // 由调用方按 neteaseAutoSwitch 设置决定是否自动切另一音源。
        val order = NeteaseQuality.FALLBACK_ORDER.dropWhile { it != wanted }
        for (level in order) {
            val result = client.songUrl(id, level).getOrNull() ?: return Result.success(null)
            val url = result.url
            if (!url.isNullOrEmpty() && result.freeTrialInfo == null) {
                // CDN 签发的链接是 http://,Android 默认禁明文流量(ExoPlayer 报 Source error),
                // music.126.net 的 CDN 支持 https,统一升级
                return Result.success(url.replaceFirst("http://", "https://"))
            }
        }
        return Result.success(null)
    }

    override suspend fun getLyrics(songId: String): Result<ProviderLyrics?> {
        val id = songId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("netease songId 非数字: $songId"))
        return client.lyric(id).map {
            ProviderLyrics(
                plain = it.lrc,
                wordTimed = it.yrc,
                translated = it.translated,
                romanized = it.romanized,
            )
        }
    }

    override suspend fun getHome(): Result<List<HomeItem>> =
        runCatching {
            // 五组请求并行(总耗时=最慢一组,而不是相加);每组失败独立跳过,不拖垮整页
            coroutineScope {
                val daily = async { client.dailyRecommendPlaylists().getOrNull() }
                val radar = async { client.radarPlaylists().getOrNull() }
                val top = async { toplistCached() }
                val newSongs = async { client.personalizedNewSongs(20).getOrNull() }
                val hq = async { client.highQualityPlaylists().getOrNull()?.playlists }
                buildList {
                    daily.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("每日推荐歌单")) }
                    radar.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("私人雷达")) }
                    top.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("排行榜")) }
                    newSongs.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toSongHomeItem("推荐新歌")) }
                    hq.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("精品歌单")) }
                }
            }
        }

    /** 网易专属:高质量分类标签(主页 chips 用) */
    suspend fun getHighQualityTags(): Result<List<NeteaseHighQualityTag>> = client.highQualityTags()

    /** 网易专属:按分类取精品歌单行(cat=null 即默认"全部"),chip 点击局部换行,不整页重拉;
     *  游标翻两页(≈100 张),内容量对标网易 App 的分类页 */
    suspend fun getHqPlaylistsRow(cat: String?): Result<HomeItem?> =
        client
            .highQualityPlaylistsPaged(cat = cat, pages = 2)
            .map { list -> if (list.isEmpty()) null else list.toPlaylistHomeItem("精品歌单") }

    // ----------------------------------------------------------------------------
    // 主页复用适配:把网易数据映射进上游 HomeScreen 的 Mood/Chart 形状
    // ----------------------------------------------------------------------------

    /** 网易专属:主页 chips 精选标签(全量 34 个太多,精选与 YT mood 语义接近的常用项) */
    val curatedHomeTags =
        listOf("华语", "欧美", "日语", "韩语", "流行", "摇滚", "民谣", "电子", "说唱", "ACG")

    /**
     * 分类区块:高质量标签按 category 全组映射进 YT Mood 形状,分组对标网易 App
     * (0=语种 1=风格 2=场景 3=情感 4=主题,五组全展示)。params 即标签名。
     */
    suspend fun getMoodSections(): Result<Mood?> =
        client.highQualityTags().mapCatching { tags ->
            // 分组顺序与配色对标网易 App 的分类页;stripeColor 仅是标签卡左侧色条
            val groups =
                listOf(
                    0 to ("语种" to 0xFFD43C33),
                    1 to ("风格" to 0xFF4C6EAF),
                    2 to ("场景" to 0xFF3AA675),
                    3 to ("情感" to 0xFFC2753B),
                    4 to ("主题" to 0xFF8A6BB8),
                )
            Mood(
                sections =
                    buildList {
                        groups.forEach { (cat, titleAndColor) ->
                            val (title, color) = titleAndColor
                            val items = tags.filter { it.category == cat }
                            if (items.isNotEmpty()) {
                                add(
                                    MoodSection(
                                        title = title,
                                        items = items.map { MoodItem(title = it.name, params = it.name, stripeColor = color) },
                                    ),
                                )
                            }
                        }
                    },
            )
        }

    /** 标签分类内容(标签→高质量歌单列表),映射进 YT MoodsMomentObject 形状,MoodScreen 直接渲染;
     *  游标翻两页(≈100 张),对标网易 App 分类页的内容量 */
    suspend fun getMoodContent(tag: String): Result<MoodsMomentObject?> =
        client.highQualityPlaylistsPaged(cat = tag, pages = 2).mapCatching { list ->
            if (list.isEmpty()) {
                null
            } else {
                MoodsMomentObject(
                    endpoint = "",
                    header = tag,
                    params = tag,
                    items =
                        listOf(
                            MoodItemShelf(
                                header = tag,
                                contents =
                                    list.map { pl ->
                                        MoodContent(
                                            playlistBrowseId = pl.id.toString(),
                                            subtitle = pl.description.orEmpty(),
                                            thumbnails =
                                                pl.coverUrl?.let {
                                                    listOf(Thumbnail(height = 540, url = it, width = 540))
                                                },
                                            title = pl.name,
                                        )
                                    },
                            ),
                        ),
                )
            }
        }

    // ----------------------------------------------------------------------------
    // HomeRepository 契约实现:与 YT 同名同形状,SourceRoutingHomeRepository 按 selectedSource
    // 选实例 —— ViewModel/Screen 只认 HomeRepository 接口,对音源无感知。
    // ----------------------------------------------------------------------------

    override fun getHomeData(
        params: String?,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> =
        flow {
            val rows =
                if (params.isNullOrEmpty()) {
                    getHome().getOrNull() ?: emptyList()
                } else {
                    // chip 选中态:params=标签 → 该分类高质量歌单(YT mood 同契约)
                    listOfNotNull(getHqPlaylistsRow(params).getOrNull())
                }
            emit(Resource.Success(null to rows)) // 一次性拉取,无 continuation
        }

    override fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> = flowOf(Resource.Success(null to emptyList()))

    override fun getNewRelease(
        newReleaseString: String,
        musicVideoString: String,
    ): Flow<Resource<List<HomeItem>>> = flowOf(Resource.Success(emptyList())) // feed 已含"推荐新歌"行

    override fun getChartData(countryCode: String): Flow<Resource<Chart>> =
        flow {
            // 契约:必须发射至少一次 —— 空数据发 Success(null),调用方置空区块;
            // 空流会让路由侧的 .first() 抛 NoSuchElementException 炸主线程
            getHomeChart().fold(
                onSuccess = { data -> emit(if (data != null) Resource.Success(data) else Resource.Error("netease: empty")) },
                onFailure = { emit(Resource.Error(it.message ?: "netease error")) },
            )
        }

    override fun getMoodAndMomentsData(): Flow<Resource<Mood>> =
        flow {
            getMoodSections().fold(
                onSuccess = { data -> emit(if (data != null) Resource.Success(data) else Resource.Error("netease: empty")) },
                onFailure = { emit(Resource.Error(it.message ?: "netease error")) },
            )
        }

    override fun getMoodCategoryArtwork(params: String): Flow<String?> = flowOf(null)

    override fun getGenreData(params: String): Flow<Resource<GenreObject>> =
        flowOf(Resource.Error("netease: genre browse not applicable"))

    override fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>> =
        flow {
            getMoodContent(params).fold(
                onSuccess = { data -> emit(if (data != null) Resource.Success(data) else Resource.Error("netease: empty")) },
                onFailure = { emit(Resource.Error(it.message ?: "netease error")) },
            )
        }

    /** 图表区块:网易排行榜映射进 YT Chart 形状(榜单卡点击进歌单;地区下拉对网易隐藏 → countries=null);
     *  数据走 [toplistCached],与 feed 的"排行榜"行共用一份请求 */
    suspend fun getHomeChart(): Result<Chart?> =
        runCatching {
            val list = toplistCached() ?: return@runCatching null
            if (list.isEmpty()) {
                null
            } else {
                Chart(
                    artists = Artists(arrayListOf(), Any()),
                    countries = null,
                    listChartItem =
                        listOf(
                            ChartItemPlaylist(
                                title = "排行榜",
                                playlists =
                                    list.map { pl ->
                                        ResultPlaylist(
                                            id = pl.id.toString(),
                                            author = "",
                                            thumbnails = pl.coverUrl.toThumbnails(),
                                            title = pl.name,
                                        )
                                    },
                            ),
                        ),
                )
            }
        }

    override suspend fun getLibraryPlaylists(): Result<List<PlaylistEntity>> {
        val account = client.getAccountStatus().getOrNull() ?: return Result.success(emptyList())
        if (account == null || account.userId == 0L) return Result.success(emptyList())
        return client.userPlaylists(account.userId).map { list -> list.map { it.toPlaylistEntity() } }
    }

    override suspend fun getPlaylistSongs(playlistId: String): Result<List<SongEntity>> {
        val id = playlistId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("netease playlistId 非数字: $playlistId"))
        val detail = client.playlistDetail(id).getOrNull() ?: return Result.failure(IllegalStateException("歌单不存在: $playlistId"))
        return client
            .playlistTracks(id, limit = detail.first.trackCount.coerceAtLeast(1))
            .map { list -> list.map(NeteaseSong::toSongEntity) }
    }

    override suspend fun getDailyPicks(): Pair<List<SongEntity>, List<PlaylistEntity>>? {
        val songs = client.dailyRecommendSongs().getOrNull() ?: return null
        val playlists = client.dailyRecommendPlaylists().getOrNull() ?: emptyList()
        if (songs.isEmpty() && playlists.isEmpty()) return null
        return songs.map(NeteaseSong::toSongEntity) to playlists.map { it.toPlaylistEntity() }
    }

    override suspend fun getRadarPlaylists(): List<PlaylistEntity>? =
        client.radarPlaylists().getOrNull()?.map { it.toPlaylistEntity() }

    override suspend fun getPersonalRadio(seed: PlaylistEntity?): ProviderRadioSession? =
        client.personalRadio().getOrNull()?.let {
            ProviderRadioSession(songs = it.songs.map(NeteaseSong::toSongEntity))
        }

    override suspend fun likeSong(
        songId: String,
        like: Boolean,
    ): Result<Boolean> {
        val id = songId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("netease songId 非数字: $songId"))
        return client.likeSong(id, like)
    }
}

// ----------------------------------------------------------------------------
// DTO → YTM 形状实体映射
// ----------------------------------------------------------------------------

private const val VIDEO_TYPE_SONG = "MUSIC_VIDEO_TYPE_ATV"

internal fun NeteaseSong.toSongEntity(): SongEntity =
    SongEntity(
        videoId = id.toString(),
        source = MusicSource.NETEASE.name,
        albumId = albumId?.toString(),
        albumName = albumName,
        // C 档(歌手详情)未实现前 artistId 留空:播放页点歌手会静默无效(与无艺人信息的
        // 现有歌曲一致),TODO(NETEASE_C_TIER) 打通艺人页后再填。
        artistId = emptyList(),
        artistName = artists,
        duration = durationMs.toMinutesSeconds(),
        durationSeconds = (durationMs / 1000).toInt(),
        isAvailable = hasCopyright ?: true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = coverUrl,
        title = name,
        videoType = VIDEO_TYPE_SONG,
        category = null,
        resultType = "song",
    )

internal fun NeteasePlaylist.toPlaylistEntity(): PlaylistEntity =
    PlaylistEntity(
        id = id.toString(),
        source = MusicSource.NETEASE.name,
        author = null,
        description = description.orEmpty(),
        duration = "",
        durationSeconds = 0,
        thumbnails = coverUrl.orEmpty(),
        title = name,
        trackCount = trackCount,
        tracks = null,
    )

private fun List<NeteasePlaylist>.toPlaylistHomeItem(title: String): HomeItem =
    HomeItem(
        title = title,
        contents =
            map { pl ->
                Content(
                    album = null,
                    artists = null,
                    description = pl.description,
                    isExplicit = false,
                    playlistId = pl.id.toString(),
                    browseId = null,
                    thumbnails = pl.coverUrl.toThumbnails(),
                    title = pl.name,
                    videoId = null,
                    views = pl.playCount?.toString(),
                )
            },
    )

private fun List<NeteaseSong>.toSongHomeItem(title: String): HomeItem =
    HomeItem(
        title = title,
        contents =
            map { song ->
                Content(
                    album = null,
                    artists = null,
                    description = null,
                    isExplicit = false,
                    playlistId = null,
                    browseId = null,
                    thumbnails = song.coverUrl.toThumbnails(),
                    title = song.name,
                    videoId = song.id.toString(),
                    views = null,
                    durationSeconds = (song.durationMs / 1000).toInt(),
                )
            },
    )

private fun String?.toThumbnails(): List<Thumbnail> =
    takeUnless { it.isNullOrEmpty() }?.let {
        listOf(Thumbnail(height = 540, url = it, width = 540))
    } ?: emptyList()

internal fun Long.toMinutesSeconds(): String {
    val totalSeconds = this / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
