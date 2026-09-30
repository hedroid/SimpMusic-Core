package com.maxrave.domain.data.model.browse.artist
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail

data class ResultSong(
    val videoId: String,
    val title: String,
    val artists: List<Artist>?,
    val durationSeconds: Int = 0,
    val album: Album,
    val likeStatus: String,
    val thumbnails: List<Thumbnail>,
    val isAvailable: Boolean,
    val isExplicit: Boolean,
    /** YouTube's `MUSIC_VIDEO_TYPE_*`, or null when the response carried none — as on [ResultVideo]. */
    val videoType: String?,
    /** 网易播客节目 id(仅播客 toResultSong 填充):随 toTrack 进队列,收听位置记忆 O(1) 读取(CR-26) */
    val neteaseProgramId: Long? = null,
)