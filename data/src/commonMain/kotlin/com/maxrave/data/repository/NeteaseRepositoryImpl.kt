package com.maxrave.data.repository

import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.source.MusicSource
import com.maxrave.logger.Logger
import com.maxrave.domain.source.MusicSourceProvider
import com.maxrave.domain.source.ProviderLyrics
import com.maxrave.domain.source.ProviderRadioSession
import com.maxrave.netease.NeteaseClient
import com.maxrave.netease.dailyRecommendPlaylists
import com.maxrave.netease.dailyRecommendSongs
import com.maxrave.netease.highQualityPlaylists
import com.maxrave.netease.likeSong
import com.maxrave.netease.lyric
import com.maxrave.netease.model.NeteaseAccount
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
) : MusicSourceProvider {
    override val source: MusicSource = MusicSource.NETEASE

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
        dataStoreManager.neteaseCookie.map { it.isNotEmpty() }

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
            valid
        }.onFailure {
            // 校验失败则清掉,不留半登录态
            Logger.e(TAG, "saveLoginCookies failed", it)
            logout()
        }
    }

    suspend fun logout() {
        client.logout()
        dataStoreManager.setNeteaseAccountName("")
        dataStoreManager.setNeteaseAccountThumbUrl("")
    }

    private suspend fun loadPersistedCookies(): Map<String, String> =
        runCatching {
            json.decodeFromString<Map<String, String>>(dataStoreManager.neteaseCookie.first())
        }.getOrDefault(emptyMap())

    private suspend fun persistCookies(cookies: Map<String, String>) {
        // NonCancellable:登录收尾协程若被取消,写盘也必须完成(现场日志显示挂起发生在
        // 这条链路上,取消风暴下最稳妥的是不让 DataStore 写入参与取消)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            dataStoreManager.setNeteaseCookie(json.encodeToString(cookies))
        }
    }

    // ---------------------------------------------------------------- MusicSourceProvider

    override suspend fun searchSongs(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<List<SongEntity>> = client.searchSongs(query, limit, offset).map { it.map(NeteaseSong::toSongEntity) }

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
            if (!result.url.isNullOrEmpty() && result.freeTrialInfo == null) {
                return Result.success(result.url)
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
            buildList {
                client.dailyRecommendPlaylists().getOrNull()?.let { list ->
                    if (list.isNotEmpty()) add(list.toPlaylistHomeItem("每日推荐歌单"))
                }
                client.radarPlaylists().getOrNull()?.let { list ->
                    if (list.isNotEmpty()) add(list.toPlaylistHomeItem("私人雷达"))
                }
                client.toplistPlaylists().getOrNull()?.let { list ->
                    if (list.isNotEmpty()) add(list.toPlaylistHomeItem("排行榜"))
                }
                client.personalizedNewSongs(20).getOrNull()?.let { list ->
                    if (list.isNotEmpty()) add(list.toSongHomeItem("推荐新歌"))
                }
                client.highQualityPlaylists().getOrNull()?.let { list ->
                    if (list.isNotEmpty()) add(list.toPlaylistHomeItem("精品歌单"))
                }
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
