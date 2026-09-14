/*
 * 音源路由仓库:同一 SearchRepository 契约下按 selectedSource 选 YT/网易实现
 * (与 SourceRoutingHomeRepository 同款策略模式)。SearchViewModel/Screen 只注入
 * SearchRepository,对音源无感知。
 *
 * - 搜索历史两源共用:搜索词无源属性,不加 source 字段(无 db 迁移)。
 * - 网易不支持的能力(video/album/podcast/featuredPlaylist)发空 Success,
 *   searchAll() 的 7 个并行 job 零改动,空类在 ALL 混排里自然消失;
 *   UI 侧 chips 按 selectedSource 过滤,这些 tab 在网易源下不可见。
 * - 网易侧单发取 first 形状(YT 侧 emitAll 透传多发射语义)。
 */
package com.maxrave.data.repository

import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.searchResult.SearchSuggestions
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.source.MusicSource
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

internal class SourceRoutingSearchRepository(
    private val youtube: SearchRepositoryImpl,
    private val netease: NeteaseRepositoryImpl,
    private val dataStoreManager: DataStoreManager,
) : SearchRepository {
    private suspend fun isNetease(): Boolean = dataStoreManager.selectedSource.first() == MusicSource.NETEASE.name

    override fun getSearchHistory(): Flow<List<SearchHistory>> = youtube.getSearchHistory()

    override fun insertSearchHistory(searchHistory: SearchHistory): Flow<Long> = youtube.insertSearchHistory(searchHistory)

    override suspend fun deleteSearchHistory() = youtube.deleteSearchHistory()

    override fun getSearchDataSong(query: String): Flow<Resource<ArrayList<SongsResult>>> =
        flow {
            if (isNetease()) {
                netease.searchSongsResult(query).fold(
                    onSuccess = { emit(Resource.Success(it)) },
                    onFailure = { emit(Resource.Error(it.message ?: "netease search error")) },
                )
            } else {
                emitAll(youtube.getSearchDataSong(query))
            }
        }

    override fun getSearchDataVideo(query: String): Flow<Resource<ArrayList<VideosResult>>> =
        flow {
            if (isNetease()) {
                emit(Resource.Success(arrayListOf())) // MV 播放链路未接,tab 已隐藏
            } else {
                emitAll(youtube.getSearchDataVideo(query))
            }
        }

    override fun getSearchDataPodcast(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        flow {
            if (isNetease()) {
                emit(Resource.Success(arrayListOf())) // 播客播放链路未接,tab 已隐藏
            } else {
                emitAll(youtube.getSearchDataPodcast(query))
            }
        }

    override fun getSearchDataFeaturedPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        flow {
            if (isNetease()) {
                emit(Resource.Success(arrayListOf())) // 网易搜索无"精选"维度,tab 已隐藏
            } else {
                emitAll(youtube.getSearchDataFeaturedPlaylist(query))
            }
        }

    override fun getSearchDataArtist(query: String): Flow<Resource<ArrayList<ArtistsResult>>> =
        flow {
            if (isNetease()) {
                netease.searchArtistsResult(query).fold(
                    onSuccess = { emit(Resource.Success(it)) },
                    onFailure = { emit(Resource.Error(it.message ?: "netease search error")) },
                )
            } else {
                emitAll(youtube.getSearchDataArtist(query))
            }
        }

    override fun getSearchDataAlbum(query: String): Flow<Resource<ArrayList<AlbumsResult>>> =
        flow {
            if (isNetease()) {
                netease.searchAlbumsResult(query).fold(
                    onSuccess = { emit(Resource.Success(it)) },
                    onFailure = { emit(Resource.Error(it.message ?: "netease search error")) },
                )
            } else {
                emitAll(youtube.getSearchDataAlbum(query))
            }
        }

    override fun getSearchDataPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        flow {
            if (isNetease()) {
                netease.searchPlaylistsResult(query).fold(
                    onSuccess = { emit(Resource.Success(it)) },
                    onFailure = { emit(Resource.Error(it.message ?: "netease search error")) },
                )
            } else {
                emitAll(youtube.getSearchDataPlaylist(query))
            }
        }

    override fun getSuggestQuery(query: String): Flow<Resource<SearchSuggestions>> =
        flow {
            if (isNetease()) {
                netease.searchSuggestData(query).fold(
                    onSuccess = { emit(Resource.Success(it)) },
                    onFailure = { emit(Resource.Error(it.message ?: "netease suggest error")) },
                )
            } else {
                emitAll(youtube.getSuggestQuery(query))
            }
        }
}
