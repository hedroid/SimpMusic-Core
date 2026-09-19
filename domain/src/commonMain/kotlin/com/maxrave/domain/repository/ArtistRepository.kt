package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface ArtistRepository {
    fun getAllArtists(limit: Int): Flow<List<ArtistEntity>>

    fun getArtistById(id: String): Flow<ArtistEntity?>

    suspend fun insertArtist(artistEntity: ArtistEntity)

    suspend fun updateArtistImage(
        channelId: String,
        thumbnail: String,
    )

    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    )

    /** Writes only the local follow flag. The source account is [setRemoteFollowedStatus]'s job. */
    suspend fun updateFollowedStatus(
        channelId: String,
        followedStatus: Int,
    )

    /** Updates only the source account. The local follow flag is deliberately untouched. */
    suspend fun setRemoteFollowedStatus(
        channelId: String,
        followed: Boolean,
    ): Boolean

    /**
     * 只写本地关注位,不镜像任何账号。浏览艺人页时把服务端关注态落库用——
     * 走 [updateFollowedStatus] 会对网易歌手再发一次 /artist/sub、对 YT 走镜像逻辑。
     */
    suspend fun setFollowedLocal(
        channelId: String,
        followed: Boolean,
    )

    /**
     * Subscribes to every artist already followed locally.
     *
     * Turning the setting on is a statement about the whole library, not about the next artist
     * tapped — without this, artists followed before the switch stay invisible to the account.
     * Emits the number that succeeded and the number attempted.
     */
    fun syncFollowedArtistsToYouTube(): Flow<Pair<Int, Int>>

    fun getFollowedArtists(): Flow<List<ArtistEntity>>

    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    )

    fun getArtistData(channelId: String): Flow<Resource<ArtistBrowse>>
}
