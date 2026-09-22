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
     * [getLibraryPlaylistSplit] 的分区结果(2026-09-21 登录账号实测重写):
     * - auto=系统歌单置顶满行:赞过的音乐(LM)/稍后再听(SE),browseId 匹配前先剥 VL 前缀
     *   (响应实测给的是 VLLM/VLSE),标题兜底;
     * - own/liked(自建/收藏)按 kebab 菜单动作签名分(见 data 层 ownSavedByMenu:
     *   EDIT/DELETE=自建、BOOKMARK 双态项=收藏),token 失配退化为全自建不误分。
     */
    fun getLibraryPlaylistSplit(): Flow<YouTubeLibraryPlaylists?>

    data class YouTubeLibraryPlaylists(
        val own: List<PlaylistsResult>,
        val liked: List<PlaylistsResult>,
    ) {
        /** 系统歌单置顶行,LM(赞过的音乐)固定在 SE(稍后再听)上方 */
        val auto: List<PlaylistsResult>
            get() = own.filter { it.isSystemPlaylist() }.sortedBy { systemRowRank(it) }

        val created: List<PlaylistsResult> get() = own.filter { !it.isSystemPlaylist() }

        companion object {
            /** 系统歌单固定 browseId(剥 VL 前缀后匹配):LM=赞过的音乐,SE=稍后再听,WL=YouTube 待看 */
            private val SYSTEM_PLAYLIST_BROWSE_IDS = setOf("LM", "SE", "WL")

            /** 置顶行顺序:LM 在 SE 上方,其余系统歌单按原顺序跟在后面 */
            private val SYSTEM_ROW_ORDER = listOf("LM", "SE")

            /**
             * 系统歌单标题兜底(服务端按账号语言下发,zh/en 双语都收)。用户自建同名歌单
             * 会被误收进置顶行,可接受。
             */
            private val SYSTEM_PLAYLIST_TITLES =
                setOf("赞过的音乐", "喜欢的音乐", "稍后在听", "稍后再听", "Listen later", "Liked music", "Liked songs", "稍后再看", "Watch later", "待听清单")

            fun PlaylistsResult.isSystemPlaylist(): Boolean =
                browseId.removePrefix("VL") in SYSTEM_PLAYLIST_BROWSE_IDS || title in SYSTEM_PLAYLIST_TITLES

            internal fun systemRowRank(playlist: PlaylistsResult): Int {
                val idx = SYSTEM_ROW_ORDER.indexOf(playlist.browseId.removePrefix("VL"))
                return if (idx >= 0) idx else SYSTEM_ROW_ORDER.size
            }
        }
    }

    /** 在 YT 账号下新建歌单并把初始曲目一次塞进去(三点菜单"添加到歌单→新建歌单")。
     *  videoIds 传空 = 建空歌单(库页"新建歌单"入口);返回新歌单 id;失败(null)由调用方提示。 */
    suspend fun createYouTubePlaylistWithTracks(
        title: String,
        videoIds: List<String>,
    ): String?

    /** 删除自建 YT 歌单(playlist/delete,仅自建有权限;playlistId 不带 VL)。
     *  返回是否成功。 */
    suspend fun deleteYouTubePlaylist(playlistId: String): Boolean

    /** 把收藏的他人 YT 歌单移出资料库(like/removelike,playlistId 不带 VL;
     *  playlist/delete 对收藏歌单是 403)。返回是否成功。 */
    suspend fun removeYouTubePlaylistFromLibrary(playlistId: String): Boolean

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
