package com.maxrave.domain.data.model.browse.playlist

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint

data class PlaylistBrowse(
    val author: Author,
    val description: String?,
    val duration: String,
    val durationSeconds: Int,
    val id: String,
    val privacy: String,
    val thumbnails: List<Thumbnail>,
    val title: String,
    val trackCount: Int,
    val tracks: List<Track>,
    val year: String,
    val shuffleEndpoint: YouTubeWatchEndpoint? = null,
    val radioEndpoint: YouTubeWatchEndpoint? = null,
    /**
     * 当前账号可编辑该歌单(YT browse 响应对自建歌单用 musicEditablePlaylistDetailHeaderRenderer
     * 包装头部)。自建歌单没有"收藏进资料库"的概念,详情页顶栏收藏心按它隐藏。
     */
    val isOwnYouTubePlaylist: Boolean = false,
)