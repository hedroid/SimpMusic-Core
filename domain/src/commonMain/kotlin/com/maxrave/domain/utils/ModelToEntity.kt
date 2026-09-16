package com.maxrave.domain.utils

import com.maxrave.domain.source.MusicSource

import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ResultSong
import com.maxrave.domain.data.model.browse.artist.ResultVideo
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.podcast.PodcastBrowse
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import kotlin.jvm.JvmName

fun SearchHistory.toQuery(): String = this.query

fun List<SearchHistory>.toQueryList(): ArrayList<String> {
    val list = ArrayList<String>()
    for (item in this) {
        list.add(item.query)
    }
    return list
}

fun ResultSong.toTrack(): Track =
    Track(
        album = album,
        artists = artists,
        duration = "",
        durationSeconds = this.durationSeconds,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        thumbnails = thumbnails,
        title = title,
        videoId = videoId,
        videoType = videoType,
        category = null,
        feedbackTokens = null,
        resultType = null,
        year = "",
    )

fun ResultVideo.toTrack(): Track =
    Track(
        album = null,
        artists = this.artists ?: listOf(),
        duration = this.duration,
        durationSeconds = this.durationSeconds,
        isAvailable = false,
        isExplicit = false,
        likeStatus = null,
        thumbnails = this.thumbnails,
        title = this.title,
        videoId = this.videoId,
        // Was `this.views`, which wrote the localized view-count subtitle ("432K views",
        // "168 N lượt xem") into the type column and from there into song.videoType. The view
        // count now travels in `views`, where the Videos shelf reads it.
        videoType = this.videoType,
        category = null,
        feedbackTokens = null,
        resultType = null,
        year = "",
        views = this.views,
    )

fun SongsResult.toTrack(): Track =
    Track(
        this.album,
        this.artists,
        this.duration ?: "",
        this.durationSeconds ?: 0,
        true,
        this.isExplicit ?: false,
        "",
        this.thumbnails,
        this.title ?: "",
        this.videoId,
        this.videoType ?: "",
        this.category,
        this.feedbackTokens,
        this.resultType,
        "",
    )

fun Track.toSongEntity(): SongEntity {
    return SongEntity(
        videoId = this.videoId,
        // Track(scraper 模型)不携带音源;网易歌曲的 videoId 是纯数字,YT id 固定 11 位
        // 含字母 —— 以 ID 形状回填 source,保证任何播放入口落库的行都标对源。
        source = if (this.videoId.toLongOrNull() != null) MusicSource.NETEASE.name else MusicSource.YOUTUBE_MUSIC.name,
        albumId = this.album?.id,
        albumName = this.album?.name,
        artistId = this.artists?.toListId(),
        artistName = this.artists?.toListName(),
        duration = this.duration ?: "",
        durationSeconds = this.durationSeconds ?: 0,
        isAvailable = this.isAvailable,
        isExplicit = this.isExplicit,
        likeStatus = this.likeStatus ?: "",
        thumbnails =
            this.thumbnails?.last()?.url?.let {
                if (it.contains("w120")) {
                    return@let Regex("([wh])120").replace(it, "$1544")
                } else if (it.contains("sddefault")) {
                    return@let it.replace("sddefault", "maxresdefault")
                } else {
                    return@let it
                }
            },
        title = this.title,
        videoType = this.videoType ?: "",
        category = this.category,
        resultType = this.resultType,
        liked = false,
        totalPlayTime = 0,
        downloadState = 0,
    )
}

fun SongEntity.toTrack(): Track {
    val listArtist = mutableListOf<Artist>()
    val artistName = this.artistName
    if (artistName != null) {
        for (i in 0 until artistName.size) {
            // 两列表长度不保证一致:网易映射在艺人页打通前 artistId 恒为空表(artistName 非空),
            // get(i) 会越界炸掉整个 toTrack;缺 id 给空串,与"无艺人信息"的既有歌一致
            listArtist.add(Artist(this.artistId?.getOrNull(i) ?: "", artistName[i]))
        }
    }
    // w544-h544 是 YT 封面 URL 尺寸参数启发式;网易封面(music.126.net)不带 → 被标成
    // 720x1080 非方形,下游 Track.toGenericMediaItem 误判 VIDEO,播放页封面渲染成宽条。
    // 数字 videoId 一律是歌(与 5c2eeb3 的 SongEntity.toGenericMediaItem 同款守卫)。
    val isSong =
        (this.thumbnails?.contains("w544") == true && this.thumbnails?.contains("h544") == true) ||
            this.videoId.toLongOrNull() != null
    return Track(
        album = this.albumId?.let { this.albumName?.let { it1 -> Album(it, it1) } },
        artists = listArtist,
        duration = this.duration,
        durationSeconds = this.durationSeconds,
        isAvailable = this.isAvailable,
        isExplicit = this.isExplicit,
        likeStatus = this.likeStatus,
        thumbnails = if (isSong) listOf(Thumbnail(544, this.thumbnails ?: "", 544)) else listOf(Thumbnail(720, this.thumbnails ?: "", 1080)),
        title = this.title,
        videoId = this.videoId,
        videoType = this.videoType,
        category = this.category,
        feedbackTokens = null,
        resultType = null,
        year = "",
    )
}

