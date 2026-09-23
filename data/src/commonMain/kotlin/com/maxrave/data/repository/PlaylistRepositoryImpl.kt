package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toTrack
import com.maxrave.data.mapping.toYouTubeWatchEndpoint
import com.maxrave.data.parser.parseLibraryPlaylist
import com.maxrave.data.parser.parseNextLibraryPlaylist
import com.maxrave.data.parser.ownSavedByMenu
import com.maxrave.data.parser.parsePlaylistData
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioMix
import com.maxrave.domain.utils.toTrack
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistRadioEndpoint
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistShuffleEndpoint
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

internal class PlaylistRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val neteaseRepository: NeteaseRepositoryImpl,
) : PlaylistRepository {
    override fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>> =
        flow {
            emit(localDataSource.getAllPlaylists(limit))
        }.flowOn(Dispatchers.IO)

    override fun getPlaylist(id: String): Flow<PlaylistEntity?> =
        flow {
            emit(localDataSource.getPlaylist(id))
        }.flowOn(Dispatchers.IO)

    override fun getLikedPlaylists(): Flow<List<PlaylistEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedPlaylists(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun insertPlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) { localDataSource.insertPlaylist(playlistEntity) }

    override suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) {
            val oldPlaylist = getPlaylist(playlistEntity.id).firstOrNull()
            if (oldPlaylist != null) {
                localDataSource.insertAndReplacePlaylist(
                    playlistEntity.copy(
                        downloadState = oldPlaylist.downloadState,
                        liked = oldPlaylist.liked,
                    ),
                )
            } else {
                localDataSource.insertAndReplacePlaylist(playlistEntity)
            }
        }

    override suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) { localDataSource.insertRadioPlaylist(playlistEntity) }

    override suspend fun reconcileLikedNeteasePlaylists(cloudIds: Set<String>) {
        if (cloudIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val stale =
                getLikedPlaylists().firstOrNull().orEmpty().filter { row ->
                    row.id.toLongOrNull() != null && row.id !in cloudIds
                }
            stale.forEach {
                Logger.w("Library", "reconcile liked netease: clearing ${it.title}(${it.id})")
                localDataSource.updatePlaylistLiked(0, it.id)
            }
        }
    }

    override suspend fun reconcileLikedYouTubePlaylists(cloudIds: Set<String>) {
        if (cloudIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val normalized = cloudIds.map { it.removePrefix("VL") }.toSet()
            val stale =
                getLikedPlaylists().firstOrNull().orEmpty().filter { row ->
                    row.id.toLongOrNull() == null && row.id.removePrefix("VL") !in normalized
                }
            stale.forEach {
                Logger.w("Library", "reconcile liked youtube: clearing ${it.title}(${it.id})")
                localDataSource.updatePlaylistLiked(0, it.id)
            }
        }
    }

    override suspend fun updatePlaylistLiked(
        playlistId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistLiked(
            likeStatus,
            playlistId,
        )
    }

    override suspend fun getRemoteSavedState(playlistId: String): Boolean? {
        if (playlistId.toLongOrNull() != null) {
            return if (neteaseRepository.isLoggedIn.first()) {
                neteaseRepository.getPlaylistSubscribed(playlistId)
            } else {
                null
            }
        }
        if (dataStoreManager.cookie.first().isEmpty()) return null
        val normalized = playlistId.removePrefix("VL")
        return getLibraryPlaylist().firstOrNull()?.any { it.browseId.removePrefix("VL") == normalized }
    }

    override suspend fun setRemoteSavedState(
        playlistId: String,
        saved: Boolean,
    ): Boolean =
        if (playlistId.toLongOrNull() != null) {
            if (!neteaseRepository.isLoggedIn.first()) {
                false
            } else {
                // 失败抛出(异常消息=服务端原文,如"操作过于频繁"),由 VM 透传 toast
                neteaseRepository.subscribeNeteasePlaylist(playlistId, saved).getOrThrow()
            }
        } else {
            if (dataStoreManager.cookie.first().isEmpty()) false
            // Browse navigation commonly prefixes playlist ids with "VL" (VLPL...). The
            // like/unlike mutation endpoint expects the actual playlist id (PL...), otherwise
            // YouTube responds with a failed action even though the detail page loaded normally.
            else youTube.setPlaylistInLibrary(playlistId.removePrefix("VL"), saved).getOrNull() in 200..299
        }

    override suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistInLibrary(
            inLibrary,
            playlistId,
        )
    }

    override suspend fun updatePlaylistDownloadState(
        playlistId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistDownloadState(
            downloadState,
            playlistId,
        )
    }

    override fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>> =
        flow { emit(localDataSource.getAllDownloadedPlaylist()) }.flowOn(Dispatchers.IO)

    override fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>> =
        flow { emit(localDataSource.getAllDownloadingPlaylist()) }.flowOn(Dispatchers.IO)

    private suspend fun insertSetVideoId(setVideoId: SetVideoIdEntity) = withContext(Dispatchers.IO) { localDataSource.insertSetVideoId(setVideoId) }

    override fun getRadio(
        radioId: String,
        defaultDescription: String,
        radioString: String,
        viewString: String,
        originalTrack: SongEntity?,
        artist: ArtistEntity?,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        if (radioId.isRadioMix()) {
            getRDATRadioData(radioId, viewString)
        } else {
            flow {
                runCatching {
                    youTube
                        .next(endpoint = WatchEndpoint(playlistId = radioId))
                        .onSuccess { next ->
                            Logger.w("Radio", "Title: ${next.title}")
                            val data: ArrayList<SongItem> = arrayListOf()
                            data.addAll(next.items)
                            var continuation = next.continuation
                            Logger.w("Radio", "data: ${data.size}")
                            var count = 0
                            while (continuation != null && count < 3) {
                                youTube
                                    .next(
                                        endpoint = WatchEndpoint(playlistId = radioId),
                                        continuation = continuation,
                                    ).onSuccess { nextContinue ->
                                        data.addAll(nextContinue.items)
                                        continuation = nextContinue.continuation
                                        if (data.size >= 50) {
                                            count = 3
                                        }
                                        Logger.w("Radio", "data: ${data.size}")
                                        count++
                                    }.onFailure {
                                        count = 3
                                    }
                            }
                            val listTrackResult = data.toListTrack()
                            if (originalTrack != null) {
                                listTrackResult.add(0, originalTrack.toTrack())
                            }
                            Logger.w("Repository", "data: ${data.size}")
                            val playlistBrowse =
                                PlaylistBrowse(
                                    author = Author(id = "", name = "YouTube Music"),
                                    description =
                                    defaultDescription,
                                    duration = "",
                                    durationSeconds = 0,
                                    id = radioId,
                                    privacy = "PRIVATE",
                                    thumbnails =
                                        listOf(
                                            Thumbnail(
                                                544,
                                                originalTrack?.thumbnails ?: artist?.thumbnails ?: "",
                                                544,
                                            ),
                                        ),
                                    title = "${originalTrack?.title ?: artist?.name} $radioString",
                                    trackCount = listTrackResult.size,
                                    tracks = listTrackResult,
                                    year = now().year.toString(),
                                )
                            Logger.w("Repository", "playlistBrowse: $playlistBrowse")
                            emit(Resource.Success<Pair<PlaylistBrowse, String?>>(Pair(playlistBrowse, continuation)))
                        }.onFailure { exception ->
                            exception.printStackTrace()
                            emit(Resource.Error<Pair<PlaylistBrowse, String?>>(exception.message.toString()))
                        }
                }
            }.flowOn(Dispatchers.IO)
        }

    override fun getRDATRadioData(
        radioId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        flow<Resource<Pair<PlaylistBrowse, String?>>> {
            runCatching {
                val id =
                    if (radioId.startsWith("VL")) {
                        radioId
                    } else {
                        "VL$radioId"
                    }
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<MusicShelfRenderer.Content> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("Data", "data: $data")
                            Logger.d("Data", "data size: ${data.size}")
                            listContent.addAll(data)
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("Header", "header: $header")
                        val finalContinueParam =
                            result.getPlaylistContinuation()
                        Logger.d("Repository", "playlist data: ${listContent.size}")
                        Logger.d("Repository", "continueParam: $finalContinueParam")
//                        else {
//                            var listTrack = playlistBrowse.tracks.toMutableList()
                        Logger.d("Repository", "playlist final data: ${listContent.size}")
                        if (finalContinueParam != null) {
                            parsePlaylistData(header, listContent, radioId, viewString)?.let { playlist ->
                                emit(
                                    Resource.Success(
                                        Pair(
                                            playlist.copy(
                                                author = Author("", "YouTube Music"),
                                            ),
                                            finalContinueParam,
                                        ),
                                    ),
                                )
                            } ?: emit(Resource.Error("Can't parse data"))
                        } else {
                            emit(Resource.Error("Continue param is null"))
                        }
                    }.onFailure { e ->
                        Logger.e("Playlist Data", e.message ?: "Error")
                        emit(Resource.Error(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getFullPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<PlaylistBrowse>> =
        flow {
            runCatching {
                var id = ""
                id +=
                    if (!playlistId.startsWith("VL")) {
                        "VL$playlistId"
                    } else {
                        playlistId
                    }
                Logger.d("getPlaylistData", "playlist id: $id")
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<Track> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("getPlaylistData", "data: $data")
                            Logger.d("getPlaylistData", "data size: ${data.size}")
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("getPlaylistData", "header: $header")
                        var continueParam =
                            result.getPlaylistContinuation()
                        var count = 0
                        Logger.d("getPlaylistData", "playlist data: ${listContent.size}")
                        Logger.d("getPlaylistData", "continueParam: $continueParam")
//                        else {
//                            var listTrack = playlistBrowse.tracks.toMutableList()
                        while (continueParam != null) {
                            youTube
                                .customQuery(
                                    browseId = null,
                                    continuation = continueParam,
                                    setLogin = true,
                                ).onSuccess { values ->
                                    Logger.d("getPlaylistData", "continue: $continueParam")
                                    Logger.d(
                                        "getPlaylistData",
                                        "values: ${values.onResponseReceivedActions}",
                                    )
                                    val dataMore: List<SongItem> =
                                        values.onResponseReceivedActions
                                            ?.firstOrNull()
                                            ?.appendContinuationItemsAction
                                            ?.continuationItems
                                            ?.apply {
                                                Logger.w("getPlaylistData", "dataMore: ${this.size}")
                                            }?.mapNotNull {
                                                NextPage.fromMusicResponsiveListItemRenderer(
                                                    it.musicResponsiveListItemRenderer ?: return@mapNotNull null,
                                                )
                                            } ?: emptyList()
                                    listContent.addAll(dataMore.map { it.toTrack() })
                                    continueParam =
                                        values.getPlaylistContinuation()
                                    count++
                                }.onFailure {
                                    Logger.e("getPlaylistData", "Error: ${it.message}")
                                    continueParam = null
                                    count++
                                }
                        }
                        Logger.d("getPlaylistData", "playlist final data: ${listContent.size}")
                        parsePlaylistData(header, data ?: emptyList(), playlistId, viewString)?.let { playlist ->
                            emit(
                                Resource.Success<PlaylistBrowse>(
                                    playlist.copy(
                                        tracks =
                                            playlist.tracks.toMutableList().apply {
                                                addAll(listContent)
                                            },
                                        trackCount = (playlist.trackCount + listContent.size),
                                    ),
                                ),
                            )
                        } ?: emit(Resource.Error<PlaylistBrowse>("Error"))
                    }.onFailure { e ->
                        Logger.e("getPlaylistData", e.message ?: "Error")
                        emit(Resource.Error<PlaylistBrowse>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        flow {
            runCatching {
                // 网易歌单:id 为纯数字(YT 永远 VL/UC 前缀),同页面换数据源
                if (playlistId.toLongOrNull() != null) {
                    neteaseRepository
                        .getPlaylistBrowseData(playlistId)
                        .fold(
                            onSuccess = { emit(Resource.Success(it)) },
                            onFailure = { emit(Resource.Error(it.message ?: "netease playlist error")) },
                        )
                    return@flow
                }
                var id = ""
                id +=
                    if (!playlistId.startsWith("VL")) {
                        "VL$playlistId"
                    } else {
                        playlistId
                    }
                Logger.d("getPlaylistData", "playlist id: $id")
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<Track> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("getPlaylistData", "data: $data")
                            Logger.d("getPlaylistData", "data size: ${data.size}")
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("getPlaylistData", "header: $header")
                        val continueParam =
                            result.getPlaylistContinuation()
                        val radioEndpoint =
                            result.getPlaylistRadioEndpoint()
                        val shuffleEndpoint =
                            result.getPlaylistShuffleEndpoint()
                        Logger.d("getPlaylistData", "Endpoint: $radioEndpoint $shuffleEndpoint")
                        try {
                            parsePlaylistData(header, data ?: emptyList(), playlistId, viewString)?.let { playlist ->
                                emit(
                                    Resource.Success<Pair<PlaylistBrowse, String?>>(
                                        Pair(
                                            playlist.copy(
                                                tracks =
                                                    playlist.tracks.toMutableList().apply {
                                                        addAll(listContent)
                                                    },
                                                trackCount = (playlist.trackCount + listContent.size),
                                                shuffleEndpoint = shuffleEndpoint?.toYouTubeWatchEndpoint(),
                                                radioEndpoint = radioEndpoint?.toYouTubeWatchEndpoint(),
                                            ),
                                            continueParam,
                                        ),
                                    ),
                                )
                            } ?: emit(
                                Resource.Error<
                                    Pair<PlaylistBrowse, String?>,
                                >("Error"),
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            emit(
                                Resource.Error<
                                    Pair<PlaylistBrowse, String?>,
                                >(e.message.toString()),
                            )
                        }
                    }.onFailure { e ->
                        Logger.e("getPlaylistData", e.message ?: "Error")
                        emit(
                            Resource.Error<
                                Pair<PlaylistBrowse, String?>,
                            >(e.message.toString()),
                        )
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?> =
        flow {
            youTube
                .getLibraryPlaylists()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.get(
                                0,
                            )?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.get(
                                0,
                            )?.gridRenderer
                            ?.items
                    val listItem = mutableListOf<PlaylistsResult>()
                    if (input.isNullOrEmpty()) {
                        Logger.w("Library", "No playlists found")
                        emit(null)
                        return@onSuccess
                    }
                    listItem.addAll(
                        parseLibraryPlaylist(input),
                    )
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                Logger.w("Library", "continuation: $continuation")
                                val nextInput = nextData.first
                                listItem.addAll(
                                    parseNextLibraryPlaylist(nextInput),
                                )
                            }.onFailure { exception ->
                                exception.printStackTrace()
                                Logger.e("Library", "Error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) {
                        emit(listItem)
                        val account = localDataSource.getUsedGoogleAccount()
                        val isNeeded =
                            dataStoreManager.keepYouTubePlaylistOffline.first() == DataStoreManager.TRUE &&
                                dataStoreManager.loggedIn.first() == DataStoreManager.TRUE && account != null
                        if (isNeeded) {
                            insertYourYouTubePlaylist(
                                YourYouTubePlaylistList(
                                    emailPageId = "${account.email}_${account.pageId ?: ""}",
                                    listBrowseIds = listItem.map { it.browseId },
                                ),
                            )
                        }
                    } else {
                        emit(null)
                    }
                }.onFailure { e ->
                    Logger.e("Library", "Error: ${e.message}")
                    e.printStackTrace()
                    val account = localDataSource.getUsedGoogleAccount()
                    val isNeeded =
                        dataStoreManager.keepYouTubePlaylistOffline.first() == DataStoreManager.TRUE &&
                            dataStoreManager.loggedIn.first() == DataStoreManager.TRUE && account != null
                    if (isNeeded) {
                        val list =
                            getYourYouTubePlaylistList("${account.email}_${account.pageId ?: ""}")
                                .lastOrNull()
                        if (list != null) {
                            emit(
                                list.listBrowseIds.mapNotNull { id ->
                                    getPlaylist(id).lastOrNull()?.let {
                                        PlaylistsResult(
                                            author = it.author ?: "",
                                            browseId = it.id,
                                            category = "",
                                            itemCount = "${ it.trackCount }",
                                            resultType = "",
                                            thumbnails =
                                                listOf(
                                                    Thumbnail(
                                                        width = 544,
                                                        url = it.thumbnails,
                                                        height = 544,
                                                    ),
                                                ),
                                            title = it.title,
                                        )
                                    }
                                },
                            )
                        } else {
                            emit(null)
                        }
                    } else {
                        emit(null)
                    }
                }
        }.flowOn(Dispatchers.IO)

    override fun getLibraryAlbum(): Flow<List<AlbumsResult>?> =
        flow {
            youTube
                .getLibraryAlbums()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.items
                    if (input.isNullOrEmpty()) {
                        Logger.w("Library", "No liked albums found")
                        emit(null)
                        return@onSuccess
                    }
                    val listItem = parseLibraryPlaylist(input).map { it.toAlbumsResult() }.toMutableList()
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                listItem.addAll(parseNextLibraryPlaylist(nextData.first).map { it.toAlbumsResult() })
                            }.onFailure { exception ->
                                exception.printStackTrace()
                                Logger.e("Library", "getLibraryAlbum continuation error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) emit(listItem) else emit(null)
                }.onFailure { e ->
                    Logger.e("Library", "getLibraryAlbum error: ${e.message}")
                    e.printStackTrace()
                    emit(null)
                }
        }.flowOn(Dispatchers.IO)

    override fun getLibraryPlaylistSplit(): Flow<PlaylistRepository.YouTubeLibraryPlaylists?> =
        flow {
            youTube
                .getLibraryPlaylists()
                .onSuccess { data ->
                    // 实测响应 tab=[媒体库, 下载内容]:歌单全在 tabs[0] 的 grid(自建/收藏混排),
                    // 后面的 tab 是下载内容等无关分区(ytmusicapi 也只读 tab0),一律不碰
                    val grid =
                        data.contents?.singleColumnBrowseResultsRenderer?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                    val items = mutableListOf<PlaylistsResult>()
                    // 收藏他人歌单的 browseId 集合:按 kebab 菜单动作签名分(见 ownSavedByMenu)
                    val savedIds = mutableSetOf<String>()
                    grid?.items?.let { raw ->
                        items.addAll(parseLibraryPlaylist(raw))
                        savedIds.addAll(
                            raw.mapNotNull { it.musicTwoRowItemRenderer }
                                .mapNotNull { renderer ->
                                    renderer.navigationEndpoint?.browseEndpoint?.browseId
                                        ?.takeIf { renderer.ownSavedByMenu() == true }
                                },
                        )
                    }
                    var continuation =
                        grid?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                items.addAll(parseNextLibraryPlaylist(nextData.first))
                                savedIds.addAll(
                                    nextData.first
                                        .mapNotNull { renderer ->
                                            renderer.navigationEndpoint?.browseEndpoint?.browseId
                                                ?.takeIf { renderer.ownSavedByMenu() == true }
                                        },
                                )
                            }.onFailure { exception ->
                                exception.printStackTrace()
                                Logger.e("Library", "getLibraryPlaylistSplit continuation error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (items.isEmpty()) {
                        emit(null)
                        return@onSuccess
                    }
                    Logger.w(
                        "Library",
                        "getLibraryPlaylistSplit: ${items.size} playlists, saved-by-menu ${items.count { it.browseId in savedIds }}",
                    )
                    emit(
                        PlaylistRepository.YouTubeLibraryPlaylists(
                            own = items.filterNot { it.browseId in savedIds },
                            liked = items.filter { it.browseId in savedIds },
                        ),
                    )
                }.onFailure { e ->
                    Logger.e("Library", "getLibraryPlaylistSplit error: ${e.message}")
                    e.printStackTrace()
                    emit(null)
                }
        }.flowOn(Dispatchers.IO)

    override suspend fun createYouTubePlaylistWithTracks(
        title: String,
        videoIds: List<String>,
    ): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // 空曲目建单传 null(省略 videoIds 字段)——InnerTube 对缺省最宽容,空数组未验证
            youTube
                .createPlaylist(title, videoIds.takeIf { it.isNotEmpty() })
                .getOrNull()
                ?.playlistId
                ?.takeIf { it.isNotBlank() }
        }

    override suspend fun getYouTubePlaylistAsLibraryRow(playlistId: String): PlaylistsResult? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // YouTube.playlist 内部自己补 VL 前缀,这里统一剥掉防止双前缀
            youTube
                .playlist(playlistId.removePrefix("VL"))
                .getOrNull()
                ?.playlist
                ?.let { pl ->
                    PlaylistsResult(
                        author = pl.author?.name ?: "",
                        browseId = pl.id,
                        category = "",
                        itemCount = pl.songCountText ?: "",
                        resultType = "Playlist",
                        thumbnails =
                            pl.thumbnail.takeIf { it.isNotBlank() }
                                ?.let { listOf(Thumbnail(height = 544, url = it, width = 544)) }
                                ?: listOf(),
                        title = pl.title,
                    )
                }
        }

    override suspend fun deleteYouTubePlaylist(playlistId: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            youTube.deletePlaylist(playlistId).isSuccess
        }

    override suspend fun removeYouTubePlaylistFromLibrary(playlistId: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            youTube.removePlaylistFromLibrary(playlistId).isSuccess
        }

    override suspend fun removeTrackFromYouTubePlaylist(
        playlistId: String,
        videoId: String,
    ): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // scrape 端点按无 VL 形状工作(Ytmusic.removeItemYouTubePlaylist 自行剥前缀)
            val bareId = playlistId.removePrefix("VL")
            // 快路径:缓存命中直接试删。setVideoId 在"移除+重新入单"后会换新值,缓存行
            // 会陈旧——被服务端拒(non-200)无副作用,落慢路径整单拉当前值重试自愈
            val cached = localDataSource.getSetVideoIdForPlaylist(videoId, bareId)?.setVideoId
            if (cached != null &&
                youTube.removeItemYouTubePlaylist(bareId, videoId, cached).getOrNull() == 200
            ) {
                return@withContext true
            }
            val fresh =
                youTube
                    .getYouTubePlaylistFullTracksWithSetVideoId(bareId)
                    .getOrNull()
                    ?.firstOrNull { it.first.id == videoId }
                    ?.second
            if (fresh == null) {
                Logger.w("YTRemove", "no setVideoId for $videoId in $bareId")
                return@withContext false
            }
            localDataSource.insertSetVideoId(SetVideoIdEntity(videoId, fresh, bareId))
            youTube.removeItemYouTubePlaylist(bareId, videoId, fresh).getOrNull() == 200
        }

    override fun getMixedForYou(): Flow<List<PlaylistsResult>?> =
        flow {
            youTube
                .getMixedForYou()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.get(
                                0,
                            )?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.get(
                                0,
                            )?.gridRenderer
                            ?.items
                    val listItem = mutableListOf<PlaylistsResult>()
                    if (input.isNullOrEmpty()) {
                        Logger.w("Mixed For You", "No playlists found")
                        emit(null)
                        return@onSuccess
                    }
                    listItem.addAll(
                        parseLibraryPlaylist(input),
                    )
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                Logger.w("Mixed For You", "continuation: $continuation")
                                val nextInput = nextData.first
                                listItem.addAll(
                                    parseNextLibraryPlaylist(nextInput),
                                )
                            }.onFailure { exception ->
                                exception.printStackTrace()
                                Logger.e("Mixed For You", "Error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) {
                        emit(listItem)
                    } else {
                        emit(null)
                    }
                }
        }.flowOn(Dispatchers.IO)

    override fun updateYourYouTubePlaylistTitle(
        playlistId: String,
        newTitle: String,
    ): Flow<Resource<String>> =
        flow {
            youTube
                .editPlaylist(playlistId, newTitle)
                .onSuccess {
                    emit(Resource.Success(it.toString()))
                }.onFailure {
                    emit(Resource.Error<String>(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) =
        withContext(Dispatchers.IO) {
            localDataSource.insertYourYouTubePlaylist(yourYouTubePlaylist)
        }

    override suspend fun deleteAllYourYouTubePlaylist() =
        withContext(Dispatchers.IO) {
            localDataSource.deleteAllYourYouTubePlaylist()
        }

    override fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?> =
        flow {
            emit(localDataSource.getYourYouTubePlaylistList(emailPageId))
        }.flowOn(Dispatchers.IO)

    override fun getChartPlaylist(): Flow<Resource<List<ChartItem>>> =
        flow {
            youTube
                .getSimpMusicChart()
                .onSuccess { response ->
                    val data = response.data?.filterNotNull() ?: emptyList()
                    val result =
                        data.mapNotNull {
                            ChartItem(
                                name = it.name ?: return@mapNotNull null,
                                ytPlaylistId = it.youtubePlaylistId ?: return@mapNotNull null,
                            )
                        }
                    emit(Resource.Success(result))
                }.onFailure { exception ->
                    exception.printStackTrace()
                    emit(Resource.Error<List<ChartItem>>(exception.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

/** 库页"收藏的专辑"tile:liked_albums 网格项(playlist 形状解析)→ 专辑形状(author 行即艺人副标题) */
private fun PlaylistsResult.toAlbumsResult() =
    AlbumsResult(
        artists = author.takeIf { it.isNotBlank() }?.let { listOf(Artist(id = null, name = it)) } ?: emptyList(),
        browseId = browseId,
        category = "Album",
        duration = Unit,
        isExplicit = false,
        resultType = "Album",
        thumbnails = thumbnails,
        title = title,
        type = "album",
        year = "",
    )
}
