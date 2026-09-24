package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.searchResult.SearchSuggestions
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface SearchRepository {
    fun getSearchHistory(): Flow<List<SearchHistory>>

    fun insertSearchHistory(searchHistory: SearchHistory): Flow<Long>

    suspend fun deleteSearchHistory()

    fun getSearchDataSong(query: String): Flow<Resource<ArrayList<SongsResult>>>

    /** 搜索单曲分页页(SONGS tab 加载更多):pageToken=null=首页,返回 (曲目, 下一页令牌|null=没有更多)。
     *  YT 令牌=InnerTube continuation;网易令牌="offset:<n>"(服务端 offset 游标)。
     *  其余 tab 与 ALL 混排仍走一次拉完的原方法。 */
    fun getSearchDataSongPage(
        query: String,
        pageToken: String?,
    ): Flow<Resource<Pair<ArrayList<SongsResult>, String?>>>

    fun getSearchDataVideo(query: String): Flow<Resource<ArrayList<VideosResult>>>

    fun getSearchDataPodcast(query: String): Flow<Resource<ArrayList<PlaylistsResult>>>

    fun getSearchDataFeaturedPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>>

    fun getSearchDataArtist(query: String): Flow<Resource<ArrayList<ArtistsResult>>>

    fun getSearchDataAlbum(query: String): Flow<Resource<ArrayList<AlbumsResult>>>

    fun getSearchDataPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>>

    fun getSuggestQuery(query: String): Flow<Resource<SearchSuggestions>>
}