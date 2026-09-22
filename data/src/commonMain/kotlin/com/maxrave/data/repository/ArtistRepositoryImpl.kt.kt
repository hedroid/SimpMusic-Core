package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.parser.parseArtistData
import com.maxrave.data.parser.parseLibraryArtistsFromGrid
import com.maxrave.data.parser.parseLibraryArtistsFromShelf
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.getContinuation
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

internal class ArtistRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val dataStoreManager: DataStoreManager,
    private val neteaseRepository: NeteaseRepositoryImpl,
) : ArtistRepository {
    override fun getAllArtists(limit: Int): Flow<List<ArtistEntity>> =
        flow {
            emit(localDataSource.getAllArtists(limit))
        }.flowOn(Dispatchers.IO)

    override fun getArtistById(id: String): Flow<ArtistEntity?> =
        flow {
            emit(localDataSource.getArtist(id))
        }.flowOn(Dispatchers.IO)

    override suspend fun insertArtist(artistEntity: ArtistEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertArtist(artistEntity)
        }

    override suspend fun updateArtistImage(
        channelId: String,
        thumbnail: String,
    ) = withContext(
        Dispatchers.Main,
    ) {
        localDataSource.updateArtistImage(
            channelId,
            thumbnail,
        )
    }

    override suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    ) = withContext(Dispatchers.IO) {
        localDataSource.updateArtistNameLogo(channelId, nameLogoUrl, nameLogoColor)
    }

    /**
     * Unfollowing also drops what only existed to serve the follow.
     *
     * Flipping the flag was all this ever did, so an artist's notifications and their new-releases
     * tracking row survived every unfollow — and once the unfollowed `artist` row is itself swept by
     * `SongRepository.clearHistoryAndOrphanedSongs`, they have nothing left to point back at.
     */
    /** 只写本地关注位(浏览艺人页把服务端关注态落库),不镜像任何账号 */
    override suspend fun setFollowedLocal(
        channelId: String,
        followed: Boolean,
    ) = withContext(Dispatchers.IO) {
        localDataSource.updateFollowed(if (followed) 1 else 0, channelId)
    }

    /** 纯本地关注位:云端账号由 [setRemoteFollowedStatus] 显式操作,不再自动镜像。 */
    override suspend fun updateFollowedStatus(
        channelId: String,
        followedStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateFollowed(followedStatus, channelId)
        if (followedStatus == 0) {
            localDataSource.deleteNotificationsByChannelId(channelId)
            localDataSource.deleteFollowedArtistSingleAndAlbum(channelId)
        }
    }

    override suspend fun setRemoteFollowedStatus(
        channelId: String,
        followed: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (channelId.toLongOrNull() != null) {
                // getOrDefault(false) 而非 isSuccess:endpoint 对业务 code!=200 返回
                // success(false),isSuccess 会把"服务端拒绝"误读为成功(用户实测假成功根源)
                neteaseRepository.subscribeArtistNetease(channelId, followed).getOrDefault(false)
            } else {
                setSubscription(channelId, followed)
            }
        }

    override fun syncFollowedArtistsToYouTube(): Flow<Pair<Int, Int>> =
        flow {
            val followed =
                getFullDataFromDB { limit, offset ->
                    localDataSource.getFollowedArtists(limit, offset)
                }
            // Sequential on purpose. These are writes to someone's account, and firing a few
            // hundred of them at once is exactly the shape that gets a session rate-limited.
            var done = 0
            followed.forEach { artist ->
                if (setSubscription(artist.channelId, true)) done++
            }
            emit(done to followed.size)
        }.flowOn(Dispatchers.IO)

    /** One subscribe/unsubscribe call, logged on failure. Returns whether the account was updated. */
    private suspend fun setSubscription(
        channelId: String,
        subscribe: Boolean,
    ): Boolean {
        val result =
            if (subscribe) {
                youTube.subscribeChannel(channelId)
            } else {
                youTube.unsubscribeChannel(channelId)
            }
        return result
            .onFailure {
                Logger.w("ArtistRepositoryImpl", "Channel subscription sync failed: ${it.message}")
            }.isSuccess
    }

    override fun getFollowedArtists(): Flow<List<ArtistEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getFollowedArtists(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    /** [getYouTubeLibraryArtists] 的 10 分钟内存缓存(与网易 getSubscribedArtists 同策略) */
    private var youTubeLibraryArtistsCache: Pair<List<ArtistEntity>, TimeSource.Monotonic.ValueTimeMark>? = null

    override fun getYouTubeLibraryArtists(force: Boolean): Flow<List<ArtistEntity>?> =
        flow {
            val cached = youTubeLibraryArtistsCache
            if (!force && cached != null && cached.second.elapsedNow() < 10.minutes && cached.first.isNotEmpty()) {
                emit(cached.first)
                return@flow
            }
            youTube
                .getLibraryArtists()
                .onSuccess { data ->
                    // 两种已知形状都收:shelf(musicResponsiveListItemRenderer)/grid(musicTwoRowItemRenderer)
                    val parsed = mutableListOf<com.maxrave.data.parser.LibraryArtistItem>()
                    var fetchComplete = true
                    data.contents?.singleColumnBrowseResultsRenderer?.tabs.orEmpty().forEach { tab ->
                        val sections = tab.tabRenderer.content?.sectionListRenderer?.contents.orEmpty()
                        sections.forEach { content ->
                            val shelf = content.musicShelfRenderer
                            val grid = content.gridRenderer
                            if (shelf != null) {
                                parsed.addAll(
                                    parseLibraryArtistsFromShelf(shelf.contents.orEmpty().mapNotNull { it.musicResponsiveListItemRenderer }),
                                )
                                var continuation = shelf.continuations?.getContinuation()
                                while (continuation != null) {
                                    youTube
                                        .nextLibraryArtists(continuation)
                                        .onSuccess { (shelfItems, gridItems, next) ->
                                            parsed.addAll(parseLibraryArtistsFromShelf(shelfItems))
                                            parsed.addAll(parseLibraryArtistsFromGrid(gridItems))
                                            continuation = next
                                        }.onFailure {
                                            fetchComplete = false
                                            Logger.w("ArtistRepositoryImpl", "library artists continuation error: ${it.message}")
                                            continuation = null
                                        }
                                }
                            }
                            if (grid != null) {
                                parsed.addAll(parseLibraryArtistsFromGrid(grid.items.mapNotNull { it.musicTwoRowItemRenderer }))
                            }
                        }
                    }
                    Logger.w("ArtistRepositoryImpl", "getLibraryArtists parsed ${parsed.size} subscribed artists, fetchComplete=$fetchComplete")
                    if (parsed.isEmpty()) {
                        // 空响应(未登录/形状又变了):回落本地镜像,别把已有分区清掉
                        emit(ytFollowedFromLocal())
                        return@onSuccess
                    }
                    // 云端为准回填:新行 INSERT IGNORE(followed=1),老行补关注位
                    parsed.forEach { item ->
                        localDataSource.insertArtist(
                            ArtistEntity(
                                channelId = item.channelId,
                                name = item.name,
                                thumbnails = item.thumbnailUrl,
                                followed = true,
                            ),
                        )
                        localDataSource.updateFollowed(1, item.channelId)
                    }
                    // 云端取关:拉取完整(翻页无失败)才执行删除方向,半截响应不动本地
                    if (fetchComplete) {
                        val remoteIds = parsed.map { it.channelId }.toSet()
                        val removed = ytFollowedFromLocal().filter { it.channelId !in remoteIds }
                        if (removed.isNotEmpty()) {
                            Logger.w(
                                "ArtistRepositoryImpl",
                                "unfollowing ${removed.size} artists absent from cloud: " +
                                    removed.joinToString { "${it.name}(${it.channelId})" },
                            )
                            removed.forEach { updateFollowedStatus(it.channelId, 0) }
                        }
                    }
                    val merged = ytFollowedFromLocal()
                    youTubeLibraryArtistsCache = merged to TimeSource.Monotonic.markNow()
                    emit(merged)
                }.onFailure { e ->
                    Logger.w("ArtistRepositoryImpl", "getLibraryArtists error: ${e.message}")
                    emit(cached?.first ?: ytFollowedFromLocal())
                }
        }.flowOn(Dispatchers.IO)

    private suspend fun ytFollowedFromLocal(): List<ArtistEntity> =
        getFullDataFromDB { limit, offset ->
            localDataSource.getFollowedArtists(limit, offset)
        }.filter { it.channelId.toLongOrNull() == null }

    override suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateArtistInLibrary(
            inLibrary,
            channelId,
        )
    }

    override fun getArtistData(channelId: String): Flow<Resource<ArtistBrowse>> =
        flow {
            runCatching {
                // 网易歌手:id 为纯数字(YT 恒 UC 前缀),同页面换数据源(M2 歌单同款)
                if (channelId.toLongOrNull() != null) {
                    neteaseRepository
                        .getArtistBrowseData(channelId)
                        .fold(
                            onSuccess = { emit(Resource.Success(it)) },
                            onFailure = { emit(Resource.Error(it.message ?: "netease artist error")) },
                        )
                    return@flow
                }
                youTube
                    .artist(channelId)
                    .onSuccess { result ->
                        emit(Resource.Success<ArtistBrowse>(parseArtistData(result)))
                    }.onFailure { e ->
                        Logger.d("Artist", "Error: ${e.message}")
                        emit(Resource.Error<ArtistBrowse>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)
}
