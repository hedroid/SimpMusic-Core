package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.repository.NeteaseRepositoryImpl
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toAlbumsResult
import com.maxrave.data.parser.parseAlbumData
import com.maxrave.data.parser.parseLibraryPlaylist
import com.maxrave.data.parser.parseNextLibraryPlaylist
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.FollowedArtistSingleAndAlbum
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

private const val TAG = "AlbumRepositoryImpl"

/** MoreAlbumsViewModel.SINGLE_PARAM 的同值复制(composeApp 常量,core 无法引用,只认这一个) */
private const val NETEASE_SINGLE_PARAM = "ggMIegYIAhoCAQI%3D"

internal class AlbumRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val neteaseRepository: NeteaseRepositoryImpl,
) : AlbumRepository {
    override fun getAllAlbums(limit: Int): Flow<List<AlbumEntity>> =
        flow {
            emit(localDataSource.getAllAlbums(limit))
        }.flowOn(Dispatchers.IO)

    override fun getAlbum(id: String): Flow<AlbumEntity?> =
        flow {
            emit(localDataSource.getAlbum(id))
        }.flowOn(Dispatchers.IO)

    override fun getAlbumAsFlow(id: String) = localDataSource.getAlbumAsFlow(id)

    override fun getLikedAlbums(): Flow<List<AlbumEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedAlbums(
                        limit,
                        offset,
                    )
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun insertAlbum(albumEntity: AlbumEntity) =
        flow {
            emit(localDataSource.insertAlbum(albumEntity))
        }.flowOn(Dispatchers.IO)

    override suspend fun updateAlbumLiked(
        albumId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) { localDataSource.updateAlbumLiked(likeStatus, albumId) }

    override suspend fun getRemoteSavedState(
        albumId: String,
        backingPlaylistId: String?,
    ): Boolean? {
        if (albumId.toLongOrNull() != null) {
            if (!neteaseRepository.isLoggedIn.first()) return null
            // The subscribed-album endpoint is a list rather than a per-album status call.
            return neteaseRepository.getStarredAlbums().getOrNull()?.any { it.browseId == albumId }
        }
        val response = youTube.getLibraryAlbums().getOrNull() ?: return null
        val grid =
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.gridRenderer
        val ids = parseLibraryPlaylist(grid?.items.orEmpty()).map { it.browseId }.toMutableSet()
        var continuation = grid?.continuations?.firstOrNull()?.nextContinuationData?.continuation
        while (continuation != null) {
            val next = youTube.nextYouTubePlaylists(continuation).getOrNull() ?: break
            ids += parseNextLibraryPlaylist(next.first).map { it.browseId }
            continuation = next.second
        }
        return albumId in ids || backingPlaylistId?.removePrefix("VL") in ids.map { it.removePrefix("VL") }
    }

    override suspend fun setRemoteSavedState(
        albumId: String,
        backingPlaylistId: String?,
        saved: Boolean,
    ): Boolean =
        if (albumId.toLongOrNull() != null) {
            if (!neteaseRepository.isLoggedIn.first()) false
            else neteaseRepository.subscribeNeteaseAlbum(albumId, saved).getOrDefault(false)
        } else {
            val playlistId = backingPlaylistId?.takeIf { it.isNotBlank() } ?: return false
            youTube.setPlaylistInLibrary(playlistId.removePrefix("VL"), saved).getOrNull() in 200..299
        }

    override suspend fun updateAlbumInLibrary(
        inLibrary: LocalDateTime,
        albumId: String,
    ) = withContext(
        Dispatchers.Main,
    ) { localDataSource.updateAlbumInLibrary(inLibrary, albumId) }

    override suspend fun updateAlbumDownloadState(
        albumId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateAlbumDownloadState(
            downloadState,
            albumId,
        )
    }

    override suspend fun insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum: FollowedArtistSingleAndAlbum) =
        withContext(Dispatchers.IO) {
            localDataSource.insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum)
        }

    override suspend fun deleteFollowedArtistSingleAndAlbum(channelId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.deleteFollowedArtistSingleAndAlbum(channelId)
        }

    override suspend fun getAllFollowedArtistSingleAndAlbums(): Flow<List<FollowedArtistSingleAndAlbum>?> =
        flow {
            val list =
                getFullDataFromDB { limit, offset ->
                    localDataSource.getAllFollowedArtistSingleAndAlbums(limit, offset)
                }
            emit(list)
        }.flowOn(Dispatchers.IO)

    override suspend fun getFollowedArtistSingleAndAlbum(channelId: String): Flow<FollowedArtistSingleAndAlbum?> =
        flow {
            emit(localDataSource.getFollowedArtistSingleAndAlbum(channelId))
        }.flowOn(Dispatchers.IO)

    override fun getAlbumData(browseId: String): Flow<Resource<AlbumBrowse>> =
        flow {
            runCatching {
                // 网易专辑:id 为纯数字(YT 恒含字母),同页面换数据源(M2 歌单同款)
                if (browseId.toLongOrNull() != null) {
                    neteaseRepository
                        .getAlbumBrowseData(browseId)
                        .fold(
                            onSuccess = { emit(Resource.Success(it)) },
                            onFailure = { emit(Resource.Error(it.message ?: "netease album error")) },
                        )
                    return@flow
                }
                youTube
                    .album(browseId, withSongs = true)
                    .onSuccess { result ->
                        emit(Resource.Success(parseAlbumData(result)))
                    }.onFailure { e ->
                        Logger.d(TAG, "getAlbumData -> error: ${e.message}")
                        emit(Resource.Error(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getAlbumMore(
        browseId: String,
        params: String,
    ): Flow<Pair<String, List<AlbumsResult>>?> =
        flow {
            runCatching {
                // 网易艺人"更多专辑/单曲":ArtistScreen/NotifyWork 拼的是 MPAD{数字id} → artistAlbums
                // 按 more 翻页拉全再按 type 拆两组。params 值与 MoreAlbumsViewModel 的常量同源
                // (core 不依赖 composeApp,只能复制这两个不透明 YTM 参数串)。
                // 返回 null=请求失败(NotifyWork 凭此跳过快照写入);空列表同样折叠成 null,
                // 维持 MoreAlbums UI 对 null="无更多"的既有语义。
                if (browseId.startsWith("MPAD")) {
                    browseId.removePrefix("MPAD").toLongOrNull()?.let { artistId ->
                        val singles = params == NETEASE_SINGLE_PARAM
                        val albums = neteaseRepository.getArtistMoreAlbums(artistId, singles)
                        emit(albums?.takeIf { it.isNotEmpty() }?.let { (if (singles) "单曲" else "专辑") to it })
                        return@flow
                    }
                }
                youTube
                    .browse(browseId = browseId, params = params)
                    .onSuccess { data ->
                        Logger.w(TAG, "getAlbumMore -> result: $data")
                        val items =
                            (data.items.firstOrNull()?.items ?: emptyList()).mapNotNull { item ->
                                item as? AlbumItem
                            }
                        emit(
                            (data.title ?: "") to (
                                items.map {
                                    it.toAlbumsResult()
                                }
                            ),
                        )
                    }.onFailure {
                        it.printStackTrace()
                        emit(null)
                    }
            }
        }.flowOn(Dispatchers.IO)
}
