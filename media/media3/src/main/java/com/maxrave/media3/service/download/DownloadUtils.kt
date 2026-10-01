package com.maxrave.media3.service.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.extension.isFullyCached
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

@UnstableApi
internal class DownloadUtils(
    private val context: Context,
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
    databaseProvider: DatabaseProvider,
) : DownloadHandler {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        const val TAG = "DownloadUtils"
        const val FILE_REQUEST_PREFIX = "FILEv1|"

        /** 单任务手动暂停的 stopReason 值(区别于移除过渡等 stopReason=0 的 STOPPED) */
        const val MANUAL_PAUSE_REASON = 1
    }

    /** 文件式转存端(COMPLETED 后缓存→真实文件);惰性避免构造期碰 Koin */
    private val exporter by lazy {
        FileDownloadExporter(context, downloadCache, songRepository, dataStoreManager, downloadManager) {
            landedFileIds.add(it)
        }
    }

    /** 文件式任务的 songId 集合(DownloadIndex 扫描+入队时填充):它们 COMPLETED≠已下载,要等转存 */
    private val fileBasedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 文件已落地的文件式 songId 集(collect 写终态的唯一依据):exporter 成功回调+启动
     * 对账(Room 有路径)填充。没有它,并发期全量 collect 重放会把 exporter 刚写的 3
     * 打回 2/0(state 列多写者竞态,实测 52 条并发后全表错乱)——终态写入只认这个集合。
     */
    private val landedFileIds = ConcurrentHashMap.newKeySet<String>()

    /** 启动对账(landed 填充)是否完成——完成前 collect 不写"转存中(2)",防把 3 打成 2 后无人修 */
    private val landedInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 仅 Wi-Fi 下载→DownloadManager requirements(原生排队语义:蜂窝下任务等待,回 Wi-Fi 自动续) */
    private fun applyNetworkRequirements() {
        runBlocking {
            val wifiOnly = dataStoreManager.downloadWifiOnly.firstOrNull() == DataStoreManager.TRUE
            downloadManager.requirements =
                if (wifiOnly) {
                    Requirements(Requirements.NETWORK_UNMETERED)
                } else {
                    Requirements(Requirements.NETWORK)
                }
        }
    }

    private fun applyParallelDownloads() {
        runBlocking {
            downloadManager.maxParallelDownloads = dataStoreManager.simultaneousDownloads.firstOrNull() ?: 3
        }
    }

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        OkHttpClient
                            .Builder()
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            Logger.w("Stream", mediaId)
            Logger.w("Stream", mediaId.startsWith(MERGING_DATA_TYPE.VIDEO).toString())
            // Already downloaded in full: hand the DataSpec back untouched so the enclosing
            // CacheDataSource reads straight off disk. Safe precisely because the whole track
            // is there — it never needs the upstream, which is the only thing the bare media id
            // in the URI cannot survive. Re-resolving here would re-fetch a track the user
            // already has.
            if (downloadCache.isFullyCached(mediaId, dataSpec.position)) {
                Logger.w("Stream", "Already downloaded $mediaId")
                return@Factory dataSpec
            }
            // Anything short of a full copy must resolve a real URL, and must never hand back
            // the incoming DataSpec: its URI is the bare media id. Skipping the bytes already on
            // disk is not this resolver's job — CacheWriter does that via cache.getCachedLength(),
            // and the CacheDataSource wrapping this resolver still serves whatever playerCache
            // holds.
            //
            // The upstream here is OkHttpDataSource (not DefaultDataSource, as on the playback
            // side), so a scheme-less URI dies in HttpUrl.parse as
            // HttpDataSourceException("Malformed URL", ERROR_CODE_FAILED_RUNTIME_CHECK) — a
            // misleading error for what is really "we could not get a stream URL". Fail loudly
            // instead, and let DownloadManager mark the download failed for the true reason.
            var dataSpecReturn: DataSpec = dataSpec
            var resolved = false
            runBlocking(Dispatchers.IO) {
                if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                    val id = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    streamRepository.getNewFormat(id).lastOrNull()?.let {
                        val videoUrl = it.videoUrl
                        if (videoUrl != null && it.expiredTime > now()) {
                            Logger.d("Stream", videoUrl)
                            Logger.w("Stream", "Video from format")
                            val is403Url = streamRepository.is403Url(videoUrl).firstOrNull() != false
                            if (!is403Url) {
                                dataSpecReturn = dataSpec.withUri(videoUrl.toUri())
                                resolved = true
                                return@runBlocking
                            }
                        }
                    }
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            id,
                            isDownloading = true,
                            true,
                        ).lastOrNull()
                        ?.let {
                            dataSpecReturn = dataSpec.withUri(it.toUri())
                            resolved = true
                        }
                } else {
                    streamRepository.getNewFormat(mediaId).lastOrNull()?.let {
                        val audioUrl = it.audioUrl
                        if (audioUrl != null && it.expiredTime > now()) {
                            Logger.d("Stream", audioUrl)
                            Logger.w("Stream", "Audio from format")
                            val is403Url = streamRepository.is403Url(audioUrl).firstOrNull() != false
                            if (!is403Url) {
                                dataSpecReturn = dataSpec.withUri(audioUrl.toUri())
                                resolved = true
                                return@runBlocking
                            }
                        }
                    }
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            mediaId,
                            isDownloading = true,
                            isVideo = false,
                        ).lastOrNull()
                        ?.let {
                            if (it.contains("MPD")) {
                                DashManifestParser().parse(
                                    it.toUri(),
                                    ByteArrayInputStream(it.toByteArray()),
                                )
                            }
                            dataSpecReturn = dataSpec.withUri(it.toUri())
                            resolved = true
                        }
                }
            }
            if (!resolved) {
                Logger.e("Stream", "Failed to resolve download stream URL for $mediaId")
                throw java.io.IOException("Failed to resolve stream URL for $mediaId")
            }
            return@Factory dataSpecReturn
        }
    val downloadNotificationHelper =
        DownloadNotificationHelper(
            context,
            MusicDownloadService.Companion.CHANNEL_ID,
        )
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run),
        ).apply {
            maxParallelDownloads = 20
            minRetryCount = 3
            addListener(
                MusicDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = MusicDownloadService.NOTIFICATION_ID + 1,
                ),
            )
        }
    private var _downloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())

    // Audio / Video
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>
        get() = _downloads
    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> get() = _downloadTask
    val downloadingVideoIds = MutableStateFlow<MutableSet<String>>(mutableSetOf())

    /**
     * 文件式任务标记:DownloadRequest.data = "FILEv1|<标题>"。
     * 旧一代(SimpleCache)任务的 data 是纯标题文本——没有该前缀即按旧语义处理
     * (COMPLETED 直接置已下载,不触发转存),两代互不干扰。前缀而非 JSON:media3
     * 模块不带 kotlinx-serialization,别为两字节的元数据加依赖。
     */
    private fun isFileBasedRequest(data: ByteArray?): Boolean =
        data != null && runCatching { data.decodeToString().startsWith(FILE_REQUEST_PREFIX) }.getOrDefault(false)

    private fun buildFileRequestData(title: String): ByteArray = (FILE_REQUEST_PREFIX + title).toByteArray()

    /** media3 Download → 契约层(含进度/字节/暂停原因,下载管理页消费) */
    private fun toHandlerDownload(d: Download): DownloadHandler.Download =
        DownloadHandler.Download(
            state = d.state,
            stopReason = d.stopReason,
            bytesDownloaded = d.bytesDownloaded,
            contentLength = d.contentLength,
            percentDownloaded = d.percentDownloaded.toInt(),
        )

    /** 磁盘预检:公共音乐卷剩余 <500MB 拒绝入队(无损 flac 单首几十 MB,写满=静默失败) */
    private fun hasEnoughDisk(): Boolean =
        runCatching {
            val stat = StatFs(FileDownloadExporter.AUDIO_ROOT_REL.let { android.os.Environment.getExternalStorageDirectory() }.absolutePath)
            stat.availableBytes > 500L * 1024 * 1024
        }.getOrDefault(true)

    /**
     * 音频文件下载(第二代):DownloadManager 打底下载进缓存,COMPLETED 后由
     * [FileDownloadExporter] 转存成真实文件。返回 false=磁盘预检未过(未入队)。
     */
    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ): Boolean {
        if (!hasEnoughDisk()) {
            Logger.w(TAG, "downloadTrack rejected: low disk for $videoId")
            return false
        }
        // 重下场景(文件被外部删/转存失败残留):DownloadIndex 里的 COMPLETED 条目会挡住
        // addDownload 重跑(addDownload 对已存在条目不重启),先清——两个调用走同一内部
        // handler 队列,顺序有保证
        val existing = downloadManager.downloadIndex.getDownload(videoId)
        if (existing != null && existing.state == Download.STATE_COMPLETED) {
            runCatching { downloadManager.removeDownload(videoId) }
                .onFailure { Logger.w(TAG, "clear stale index entry failed: ${it.message}") }
        }
        val downloadRequest =
            DownloadRequest
                .Builder(videoId, videoId.toUri())
                .setData(buildFileRequestData(title))
                .setCustomCacheKey(videoId)
                .build()
        fileBasedIds.add(videoId)
        DownloadService.sendAddDownload(
            context,
            MusicDownloadService::class.java,
            downloadRequest,
            false,
        )
        return true
    }

    /**
     * 视频文件下载(仅 YT):音视频两条任务,双双 COMPLETED 后 merge 成 mp4 落
     * Movies/SimpMusic。音频条目与纯音频下载共用同一 cacheKey——已下载过音频则
     * 该条目直接 COMPLETED,只补视频条目,对账逻辑天然兼容。
     */
    override suspend fun downloadVideo(
        videoId: String,
        title: String,
        thumbnail: String,
    ): Boolean {
        if (!hasEnoughDisk()) {
            Logger.w(TAG, "downloadVideo rejected: low disk for $videoId")
            return false
        }
        downloadTrack(videoId, title, thumbnail)
        val id = MERGING_DATA_TYPE.VIDEO + videoId
        val downloadRequestVideo =
            DownloadRequest
                .Builder(id, id.toUri())
                .setData(buildFileRequestData("Video $title"))
                .setCustomCacheKey(id)
                .build()
        DownloadService.sendAddDownload(
            context,
            MusicDownloadService::class.java,
            downloadRequestVideo,
            false,
        )
        return true
    }

    override suspend fun removeDownload(videoId: String) {
        removeAudioDownload(videoId)
        removeVideoDownload(videoId)
    }

    /** 删音频:有文件→删文件+MediaStore 行+Room 清列;缓存条目一并清(转存失败残留) */
    override suspend fun removeAudioDownload(videoId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                songRepository.getSongById(videoId).firstOrNull()?.downloadedFilePath?.let { path ->
                    deleteMediaByPath(path)
                    File(path).delete()
                    songRepository.updateDownloadedFilePath(videoId, null)
                }
                // 先落 0 再发移除:collect 对 REMOVING 过渡有"文件在→保 3"防线(见 NOT_DOWNLOADED
                // 分支),删除路径必须在条目移除事件到达前就把路径清掉+state 归 0,防误保 3
                songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            }
            landedFileIds.remove(videoId)
            DownloadService.sendRemoveDownload(
                context,
                MusicDownloadService::class.java,
                videoId,
                false,
            )
        }
    }

    override suspend fun removeVideoDownload(videoId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                songRepository.getSongById(videoId).firstOrNull()?.downloadedVideoFilePath?.let { path ->
                    deleteMediaByPath(path)
                    File(path).delete()
                    songRepository.updateDownloadedVideoFilePath(videoId, null)
                }
                // 只删视频:音频文件还在则维持"已下载"(state 是歌曲级,音频仍是有效下载)
                val audioExists =
                    songRepository.getSongById(videoId).firstOrNull()?.downloadedFilePath
                        ?.let { File(it).exists() } == true
                songRepository.updateDownloadState(
                    videoId,
                    if (audioExists) DownloadState.STATE_DOWNLOADED else DownloadState.STATE_NOT_DOWNLOADED,
                )
                if (!audioExists) landedFileIds.remove(videoId)
            }
            DownloadService.sendRemoveDownload(
                context,
                MusicDownloadService::class.java,
                MERGING_DATA_TYPE.VIDEO + videoId,
                false,
            )
        }
    }

    /** 按 DATA 绝对路径删 MediaStore 行(自己贡献的行免权限);File.delete 兜底双清 */
    private fun deleteMediaByPath(path: String) {
        runCatching {
            val resolver = context.contentResolver
            listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null)?.use { c ->
                    while (c.moveToFirst()) {
                        resolver.delete(Uri.withAppendedPath(collection, c.getLong(0).toString()), null, null)
                        break
                    }
                }
            }
        }.onFailure { Logger.w(TAG, "deleteMediaByPath failed: ${it.message}") }
    }

    override suspend fun removeAllDownloads() {
        withContext(Dispatchers.IO) {
            // 文件式:遍历删文件+Room 清列(下载管理页/设置入口共用)
            runCatching {
                songRepository.getDownloadedSongs().firstOrNull()?.forEach { song ->
                    song.downloadedFilePath?.let {
                        deleteMediaByPath(it); File(it).delete(); songRepository.updateDownloadedFilePath(song.videoId, null)
                    }
                    song.downloadedVideoFilePath?.let {
                        deleteMediaByPath(it); File(it).delete(); songRepository.updateDownloadedVideoFilePath(song.videoId, null)
                    }
                    songRepository.updateDownloadState(song.videoId, DownloadState.STATE_NOT_DOWNLOADED)
                    landedFileIds.remove(song.videoId)
                }
            }
            _downloads.value = emptyMap()
            _downloadTask.value = emptyMap()
            downloadingVideoIds.value = mutableSetOf()
            downloadManager.removeAllDownloads()
        }
    }

    override suspend fun isAudioFileDownloaded(videoId: String): Boolean =
        songRepository.getSongById(videoId).firstOrNull()?.downloadedFilePath
            ?.let { File(it).exists() } == true

    override suspend fun isVideoFileDownloaded(videoId: String): Boolean =
        songRepository.getSongById(videoId).firstOrNull()?.downloadedVideoFilePath
            ?.let { File(it).exists() } == true

    override fun isAudioQueuedOrDownloading(videoId: String): Boolean {
        val state = _downloads.value[videoId]?.first?.state ?: return false
        return state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING ||
            state == Download.STATE_RESTARTING || state == Download.STATE_STOPPED
    }

    override fun isVideoQueuedOrDownloading(videoId: String): Boolean {
        val pair = _downloads.value[videoId] ?: return false
        val audioBusy = pair.first?.state?.let { it == Download.STATE_QUEUED || it == Download.STATE_DOWNLOADING || it == Download.STATE_RESTARTING || it == Download.STATE_STOPPED } == true
        val videoBusy = pair.second?.state?.let { it == Download.STATE_QUEUED || it == Download.STATE_DOWNLOADING || it == Download.STATE_RESTARTING || it == Download.STATE_STOPPED } == true
        // 只有视频条目在途才算"视频下载中";纯音频任务不算(视频入口的三态判定用)
        return videoBusy || (audioBusy && pair.second != null)
    }

    /** 对单个条目(完整 id)发 stopReason;条目不在列表时跳过,避免 media3 侧 error log 噪音 */
    private fun sendStopReasonIfPresent(
        id: String,
        stopReason: Int,
        requireInFlight: Boolean,
    ) {
        val songId = id.removePrefix(MERGING_DATA_TYPE.VIDEO)
        val entry = _downloads.value[songId] ?: return
        val d = if (id.startsWith(MERGING_DATA_TYPE.VIDEO)) entry.second else entry.first
        if (d == null) return
        if (requireInFlight) {
            val s = d.state
            if (s != Download.STATE_QUEUED && s != Download.STATE_DOWNLOADING) return
        } else {
            // 恢复:只动"手动暂停"的条目,不碰其它状态
            if (d.state != Download.STATE_STOPPED || d.stopReason == 0) return
        }
        DownloadService.sendSetStopReason(context, MusicDownloadService::class.java, id, stopReason, false)
    }

    override fun pauseDownload(videoId: String) {
        sendStopReasonIfPresent(videoId, MANUAL_PAUSE_REASON, requireInFlight = true)
        sendStopReasonIfPresent(MERGING_DATA_TYPE.VIDEO + videoId, MANUAL_PAUSE_REASON, requireInFlight = true)
    }

    override fun resumeDownload(videoId: String) {
        sendStopReasonIfPresent(videoId, Download.STOP_REASON_NONE, requireInFlight = false)
        sendStopReasonIfPresent(MERGING_DATA_TYPE.VIDEO + videoId, Download.STOP_REASON_NONE, requireInFlight = false)
    }

    override suspend fun retryDownload(videoId: String) {
        listOf(videoId, MERGING_DATA_TYPE.VIDEO + videoId).forEach { id ->
            runCatching { downloadManager.downloadIndex.getDownload(id) }.getOrNull()
                ?.takeIf { it.state == Download.STATE_FAILED }
                ?.let { DownloadService.sendAddDownload(context, MusicDownloadService::class.java, it.request, false) }
        }
    }

    override fun pauseAllActiveDownloads() {
        _downloads.value.forEach { (songId, pair) ->
            val inFlight = { s: Int -> s == Download.STATE_QUEUED || s == Download.STATE_DOWNLOADING }
            if (pair.first?.state?.let(inFlight) == true) {
                DownloadService.sendSetStopReason(context, MusicDownloadService::class.java, songId, MANUAL_PAUSE_REASON, false)
            }
            if (pair.second?.state?.let(inFlight) == true) {
                DownloadService.sendSetStopReason(context, MusicDownloadService::class.java, MERGING_DATA_TYPE.VIDEO + songId, MANUAL_PAUSE_REASON, false)
            }
        }
    }

    override fun resumeAllPausedDownloads() {
        _downloads.value.forEach { (songId, pair) ->
            val paused = { d: DownloadHandler.Download -> d.state == Download.STATE_STOPPED && d.stopReason != 0 }
            if (pair.first?.let(paused) == true) {
                DownloadService.sendSetStopReason(context, MusicDownloadService::class.java, songId, Download.STOP_REASON_NONE, false)
            }
            if (pair.second?.let(paused) == true) {
                DownloadService.sendSetStopReason(context, MusicDownloadService::class.java, MERGING_DATA_TYPE.VIDEO + songId, Download.STOP_REASON_NONE, false)
            }
        }
    }

    override suspend fun retryAllFailedDownloads(): Int {
        var retried = 0
        runCatching {
            downloadManager.downloadIndex.getDownloads(Download.STATE_FAILED).use { cursor ->
                while (cursor.moveToNext()) {
                    DownloadService.sendAddDownload(context, MusicDownloadService::class.java, cursor.download.request, false)
                    retried++
                }
            }
        }.onFailure { Logger.w(TAG, "retryAllFailedDownloads failed: ${it.message}") }
        return retried
    }

    init {
        coroutineScope.launch {
            downloads.collect { download ->
                download.forEach {
                    val videoId = it.key
                    val audioDownload = it.value.first
                    val videoDownload = it.value.second
                    val audio = audioDownload?.state
                    val video = videoDownload?.state
                    // 手动暂停(stopReason!=0 的 STOPPED):保持可见,写成排队态——
                    // 下载管理页从 downloads 流读"已暂停",song 表只保证它不出"已下载"
                    val manuallyPaused =
                        (audioDownload?.state == Download.STATE_STOPPED && audioDownload.stopReason != 0) ||
                            (videoDownload?.state == Download.STATE_STOPPED && videoDownload.stopReason != 0)
                    val combineState =
                        // Removal transits through STOPPED/REMOVING/RESTARTING; classifying those
                        // as NOT_DOWNLOADED (rather than the "downloading" fall-through below)
                        // keeps removeDownload() from writing the song back as "downloading" —
                        // which container watchers read as a live download and re-queue.
                        if (manuallyPaused) {
                            DownloadState.STATE_PREPARING
                        } else if (audio == Download.STATE_STOPPED ||
                            audio == Download.STATE_REMOVING ||
                            audio == Download.STATE_RESTARTING ||
                            video == Download.STATE_STOPPED ||
                            video == Download.STATE_REMOVING ||
                            video == Download.STATE_RESTARTING
                        ) {
                            DownloadState.STATE_NOT_DOWNLOADED
                        } else {
                            when (audio to video) {
                                Download.STATE_COMPLETED to Download.STATE_COMPLETED -> DownloadState.STATE_DOWNLOADED
                                Download.STATE_FAILED to Download.STATE_FAILED -> DownloadState.STATE_NOT_DOWNLOADED
                                Download.STATE_QUEUED to Download.STATE_QUEUED -> DownloadState.STATE_PREPARING
                                Download.STATE_COMPLETED to null -> DownloadState.STATE_DOWNLOADED
                                Download.STATE_FAILED to null -> DownloadState.STATE_NOT_DOWNLOADED
                                Download.STATE_QUEUED to null -> DownloadState.STATE_PREPARING
                                null to Download.STATE_COMPLETED -> DownloadState.STATE_DOWNLOADING
                                null to Download.STATE_QUEUED -> DownloadState.STATE_PREPARING
                                null to Download.STATE_FAILED -> DownloadState.STATE_NOT_DOWNLOADED
                                else -> DownloadState.STATE_DOWNLOADING
                            }
                        }
                    _downloadTask.update {
                        it.toMutableMap().apply {
                            set(videoId, combineState)
                        }
                    }
                    when (combineState) {
                        DownloadState.STATE_DOWNLOADED -> {
                            downloadingVideoIds.update {
                                it.apply {
                                    remove(videoId)
                                }
                            }
                            // 文件式:终态只认 landedFileIds(exporter 成功回调填充)——文件落地
                            // 前 state 保持"下载中"(UI 读作转存中);旧一代维持完成即 3。
                            if (videoId in fileBasedIds) {
                                if (videoId in landedFileIds) {
                                    if (_downloadTask.value[videoId] != DownloadState.STATE_DOWNLOADED) {
                                        songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                                    }
                                } else if (landedInitialized.get()) {
                                    songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
                                }
                            } else {
                                songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                            }
                        }
                        DownloadState.STATE_DOWNLOADING -> {
                            songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
                        }
                        DownloadState.STATE_NOT_DOWNLOADED -> {
                            downloadingVideoIds.update {
                                it.apply {
                                    remove(videoId)
                                }
                            }
                            // 文件式:REMOVING/STOPPED 过渡不写终态——删除路径(removeAudio/Video
                            // Download)自己清路径+落 0;这里写会把"文件已落地"的歌打回 0(实测
                            // 转存完成清条目时触发,管理页误报文件已丢失)。旧一代维持原语义。
                            if (videoId !in fileBasedIds) {
                                songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
                            }
                        }
                        DownloadState.STATE_PREPARING -> {
                            downloadingVideoIds.update {
                                it.apply {
                                    remove(videoId)
                                }
                            }
                            songRepository.updateDownloadState(videoId, DownloadState.STATE_PREPARING)
                        }
                    }
                }
            }
        }

        // Pair Audio and Video
        val result = mutableMapOf<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        val pendingExports = mutableListOf<Pair<String, Boolean>>() // songId to isVideoPair
        while (cursor.moveToNext()) {
            val id = cursor.download.request.id
            val isVideo = id.contains(MERGING_DATA_TYPE.VIDEO)
            val songId =
                if (id.contains(MERGING_DATA_TYPE.VIDEO)) {
                    id.removePrefix(MERGING_DATA_TYPE.VIDEO)
                } else {
                    id
                }
            if (isFileBasedRequest(cursor.download.request.data)) {
                fileBasedIds.add(songId)
                // 启动对账:COMPLETED 但文件还没落地(转存途中 app 被杀/上次失败)→重试转存。
                // 必须查 Room 路径:转存成功的条目缓存已清(exporter removeResource),不查的话
                // 每轮启动都白试一遍 cache-miss→清条目→REMOVING,把 map 抖成过渡态(实测
                // landed=true 的行反复被打 combine=0 的元凶)。
                if (cursor.download.state == Download.STATE_COMPLETED) {
                    val hasStoredPath =
                        runCatching {
                            runBlocking { songRepository.getSongById(songId).firstOrNull() }
                                ?.let { it.downloadedFilePath != null || it.downloadedVideoFilePath != null } == true
                        }.getOrDefault(false)
                    if (!hasStoredPath) pendingExports += songId to isVideo
                }
            }
            result[songId] =
                if (isVideo) {
                    result[songId]?.copy(second = toHandlerDownload(cursor.download))
                        ?: Pair(null, toHandlerDownload(cursor.download))
                } else {
                    result[songId]?.copy(first = toHandlerDownload(cursor.download))
                        ?: Pair(toHandlerDownload(cursor.download), null)
                }
        }
        _downloads.value = result
        // 启动对账:Room 有路径的行=文件已落地——填充 landed 集,并把文件式条目被过渡态
        // 写坏的 state(手动暂停/上代进程竞态残留的 0/1)纠正回 3
        coroutineScope.launch {
            runCatching {
                songRepository.getDownloadActivitySongs().firstOrNull()?.forEach { song ->
                    // 有路径=文件式已落地(不看 fileBased:条目可能已被 cache-miss 对账清掉,
                    // 历史竞态写坏的 state 也要在此一并纠正——删除路径先清路径再落 0,不会
                    // 被这里的"路径在"误纠)
                    if (song.downloadedFilePath != null || song.downloadedVideoFilePath != null) {
                        landedFileIds.add(song.videoId)
                        if (song.downloadState != DownloadState.STATE_DOWNLOADED) {
                            songRepository.updateDownloadState(song.videoId, DownloadState.STATE_DOWNLOADED)
                        }
                    }
                }
            }.onFailure { Logger.w(TAG, "landed reconcile failed: ${it.message}") }
            if (landedFileIds.isNotEmpty()) landedInitialized.set(true)
        }
        if (pendingExports.isNotEmpty()) {
            coroutineScope.launch {
                // 同一首歌音视频都在列时按视频 merge 一次;纯音频按音频转存
                val bySong = pendingExports.groupBy({ it.first }, { it.second })
                bySong.forEach { (songId, entries) ->
                    runCatching {
                        if (entries.any { it }) {
                            val audioDone = _downloads.value[songId]?.first?.state == Download.STATE_COMPLETED
                            val videoDone = _downloads.value[songId]?.second?.state == Download.STATE_COMPLETED
                            if (audioDone && videoDone) {
                                exporter.exportVideo(songId)
                            } else if (audioDone) {
                                exporter.exportAudio(songId)
                            }
                        } else {
                            // 只有音频条目:视频条目不存在→纯音频转存;存在但未完成→等它
                            if (_downloads.value[songId]?.second == null) exporter.exportAudio(songId)
                        }
                    }
                }
            }
        }
        // 文件式下载的网络与并发设置(每次启动重放一次;设置页改动的实时重放在 collect 里)
        applyNetworkRequirements()
        applyParallelDownloads()
        // 冷启动队列自愈:DownloadManager 构造默认 downloadsPaused,只有 DownloadService
        // onCreate 才 resume——app 重启后没人拉 service 的话,QUEUED 任务会死等(实测:
        // 重启后 12 条 QUEUED 零条在跑)。普通 start() 起的 service 调 startForeground
        // 会被 FGS 规则拒(无前台链路时 DENIED),必须 startForegroundService——仅在
        // index 里确有在途条目时才用,无任务空启动不弹前台通知。
        val hasInFlightQueue =
            runCatching {
                downloadManager.downloadIndex.getDownloads(
                    Download.STATE_QUEUED,
                    Download.STATE_DOWNLOADING,
                ).use { it.count > 0 }
            }.getOrDefault(false)
        runCatching {
            if (hasInFlightQueue) {
                DownloadService.startForeground(context, MusicDownloadService::class.java)
            } else {
                DownloadService.start(context, MusicDownloadService::class.java)
            }
        }.onFailure { Logger.w(TAG, "queue bootstrap service start failed: ${it.message}") }
        coroutineScope.launch {
            dataStoreManager.downloadWifiOnly.collect { applyNetworkRequirements() }
        }
        coroutineScope.launch {
            dataStoreManager.simultaneousDownloads.collect { applyParallelDownloads() }
        }
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    download.request.id.let { id ->
                        var isVideo = false
                        val songId =
                            if (id.contains(MERGING_DATA_TYPE.VIDEO)) {
                                isVideo = true
                                id.removePrefix(MERGING_DATA_TYPE.VIDEO)
                            } else {
                                id
                            }
                        _downloads.update { map ->
                            map.toMutableMap().apply {
                                val current = map.getOrDefault(songId, null)
                                if (isVideo) {
                                    set(
                                        songId,
                                        current?.copy(second = toHandlerDownload(download))
                                            ?: Pair(null, toHandlerDownload(download)),
                                    )
                                } else {
                                    set(
                                        songId,
                                        current?.copy(first = toHandlerDownload(download))
                                            ?: Pair(toHandlerDownload(download), null),
                                    )
                                }
                            }
                        }
                        when (download.state) {
                            Download.STATE_COMPLETED -> {
                                playerCache.removeResource(id)
                                // 文件式任务:COMPLETED → 转存真实文件(旧一代任务不进这里)
                                if (isFileBasedRequest(download.request.data)) {
                                    coroutineScope.launch {
                                        runCatching {
                                            if (isVideo) {
                                                // 视频条目完成:音频也完成才 merge mp4(定稿:视频任务
                                                // 不产独立 mp3,音频播放用 mp4 兜底)
                                                val audioDone =
                                                    _downloads.value[songId]?.first?.state == Download.STATE_COMPLETED
                                                if (audioDone) exporter.exportVideo(songId)
                                            } else {
                                                // 音频条目完成:没有视频条目(纯音频任务)才独立转存;
                                                // 视频任务由视频条目完成时统一 merge
                                                if (_downloads.value[songId]?.second == null) {
                                                    exporter.exportAudio(songId)
                                                }
                                            }
                                        }.onFailure {
                                            if (it is kotlinx.coroutines.CancellationException) throw it
                                            Logger.e(TAG, "export dispatch failed for $songId: ${it.message}")
                                        }
                                    }
                                }
                            }

                            Download.STATE_DOWNLOADING -> {
                                coroutineScope.launch {
                                    downloadingVideoIds.update {
                                        it.apply {
                                            add(songId)
                                        }
                                    }
                                    songRepository.updateDownloadState(songId, DownloadState.STATE_DOWNLOADING)
                                }
                            }
                            else -> {
                            }
                        }
                    }
                }
            },
        )
    }
}