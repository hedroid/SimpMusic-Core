package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.entities.DownloadState.STATE_NOT_DOWNLOADED
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.extension.now
import com.maxrave.domain.source.MusicSource
import kotlinx.datetime.LocalDateTime

@Entity(tableName = "song")
data class SongEntity(
    @PrimaryKey(autoGenerate = false) val videoId: String = "",
    /**
     * 方案B:独立 source 列。主键仍只存平台原始 ID(YT 11 位字母数字 / 网易纯数字,
     * 实际不可能撞车),按源分叉(取流/下载格式/歌词来源)查这个字段,不玩前缀约定。
     */
    @ColumnInfo(defaultValue = "YOUTUBE_MUSIC")
    val source: String = MusicSource.YOUTUBE_MUSIC.name,
    val albumId: String? = null,
    val albumName: String? = null,
    val artistId: List<String>? = null,
    val artistName: List<String>? = null,
    val duration: String,
    val durationSeconds: Int,
    val isAvailable: Boolean,
    val isExplicit: Boolean,
    val likeStatus: String,
    val thumbnails: String? = null,
    val title: String,
    val videoType: String,
    val category: String?,
    val resultType: String?,
    val liked: Boolean = false,
    val totalPlayTime: Long = 0,
    val downloadState: Int = STATE_NOT_DOWNLOADED,
    val favoriteAt: LocalDateTime? = now(),
    val downloadedAt: LocalDateTime? = now(),
    val inLibrary: LocalDateTime = now(),
    val canvasUrl: String? = null,
    val canvasThumbUrl: String? = null,
    /**
     * 文件式下载(第二代):转存出的真实音频文件绝对路径。null=从未走过文件下载
     * (旧 SimpleCache 下载或未下载)。已下载判定一律以此列+File.exists 为准,
     * 旧缓存条目 downloadState=3 而此列 null 时按"未下载"处理(两代并存)。
     */
    @ColumnInfo(defaultValue = "NULL")
    val downloadedFilePath: String? = null,
    /** 文件式下载的视频版(音视频 merge 后的 mp4),语义同上;音频播放可用它兜底 */
    @ColumnInfo(defaultValue = "NULL")
    val downloadedVideoFilePath: String? = null,
    /**
     * 网易播客节目 id(2026-10-01 下载页播客 tab):非空=song 行是播客节目而非歌曲。
     * 取流/播放仍用主键(videoId=mainSong.id),此列只承载"这是播客"的归类语义
     * (队列隔离:点播客下载项组纯播客队列,不与歌曲混排)。
     */
    @ColumnInfo(defaultValue = "NULL")
    val neteaseProgramId: Long? = null,
) : RecentlyType {
    override fun objectType(): RecentlyType.Type = RecentlyType.Type.SONG
}