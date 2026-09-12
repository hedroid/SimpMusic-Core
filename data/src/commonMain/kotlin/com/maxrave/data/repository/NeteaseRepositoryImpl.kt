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
import com.maxrave.netease.NeteaseConstants
import com.maxrave.netease.dailyRecommendPlaylists
import com.maxrave.netease.dailyRecommendSongs
import com.maxrave.netease.highQualityPlaylists
import com.maxrave.netease.categoryPlaylists
import com.maxrave.netease.categoryPlaylistsPaged
import com.maxrave.netease.playlistCatalog
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
import com.maxrave.netease.playlistTracksViaDetail
import com.maxrave.netease.songDetail
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

    /** 首页 feed 会话级缓存(NeriPlayer 同思路:短时间内重进主页不全刷,10 分钟过期) */
    private var homeCache: Pair<List<HomeItem>, kotlin.time.TimeMark>? = null

    /** force=true 绕过缓存(下拉刷新用):瞬时失败缺行的结果不能被缓存钉住 */
    override suspend fun getHome(force: Boolean): Result<List<HomeItem>> =
        runCatching {
            if (!force) {
                @OptIn(kotlin.time.ExperimentalTime::class)
                homeCache?.let { (rows, mark) ->
                    if (mark.elapsedNow() < 10.minutes) return Result.success(rows)
                }
            }
            fetchHome().also { rows ->
                @OptIn(kotlin.time.ExperimentalTime::class)
                homeCache = rows to TimeSource.Monotonic.markNow()
            }
        }

    private suspend fun fetchHome(): List<HomeItem> =
        runCatching {
            // 五组请求并行(总耗时=最慢一组,而不是相加);每组失败独立跳过,不拖垮整页
            coroutineScope {
                val daily = async { client.dailyRecommendPlaylists().getOrNull() }
                // NeriPlayer 结构:私人雷达=该歌单曲目按歌曲展示;雷达歌单=5 张雷达歌单卡。
                // /playlist/track/all 对雷达这类特殊歌单不稳定(实测可能返回空),
                // 兜底走 detail.trackIds → songDetail 两步
                val radarSongs =
                    async {
                        val direct =
                            client.playlistTracks(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID, limit = 30)
                                .getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                        if (direct != null) {
                            direct
                        } else {
                            // 雷达 trackIds 是 -10000 占位,songDetail 无效;走带 n 的 detail 直取 tracks
                            val viaDetail =
                                client.playlistTracksViaDetail(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID, limit = 30).getOrNull()
                            if (viaDetail.isNullOrEmpty()) {
                                com.maxrave.logger.Logger.w("NeteaseHome", "radar songs: track/all and viaDetail both empty")
                            }
                            viaDetail.orEmpty()
                        }
                    }
                val radarLists =
                    async {
                        // 只留时光/宝藏/新歌/乐迷/神秘五张卡,私人雷达已作为歌曲行呈现
                        client.radarPlaylists().getOrNull()?.filter { it.id != NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID }
                    }
                val newSongs = async { client.personalizedNewSongs(30).getOrNull() }
                val hq = async { client.highQualityPlaylists().getOrNull()?.playlists }
                // 雷达歌单 ID 集:每日推荐接口会把私人雷达混进来,从 daily 行剔除避免重复
                val radarIds =
                    setOf(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID) + NeteaseConstants.RADAR_PLAYLISTS.map { it.first }
                buildList {
                    daily.await()?.filter { it.id !in radarIds }?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("每日推荐歌单")) }
                    radarSongs.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toSongHomeItem("私人雷达")) }
                    radarLists.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("雷达歌单")) }
                    // 行序:推荐新歌(3 行网格)排在精品歌单下面
                    hq.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("精品歌单")) }
                    newSongs.await()?.takeIf { it.isNotEmpty() }?.let { add(it.toSongHomeItem("推荐新歌")) }
                }
            }
        }.getOrElse { emptyList() }

    /** 网易专属:高质量分类标签(主页 chips 用) */
    suspend fun getHighQualityTags(): Result<List<NeteaseHighQualityTag>> = client.highQualityTags()

    /** 网易专属:按分类取歌单行(chip 选中态整页换行;网页版分类页同源,热度排序),
     *  翻两页 ≈100 张;行标题用标签名 */
    suspend fun getHqPlaylistsRow(cat: String?): Result<HomeItem?> =
        client
            .categoryPlaylistsPaged(cat = cat ?: "全部", pages = 2)
            .map { list -> if (list.isEmpty()) null else list.toPlaylistHomeItem(cat ?: "全部歌单") }

    // ----------------------------------------------------------------------------
    // 主页复用适配:把网易数据映射进上游 HomeScreen 的 Mood/Chart 形状
    // ----------------------------------------------------------------------------

    /** 目录缓存(分类体系进程内基本不变,省去 chips/分区块重复拉取) */
    private var catalogCache: List<Pair<String, List<com.maxrave.netease.NeteaseCatalogTag>>>? = null

    private suspend fun catalogCached(): List<Pair<String, List<com.maxrave.netease.NeteaseCatalogTag>>> =
        catalogCache ?: client.playlistCatalog().getOrNull()?.also { catalogCache = it } ?: emptyList()

    /**
     * 网易专属:主页 chips = 固定 8 个高频分类快捷(方案1:快捷方式,与下方目录是
     * "精选 vs 全量"关系,目录不因 chips 占用而缺项 —— 与网页版/YT 的双入口惯例一致)
     */
    val curatedHomeTags =
        listOf("华语", "欧美", "日语", "韩语", "流行", "摇滚", "说唱", "ACG")

    suspend fun getHotChips(): List<String> = curatedHomeTags

    /**
     * 分类区块:网页版 discover/playlist 的分类目录(weapi /playlist/catalogue)默认视图 ——
     * 每组只显示热门子类(hot=true,网页默认同款),全页 ≈20 张卡;
     * 全量目录的完整入口后续以独立"全部分类"页承接。
     */
    suspend fun getMoodSections(): Result<Mood?> =
        runCatching {
            val groups = catalogCached()
            val colors = listOf(0xFFD43C33, 0xFF4C6EAF, 0xFF3AA675, 0xFFC2753B, 0xFF8A6BB8)
            Mood(
                sections =
                    groups.mapIndexed { index, (title, tags) ->
                        // hot 优先,补足到 15 个;chips 是"快捷方式"不算重复(网页版惯例)
                        val ordered = tags.sortedByDescending { it.hot }
                        MoodSection(
                            title = title,
                            items =
                                ordered.take(15).map {
                                    MoodItem(title = it.name, params = it.name, stripeColor = colors[index % colors.size])
                                },
                        )
                    }.filter { it.items.isNotEmpty() },
            )
        }

    /** 标签分类内容(标签→分类歌单列表,网页版 discover/playlist?cat=xxx 同源,热度排序),
     *  映射进 YT MoodsMomentObject 形状,MoodScreen 直接渲染;翻两页 ≈100 张 */
    suspend fun getMoodContent(tag: String): Result<MoodsMomentObject?> =
        client.categoryPlaylistsPaged(cat = tag, pages = 2).mapCatching { list ->
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
                                            subtitle = pl.description.sanitizeCardSubtitle().orEmpty(),
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
                    // chip 选中态:多行 feed 对标 YT mood 态 —— 热门/最新/精品三行并行
                    categoryFeedRows(params)
                }
            emit(Resource.Success(null to rows)) // 一次性拉取,无 continuation
        }

    /** 标签选中态的三行 feed:热门(播放量序)/最新(上架序)/精品,各 50 张并行拉取 */
    private suspend fun categoryFeedRows(tag: String): List<HomeItem> =
        coroutineScope {
            val hot =
                async {
                    client.categoryPlaylistsPaged(cat = tag, pages = 1, order = "hot").getOrNull() ?: emptyList()
                }
            val new =
                async {
                    client.categoryPlaylists(cat = tag, limit = 50, offset = 0, order = "new").getOrNull()?.playlists ?: emptyList()
                }
            val hq =
                async {
                    client.highQualityPlaylistsPaged(cat = tag, pages = 1).getOrNull() ?: emptyList()
                }
            buildList {
                hot.await().takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("$tag · 热门")) }
                new.await().takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("$tag · 最新")) }
                hq.await().takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("$tag · 精品")) }
            }
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

    /**
     * 歌单详情数据(数字 id=网易歌单),映射进 YT PlaylistBrowse 形状 ——
     * PlaylistScreen/PlaylistViewModel 零改动,数据源切换对页面透明。
     * 曲目:track/all 优先(普通歌单稳定),空则带 n 的 detail 兜底(雷达类特殊歌单)。
     */
    suspend fun getPlaylistBrowseData(playlistId: String): Result<Pair<com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse, String?>> =
        runCatching {
            val id = playlistId.toLongOrNull() ?: error("netease playlistId 非数字: $playlistId")
            val (meta, trackIds) =
                client.playlistDetail(id).getOrNull() ?: error("歌单不存在: $playlistId")
            val tracks =
                client.playlistTracks(id, limit = 500).getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: client.playlistTracksViaDetail(id, limit = 500).getOrNull().orEmpty()
            if (tracks.isEmpty() && trackIds.isEmpty()) error("歌单曲目为空: $playlistId")
            val browse =
                com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse(
                    author =
                        com.maxrave.domain.data.model.browse.playlist.Author(
                            id = meta.creatorId?.toString() ?: "",
                            name = meta.creatorNickname ?: "网易云音乐",
                        ),
                    description = meta.description,
                    duration = "",
                    durationSeconds = 0,
                    id = meta.id.toString(),
                    privacy = "PUBLIC",
                    thumbnails = meta.coverUrl.toThumbnails(),
                    title = meta.name,
                    trackCount = meta.trackCount,
                    tracks = tracks.map { it.toTrackPlaylist() },
                    year = "",
                )
            browse to null // 一次性返回全部曲目,无 continuation
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
                    // 推荐接口的 description 常是 "0"/"1"/"2" 这类数字,清掉回退到"播放列表"占位
                    description = pl.description.sanitizeCardSubtitle(),
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

/** 歌单卡副标题净化:纯数字/空白视为无描述(网易推荐接口的 description 是 0/1/2 序号) */
private fun String?.sanitizeCardSubtitle(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

/** NeteaseSong → 歌单页曲目形状(PlaylistBrowse.tracks = browse.album.Track) */
internal fun NeteaseSong.toTrackPlaylist(): com.maxrave.domain.data.model.browse.album.Track =
    com.maxrave.domain.data.model.browse.album.Track(
        album =
            com.maxrave.domain.data.model.searchResult.songs.Album(
                id = albumId?.toString() ?: "",
                name = albumName ?: "",
            ),
        artists =
            artists.mapIndexed { index, name ->
                com.maxrave.domain.data.model.searchResult.songs.Artist(
                    id = artistIds.getOrNull(index)?.toString(),
                    name = name,
                )
            },
        duration = durationMs.toMinutesSeconds(),
        durationSeconds = (durationMs / 1000).toInt(),
        isAvailable = hasCopyright ?: true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = coverUrl.toThumbnails(),
        title = name,
        videoId = id.toString(),
        videoType = "MUSIC_VIDEO_TYPE_ATV",
        category = null,
        feedbackTokens = null,
        resultType = "song",
    )

private fun String?.toThumbnails(): List<Thumbnail> =
    takeUnless { it.isNullOrEmpty() }?.let {
        listOf(Thumbnail(height = 540, url = it, width = 540))
    } ?: emptyList()

internal fun Long.toMinutesSeconds(): String {
    val totalSeconds = this / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
