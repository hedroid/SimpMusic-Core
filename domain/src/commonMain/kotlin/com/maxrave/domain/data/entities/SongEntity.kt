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
) : RecentlyType {
    override fun objectType(): RecentlyType.Type = RecentlyType.Type.SONG
}