fun List<SongEntity>?.toArrayListTrack(): ArrayList<Track> {
    val listTrack: ArrayList<Track> = arrayListOf()
    if (this != null) {
        for (item in this) {
            listTrack.add(item.toTrack())
        }
    }
    return listTrack
}

@JvmName("SongResulttoListTrack")
fun ArrayList<SongsResult>.toListTrack(): ArrayList<Track> {
    val listTrack = arrayListOf<Track>()
    for (song in this) {
        listTrack.add(song.toTrack())
    }
    return listTrack
}

fun VideosResult.toTrack(): Track {
    val thumb = Thumbnail(720, "http://i.ytimg.com/vi/${this.videoId}/maxresdefault.jpg", 1280)
    val thumbList = this.thumbnails ?: mutableListOf(thumb)
    return Track(
        album = null,
        artists = this.artists,
        duration = this.duration ?: "",
        durationSeconds = this.durationSeconds ?: 0,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = thumbList,
        title = this.title,
        videoId = this.videoId,
        videoType = this.videoType ?: "",
        category = this.category,
        feedbackTokens = null,
        resultType = this.resultType,
        year = "",
    )
}

@JvmName("VideoResulttoTrack")
fun ArrayList<VideosResult>.toListTrack(): ArrayList<Track> {
    val listTrack = arrayListOf<Track>()
    for (video in this) {
        listTrack.add(video.toTrack())
    }
    return listTrack
}

fun Content.toTrack(): Track =
    Track(
        album = album,
        artists = artists ?: listOf(Artist("", "")),
        duration = "",
        durationSeconds = durationSeconds,
        isAvailable = false,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = thumbnails,
        title = title,
        videoId = videoId!!,
        videoType = videoType,
        category = null,
        feedbackTokens = null,
        resultType = null,
        year = "",
    )

fun List<Track>.toListVideoId(): List<String> {
    val list = mutableListOf<String>()
    for (item in this) {
        list.add(item.videoId)
    }
    return list
}

fun List<Artist>?.toListName(): List<String> {
    val list = mutableListOf<String>()
    if (this != null) {
        for (item in this) {
            list.add(item.name)
        }
    }
    return list
}

fun List<Artist>?.toListId(): List<String> {
    val list = mutableListOf<String>()
    if (this != null) {
        for (item in this) {
            list.add(item.id ?: "")
        }
    }
    return list
}

fun AlbumBrowse.toAlbumEntity(id: String): AlbumEntity =
    AlbumEntity(
        browseId = id,
        artistId = this.artists.toListId(),
        artistName = this.artists.toListName(),
        audioPlaylistId = this.audioPlaylistId,
        description = this.description ?: "",
        duration = this.duration,
        durationSeconds = this.durationSeconds,
        thumbnails = this.thumbnails?.last()?.url,
        title = this.title,
        trackCount = this.trackCount,
        tracks = this.tracks.toListVideoId(),
        type = this.type,
        year = this.year,
    )

fun PlaylistBrowse.toPlaylistEntity(): PlaylistEntity =
    PlaylistEntity(
        // 歌单 id 自带音源特征:纯数字=网易(YT 恒为 VL/UC 前缀) —— 持久化边界回填,
        // 与 Track.toSongEntity 同一规则,不依赖调用方传"当前源"
        source = if (this.id.toLongOrNull() != null) MusicSource.NETEASE.name else MusicSource.YOUTUBE_MUSIC.name,
        id = this.id,
        author = this.author.name,
        description = this.description ?: "",
        duration = this.duration,
        durationSeconds = this.durationSeconds,
        privacy = this.privacy,
        thumbnails = this.thumbnails.last().url,
        title = this.title,
        trackCount = this.trackCount,
        tracks = this.tracks.toListVideoId(),
        year = this.year,
        downloadState = DownloadState.STATE_NOT_DOWNLOADED,
    )

fun Track.addThumbnails(): Track =
    Track(
        album = this.album,
        artists = this.artists,
        duration = this.duration,
        durationSeconds = this.durationSeconds,
        isAvailable = this.isAvailable,
        isExplicit = this.isExplicit,
        likeStatus = this.likeStatus,
        thumbnails =
            listOf(
                Thumbnail(
                    720,
                    "https://i.ytimg.com/vi/${this.videoId}/maxresdefault.jpg",
                    1280,
                ),
            ),
        title = this.title,
        videoId = this.videoId,
        videoType = this.videoType,
        category = this.category,
        feedbackTokens = this.feedbackTokens,
        resultType = this.resultType,
        year = this.year,
    )

