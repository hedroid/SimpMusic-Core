package com.maxrave.data.repository

import DatabaseDao
import com.maxrave.domain.data.entities.NeteaseAccountEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.domain.data.entities.NeteaseSongInfoEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.common.NETEASE_PLAYLIST_PAGE_PREFIX
import com.maxrave.common.NETEASE_PLAYLIST_PAGE_SIZE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Artists
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.home.chart.ChartItemPlaylist
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.artist.Albums
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.data.model.browse.artist.Related
import com.maxrave.domain.data.model.browse.artist.ResultAlbum
import com.maxrave.domain.data.model.browse.artist.ResultRelated
import com.maxrave.domain.data.model.browse.artist.ResultSong
import com.maxrave.domain.data.model.browse.artist.Songs
import com.maxrave.domain.data.model.browse.artist.ResultPlaylist
import com.maxrave.domain.data.model.browse.artist.ResultSingle
import com.maxrave.domain.data.model.browse.artist.Singles
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.domain.data.model.mood.MoodSection
import com.maxrave.domain.data.model.mood.moodmoments.Content as MoodContent
import com.maxrave.domain.data.model.mood.moodmoments.Item as MoodItemShelf
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.data.model.searchResult.SearchSuggestions
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
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
import com.maxrave.netease.createPlaylist
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
import com.maxrave.netease.model.NeteaseAlbum
import com.maxrave.netease.model.NeteaseArtist
import com.maxrave.netease.model.NeteaseHighQualityTag
import com.maxrave.netease.model.NeteaseHotWord
import com.maxrave.netease.model.NeteasePlaylist
import com.maxrave.netease.model.NeteaseQuality
import com.maxrave.netease.model.NeteaseSong
import com.maxrave.netease.personalRadio
import com.maxrave.netease.similarSongs
import com.maxrave.netease.scrobble
import com.maxrave.netease.songComments
import com.maxrave.netease.NeteaseLyricsConverter
import com.maxrave.netease.searchAlbums
import com.maxrave.netease.albumDetail
import com.maxrave.netease.artistDetail
import com.maxrave.netease.artistSongs
import com.maxrave.netease.artistAlbums
import com.maxrave.netease.artistDynamic
import com.maxrave.netease.artistFollowerCount
import com.maxrave.netease.similarArtists
import com.maxrave.netease.topArtists
import com.maxrave.netease.newAlbums
import com.maxrave.netease.newSongsExpress
import com.maxrave.netease.playRecord
import com.maxrave.netease.radioTrash
import com.maxrave.netease.subscribeArtist
import com.maxrave.netease.subscribedArtists
import com.maxrave.netease.songRedCount
import com.maxrave.netease.userStaredAlbums
import com.maxrave.netease.personalizedNewSongs
import com.maxrave.netease.playlistDetail
import com.maxrave.netease.playlistTracks
import com.maxrave.netease.playlistTracksViaDetail
import com.maxrave.netease.songDetail
import com.maxrave.netease.userLikedSongIds
import com.maxrave.netease.removeFromPlaylist
import com.maxrave.netease.addToPlaylist
import com.maxrave.netease.subscribeAlbum
import com.maxrave.netease.subscribePlaylist
import com.maxrave.netease.deletePlaylist
import com.maxrave.netease.radarPlaylists
import com.maxrave.netease.searchArtists
import com.maxrave.netease.searchHot
import com.maxrave.netease.searchPlaylists
import com.maxrave.netease.searchSongs
import com.maxrave.netease.searchSuggest
import com.maxrave.netease.songUrl
import com.maxrave.netease.toplistPlaylists
import com.maxrave.netease.userPlaylists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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

    /**
     * 空态分类卡封面:该分类最热一张歌单的封面。三级获取:
     * 1. 进程内存缓存(含失败标记);
     * 2. DataStore 持久缓存(与 YT 共用 [DataStoreManager.moodArtworkCache],键空间不相交:
     *    YT=browse params 串,网易=分类名;7 天 TTL);
     * 3. 受频控保护的网络回填——网易 /playlist/list 有 405 频控,裸逐卡请求会把 tag 页的
     *    同接口请求打挂(实测),所以必须:串行+锁内 delay 限速、会话预算、失败熔断
     *    (任一请求异常/无数据即停,保证 tag 页永远优先活下来)。
     * 持久缓存让请求"一分类一周最多一次",跨会话累积,几天正常使用即全页有图。
     */
    private val artworkMutex = Mutex()
    private val artworkCache = mutableMapOf<String, String?>()
    private var artworkBudget = 25
    private var artworkTripped = false
    private var persistedArtwork: Map<String, NeteaseMoodArtwork>? = null

    private suspend fun categoryArtworkCached(params: String): String? =
        artworkMutex.withLock {
            artworkCache[params]?.let { return it } // 双检:排队期间可能已被填上
            persistedArtwork ?: readPersistedArtwork().also { persistedArtwork = it }
            persistedArtwork?.get(params)?.takeIf { !it.isStale() }?.let {
                artworkCache[params] = it.url
                return it.url // 持久缓存命中:零请求
            }
            if (artworkTripped || artworkBudget <= 0) return null
            artworkBudget--
            delay(400) // 锁内限速:请求天然串行,间隔拉开频控窗口
            val result = client.categoryPlaylists(cat = params, limit = 1).getOrNull()
            val cover = result?.playlists?.firstOrNull()?.coverUrl
            if (result == null || cover == null) artworkTripped = true
            artworkCache[params] = cover
            if (cover != null) {
                val map =
                    (persistedArtwork ?: emptyMap()) +
                        (params to NeteaseMoodArtwork(cover, Clock.System.now().toEpochMilliseconds()))
                persistedArtwork = map
                runCatching { dataStoreManager.setMoodArtworkCache(artworkJson.encodeToString(map)) }
            }
            cover
        }

    private suspend fun readPersistedArtwork(): Map<String, NeteaseMoodArtwork> =
        dataStoreManager.moodArtworkCache
            .first()
            ?.let { runCatching { artworkJson.decodeFromString<Map<String, NeteaseMoodArtwork>>(it) }.getOrNull() }
            .orEmpty()

    /** tag 页数据到手后回填该分类封面(首张歌单封面,零额外请求);写穿内存与持久两层 */
    private suspend fun rememberTagArtwork(
        tag: String,
        coverUrl: String?,
    ) {
        if (coverUrl == null) return
        artworkMutex.withLock {
            artworkCache[tag] = coverUrl
            val map =
                (persistedArtwork ?: emptyMap()) +
                    (tag to NeteaseMoodArtwork(coverUrl, Clock.System.now().toEpochMilliseconds()))
            persistedArtwork = map
            runCatching { dataStoreManager.setMoodArtworkCache(artworkJson.encodeToString(map)) }
        }
    }


    private companion object {
        const val TAG = "NeteaseRepo"
        const val NETEASE_SEARCH_PAGE_SIZE = 30
        const val NETEASE_SEARCH_OFFSET_PREFIX = "offset:"
    }

    private val json = Json { ignoreUnknownKeys = true }

    val client =
        NeteaseClient(
            cookieProvider = { loadPersistedCookies() },
            cookieSaver = { cookies -> persistCookies(cookies) },
        )

    override val isLoggedIn: Flow<Boolean> =
        dataStoreManager.neteaseCookie.map { it.isNotEmpty() && it != "{}" && it.contains("MUSIC_U") }

    /**
     * 账户表自愈:cookie 在 DataStore、netease_account 表却没有对应行(典型成因:开发期
     * 清数据库只清了 Room 不清 DataStore)——账户管理页会显示"无账户"但功能又是登录态。
     * 拉一次账号摘要把行补回去;cookie 失效则不补,由正式登录流程重建。
     */
    suspend fun repairAccountRowIfMissing() {
        val cookies = loadPersistedCookies()
        if (!client.hasLoginCookie(cookies)) return
        val accounts = dao.getAllNeteaseAccount()
        if (accounts.isNotEmpty()) return
        val account = client.getAccountStatus().getOrNull() ?: return
        dao.insertNeteaseAccount(
            NeteaseAccountEntity(
                userId = account.userId,
                nickname = account.nickname ?: "NetEase user",
                avatarUrl = account.avatarUrl ?: "",
                cookies = json.encodeToString(cookies),
                isUsed = true,
            ),
        )
        dataStoreManager.setNeteaseAccountName(account.nickname ?: "NetEase user")
        dataStoreManager.setNeteaseAccountThumbUrl(account.avatarUrl ?: "")
        Logger.w(TAG, "repaired missing netease_account row for ${account.userId}")
    }

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
    ): Result<String?> = getStreamInfo(songId, isDownload).map { it?.url }

    /** 取流附带格式(mimeType/level),供播放页 codec 徽章与 NewFormat 缓存使用 */
    suspend fun getStreamInfo(
        songId: String,
        isDownload: Boolean,
    ): Result<NeteaseStreamInfo?> {
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
        // 由调用方按 neteaseUnavailableAction 设置决定后续动作(跳过/暂停/回退 YT)。
        val order = NeteaseQuality.FALLBACK_ORDER.dropWhile { it != wanted }
        // 请求失败(网络抖动/网易频控)≠灰歌:带增量退避重试(NeriPlayer 同款语义),别把可恢复
        // 的瞬时失败一次性判成"不可播放"。灰歌(响应成功但无 url/全试听)不进重试分支。
        var retriesLeft = 2
        chainLoop@ while (true) {
            for (level in order) {
                val result = client.songUrl(id, level).getOrNull()
                if (result == null) {
                    if (retriesLeft > 0) {
                        val backoffMs = 1500L * (3 - retriesLeft)
                        retriesLeft--
                        Logger.w(TAG, "songUrl request failed ($songId @${level.key}), retry in ${backoffMs}ms ($retriesLeft left)")
                        delay(backoffMs)
                        continue@chainLoop
                    }
                    return Result.success(null)
                }
                val url = result.url
                if (!url.isNullOrEmpty() && result.freeTrialInfo == null) {
                    // CDN 签发的链接是 http://,Android 默认禁明文流量(ExoPlayer 报 Source error),
                    // music.126.net 的 CDN 支持 https,统一升级
                    return Result.success(
                        NeteaseStreamInfo(
                            url = url.replaceFirst("http://", "https://"),
                            mimeType = result.mimeType,
                            level = level.key,
                        ),
                    )
                }
            }
            return Result.success(null)
        }
    }

    /**
     * 网易歌可播性探针(播放失败的分流判定):读 songDetail 的 privilege 版权态 + song.fee。
     * 返回 null=请求失败(网络断/频控),调用方按普通播放错误处理,别误跳歌;
     * NO_COPYRIGHT=灰歌;PAYWALLED=VIP 专属/需购专辑(非会员同样取不到完整流)。
     * PLAYABLE=服务端认为可播,取流失败大概率是瞬时的,走既有错误路径。
     */
    suspend fun probeNeteasePlayable(songId: String): NeteasePlayability? {
        val id = songId.toLongOrNull() ?: return null
        val song = client.songDetail(listOf(id)).getOrNull()?.firstOrNull() ?: return null
        return when {
            song.hasCopyright == false -> NeteasePlayability.NO_COPYRIGHT
            song.fee == 1 || song.fee == 4 -> NeteasePlayability.PAYWALLED
            else -> NeteasePlayability.PLAYABLE
        }
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

    /** 网易官方歌词包:原文(yrc 逐字→words 内嵌 <MM:SS.mm> 标记/lrc 行级)+官方中文翻译+官方罗马音。
     *  罗马音(romalrc)与翻译同为行级、与原文同源时间轴;缺失时为 null,渲染端回退本地罗马音引擎。
     *  原文/翻译任一存在即成功;全空(冷门歌无歌词)返回 failure 走 LRCLIB 兜底。 */
    suspend fun getNeteaseLyricsData(songId: String): Result<Triple<com.maxrave.domain.data.model.metadata.Lyrics, com.maxrave.domain.data.model.metadata.Lyrics?, com.maxrave.domain.data.model.metadata.Lyrics?>> =
        runCatching {
            val id = songId.toLongOrNull() ?: error("netease songId 非数字: $songId")
            val raw = client.lyric(id).getOrNull() ?: error("歌词请求失败: $songId")
            // 优先 yrc(逐字);Word 只带 charCount,逐字文字由整行 text 按 charCount 切片
            val content = raw.yrc ?: raw.lrc ?: error("网易无歌词: $songId")
            val original = NeteaseLyricsConverter.parseAuto(content)
            check(original.isNotEmpty()) { "网易无歌词: $songId" }
            val hasYrc = NeteaseLyricsConverter.isYrc(content)
            val lyrics =
                com.maxrave.domain.data.model.metadata.Lyrics(
                    lines =
                        original.map { line ->
                            com.maxrave.domain.data.model.metadata.Line(
                                endTimeMs = line.endMs.toString(),
                                startTimeMs = line.startMs.toString(),
                                // 渲染端的逐字卡拉OK吃的是 words 内嵌 <MM:SS.mm> 时间戳标记
                                // (RichSyncParser,RICH_SYNCED)——syllables 无人消费,别再喂错
                                words = if (hasYrc) toRichSyncWords(line) else line.text,
                            )
                        },
                    // UI 判断用大写字符串(RICH_SYNCED/LINE_SYNCED),小写会退回行级渲染
                    syncType = if (hasYrc) "RICH_SYNCED" else "LINE_SYNCED",
                )
            fun lineLevelLyrics(raw_: String?) =
                raw_?.let { NeteaseLyricsConverter.parseAuto(it) }?.takeIf { it.isNotEmpty() }
                    ?.let { lines ->
                        com.maxrave.domain.data.model.metadata.Lyrics(
                            lines =
                                lines.map { line ->
                                    com.maxrave.domain.data.model.metadata.Line(
                                        endTimeMs = line.endMs.toString(),
                                        startTimeMs = line.startMs.toString(),
                                        words = line.text,
                                    )
                                },
                            syncType = "LINE_SYNCED",
                        )
                    }
            val translated = lineLevelLyrics(raw.translated)
            val romanized = lineLevelLyrics(raw.romanized)
            Triple(lyrics, translated, romanized)
        }

    /** yrc 行 → 渲染端 rich-sync 格式:每个词前内嵌 <MM:SS.mm> 起始标记。
     *  词文字由整行 text 按 Word.charCount 顺序切片;String.format KMP 不存在,手动补零。 */
    private fun toRichSyncWords(line: com.maxrave.netease.model.NeteaseLyricLine): String {
        if (line.words.isEmpty()) return line.text
        val sb = StringBuilder()
        var index = 0
        line.words.forEach { word ->
            val end = (index + word.charCount).coerceAtMost(line.text.length)
            if (end > index) {
                val minutes = word.startMs / 60000
                val seconds = (word.startMs % 60000) / 1000
                val centis = (word.startMs % 1000) / 10
                sb.append('<')
                    .append(minutes.toString().padStart(2, '0')).append(':')
                    .append(seconds.toString().padStart(2, '0')).append('.')
                    .append(centis.toString().padStart(2, '0'))
                    .append('>')
                sb.append(line.text.substring(index, end))
                index = end
            }
        }
        return sb.toString()
    }

    /** 行级懒加载用的行内容缓存条目 */
    private class RowCache<T> {
        var value: T? = null
        var mark: kotlin.time.TimeMark? = null

        @OptIn(kotlin.time.ExperimentalTime::class)
        fun get(force: Boolean): T? {
            val m = mark ?: return null
            return value?.takeIf { !force && m.elapsedNow() < 10.minutes }
        }

        fun set(v: T) {
            value = v
            mark = TimeSource.Monotonic.markNow()
        }
    }

    private val dailyRowCache = RowCache<HomeItem>()
    private val radarSongsRowCache = RowCache<HomeItem>()
    private val radarListsRowCache = RowCache<HomeItem>()
    private val hqRowCache = RowCache<HomeItem>()
    private val newSongsRowCache = RowCache<HomeItem>()
    private val topArtistsCache = RowCache<ArrayList<ArtistsResult>>()
    private val subArtistsCache = RowCache<ArrayList<ArtistsResult>>()
    private val starredAlbumsCache = RowCache<ArrayList<AlbumsResult>>()

    /** 每日推荐歌单行(滤雷达歌单防重复);null=无内容或失败,行隐藏/可重试 */
    suspend fun getDailyPlaylistsRow(force: Boolean = false): HomeItem? {
        dailyRowCache.get(force)?.let { return it }
        val radarIds =
            setOf(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID) + NeteaseConstants.RADAR_PLAYLISTS.map { it.first }
        val list =
            client.dailyRecommendPlaylists().getOrNull()
                ?.filter { it.id !in radarIds }
                .orEmpty()
        return list.toPlaylistHomeItem("每日推荐歌单").also { dailyRowCache.set(it) }
    }

    /** 私人雷达行(歌曲行):track/all 对雷达不稳定,双路兜底走带 n 的 detail 直取 */
    suspend fun getRadarSongsRow(force: Boolean = false): HomeItem? {
        radarSongsRowCache.get(force)?.let { return it }
        val direct =
            client.playlistTracks(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID, limit = 30)
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        val songs =
            direct
                ?: client.playlistTracksViaDetail(NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID, limit = 30).getOrNull().orEmpty()
        if (direct == null && songs.isEmpty()) {
            com.maxrave.logger.Logger.w("NeteaseHome", "radar songs: track/all and viaDetail both empty")
            return null
        }
        return songs.toSongHomeItem("私人雷达").also { radarSongsRowCache.set(it) }
    }

    /** 雷达歌单行(5 张卡,私人雷达已作歌曲行呈现) */
    suspend fun getRadarPlaylistsRow(force: Boolean = false): HomeItem? {
        radarListsRowCache.get(force)?.let { return it }
        val list =
            client.radarPlaylists().getOrNull()
                ?.filter { it.id != NeteaseConstants.RADAR_PRIVATE_PLAYLIST_ID }
                .orEmpty()
        return list.toPlaylistHomeItem("雷达歌单").also { radarListsRowCache.set(it) }
    }

    /** 精品歌单行(与 getHqPlaylistsRow(cat) 重载,行级懒加载版) */
    suspend fun getHqPlaylistsRow(force: Boolean = false): HomeItem? {
        hqRowCache.get(force)?.let { return it }
        val list = client.highQualityPlaylists().getOrNull()?.playlists.orEmpty()
        return list.toPlaylistHomeItem("精品歌单").also { hqRowCache.set(it) }
    }

    /** 推荐新歌行 */
    suspend fun getNewSongsRow(force: Boolean = false): HomeItem? {
        newSongsRowCache.get(force)?.let { return it }
        val list = client.personalizedNewSongs(30).getOrNull().orEmpty()
        return list.toSongHomeItem("推荐新歌").also { newSongsRowCache.set(it) }
    }

    override suspend fun getHome(force: Boolean): Result<List<HomeItem>> =
        runCatching {
            // 五行并行;各自独立 10min 会话缓存(行级懒加载后此契约方法仅供路由仓库兜底)
            coroutineScope {
                listOf(
                    async { getDailyPlaylistsRow(force) },
                    async { getRadarSongsRow(force) },
                    async { getRadarPlaylistsRow(force) },
                    async { getHqPlaylistsRow(force) },
                    async { getNewSongsRow(force) },
                ).mapNotNull { it.await() }
            }
        }

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

    /** 标签分类内容(tag 页/MoodScreen 通用):热门/精品两档 + 会话缓存。
     *  缓存 key=order:tag,10 分钟 TTL(歌单广场按天级变化),Mutex 单飞防同页并发重复拉取;
     *  force=true(下拉刷新)绕过。热门=/playlist/list order=hot,精品=highquality 游标分页
     *  (order=new 服务端不支持,实测返回空表,别加)。取到的首张歌单封面顺手回填分类卡持久缓存。 */
    private val tagMutex = Mutex()
    private val tagCache = mutableMapOf<String, Pair<List<NeteasePlaylist>, kotlin.time.TimeMark>>()

    @OptIn(ExperimentalTime::class)
    suspend fun getTagContent(
        tag: String,
        order: NeteaseTagOrder = NeteaseTagOrder.HOT,
        force: Boolean = false,
    ): Result<MoodsMomentObject?> =
        tagMutex.withLock {
            val key = "$order:$tag"
            if (!force) {
                tagCache[key]?.let { (list, mark) ->
                    if (mark.elapsedNow() < 10.minutes && list.isNotEmpty()) {
                        return@withLock Result.success(list.toMoodsMomentObject(tag))
                    }
                }
            }
            val fetched =
                when (order) {
                    NeteaseTagOrder.HOT -> client.categoryPlaylistsPaged(cat = tag, pages = 2, order = "hot")
                    NeteaseTagOrder.HQ -> client.highQualityPlaylistsPaged(cat = tag, pages = 2)
                }
            fetched.mapCatching { list ->
                if (list.isNotEmpty()) {
                    tagCache[key] = list to TimeSource.Monotonic.markNow()
                    rememberTagArtwork(tag, list.first().coverUrl)
                }
                list.toMoodsMomentObject(tag)
            }
        }

    /** 标签分类内容(旧入口,热度序):委托 [getTagContent],走同一份缓存 */
    suspend fun getMoodContent(tag: String): Result<MoodsMomentObject?> = getTagContent(tag, NeteaseTagOrder.HOT)

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

    /** 标签选中态的两行 feed:热门/精品,各 50 张并行拉取(order=new 服务端不支持返回空,
     *  实测后移除——别加回来) */
    private suspend fun categoryFeedRows(tag: String): List<HomeItem> =
        coroutineScope {
            val hot =
                async {
                    client.categoryPlaylistsPaged(cat = tag, pages = 1, order = "hot").getOrNull() ?: emptyList()
                }
            val hq =
                async {
                    client.highQualityPlaylistsPaged(cat = tag, pages = 1).getOrNull() ?: emptyList()
                }
            buildList {
                hot.await().takeIf { it.isNotEmpty() }?.let { add(it.toPlaylistHomeItem("$tag · 热门")) }
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

    override fun getMoodCategoryArtwork(params: String): Flow<String?> =
        flow {
            emit(categoryArtworkCached(params))
        }

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
     * 曲目分页:trackIds 真实可用时 **trackIds → songDetail 分片**(首页 [NETEASE_PLAYLIST_PAGE_SIZE] 首,
     * 滚动经 NETEASE_PL_PAGE_{offset} 令牌续拉,任意大小歌单都吃得下;track/all 端点
     * 2026-09-15 复测已 404,不再依赖);trackIds 不可用(雷达类特殊形状)→ 带 n 的
     * detail 直取一次全量,无分页。
     */
    suspend fun getPlaylistBrowseData(playlistId: String): Result<Pair<com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse, String?>> =
        runCatching {
            val id = playlistId.toLongOrNull() ?: error("netease playlistId 非数字: $playlistId")
            val (meta, trackIds) =
                client.playlistDetail(id).getOrNull() ?: error("歌单不存在: $playlistId")
            meta.subscribed?.let { playlistSubscribedCache = playlistSubscribedCache + (id to it) }
            val firstPage =
                if (trackIds.isNotEmpty()) {
                    client.songDetail(trackIds.take(NETEASE_PLAYLIST_PAGE_SIZE)).getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            // trackIds 路径失败(全空)才走带 n 的 detail 兜底(雷达类,一次给全)
            val tracks =
                firstPage.ifEmpty {
                    client.playlistTracksViaDetail(id, limit = NETEASE_PLAYLIST_PAGE_SIZE).getOrNull().orEmpty()
                }
            if (tracks.isEmpty() && trackIds.isEmpty()) error("歌单曲目为空: $playlistId")
            // 歌曲行红心:云村 likedIds(OR 合并缓存,null=未登录视作空)
            val likedIds = likedIdsOrNull().orEmpty()
            // 令牌 = 下一页 offset。trackIds.size 是权威总数(探针实测 songDetail 响应
            // 顺序与输入一致,个别失效 id 缺席不影响 offset 推进)。
            val continuation =
                if (firstPage.isNotEmpty() && trackIds.size > NETEASE_PLAYLIST_PAGE_SIZE) {
                    "$NETEASE_PLAYLIST_PAGE_PREFIX$NETEASE_PLAYLIST_PAGE_SIZE"
                } else {
                    null
                }
            val browse =
                com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse(
                    // creator 是账号不是歌手(creatorId 传去歌手页必报"歌手不存在"),
                    // id 恒置空串 → PlaylistScreen 的 isNotEmpty 守卫令作者名不可点
                    author =
                        com.maxrave.domain.data.model.browse.playlist.Author(
                            id = "",
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
                    tracks = tracks.map { it.toTrackPlaylist(likedIds.contains(it.id)) },
                    // 年份取歌单创建年份(YT 歌单页同位置语义)
                    year = meta.createTimeMs?.let { (it / 31_536_000_000L + 1970).toString() } ?: "",
                )
            browse to continuation
        }

    /**
     * 网易歌单分页续拉(getContinueTrack 的 NETEASE_PL_PAGE_{offset} 令牌落地):
     * 重取 playlistDetail(n=0,只回 id 列表,轻)拿权威 trackIds,按 offset 切片 songDetail。
     * 返回 (本页歌曲, 下页 offset);切到末尾即 null。
     */
    suspend fun getPlaylistTracksPage(
        playlistId: Long,
        offset: Int,
    ): Result<Pair<List<NeteaseSong>, Int?>> =
        runCatching {
            val (_, trackIds) = client.playlistDetail(playlistId).getOrThrow()
            require(trackIds.isNotEmpty()) { "trackIds 不可用: $playlistId" }
            val slice = trackIds.drop(offset).take(NETEASE_PLAYLIST_PAGE_SIZE)
            // songDetail 响应顺序与输入一致,但失效 id 会缺席——按 slice 顺序回填防乱序
            val byId = client.songDetail(slice).getOrThrow().associateBy { it.id }
            val ordered = slice.mapNotNull { byId[it] }
            val nextOffset = if (offset + slice.size < trackIds.size) offset + slice.size else null
            ordered to nextOffset
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

    /** 最近一次 getLibraryPlaylists 的 歌单id→创建者id 映射(判定自建/收藏歌单的依据) */
    @Volatile private var libraryCreatorIds: Map<Long, Long?> = emptyMap()

    /** 最近一次歌单详情的 订阅态缓存(歌单id→云端是否已收藏);浏览歌单页红心回填用 */
    @Volatile private var playlistSubscribedCache: Map<Long, Boolean> = emptyMap()

    /** 红心歌单("我喜欢的音乐")id;不可删除/不可取消收藏,库页与详情页入口都排除它 */
    @Volatile private var neteaseLikedPlaylistId: Long? = null

    override suspend fun getLibraryPlaylists(): Result<List<PlaylistEntity>> {
        val account = client.getAccountStatus().getOrNull()
        if (account == null) {
            Logger.w(TAG, "getLibraryPlaylists: account status failed")
            return Result.success(emptyList())
        }
        if (account.userId == 0L) {
            Logger.w(TAG, "getLibraryPlaylists: userId=0 (profile missing?), nickname=${account.nickname}")
            return Result.success(emptyList())
        }
        return client.userPlaylists(account.userId).map { list ->
            Logger.w(TAG, "getLibraryPlaylists: uid=${account.userId} size=${list.size}")
            // 红心歌单固定首位:userPlaylists 已标 specialType=FAVORITE,稳定排序兜底服务端乱序
            libraryCreatorIds = list.associate { it.id to it.creatorId }
            neteaseLikedPlaylistId =
                list.firstOrNull { it.specialType == NeteasePlaylist.SpecialType.FAVORITE }?.id
            list.sortedBy { it.specialType != NeteasePlaylist.SpecialType.FAVORITE }.map { it.toPlaylistEntity() }
        }
    }

    /**
     * 该网易歌单是否当前账号自建(creatorId==uid)。依赖 [getLibraryPlaylists] 留下的
     * 创建者缓存;未知歌单 fail-closed 返回 false(收藏入口/移除歌曲等敏感操作不露出)。
     */
    suspend fun isOwnNeteasePlaylist(playlistId: String): Boolean {
        val id = playlistId.toLongOrNull() ?: return false
        val uid = client.getAccountStatus().getOrNull()?.userId ?: return false
        if (uid == 0L) return false
        return libraryCreatorIds[id] == uid
    }

    /** 自建网易歌单 ID 集(库页长按菜单:仅收藏歌单露出"取消收藏") */
    suspend fun getOwnNeteasePlaylistIds(): Set<String> {
        val uid = client.getAccountStatus().getOrNull()?.userId ?: return emptySet()
        if (uid == 0L) return emptySet()
        return libraryCreatorIds.filterValues { it == uid }.keys.map { it.toString() }.toSet()
    }

    /** Current account's editable cloud playlists, for the shared add-to-playlist sheet. */
    suspend fun getOwnNeteasePlaylists(): List<PlaylistsResult> {
        val account = client.getAccountStatus().getOrNull() ?: return emptyList()
        if (account.userId == 0L) return emptyList()
        return client.userPlaylists(account.userId).getOrNull()
            .orEmpty()
            .filter {
                it.creatorId == account.userId && it.specialType == NeteasePlaylist.SpecialType.NORMAL
            }
            .map { it.toPlaylistsResult() }
    }

    suspend fun addTracksToNeteasePlaylist(
        playlistId: String,
        songIds: List<String>,
    ): Result<Boolean> =
        runCatching {
            val pid = playlistId.toLongOrNull() ?: error("Invalid NetEase playlist id")
            val ids = songIds.mapNotNull(String::toLongOrNull)
            client.addToPlaylist(pid, ids).getOrThrow()
        }

    /** 创建自己的网易歌单(隐私),返回新歌单 id——本地歌单同步上云的建单步骤。 */
    suspend fun createNeteasePlaylist(name: String): Result<String> =
        client.createPlaylist(name).map { it.toString() }

    /**
     * 建单成功的单体回读:/v6/playlist/detail → 与库列表(userPlaylists)完全同一
     * [toPlaylistEntity] 映射的权威行(封面/创建者昵称/曲目数),库页本地插入用它
     * 保证与下次全量拉回的行同构,样式不跳变。失败返回 null,调用方退占位行。
     * 顺带把创建者缓存补上,新歌单的"自建"判定(收藏入口/移除歌曲)即刻生效。
     */
    suspend fun getNeteasePlaylistAsLibraryRow(playlistId: String): PlaylistEntity? {
        val id = playlistId.toLongOrNull() ?: return null
        val playlist = client.playlistDetail(id).getOrNull()?.first ?: return null
        libraryCreatorIds = libraryCreatorIds + (playlist.id to playlist.creatorId)
        return playlist.toPlaylistEntity()
    }

    /** 拉自己歌单的全部 trackIds(增量同步的差集基准);null = 拉取失败。 */
    suspend fun getNeteasePlaylistTrackIds(playlistId: String): List<Long>? {
        val id = playlistId.toLongOrNull() ?: return null
        return client.playlistDetail(id).getOrNull()?.second
    }

    /**
     * 云端收藏态(歌单页红心回填):详情缓存命中直接回,miss 打一次 n=0 详情(轻)。
     * null = 未知(未登录/拉取失败/列表项无该字段),调用方保持本地不动。
     */
    suspend fun getPlaylistSubscribed(playlistId: String): Boolean? {
        val id = playlistId.toLongOrNull() ?: return null
        playlistSubscribedCache[id]?.let { return it }
        val sub = client.playlistDetail(id).getOrNull()?.first?.subscribed ?: return null
        playlistSubscribedCache = playlistSubscribedCache + (id to sub)
        return sub
    }

    /** 红心歌单 id 缓存读取(库页长按入口排除用);未拉过库则 null */
    fun getNeteaseLikedPlaylistIdCached(): String? = neteaseLikedPlaylistId?.toString()

    /** 是否红心歌单("我喜欢的音乐"):不可删除、不可取消收藏 */
    fun isNeteaseLikedPlaylist(playlistId: String): Boolean =
        neteaseLikedPlaylistId != null && playlistId.toLongOrNull() == neteaseLikedPlaylistId

    /**
     * 删除自己的网易歌单(/playlist/delete,不可逆)。红心歌单由 UI 层排除
     * (服务端也会拒绝,这里再兜一层)。
     */
    suspend fun deleteNeteasePlaylist(playlistId: String): Result<Boolean> =
        runCatching {
            val id = playlistId.toLongOrNull() ?: error("netease playlistId 非数字: $playlistId")
            require(!isNeteaseLikedPlaylist(id.toString())) { "红心歌单不可删除" }
            client.deletePlaylist(id).getOrThrow()
        }

    /** 收藏/取消收藏网易歌单(/playlist/subscribe);取消后本地最近添加里的行由调用方处理 */
    suspend fun subscribeNeteasePlaylist(
        playlistId: String,
        subscribe: Boolean,
    ): Result<Boolean> =
        playlistId.toLongOrNull()
            ?.let { id ->
                client.subscribePlaylist(id, subscribe).onSuccess { ok ->
                    if (ok) playlistSubscribedCache = playlistSubscribedCache + (id to subscribe)
                }
            }
            ?: Result.failure(IllegalArgumentException("netease playlistId 非数字: $playlistId"))

    /** 收藏/取消收藏网易专辑(/album/sub) */
    suspend fun subscribeNeteaseAlbum(
        albumId: String,
        subscribe: Boolean,
    ): Result<Boolean> =
        albumId.toLongOrNull()
            ?.let { client.subscribeAlbum(it, subscribe) }
            ?: Result.failure(IllegalArgumentException("netease albumId 非数字: $albumId"))

    /** 从自己的网易歌单移除歌曲(manipulate op=del);仅自建歌单可用,服务端对他人歌单返回错误码 */
    suspend fun removeTracksFromNeteasePlaylist(
        playlistId: String,
        songIds: List<String>,
    ): Result<Boolean> =
        runCatching {
            val pid = playlistId.toLongOrNull() ?: error("netease playlistId 非数字: $playlistId")
            val ids = songIds.mapNotNull { it.toLongOrNull() }
            require(ids.isNotEmpty()) { "songIds 为空" }
            client.removeFromPlaylist(pid, ids).getOrThrow()
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

    override suspend fun getSongRadio(
        songId: String,
        limit: Int,
        offset: Int,
    ): ProviderRadioSession? {
        val id = songId.toLongOrNull() ?: return null
        val songs =
            client.similarSongs(id, limit = limit, offset = offset).getOrNull() ?: return null
        return ProviderRadioSession(songs = songs.map(NeteaseSong::toSongEntity))
    }

    override suspend fun likeSong(
        songId: String,
        like: Boolean,
    ): Result<Boolean> {
        val id = songId.toLongOrNull() ?: return Result.failure(IllegalArgumentException("netease songId 非数字: $songId"))
        return client.likeSong(id, like)
    }

    // ---------------------------------------------------------------- 云村红心(播放页云端喜欢按钮)

    /**
     * "我喜欢的音乐"曲目 id 集,会话级缓存:云村红心没有单曲状态查询接口,只能
     * 拉一次红心歌单的 trackIds(playlistDetail 不带 tracks,足够轻)。null = 未登录
     * 或首拉失败,调用方按"未登录"处理(隐藏按钮)。
     */
    private val likedIdsMutex = Mutex()
    private var likedSongIds: Pair<Set<Long>, kotlin.time.TimeMark>? = null

    /**
     * 云村红心 ID 全量,10min TTL + Mutex 单飞:会话内切歌零请求,跨会话/超时回刷,
     * 网易 App 里改的红心几分钟能回流(此前会话级缓存永不回刷)。数据源
     * /song/like/get 全量 ids(一次请求,比"红心歌单→playlistDetail"链路轻)。
     * null = 未登录/拉取失败,调用方保持本地状态。
     */
    private suspend fun likedIdsOrNull(): Set<Long>? {
        likedSongIds?.let { (ids, at) -> if (at.elapsedNow() < 10.minutes) return ids }
        return likedIdsMutex.withLock {
            likedSongIds?.let { (ids, at) -> if (at.elapsedNow() < 10.minutes) return ids }
            val account = client.getAccountStatus().getOrNull() ?: return null
            if (account.userId == 0L) return null
            val ids = client.userLikedSongIds(account.userId).getOrNull() ?: return null
            likedSongIds = ids.toSet() to TimeSource.Monotonic.markNow()
            ids.toSet()
        }
    }

    /** 云村红心状态;null = 无账号/拉取失败(按钮隐藏),非 null = 歌在不在红心歌单 */
    suspend fun isSongLiked(songId: String): Boolean? {
        val id = songId.toLongOrNull() ?: return null
        return likedIdsOrNull()?.contains(id)
    }

    /** 播放上报(切歌时听满阈值才调):喂推荐引擎/播放量,未登录由调用方拦住 */
    suspend fun scrobble(
        songId: String,
        timeMs: Long,
    ): Result<Boolean> {
        val id = songId.toLongOrNull() ?: return Result.success(false)
        return client.scrobble(id, timeMs)
    }

    /** 切换云村红心,成功后同步缓存,让下一次切歌回来的状态立刻正确 */
    suspend fun setSongLiked(
        songId: String,
        like: Boolean,
    ): Result<Boolean> {
        val result = likeSong(songId, like)
        val id = songId.toLongOrNull()
        if (result.isSuccess && id != null) {
            likedIdsMutex.withLock {
                // 只改集合保留时间戳:刚拉过的缓存继续按原 TTL 过期
                likedSongIds?.let { (ids, at) ->
                    likedSongIds = (if (like) ids + id else ids - id) to at
                }
            }
        }
        return result
    }

    /**
     * 评论分页页(播放页评论列表弹窗):首页优先热评,后续页走最新评论;
     * 返回 (条目, hasMore)。
     */
    suspend fun getSongCommentsPage(
        songId: String,
        limit: Int,
        offset: Int,
    ): Pair<List<NeteaseSongInfoEntity.HotComment>, Boolean>? {
        val id = songId.toLongOrNull() ?: run {
            com.maxrave.logger.Logger.w("NeteaseComments", "page: songId not numeric: '$songId'")
            return null
        }
        val result = client.songComments(id, limit = limit, offset = offset)
        val page =
            result.getOrNull() ?: run {
                com.maxrave.logger.Logger.w("NeteaseComments", "page fetch failed: ${result.exceptionOrNull()?.message}")
                return null
            }
        val source = page.hotComments.ifEmpty { page.latestComments }
        com.maxrave.logger.Logger.w("NeteaseComments", "page ok: ${source.size} items, hasMore=${page.hasMore}")
        val items =
            source.map {
                NeteaseSongInfoEntity.HotComment(
                    nickname = it.nickname,
                    avatarUrl = it.avatarUrl,
                    content = it.content,
                    likedCount = it.likedCount,
                    location = it.location,
                )
            }
        val hasMore = page.hasMore || items.size >= limit
        return items to hasMore
    }

    /**
     * 播放页网易详情卡:艺人(头像/粉丝)、专辑(发行日/简介)、互动(红心/评论)五路数据
     * 并行,各自独立降级——哪路失败哪路留空,整卡不因单路失败消失。
     */
    suspend fun getSongInfo(
        songId: String,
        artistId: String?,
        albumId: String?,
    ): NeteaseSongInfoEntity {
        val sid = songId.toLongOrNull() ?: return NeteaseSongInfoEntity()
        val aid = artistId?.toLongOrNull()
        val alid = albumId?.toLongOrNull()
        return coroutineScope {
            val artistDef = async { aid?.let { client.artistDetail(it).getOrNull() } }
            val followerDef = async { aid?.let { client.artistFollowerCount(it).getOrNull() } }
            val albumDef = async { alid?.let { client.albumDetail(it).getOrNull() } }
            val commentDef = async { client.songComments(sid, limit = 10, offset = 0).getOrNull() }
            val likeCountDef = async { client.songRedCount(sid).getOrNull() }
            val artist = artistDef.await()
            val followerCount = followerDef.await()
            val album = albumDef.await()
            val comments = commentDef.await()
            val likeCount = likeCountDef.await()
            NeteaseSongInfoEntity(
                artistId = aid?.toString(),
                artistName = artist?.name,
                // head/info/get 的 avatar 是小尺寸头像,直接铺 973px 宽的艺人卡横幅会糊+裁切
                // 感明显;toNeteaseCoverUrl 整段换 query 成 ?param=1080y1080(裸追加会被
                // 服务端自带处理链忽略,同歌单封面模糊根因)
                artistAvatar =
                    artist?.picUrl
                        ?.replaceFirst("http://", "https://")
                        ?.toNeteaseCoverUrl(1080),
                artistFans = followerCount,
                albumId = alid?.toString(),
                albumName = album?.first?.name,
                albumPublishDate =
                    album?.first?.publishTimeMs?.let { ms ->
                        Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date.toString()
                    },
                albumDescription = album?.first?.description?.takeIf { it.isNotBlank() },
                albumTrackCount = album?.first?.trackCount?.takeIf { it > 0 },
                albumCompany = album?.first?.company,
                artistBriefDesc = artist?.briefDesc?.takeIf { it.isNotBlank() },
                likeCount = likeCount,
                commentCount = comments?.totalCount ?: 0,
                hotComments =
                    comments?.hotComments
                        .orEmpty()
                        .sortedByDescending { it.likedCount ?: 0 }
                        .take(2)
                        .map {
                            NeteaseSongInfoEntity.HotComment(
                                nickname = it.nickname,
                                avatarUrl = it.avatarUrl,
                                content = it.content,
                                likedCount = it.likedCount,
                                location = it.location,
                            )
                        },
            )
        }
    }

    // ---------------------------------------------------------------- search (M3 搜索页)

    /** 搜歌曲 → YT SongsResult 形状(搜索页行组件直接渲染;videoId=数字 ID 原文,播放走数字路由取流) */
    suspend fun searchSongsResult(query: String): Result<ArrayList<SongsResult>> =
        client.searchSongs(query).map { r -> ArrayList(r.items.map { it.toSongsResult() }) }

    /** 网易单曲直取(songDetail → Track):deeplink 分享回流(simpmusic://watch?v=<数字id>)在
     *  本地无缓存行时的取曲路径——等价 YT getFullMetadata 的角色。失败返回 null。 */
    suspend fun getNeteaseSongTrack(songId: String): Track? {
        val id = songId.toLongOrNull() ?: return null
        val song = client.songDetail(listOf(id)).getOrNull()?.firstOrNull() ?: return null
        return song.toSongsResult().toTrack()
    }

    /** 搜歌曲分页页(SONGS tab 加载更多):token="offset:<n>"(null=首页);翻完发 null。
     *  more 判定=offset+本页条数<songCount(拿不到 total 时退"满页即有下一页")。 */
    suspend fun searchSongsPage(
        query: String,
        pageToken: String?,
    ): Result<Pair<ArrayList<SongsResult>, String?>> {
        val offset = pageToken?.removePrefix(NETEASE_SEARCH_OFFSET_PREFIX)?.toIntOrNull() ?: 0
        return client.searchSongs(query, limit = NETEASE_SEARCH_PAGE_SIZE, offset = offset).map { r ->
            val items = ArrayList(r.items.map { it.toSongsResult() })
            val hasMore =
                r.totalCount?.let { total -> offset + r.items.size < total }
                    ?: (r.items.size >= NETEASE_SEARCH_PAGE_SIZE)
            items to if (hasMore) "$NETEASE_SEARCH_OFFSET_PREFIX${offset + r.items.size}" else null
        }
    }

    /** 搜歌单 → PlaylistsResult;resultType 置非 "Podcast",点击稳定走 PlaylistDestination(与主页同页) */
    suspend fun searchPlaylistsResult(query: String): Result<ArrayList<PlaylistsResult>> =
        client.searchPlaylists(query).map { r -> ArrayList(r.items.map { it.toPlaylistsResult() }) }

    /** 搜歌手 → ArtistsResult(M6 艺人页未通前,UI 点击提示即将支持) */
    suspend fun searchArtistsResult(query: String): Result<ArrayList<ArtistsResult>> =
        client.searchArtists(query).map { r -> ArrayList(r.items.map { it.toArtistsResult() }) }

    /** 搜索建议:歌曲+艺人实体卡(该端点无词联想,queries 恒空) */
    suspend fun searchSuggestData(query: String): Result<SearchSuggestions> =
        client.searchSuggest(query).map { s ->
            SearchSuggestions(
                queries = emptyList(),
                recommendedItems = s.songs.map { it.toSongsResult() } + s.artists.map { it.toArtistsResult() },
            )
        }

    /** 热搜词榜(搜索空态页) */
    suspend fun searchHotWords(): Result<List<NeteaseHotWord>> = client.searchHot()

    // ---------------------------------------------------------------- M5 混合页:私人FM

    /** 私人FM 首批(单批 3 首,页内横滑增量由 [fetchMoreFmContents] 承接,别回到一次多批)。
     *  不复用 toSongHomeItem:那个 artists=null,FM 卡要显示艺人行;videoId=数字 ID 原文。 */
    suspend fun getPersonalFmContents(): Result<List<Content>> =
        client.personalRadio().map { session ->
            session.songs.distinctBy { it.id }.map { it.toMixContent() }
        }

    /** FM 增量:拉一批(**一次请求**)按排除集去重后返回,可能少于 3 首甚至为空(整批
     *  撞重复不自动重试,下次滑到尾/下拉再试)——保证"一次拉取=一次请求=一批"。 */
    suspend fun fetchMoreFmContents(excludeVideoIds: Set<String>): List<Content> =
        client.personalRadio().getOrNull()
            ?.songs
            .orEmpty()
            .filter { it.id.toString() !in excludeVideoIds }
            .map { it.toMixContent() }

    /** FM 垃圾桶:服务端标记不感兴趣,返回补位歌(响应 data[0];拿不到由 [fetchMoreFmContents] 兜底) */
    suspend fun trashFmSong(songId: String): Result<Content?> =
        songId.toLongOrNull()
            ?.let { client.radioTrash(it) }
            ?.mapCatching { replacement -> replacement?.toMixContent() }
            ?: Result.failure(IllegalArgumentException("netease songId 非数字: $songId"))

    /** 红心电台:红心歌单随机 30 首(本地洗牌)。/playmode/intelligence/list 心动模式端点
     *  对第三方已全面 500(weapi/eapi/明文,见 PITFALLS),此为本地替代:红心随机起播,
     *  队列挂 FM 哨兵播完自动接私人FM 续批。 */
    suspend fun getHeartRadioContents(): Result<List<Content>> =
        runCatching {
            val account = client.getAccountStatus().getOrNull()?.takeIf { it.userId != 0L }
                ?: error("未登录网易云")
            val liked = client.userLikedSongIds(account.userId).getOrNull().orEmpty()
            val picked = liked.shuffled().take(30)
            check(picked.isNotEmpty()) { "红心歌单为空" }
            client.songDetail(picked).getOrNull().orEmpty().map { it.toMixContent() }
        }

    /** 每日推荐 30 首(需登录)→ Content 列表。主页只放了每日推荐歌单,这 30 首歌是
     *  混合页的增量数据。日更新缓存:服务端每天 0 点换一批——当日命中会话缓存或
     *  DataStore 持久缓存(进程重启也免拉),跨日/force 才走网络。Mutex 单飞防并发双拉。
     *  注意端点 afresh=true 会每次重掷一版,force 调用须节制(下拉刷新不重拉本区)。 */
    suspend fun getDailyRecommendContents(force: Boolean = false): Result<List<Content>> =
        dailyMutex.withLock {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()
            if (!force) {
                val cached = dailySessionCache ?: readPersistedDailyCache()?.also { dailySessionCache = it }
                if (cached != null && cached.epochDay == today && cached.songs.isNotEmpty()) {
                    return@withLock Result.success(cached.songs.map { it.toMixContent() })
                }
            }
            client.dailyRecommendSongs().map { songs ->
                val cache =
                    NeteaseDailyCache(
                        epochDay = today,
                        songs =
                            songs.map {
                                NeteaseDailyCacheSong(
                                    id = it.id,
                                    name = it.name,
                                    artists = it.artists,
                                    durationMs = it.durationMs,
                                    coverUrl = it.coverUrl,
                                )
                            },
                    )
                dailySessionCache = cache
                runCatching { dataStoreManager.putString(NETEASE_DAILY_CACHE_KEY, json.encodeToString(cache)) }
                cache.songs.map { it.toMixContent() }
            }
        }

    private suspend fun readPersistedDailyCache(): NeteaseDailyCache? =
        runCatching { dataStoreManager.getString(NETEASE_DAILY_CACHE_KEY).first() }.getOrNull()
            ?.let { runCatching { json.decodeFromString<NeteaseDailyCache>(it) }.getOrNull() }

    private val dailyMutex = Mutex()

    private var dailySessionCache: NeteaseDailyCache? = null

    /** 混合页卡用 Content:填 artists(带 id,贯通播放页艺人跳转);videoId=数字 ID 原文 */
    private fun NeteaseSong.toMixContent(): Content =
        Content(
            album = null,
            artists =
                artists.mapIndexed { index, name ->
                    Artist(id = artistIds.getOrNull(index)?.toString(), name = name)
                },
            description = null,
            isExplicit = false,
            playlistId = null,
            browseId = null,
            thumbnails = coverUrl.toThumbnails(),
            title = name,
            videoId = id.toString(),
            views = null,
            durationSeconds = (durationMs / 1000).toInt(),
        )

    /** 持久缓存条目 → Content(字段同 toMixContent,日缓存命中路径专用) */
    private fun NeteaseDailyCacheSong.toMixContent(): Content =
        Content(
            album = null,
            artists = artists.map { Artist(id = null, name = it) },
            description = null,
            isExplicit = false,
            playlistId = null,
            browseId = null,
            thumbnails = coverUrl.toThumbnails(),
            title = name,
            videoId = id.toString(),
            views = null,
            durationSeconds = (durationMs / 1000).toInt(),
        )

    // ------------------------------------------------ 混合页:新歌速递 + 最近在听

    /** 新歌速递(地区维度,与主页"推荐新歌"个性化不同源):分区会话缓存 10min(key=areaId),
     *  chips 切换不打网络;Mutex 单飞。areaId 取值见 [com.maxrave.netease.newSongsExpress]。 */
    private val expressMutex = Mutex()

    private val expressCache = mutableMapOf<Int, Pair<List<Content>, kotlin.time.TimeMark>>()

    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun getNewSongExpress(
        areaId: Int = 7,
        force: Boolean = false,
    ): Result<List<Content>> =
        expressMutex.withLock {
            if (!force) {
                expressCache[areaId]?.let { (list, mark) ->
                    if (mark.elapsedNow() < 10.minutes && list.isNotEmpty()) {
                        return@withLock Result.success(list)
                    }
                }
            }
            client.newSongsExpress(areaId).map { songs ->
                val contents = songs.map { it.toMixContent() }
                if (contents.isNotEmpty()) expressCache[areaId] = contents to TimeSource.Monotonic.markNow()
                contents
            }
        }

    private var recentCache: Pair<List<Content>, kotlin.time.TimeMark>? = null

    /** 最近在听(网易侧听歌周榜,已按播放次数降序;不含本应用播放——无网易侧回传):
     *  会话缓存 10min;未登录/空榜返回空表。上限 [NETEASE_RECENT_MAX] 条。 */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun getRecentPlayedContents(): Result<List<Content>> =
        runCatching {
            recentCache?.let { (list, mark) ->
                if (mark.elapsedNow() < 10.minutes && list.isNotEmpty()) return@runCatching list
            }
            val account = client.getAccountStatus().getOrNull()?.takeIf { it.userId != 0L }
                ?: error("未登录网易云")
            val list =
                client.playRecord(account.userId).getOrNull().orEmpty()
                    .map { (song, _) -> song.toMixContent() }
                    .take(NETEASE_RECENT_MAX)
            if (list.isNotEmpty()) recentCache = list to TimeSource.Monotonic.markNow()
            list
        }

    // ---------------------------------------------------------------- M6: 专辑/歌手页同页路由

    /** 专辑详情 → AlbumBrowse(AlbumScreen 按数字 browseId 分流到这)。 */
    suspend fun getAlbumBrowseData(albumId: String): Result<AlbumBrowse> =
        runCatching {
            val id = albumId.toLongOrNull() ?: error("netease albumId 非数字: $albumId")
            val (album, songs) = client.albumDetail(id).getOrNull() ?: error("专辑不存在: $albumId")
            AlbumBrowse(
                // artistId 缺失(合辑/官方账号专辑)时置空串,AlbumScreen 的 isNotEmpty 守卫让歌手名不可点
                artists = listOf(Artist(id = album.artistId?.toString() ?: "", name = album.artistName ?: "网易云音乐")),
                audioPlaylistId = "",
                description = album.description,
                duration = "",
                durationSeconds = songs.sumOf { (it.durationMs / 1000).toInt() },
                thumbnails = album.coverUrl.toThumbnails(),
                title = album.name,
                trackCount = songs.size,
                tracks = songs.map { it.toTrackPlaylist(likedIdsOrNull().orEmpty().contains(it.id)) },
                type = "album",
                year = album.publishTimeMs?.let { (it / 31_536_000_000L + 1970).toString() } ?: "",
            )
        }

    /** 歌手详情 → ArtistBrowse(ArtistScreen 按数字 channelId 分流到这):
     *  详情+热门歌曲50+专辑30+相似歌手并行;电台/随机播/单曲/MV 是 YT 专属,置 null 隐藏。 */
    suspend fun getArtistBrowseData(artistId: String): Result<ArtistBrowse> =
        runCatching {
            val id = artistId.toLongOrNull() ?: error("netease artistId 非数字: $artistId")
            coroutineScope {
                val detailDeferred = async { client.artistDetail(id).getOrNull() }
                val songsDeferred = async { client.artistSongs(id).getOrNull() }
                val albumsDeferred = async { client.artistAlbums(id).getOrNull() }
                val similarDeferred = async { client.similarArtists(id).getOrNull() }
                val dynamicDeferred = async { client.artistDynamic(id).getOrNull() }
                val detail = detailDeferred.await() ?: error("歌手不存在: $artistId")
                val songs = songsDeferred.await()?.items.orEmpty()
                val albums = albumsDeferred.await()?.first.orEmpty()
                val similar = similarDeferred.await().orEmpty()
                // 按 type 拆单曲/专辑两组(与 getArtistMoreAlbums 同规则,保持行内与"更多"页一致)
                val singleItems = albums.filter { it.type == "Single" }
                val albumItems = albums.filter { it.type != "Single" }
                ArtistBrowse(
                    albums =
                        if (albumItems.isEmpty()) {
                            null
                        } else {
                            Albums(
                                browseId = id,
                                params = "", // 网易一次性给全,无"更多"页
                                results =
                                    albumItems.map {
                                        ResultAlbum(
                                            browseId = it.id.toString(),
                                            isExplicit = false,
                                            thumbnails = it.coverUrl.toThumbnails(),
                                            title = it.name,
                                            year = it.publishTimeMs?.let { p -> (p / 31_536_000_000L + 1970).toString() } ?: "",
                                        )
                                    },
                            )
                        },
                    channelId = id.toString(),
                    description = detail.briefDesc,
                    name = detail.name,
                    radioId = null,
                    related =
                        if (similar.isEmpty()) {
                            null
                        } else {
                            Related(
                                browseId = id,
                                results =
                                    similar.map {
                                        ResultRelated(
                                            browseId = it.id.toString(),
                                            subscribers = "",
                                            thumbnails = it.picUrl.toThumbnails(),
                                            title = it.name,
                                        )
                                    },
                            )
                        },
                    shuffleId = null,
                    singles =
                        if (singleItems.isEmpty()) {
                            null
                        } else {
                            Singles(
                                browseId = id.toString(),
                                params = "", // 同 albums:网易一次性给全
                                results =
                                    singleItems.map {
                                        ResultSingle(
                                            browseId = it.id.toString(),
                                            thumbnails = it.coverUrl.toThumbnails(),
                                            title = it.name,
                                            year = it.publishTimeMs?.let { p -> (p / 31_536_000_000L + 1970).toString() } ?: "",
                                        )
                                    },
                            )
                        },
                    songs =
                        if (songs.isEmpty()) {
                            null
                        } else {
                            Songs(browseId = null, results = songs.map { it.toResultSong() })
                        },
                    video = null,
                    featuredOn = null,
                    videoList = null,
                    subscribed = dynamicDeferred.await()?.followed,
                    subscribers = null,
                    thumbnails = detail.picUrl.toThumbnails(),
                    views = null,
                )
            }
        }

    /** 关注/取关网易歌手(艺人页按钮;M9 的"YT↔网易关注同步"是另一回事) */
    suspend fun subscribeArtistNetease(
        artistId: String,
        subscribe: Boolean,
    ): Result<Boolean> =
        artistId.toLongOrNull()
            ?.let { client.subscribeArtist(it, subscribe) }
            ?: Result.failure(IllegalArgumentException("netease artistId 非数字: $artistId"))

    /** 网易艺人"全部歌曲"分页页(MoreSongsScreen):/v1/artist/songs 原生 order(hot/time)
     *  +limit/offset。返回 (曲目, hasMore);艺人页人气区只展示热门 50,这里给全量+按时间排序。 */
    suspend fun getArtistSongsPage(
        artistId: String,
        order: String,
        offset: Int,
        limit: Int = 50,
    ): Result<Pair<List<ResultSong>, Boolean>> =
        runCatching {
            val id = artistId.toLongOrNull() ?: error("netease artistId 非数字: $artistId")
            val page = client.artistSongs(id, order = order, limit = limit, offset = offset).getOrThrow()
            val more =
                page.totalCount?.let { total -> offset + page.items.size < total }
                    ?: (page.items.size >= limit)
            page.items.map { it.toResultSong() } to more
        }

    // ---------------------------------------------------------------- 主页 M6 行:热门歌手 + 新碟上架

    /** 热门歌手榜 → ArtistsResult(主页行;点击进艺人页)。行缓存 10min。 */
    suspend fun getTopArtists(
        limit: Int = 30,
        force: Boolean = false,
    ): Result<ArrayList<ArtistsResult>> {
        topArtistsCache.get(force)?.let { return Result.success(it) }
        return client
            .topArtists(limit)
            .map { list -> ArrayList(list.map { it.toArtistsResult() }) }
            .onSuccess { topArtistsCache.set(it) }
    }

    /** 新碟上架 → AlbumsResult(主页行;点击进专辑页,与搜索专辑 tab 同形状)。
     *  分地区缓存 10min(area: ALL/ZH/EA/KR/JP),chips 切换不打网络。 */
    private val newAlbumsAreaCache = mutableMapOf<String, RowCache<ArrayList<AlbumsResult>>>()

    suspend fun getNewAlbums(
        area: String = "ALL",
        limit: Int = 30,
        force: Boolean = false,
    ): Result<ArrayList<AlbumsResult>> {
        val cache = newAlbumsAreaCache.getOrPut(area) { RowCache() }
        cache.get(force)?.let { return Result.success(it) }
        return client
            .newAlbums(area = area, limit = limit)
            .map { list -> ArrayList(list.map { it.toAlbumsResult() }) }
            .onSuccess { cache.set(it) }
    }

    /** 关注的歌手行(/artist/sublist,需登录);行缓存 10min */
    suspend fun getSubscribedArtists(force: Boolean = false): Result<ArrayList<ArtistsResult>> {
        subArtistsCache.get(force)?.let { return Result.success(it) }
        return client
            .subscribedArtists()
            .map { list -> ArrayList(list.map { it.toArtistsResult() }) }
            .onSuccess { subArtistsCache.set(it) }
    }

    /** 收藏的专辑行(/mine/rn/resource/list,需登录);行缓存 10min */
    suspend fun getStarredAlbums(force: Boolean = false): Result<ArrayList<AlbumsResult>> {
        starredAlbumsCache.get(force)?.let { return Result.success(it) }
        val account =
            client.getAccountStatus().getOrNull()?.takeIf { it.userId != 0L }
                ?: return Result.failure(IllegalStateException("未登录网易云"))
        return client
            .userStaredAlbums(account.userId)
            .map { list -> ArrayList(list.map { it.toAlbumsResult() }) }
            .onSuccess { starredAlbumsCache.set(it) }
    }

    /**
     * 艺人页"更多专辑/单曲"(AlbumRepository.getAlbumMore 的 MPAD{数字} 路由):一次 50 张无分页,
     * 按 type 拆不相交的两组(单曲=Single,专辑/EP/未知归专辑组)。不相交是硬约束——
     * NotifyWork 对 ALBUM/SINGLE 两个参数各调一次,同一张发行若同时落两组会发两条重复通知。
     */
    suspend fun getArtistMoreAlbums(
        artistId: Long,
        singles: Boolean = false,
    ): ArrayList<AlbumsResult> =
        ArrayList(
            client.artistAlbums(artistId, limit = 50).getOrNull()?.first.orEmpty()
                .filter { (it.type == "Single") == singles }
                .map { it.toAlbumsResult() },
        )

    /** 搜专辑 → AlbumsResult(搜索 tab;netease 数字 browseId → AlbumScreen 同页路由)。 */
    suspend fun searchAlbumsResult(query: String): Result<ArrayList<AlbumsResult>> =
        client.searchAlbums(query).map { r -> ArrayList(r.items.map { it.toAlbumsResult() }) }
}

// ----------------------------------------------------------------------------
// DTO → YTM 形状实体映射
// ----------------------------------------------------------------------------

/** 分类封面持久缓存条目(与 YT HomeRepositoryImpl.MoodArtwork 同构,共用 moodArtworkCache 存储) */
@Serializable
private data class NeteaseMoodArtwork(
    val url: String,
    val cachedAt: Long,
) {
    fun isStale(): Boolean = Clock.System.now().toEpochMilliseconds() - cachedAt > NETEASE_ARTWORK_TTL_MILLIS
}

private const val NETEASE_ARTWORK_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

/** 每日推荐 30 首的持久缓存形状(混合页;epochDay 用本地日,跨日即失效重拉) */
@Serializable
private data class NeteaseDailyCacheSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val coverUrl: String? = null,
)

@Serializable
private data class NeteaseDailyCache(
    val epochDay: Long,
    val songs: List<NeteaseDailyCacheSong>,
)

private const val NETEASE_DAILY_CACHE_KEY = "netease_daily_songs_cache"

/** 最近在听分区上限(混合页) */
private const val NETEASE_RECENT_MAX = 50

private val artworkJson = Json { ignoreUnknownKeys = true }

/** tag 页排序档位(UI chip ↔ 仓库取数参数)。注意:/playlist/list 的 order 实测只支持 hot,
 *  new 返回空表(weapi/明文皆然)——所以只有热门/精品两档,别再加 NEW。 */
enum class NeteaseTagOrder {
    HOT, // /playlist/list order=hot(网页版歌单广场热度序)
    HQ, // 精品歌单(高质量接口,游标分页)
}

/** 分类歌单列表 → YT MoodsMomentObject 形状(MoodScreen/NeteaseTagScreen 通用渲染) */
private fun List<NeteasePlaylist>.toMoodsMomentObject(tag: String): MoodsMomentObject? =
    if (isEmpty()) {
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
                            map { pl ->
                                MoodContent(
                                    playlistBrowseId = pl.id.toString(),
                                    subtitle = pl.description.sanitizeCardSubtitle().orEmpty(),
                                    thumbnails =
                                        pl.coverUrl?.let {
                                            listOf(Thumbnail(height = 540, url = it.toNeteaseCoverUrl() ?: it, width = 540))
                                        },
                                    title = pl.name,
                                )
                            },
                    ),
                ),
        )
    }

private const val VIDEO_TYPE_SONG = "MUSIC_VIDEO_TYPE_ATV"

/** 网易取流结果带格式:level(档位 key)/mimeType(audio/mpeg|audio/flac),喂 codec 徽章 */
data class NeteaseStreamInfo(
    val url: String,
    val mimeType: String?,
    val level: String?,
)

/** 网易歌可播性(songDetail privilege 判定),喂"无版权歌曲动作"的分流 */
enum class NeteasePlayability {
    /** 有版权且无付费墙 */
    PLAYABLE,

    /** 无版权/下架(灰歌,privilege.st!=0 或 fee=-1) */
    NO_COPYRIGHT,

    /** 有版权但付费墙(VIP 专属 fee=1/专辑购买 fee=4):非会员取不到完整流 */
    PAYWALLED,
}

internal fun NeteaseSong.toSongEntity(): SongEntity =
    SongEntity(
        videoId = id.toString(),
        source = MusicSource.NETEASE.name,
        albumId = albumId?.toString(),
        albumName = albumName,
        // 艺人页 M6 已通:artistId 从此带上(toSong 已解析 ar[].id)。此前留空的年代,
        // simiSong 电台/FM/每日推荐播的歌 artistId 为空串,播放页艺人卡拿不到头像/粉丝,
        // 艺人跳转也静默无效。
        artistId = artistIds.map { it.toString() },
        artistName = artists,
        duration = durationMs.toMinutesSeconds(),
        durationSeconds = (durationMs / 1000).toInt(),
        isAvailable = hasCopyright ?: true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        // 播放页大图/通知栏/FM 卡都吃这里:统一 1080(服务端图片处理链同歌单封面,见 toNeteaseCoverUrl)
        thumbnails = coverUrl.toNeteaseCoverUrl(1080),
        title = name,
        videoType = VIDEO_TYPE_SONG,
        category = null,
        resultType = "song",
    )

internal fun NeteasePlaylist.toPlaylistEntity(): PlaylistEntity =
    PlaylistEntity(
        id = id.toString(),
        source = MusicSource.NETEASE.name,
        // 创建者昵称:库页 tile 副标题显示"创建者"而不是占位"歌单",也方便肉眼区分自建/收藏
        author = creatorNickname,
        description = description.orEmpty(),
        duration = "",
        durationSeconds = 0,
        thumbnails = coverUrl.toNeteaseCoverUrl().orEmpty(),
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
                    artists =
                        song.artists.mapIndexed { index, name ->
                            Artist(id = song.artistIds.getOrNull(index)?.toString(), name = name)
                        },
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
internal fun NeteaseSong.toTrackPlaylist(liked: Boolean = false): com.maxrave.domain.data.model.browse.album.Track =
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
        // 歌单/专辑页歌曲行的红心:吃云村 likedIds(OR 合并缓存),此前恒 INDIFFERENT——
        // 收藏歌单里明明红心过的歌全显示空心("收藏歌单中的红心状态不对")
        likeStatus = if (liked) "LIKE" else "INDIFFERENT",
        thumbnails = coverUrl.toThumbnails(),
        title = name,
        videoId = id.toString(),
        videoType = "MUSIC_VIDEO_TYPE_ATV",
        category = null,
        feedbackTokens = null,
        resultType = "song",
    )

/** 网易封面统一到清晰度安全的尺寸。服务端 URL 常自带 imageView/watermark/thumbnail 处理链
 *  (实测 /playlist/list:链尾 thumbnail=140y140 才是最终生效变换 → 140px 小图+水印,
 *  分类页封面"很模糊"的根因),把 ?param= 追加到既有 query 后会被 CDN 忽略——
 *  必须整段替换 query。裸路径+?param=NNNyNN 是网易 CDN 官方变换格式
 *  (探针实测:140y140=34KB,500y500=390KB)。 */
internal fun String?.toNeteaseCoverUrl(size: Int = 500): String? {
    if (isNullOrEmpty()) return null
    val base = substringBefore('?')
    return "$base?param=${size}y$size"
}

private fun String?.toThumbnails(): List<Thumbnail> =
    toNeteaseCoverUrl()?.let {
        listOf(Thumbnail(height = 540, url = it, width = 540))
    } ?: emptyList()

// ----------------------------------------------------------------------------
// DTO → 搜索页 YT 结果模型映射(M3):videoId/browseId = 网易数字 ID 原文
// ----------------------------------------------------------------------------

internal fun NeteaseSong.toSongsResult(): SongsResult =
    SongsResult(
        album = Album(id = albumId?.toString() ?: "", name = albumName ?: ""),
        artists =
            artists.mapIndexed { index, name ->
                Artist(id = artistIds.getOrNull(index)?.toString(), name = name)
            },
        category = null,
        duration = durationMs.toMinutesSeconds(),
        durationSeconds = (durationMs / 1000).toInt(),
        feedbackTokens = null,
        isExplicit = false,
        resultType = "song",
        thumbnails = coverUrl.toThumbnails(),
        title = name,
        videoId = id.toString(),
        videoType = VIDEO_TYPE_SONG,
        year = Any(),
    )

internal fun NeteasePlaylist.toPlaylistsResult(): PlaylistsResult =
    PlaylistsResult(
        author = creatorNickname ?: "网易云音乐",
        browseId = id.toString(),
        category = "",
        itemCount = trackCount.toString(),
        resultType = "Playlist",
        thumbnails = coverUrl.toThumbnails(),
        title = name,
    )

internal fun NeteaseArtist.toArtistsResult(): ArtistsResult =
    ArtistsResult(
        artist = name,
        browseId = id.toString(),
        category = "",
        radioId = "",
        resultType = "artist",
        shuffleId = "",
        thumbnails = picUrl.toThumbnails(),
    )

/** 专辑 → 搜索形状 AlbumsResult(搜索专辑 tab + 主页新碟上架行共用) */
internal fun NeteaseAlbum.toAlbumsResult(): AlbumsResult =
    AlbumsResult(
        artists = listOfNotNull(artistName?.let { Artist(id = null, name = it) }),
        browseId = id.toString(),
        category = "Album",
        duration = Unit,
        isExplicit = false,
        resultType = "Album",
        thumbnails = coverUrl.toThumbnails(),
        title = name,
        type = "album",
        year = publishTimeMs?.let { (it / 31_536_000_000L + 1970).toString() } ?: "",
    )

/** 歌手热门歌曲 → 艺人页形状 ResultSong */
internal fun NeteaseSong.toResultSong(): ResultSong =
    ResultSong(
        videoId = id.toString(),
        title = name,
        artists =
            artists.mapIndexed { index, name ->
                Artist(id = artistIds.getOrNull(index)?.toString(), name = name)
            },
        durationSeconds = (durationMs / 1000).toInt(),
        album = Album(id = albumId?.toString() ?: "", name = albumName ?: ""),
        likeStatus = "INDIFFERENT",
        thumbnails = coverUrl.toThumbnails(),
        isAvailable = hasCopyright ?: true,
        isExplicit = false,
        videoType = null,
    )

internal fun Long.toMinutesSeconds(): String {
    val totalSeconds = this / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
