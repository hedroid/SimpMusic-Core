package com.maxrave.domain.mediaservice.handler

import kotlinx.coroutines.flow.StateFlow

interface DownloadHandler {
    /** 音频下载:入队一个文件式下载任务(参数快照随任务持久化;Wi-Fi/磁盘预检在实现内)。
     *  返回 false=入队被拒(磁盘余量不足等),调用方可 toast。 */
    suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ): Boolean

    /**
     * 视频下载(文件式,仅 YT 歌):音视频双流任务,双双完成后 merge 成 mp4 落
     * Movies/SimpMusic。与音频下载相互独立——同一首歌可以只有其中之一。
     */
    suspend fun downloadVideo(
        videoId: String,
        title: String,
        thumbnail: String,
    ): Boolean

    fun removeDownload(videoId: String)

    /** 只删音频文件/缓存条目,保留视频(下载管理页分列操作) */
    fun removeAudioDownload(videoId: String)

    /** 只删视频文件/条目,保留音频 */
    fun removeVideoDownload(videoId: String)

    fun removeAllDownloads()

    /** 文件式已下载判定:Room 有音频文件路径且文件真的在(旧 SimpleCache 下载恒 false) */
    suspend fun isAudioFileDownloaded(videoId: String): Boolean

    /** 同上,视频文件 */
    suspend fun isVideoFileDownloaded(videoId: String): Boolean

    /** 是否在(下载中或排队)音频任务队列里 */
    fun isAudioQueuedOrDownloading(videoId: String): Boolean

    /** 是否在视频任务队列里(音频或视频任一在途即算) */
    fun isVideoQueuedOrDownloading(videoId: String): Boolean

    val downloads: StateFlow<Map<String, Pair<Download?, Download?>>>

    val downloadTask: StateFlow<Map<String, Int>>

    /**
     * Copy from Media3
     */
    companion object State {
        const val STATE_QUEUED: Int = 0

        /** The download is stopped for a specified [.stopReason].  */
        const val STATE_STOPPED: Int = 1

        /** The download is currently started.  */
        const val STATE_DOWNLOADING: Int = 2

        /** The download completed.  */
        const val STATE_COMPLETED: Int = 3

        /** The download failed.  */
        const val STATE_FAILED: Int = 4

        /** The download is being removed.  */
        const val STATE_REMOVING: Int = 5

        /** The download will restart after all downloaded data is removed.  */
        const val STATE_RESTARTING: Int = 7
    }

    data class Download(
        val state: Int,
    )
}