fun LyricsEntity.toLyrics(): Lyrics =
    Lyrics(
        error = this.error,
        lines = this.lines,
        syncType = this.syncType,
    )

fun Lyrics.toLyricsEntity(videoId: String): LyricsEntity =
    LyricsEntity(
        videoId = videoId,
        error = this.error,
        lines = this.lines,
        syncType = this.syncType,
    )

fun Collection<SongEntity>.toVideoIdList(): List<String> {
    val list = mutableListOf<String>()
    for (item in this) {
        list.add(item.videoId)
    }
    return list
}

fun TranslatedLyricsEntity.toLyrics(): Lyrics =
    Lyrics(
        error = this.error,
        lines = this.lines,
        syncType = this.syncType,
    )

fun PodcastBrowse.EpisodeItem.toTrack(): Track =
    Track(
        album = null,
        artists = listOf(this.author),
        duration = this.durationString,
        durationSeconds = null,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        thumbnails = this.thumbnail,
        title = this.title,
        videoId = this.videoId,
        // The podcast browse response carries no music config; `category`/`resultType` below are
        // what mark this as an episode, so there is nothing to invent here.
        videoType = null,
        category = "Podcast",
        feedbackTokens = null,
        resultType = "Podcast",
        year = this.createdDay,
    )

@JvmName("PodcastBrowseEpisodeItemtoListTrack")
fun List<PodcastBrowse.EpisodeItem>.toListTrack(): ArrayList<Track> {
    val listTrack = arrayListOf<Track>()
    for (item in this) {
        listTrack.add(item.toTrack())
    }
    return listTrack
}

fun Lyrics.toSyncedLrcString(): String? {
    val lines = this.lines
    if (lines.isNullOrEmpty() || this.syncType != "LINE_SYNCED") {
        return null
    }
    return lines.joinToString("\n") { line ->
        val startTimeMs = line.startTimeMs.toLong()
        val minutes = (startTimeMs / 60000).toString().padStart(2, '0')
        val seconds = ((startTimeMs % 60000) / 1000).toString().padStart(2, '0')
        val milliseconds = ((startTimeMs % 1000) / 10).toString().padStart(2, '0')

        // Add space before the content as it was removed in the parsing function
        val content = if (line.words == "♫") " " else " ${line.words}"

        "[$minutes:$seconds.$milliseconds]$content"
    }
}

/**
 * Reverse of [parseRichSyncLyrics]: converts RICH_SYNCED Lyrics back to the rich sync LRC string format.
 *
 * Output format (one per line):
 * [MM:SS.mm] <MM:SS.mm>word <MM:SS.mm>word ...
 */
fun Lyrics.toRichSyncLrcString(): String? {
    val lines = this.lines
    if (lines.isNullOrEmpty() || this.syncType != "RICH_SYNCED") {
        return null
    }
    return lines.joinToString("\n") { line ->
        val startTimeMs = line.startTimeMs.toLongOrNull() ?: 0L
        val minutes = (startTimeMs / 60000).toString().padStart(2, '0')
        val seconds = ((startTimeMs % 60000) / 1000).toString().padStart(2, '0')
        val centiseconds = ((startTimeMs % 1000) / 10).toString().padStart(2, '0')

        "[$minutes:$seconds.$centiseconds] ${line.words.replace("  ", " ")}"
    }
}

/**
 * Converts RICH_SYNCED Lyrics to LINE_SYNCED Lyrics by stripping word-level <MM:SS.mm> timing
 * from each line's words, keeping only the plain text and line-level timing.
 */
fun Lyrics.toSyncedLyrics(): Lyrics {
    val lines = this.lines
    if (lines.isNullOrEmpty() || this.syncType != "RICH_SYNCED") {
        return this
    }
    val wordTimingRegex = Regex("""<\d{1,2}:\d{2}\.\d{2,3}>""")
    val syncedLines =
        lines.map { line ->
            val plainWords =
                line.words
                    .replace(wordTimingRegex, "")
                    .replace("  ", " ")
                    .trim()
            Line(
                endTimeMs = line.endTimeMs,
                startTimeMs = line.startTimeMs,
                syllables = line.syllables,
                words = plainWords.ifBlank { "♫" },
            )
        }
    return Lyrics(
        error = this.error,
        lines = syncedLines,
        syncType = "LINE_SYNCED",
        simpMusicLyrics = this.simpMusicLyrics,
    )
}

fun Lyrics.toPlainLrcString(): String? {
    val lines = this.lines
    if (lines.isNullOrEmpty()) {
        return null
    }
    return lines.joinToString("\n") { it.words }
}

fun List<String>.connectArtists(): String {
    val stringBuilder = StringBuilder()

    for ((index, artist) in this.withIndex()) {
        stringBuilder.append(artist)

        if (index < this.size - 1) {
            stringBuilder.append(", ")
        }
    }

    return stringBuilder.toString()
}