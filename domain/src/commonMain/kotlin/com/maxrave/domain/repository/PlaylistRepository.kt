package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface PlaylistRepository {
    fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>>

    fun getPlaylist(id: String): Flow<PlaylistEntity?>

    fun getLikedPlaylists(): Flow<List<PlaylistEntity>>

    suspend fun insertPlaylist(playlistEntity: PlaylistEntity)

    suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity)

    suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity)

    suspend fun updatePlaylistLiked(
        playlistId: String,
        likeStatus: Int,
    )

    /** Remote account library state. Null means signed out or not currently knowable. */
    suspend fun getRemoteSavedState(playlistId: String): Boolean?

    /** Saves/removes a foreign online playlist in its source account. */
    suspend fun setRemoteSavedState(
        playlistId: String,
        saved: Boolean,
    ): Boolean

    suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    )

    suspend fun updatePlaylistDownloadState(
        playlistId: String,
        downloadState: Int,
    )

    fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>>

    fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>>

    fun getRadio(
        radioId: String,
        defaultDescription: String,
        radioString: String,
        viewString: String,
        originalTrack: SongEntity? = null,
        artist: ArtistEntity? = null,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>>

    fun getRDATRadioData(
        radioId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>>

    fun getFullPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<PlaylistBrowse>>

    fun getPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>>

    fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?>

    /** 当前 YT 账号收藏的专辑(FEmusic_liked_albums,含 continuation 翻页) */
    fun getLibraryAlbum(): Flow<List<AlbumsResult>?>

    /**
     * YT 库歌单分区:FEmusic_liked_playlists 的响应 tab 即 YTM App"已创建/已喜欢"筛选。
     * 单 tab 响应(假设不成立/老账号)退化为 own=全部、liked=空;tab 标题打 W 级日志备查。
     */
    fun getLibraryPlaylistSplit(): Flow<YouTubeLibraryPlaylists?>

    /** [getLibraryPlaylistSplit] 的分区结果。auto=系统歌单(红心歌单 Liked Music,browseId=LM)。 */
    data class YouTubeLibraryPlaylists(
        val own: List<PlaylistsResult>,
        val liked: List<PlaylistsResult>,
    ) {
        val auto: List<PlaylistsResult> get() = own.filter { it.browseId == "LM" }
        val created: List<PlaylistsResult> get() = own.filter { it.browseId != "LM" }
    }

    /** 在 YT 账号下新建歌单并把初始曲目一次塞进去(三点菜单"添加到歌单→新建歌单")。
     *  返回新歌单 id;失败(null)由调用方提示。 */
    suspend fun createYouTubePlaylistWithTracks(
        title: String,
        videoIds: List<String>,
    ): String?

    fun getMixedForYou(): Flow<List<PlaylistsResult>?>

    fun updateYourYouTubePlaylistTitle(
        playlistId: String,
        newTitle: String,
    ): Flow<Resource<String>>

    suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList)

    /**
     * @param emailPageId = $email_$pageId
     */
    fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?>

    suspend fun deleteAllYourYouTubePlaylist()

    /**
     * @return Country Code -> YouTube Music Playlist ID
     */
    fun getChartPlaylist(): Flow<Resource<List<ChartItem>>>
}
