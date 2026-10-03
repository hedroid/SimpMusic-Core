package com.maxrave.media3.service.download

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadEnricher
import com.maxrave.domain.mediaservice.handler.EnrichedDownloadContent
import com.maxrave.domain.repository.SongRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "FileDownloadExporter"

/**
 * 文件式下载(第二代)的转存端:DownloadManager 把流下进 downloadCache(COMPLETED)后,
 * 这里把缓存读成完整文件、写 tag/封面/歌词,经 MediaStore 落进公共目录,回写 Room 路径,
 * 最后清缓存条目——用户拿到的是 Music/SimpMusic/… 下的真实 mp3/flac/mp4。
 *
 * 失败语义:任何一步失败都**保留缓存条目**返回 false,数据不丢;下次启动的
 * "COMPLETED 但 Room 无路径"对账会重试转存(见 DownloadUtils.init)。
 *
 * 音频落 Music/SimpMusic[/<主艺人>/<专辑>],视频同树(2026-10-03 用户定;MediaStore.Video 不放行 Music/,走 FUSE 直写,失败回落 Movies/SimpMusic)。
 */
@UnstableApi
internal class FileDownloadExporter(
    private val context: Context,
    private val downloadCache: SimpleCache,
    private val songRepository: SongRepository,
    private val dataStoreManager: DataStoreManager,
    private val downloadManager: androidx.media3.exoplayer.offline.DownloadManager? = null,
    /** 转存成功(videoId)回调:DownloadUtils 据此维护终态写入依据(landed 集) */
    private val onLanded: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在转存中的 key(videoId 或 VIDEO+videoId),防 listener 重入/启动对账撞车 */
    private val exporting = ConcurrentHashMap.newKeySet<String>()

    // Koin 惰性拿:repositoryModule 与 mediaServiceModule 的加载顺序无保证
    private val enricher: DownloadEnricher?
        get() = runCatching { GlobalContext.get().get<DownloadEnricher>() }.getOrNull()

    fun close() = scope.cancel()

    // ===== 删除代际门(2026-10-02 CR P1-2) =====
    // 转存是长跑协程(缓存拷贝+enricher+ffmpeg+MediaStore),删除停不住它;删除方在
    // DownloadUtils 维护每歌代际,转存开始时快照,两个提交点(写 MediaStore 前/写回
    // Room 前)核对——代际变了=用户已删,放弃写回(文件/MediaStore 行可能已多写,
    // 由 insertMediaStore 的 stale 清理与用户重下覆盖,不回滚已产生的临时文件)。
    private val deleteGenerations = ConcurrentHashMap<String, Long>()

    /** DownloadUtils 在每次删除时调:该歌代际+1,在跑转存的快照随即过期 */
    fun onDeleted(videoId: String) {
        deleteGenerations.merge(videoId, 1L) { old, _ -> old + 1 }
    }

    /** 转存开始时取当前代际快照 */
    fun currentGeneration(videoId: String): Long = deleteGenerations[videoId] ?: 0L

    /** 快照与当前代际是否一致(不一致=期间发生过删除) */
    fun isStale(videoId: String, snapshot: Long): Boolean =
        (deleteGenerations[videoId] ?: 0L) != snapshot

    /** 音频条目 COMPLETED → 转存 mp3/flac。失败保留缓存,返回 false(调用方不重复触发)。 */
    suspend fun exportAudio(videoId: String): Boolean {
        if (!exporting.add(videoId)) return false
        try {
            return exportAudioLocked(videoId)
        } finally {
            exporting.remove(videoId)
        }
    }

    /** 音+视频两条都 COMPLETED → merge 成 mp4。 */
    suspend fun exportVideo(videoId: String): Boolean {
        val gate = "video-$videoId"
        if (!exporting.add(gate)) return false
        try {
            return exportVideoLocked(videoId)
        } finally {
            exporting.remove(gate)
        }
    }

    // ============================================================ 音频

    private suspend fun exportAudioLocked(videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            val song = songRepository.getSongById(videoId).first() ?: run {
                Logger.e(TAG, "exportAudio: no song row for $videoId")
                return@withContext false
            }
            // 代际快照:此后的每个提交点核对,期间发生过删除则整次转存作废(CR P1-2)
            val generation = currentGeneration(videoId)
            val workDir = File(context.cacheDir, "dl_export").apply { mkdirs() }
            val rawInput = File(workDir, "$videoId.bin")
            try {
                // 1) 缓存 → 完整原始流(网易=mp3/flac 字节;YT=所选 itag 容器 webm/m4a)
                if (!dumpCacheTo(videoId, rawInput)) {
                    Logger.e(TAG, "exportAudio: cache miss/incomplete for $videoId")
                    // 对账路径的必然边角:转存成功后缓存已清、文件又被外部删——COMPLETED
                    // 条目在而数据没了,对账每次启动都白试。清掉 index 条目,下次点下载全新。
                    if (downloadManager != null) {
                        runCatching { downloadManager.removeDownload(videoId) }
                    }
                    return@withContext false
                }
                val isNetease = videoId.toLongOrNull() != null
                val flacMagic = flacMagicOffset(rawInput)
                val isFlac = flacMagic != null
                Logger.w(TAG, "exportAudio probe: $videoId isNetease=$isNetease flacMagicOffset=$flacMagic")

                // 2) tag/歌词尽力收集(失败全空字段,不阻塞);封面(升档 1080)用 okhttp 拉字节。
                // mp3 嵌 ID3 attached_pic;flac 由手写器嵌标准 PICTURE block(2026-10-02 用户
                // 反馈"flac 没有封面信息"——此前 flac 不嵌图是因 ffmpeg-kit 6.0.1 的 flac
                // muxer 嵌 attached_pic 会炸 InsufficientCapacity,字节级写入器绕开了它)
                val content: EnrichedDownloadContent =
                    runCatching { enricher?.enrich(song, song.thumbnails) ?: EnrichedDownloadContent() }
                        .getOrElse { EnrichedDownloadContent() }
                val ext = if (isNetease && isFlac) "flac" else "mp3"
                val coverFile =
                    song.thumbnails?.let { url -> downloadArtwork(hiResArtworkUrl(url)) }
                        ?.let { bytes -> File(workDir, "$videoId.jpg").apply { writeBytes(bytes) } }
                        ?.takeIf { it.length() > 0 }

                // 3) 成品流:flac=原始字节直落(ffmpeg-kit 6.0.1 的 flac muxer 重封即损坏——
                // 帧数据逐字节完好但头部 block 错位,ExoPlayer/ffmpeg 都解析失败;去 lyrics
                // 也一样,宿主 ffmpeg 同命令对照完好=构建级 bug,实测 2026-10-01。tag 由
                // 文件名+同名 lrc 承载);mp3 走 ffmpeg 写 tag——网易 mp3 用 -c copy 容器级
                // 重封(原格式直存,免二次有损;ID3/attached_pic 在容器层不受流 copy 影响,
                // CR P2-5:原条件 isNetease&&isFlac 在非 flac 分支恒 false,网易 mp3 全被
                // libmp3lame 重编码),YT webm 输入才 libmp3lame
                val mime = if (ext == "flac") "audio/flac" else "audio/mpeg"
                val tagged = File(workDir, "$videoId-tagged.$ext")
                if (ext == "flac") {
                    // Vorbis Comment + PICTURE 手写(ffmpeg-kit 6.0.1 flac muxer 重封即损坏,
                    // 字节级绕开;二修:剥离网易自带 comment 块去重;三修 2026-10-02:歌词入
                    // LYRICS 标签+封面入 PICTURE block,均为手写块不受坏 muxer 影响);
                    // 写失败退原始直落,不阻塞下载
                    if (!writeFlacTags(rawInput, tagged, buildTags(song, content, includeLyrics = true), coverFile)) {
                        rawInput.copyTo(tagged, overwrite = true)
                    }
                } else if (!writeTaggedAudio(rawInput, tagged, coverFile, song, content, isNetease, ext)) {
                    Logger.e(TAG, "exportAudio: ffmpeg failed for $videoId")
                    return@withContext false
                }

                // 4) 目标路径(文件名格式/目录规则/同名冲突)
                val fileName = computeAudioFileName(song, ext)
                val relPath = computeAudioRelPath(song)

                // 5) MediaStore 落盘 + Room 回写。提交点①:转存期间被删除则放弃(文件可能
                // 已写入 MediaStore,由 insertMediaStore 的 stale 清理与重下覆盖兜底)
                if (isStale(videoId, generation)) {
                    Logger.w(TAG, "exportAudio stale (deleted mid-export), discarding: $videoId")
                    return@withContext false
                }
                val stored = insertMediaStore(tagged, relPath, fileName, mime, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                if (stored == null) {
                    Logger.e(TAG, "exportAudio: MediaStore write failed for $videoId")
                    return@withContext false
                }
                // 提交点②:MediaStore 写入耗时长,写完再核一次才敢回写 Room 路径/状态
                if (isStale(videoId, generation)) {
                    Logger.w(TAG, "exportAudio stale after MediaStore write, discarding path writeback: $videoId")
                    runCatching { deleteMediaByPath(stored.absolutePath); stored.delete() }
                    return@withContext false
                }
                songRepository.updateDownloadedFilePath(videoId, stored.absolutePath)
                songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)

                // 6) lrc(开关+有词;失败只 log,不影响下载完成)
                val lrcText = content.lrcText
                if (dataStoreManager.downloadSaveLrc.first() == DataStoreManager.TRUE && !lrcText.isNullOrBlank()) {
                    runCatching { writeLrc(stored.parentFile, stored.nameWithoutExtension + ".lrc", lrcText) }
                        .onFailure { Logger.w(TAG, "lrc write failed: ${it.message}") }
                }

                // 7) 成功才清缓存
                downloadCache.removeResource(videoId)
                onLanded(videoId)
                Logger.i(TAG, "exportAudio OK: $videoId -> $stored")
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "exportAudio failed for $videoId: ${e.message}")
                false
            } finally {
                // workDir 里本条目的所有中间产物一并清(名字前缀=videoId 或其视频前缀)
                workDir.listFiles()?.forEach {
                    if (it.name.contains(videoId)) it.delete()
                }
            }
        }

    // ============================================================ 视频

    private suspend fun exportVideoLocked(videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            val song = songRepository.getSongById(videoId).first() ?: run {
                Logger.e(TAG, "exportVideo: no song row for $videoId")
                return@withContext false
            }
            // 代际快照(同 exportAudio,CR P1-2):merge/写 MediaStore 都长跑,逐点核对
            val generation = currentGeneration(videoId)
            val workDir = File(context.cacheDir, "dl_export").apply { mkdirs() }
            val audioRaw = File(workDir, "$videoId-audio.webm")
            val videoRaw = File(workDir, "$videoId-video.mp4")
            val merged = File(workDir, "$videoId-merged.mp4")
            try {
                // 音频流优先取缓存;此前纯音频下载已转存并清缓存时,回退用已落盘的
                // mp3/flac 文件当 ffmpeg 输入(-c copy 合法,音质=用户已持有的那份)
                val audioSource: File =
                    if (dumpCacheTo(videoId, audioRaw)) {
                        audioRaw
                    } else {
                        val exported = songRepository.getSongById(videoId).first()?.downloadedFilePath
                            ?.let(::File)?.takeIf { it.exists() }
                        exported ?: run {
                            Logger.e(TAG, "exportVideo: no audio stream for $videoId")
                            return@withContext false
                        }
                    }
                if (!dumpCacheTo(MERGING_DATA_TYPE.VIDEO + videoId, videoRaw)) return@withContext false
                Logger.w(TAG, "exportVideo inputs: $videoId audio=${audioSource.length()}B video=${videoRaw.length()}B")

                // merge:流 copy 不重编码,标题/艺人 tag 简版写入
                val cmd =
                    mutableListOf("-i", audioSource.path, "-i", videoRaw.path, "-map", "0:a", "-map", "1:v", "-c", "copy")
                song.title.takeIf { it.isNotBlank() }?.let { cmd += listOf("-metadata", "title=${it}") }
                song.artistName?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { cmd += listOf("-metadata", "artist=${it}") }
                cmd += listOf("-metadata", "comment=SimpMusic-Hedroid")
                cmd.add(merged.path)
                if (!runFfmpeg(cmd)) {
                    Logger.e(TAG, "exportVideo: ffmpeg merge failed for $videoId")
                    return@withContext false
                }

                // 提交点①:merge 完成、准备写 MediaStore 前核对
                if (isStale(videoId, generation)) {
                    Logger.w(TAG, "exportVideo stale (deleted mid-export), discarding: $videoId")
                    return@withContext false
                }
                val stored = storeVideoFile(merged, song)
                if (stored == null) return@withContext false
                // 提交点②:回写 Room 前再核;已写的 MediaStore 行/文件一并清掉
                if (isStale(videoId, generation)) {
                    Logger.w(TAG, "exportVideo stale after MediaStore write, cleaning: $videoId")
                    runCatching {
                        context.contentResolver.delete(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            "${MediaStore.MediaColumns.DATA}=?",
                            arrayOf(stored.absolutePath),
                        )
                    }
                    stored.delete()
                    return@withContext false
                }
                songRepository.updateDownloadedVideoFilePath(videoId, stored.absolutePath)
                // 视频完成也置下载完成态(音频文件可能没有,但音频播放可用 mp4 兜底)
                songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                downloadCache.removeResource(videoId)
                downloadCache.removeResource(MERGING_DATA_TYPE.VIDEO + videoId)
                onLanded(videoId)
                Logger.i(TAG, "exportVideo OK: $videoId -> $stored")
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "exportVideo failed for $videoId: ${e.message}")
                false
            } finally {
                audioRaw.delete()
                videoRaw.delete()
                merged.delete()
            }
        }

    // ============================================================ 公共件

    /**
     * 视频落盘(2026-10-03 用户定:与音频同树 Music/SimpMusic[/主艺人/专辑],文件管理器
     * 一处看全)。MediaStore.Video 只放行 DCIM/Movies 主目录("Primary directory Music
     * not allowed"),Music/ 下走 FUSE 直写:①.mp4 直接新建(部分 ROM 放行);②失败退
     * .mp3 媒体扩展名暂存+rename——应用是贡献者,对自家文件持有 FUSE 改名权(lrc 同款
     * 已实测);③两级都失败回落 Movies/SimpMusic 树(MediaStore.Video,目录开关同样
     * 生效)。返回实际落盘文件;全失败=null。
     */
    private suspend fun storeVideoFile(
        merged: File,
        song: SongEntity,
    ): File? {
        val stem = sanitizeFileName(song.title, song.videoId)
        val relDir = computeAudioRelPath(song)
        fuseWriteVideo(merged, relDir, stem, song.videoId, song.downloadedVideoFilePath)?.let { return it }
        // 回落:Movies/SimpMusic[/主艺人/专辑],结构对齐 Music 树
        val moviesRelDir = "Movies/" + relDir.removePrefix("Music/")
        return insertMediaStore(
            merged, moviesRelDir, "$stem.mp4",
            "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
    }

    /** FUSE 把 mp4 写进 Music 树;同名文件且非本歌残留时追加 videoId 防误删他人文件 */
    private fun fuseWriteVideo(
        merged: File,
        relDir: String,
        stem: String,
        videoId: String,
        ownStoredPath: String?,
    ): File? =
        runCatching {
            val dir = relPathToDir(relDir)
            if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed: $dir")
            var displayName = "$stem.mp4"
            var target = File(dir, displayName)
            if (target.exists() && target.absolutePath != ownStoredPath) {
                displayName = "$stem-$videoId.mp4"
                target = File(dir, displayName)
            }
            target.delete()
            runCatching {
                merged.copyTo(target, overwrite = true)
            }.onFailure { direct ->
                Logger.w(TAG, "video direct FUSE write failed (${direct.message}), try staged rename")
                val staged = File(dir, "$stem.mp3")
                staged.delete()
                merged.copyTo(staged, overwrite = true)
                if (!staged.renameTo(target)) {
                    target.delete()
                    check(staged.renameTo(target)) { "video staged rename failed" }
                }
            }
            check(target.exists() && target.length() > 0) { "video target missing after write" }
            Logger.i(TAG, "video stored via FUSE: $relDir/$displayName")
            target
        }.onFailure {
            Logger.w(TAG, "video FUSE write into $relDir failed: ${it.message}")
        }.getOrNull()

    /** 代际门清理:按 DATA 删音频 MediaStore 行+文件(自己贡献的行免权限) */
    private fun deleteMediaByPath(path: String) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path),
            )
        }
        File(path).delete()
    }

    /** 把 downloadCache 的完整流抄到文件;缓存缺失/不全直接 false(上游兜底不在这里做)。 */
    private fun dumpCacheTo(
        cacheKey: String,
        target: File,
    ): Boolean =
        runCatching {
            val source =
                CacheDataSource
                    .Factory()
                    .setCache(downloadCache)
                    .createDataSource()
            try {
                source.open(DataSpec(cacheKey.toUri(), 0L, C_LENGTH_UNSET, cacheKey))
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buf, 0, buf.size)
                        // DataSource.read 契约:0=暂时无数据(继续读),-1(RESULT_END_OF_INPUT)=流
                        // 结束。把 0 当 EOF 会把续传下载(缓存 spans 有边界)的流截断——导出的
                        // flac 帧数据损坏、ExoPlayer "First frame does not start with sync code"
                        // 即此(实测 2026-10-01)
                        if (n == -1) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                }
            } finally {
                runCatching { source.close() }
            }
            target.length() > 0
        }.onFailure { Logger.w(TAG, "dumpCacheTo failed for $cacheKey: ${it.message}") }
            .getOrDefault(false)

    /**
     * 写入最终音频:输入原始流(+可选封面),输出带全部 tag 的文件。
     * 网易 flac/mp3 一律 -c:a copy(零损失,只写容器 tag);YT(webm/m4a)转 mp3 -q:a 0。
     */
    private fun writeTaggedAudio(
        input: File,
        output: File,
        cover: File?,
        song: SongEntity,
        content: EnrichedDownloadContent,
        copyAudio: Boolean,
        ext: String,
    ): Boolean {
        val cmd = mutableListOf<String>()
        cmd += listOf("-i", input.path)
        if (cover != null) cmd += listOf("-i", cover.path)
        // 有封面:0:a + 1:v;无封面:只有 0:a
        if (cover != null) {
            cmd += listOf("-map", "0:a", "-map", "1:v")
        } else {
            cmd += listOf("-map", "0:a")
        }
        cmd += if (copyAudio) listOf("-c", "copy") else listOf("-c:a", "libmp3lame", "-q:a", "0")
        if (cover != null) {
            cmd += listOf("-c:v", "copy", "-disposition:v", "attached_pic", "-metadata:s:v", "title=Album cover")
        }
        // flac 不带 lyrics(见 buildTags 注释);mp3(ID3 USLT)暂带,标准档网易歌实测过再定
        val meta = buildTags(song, content, includeLyrics = ext == "mp3")
        meta.forEach { (k, v) -> cmd += listOf("-metadata", "$k=$v") }
        cmd.add(output.path)
        return runFfmpeg(cmd)
    }

    /** ffmpeg 通用 metadata 键(空值不写;注释 tag 固定为发布来源标记)。
     *  flac 不写 lyrics:ffmpeg-kit 6.0.1 的 flac muxer 对大体积带换行的 vorbis comment
     *  长度计算有 bug——4KB LRC 文本入 tag 后 block 边界错位,帧区解析全毁("First frame
     *  does not start with sync code",宿主 ffmpeg 同命令完好=构建差异;实测 2026-10-01
     *  逐字节对比帧数据 100% 完好、损坏全在头部 483 字节内)。歌词由同名 .lrc 文件承载。 */
    private fun buildTags(
        song: SongEntity,
        c: EnrichedDownloadContent,
        includeLyrics: Boolean,
    ): Map<String, String> =
        buildMap {
            song.title.takeIf { it.isNotBlank() }?.let { put("title", it) }
            song.artistName?.joinToString(",").takeIf { !it.isNullOrBlank() }?.let { put("artist", it) }
            c.albumArtist?.let { put("album_artist", it) }
            song.albumName?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            c.year?.let { put("date", it) }
            c.genre?.let { put("genre", it) }
            c.language?.let { put("language", it) }
            if (c.trackNumber != null) put("track", c.trackNumber.toString())
            c.discNumber?.let { put("disc", it) }
            c.lyricist?.let { put("lyricist", it) }
            c.composer?.let { put("composer", it) }
            c.copyright?.let { put("copyright", it) }
            put("comment", "SimpMusic-Hedroid")
            if (includeLyrics) c.lrcText?.let { put("lyrics", it) }
        }

    private fun runFfmpeg(args: List<String>): Boolean {
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        return when {
            ReturnCode.isSuccess(session.returnCode) -> true
            else -> {
                Logger.e(TAG, "ffmpeg failed rc=${session.returnCode} log=${session.failStackTrace?.take(400)}")
                false
            }
        }
    }

    /** ffmpeg 通用键 → FLAC Vorbis Comment 惯例大写键(lyrics 走 LYRICS 标签:大体积换行
     *  文本对坏 muxer 是坑,但字节级写入器的 24bit 块长装得下,2026-10-02 用户定歌词入标签) */
    private val flacTagKeys =
        mapOf(
            "title" to "TITLE",
            "artist" to "ARTIST",
            "album_artist" to "ALBUMARTIST",
            "album" to "ALBUM",
            "date" to "DATE",
            "genre" to "GENRE",
            "language" to "LANGUAGE",
            "track" to "TRACKNUMBER",
            "disc" to "DISCNUMBER",
            "lyricist" to "LYRICIST",
            "composer" to "COMPOSER",
            "copyright" to "COPYRIGHT",
            "comment" to "COMMENT",
            "lyrics" to "LYRICS",
        )

    /**
     * 探测 flac 魔数偏移:0=规范开头;部分网易 flac 带 ID3v2 前缀(非规范但生态常见),
     * 跳过 ID3 头后应见 "fLaC"。null=不是 flac 流。
     */
    private fun flacMagicOffset(input: File): Long? =
        runCatching {
            java.io.RandomAccessFile(input, "r").use { r ->
                val head = ByteArray(10)
                r.readFully(head)
                if (String(head, 0, 3, Charsets.US_ASCII) == "ID3") {
                    // syncsafe size(6..9)+10 字节头;footer 标志位再 +10
                    var size =
                        ((head[6].toInt() and 0x7F) shl 21) or
                            ((head[7].toInt() and 0x7F) shl 14) or
                            ((head[8].toInt() and 0x7F) shl 7) or
                            (head[9].toInt() and 0x7F)
                    size += 10
                    if (head[5].toInt() and 0x10 != 0) size += 10
                    r.seek(size.toLong())
                    val m = ByteArray(4)
                    r.readFully(m)
                    if (m.decodeToString() != "fLaC") return@runCatching null
                    size.toLong()
                } else {
                    if (!head.copyOfRange(0, 4).decodeToString().startsWith("fLaC")) return@runCatching null
                    0L
                }
            }
        }.getOrNull()

    /**
     * 手写 FLAC metadata(Vorbis Comment + 可选 PICTURE 封面)。ffmpeg-kit 6.0.1 的 flac
     * muxer 重封即损坏(实测),一切手写绕开。
     *
     * 规则:可选 ID3 前缀原样保留;既有 VORBIS_COMMENT(type=4)块全部剥离(网易自带空
     * comment,追加会被"读第一个"的播放器忽略)替换为我们的;既有 PICTURE(type=6)块
     * 同样剥离替换(有封面字节时);其余块 last 位清零原序保留;我们的 VORBIS_COMMENT
     * 作末块;音频帧区一字节不动。
     */
    private fun writeFlacTags(
        input: File,
        output: File,
        tags: Map<String, String>,
        coverFile: File?,
    ): Boolean =
        runCatching {
            val entries =
                tags.entries
                    .mapNotNull { (k, v) -> flacTagKeys[k]?.let { key -> "$key=$v" } }
                    .filter { it.toByteArray(Charsets.UTF_8).size <= 0xFFFFFF } // 24bit 块长上限
                    .also { list -> list.forEach { e -> require(e.toByteArray(Charsets.UTF_8).size < 0xFFFFFF) } }
            if (entries.isEmpty() && coverFile == null) return@runCatching false

            // 1) 魔数(可能带 ID3 前缀)与 metadata 块表
            val magicOffset = flacMagicOffset(input) ?: error("not a flac stream")

            data class Block(
                val headerOffset: Long,
                val len: Int,
                val type: Int,
            )

            val blocks = mutableListOf<Block>()
            var framesStart = 0L
            java.io.RandomAccessFile(input, "r").use { r ->
                var off = magicOffset + 4
                while (true) {
                    r.seek(off)
                    val h = r.readByte().toInt() and 0xFF
                    val type = h and 0x7F
                    val last = (h and 0x80) != 0
                    val len =
                        ((r.readByte().toLong() and 0xFF) shl 16) or
                            ((r.readByte().toLong() and 0xFF) shl 8) or
                            (r.readByte().toLong() and 0xFF)
                    blocks += Block(off, len.toInt(), type)
                    off += 4 + len
                    if (last) {
                        framesStart = off
                        break
                    }
                }
            }

            // 2) vorbis comment payload(little-endian 长度前缀)
            val vendor = "SimpMusic-Hedroid".toByteArray(Charsets.UTF_8)
            val baos = java.io.ByteArrayOutputStream()
            fun leU32(v: Int) {
                baos.write(v and 0xFF)
                baos.write((v shr 8) and 0xFF)
                baos.write((v shr 16) and 0xFF)
                baos.write((v shr 24) and 0xFF)
            }
            leU32(vendor.size)
            baos.write(vendor)
            leU32(entries.size)
            entries.forEach { e ->
                val b = e.toByteArray(Charsets.UTF_8)
                leU32(b.size)
                baos.write(b)
            }
            val vorbisPayload = baos.toByteArray()

            // 3) PICTURE block payload(type=6):picType=3(front cover)+MIME+空描述+
            //    宽高深色全 0+图数据。宽高未探测写 0,符合规范(播放器自会解码)
            var picturePayload: ByteArray? = null
            if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                runCatching {
                    val data = coverFile.readBytes()
                    val mime = if (data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
                    val p = java.io.ByteArrayOutputStream()
                    fun u32(v: Int) {
                        p.write((v shr 24) and 0xFF)
                        p.write((v shr 16) and 0xFF)
                        p.write((v shr 8) and 0xFF)
                        p.write(v and 0xFF)
                    }
                    u32(3) // front cover
                    val mimeB = mime.toByteArray(Charsets.US_ASCII)
                    u32(mimeB.size)
                    p.write(mimeB)
                    u32(0) // 描述空
                    u32(0); u32(0); u32(0); u32(0) // width/height/depth/colors
                    u32(data.size)
                    p.write(data)
                    picturePayload = p.toByteArray()
                }.onFailure { Logger.w(TAG, "picture payload failed: ${it.message}") }
            }

            // 4) 重写:ID3 前缀(如有)原样 → 既有非 type-4/6 块(last 位清零)→ PICTURE(如
            //    有)→ 新 type=4 末块 → 帧区原样。既有 type-4/6 块整个丢弃(去重)
            val strippedBytes =
                blocks.filter { it.type == 4 || it.type == 6 }.sumOf { 4L + it.len }
            fun copyRange(
                src: java.io.RandomAccessFile,
                dst: java.io.RandomAccessFile,
                from: Long,
                until: Long,
                maskFirst: Boolean = false,
            ) {
                src.seek(from)
                val buf = ByteArray(64 * 1024)
                var pos = from
                var first = true
                while (pos < until) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), until - pos).toInt())
                    if (n <= 0) break
                    if (first && maskFirst && n > 0) {
                        buf[0] = (buf[0].toInt() and 0x7F).toByte()
                        first = false
                    }
                    dst.write(buf, 0, n)
                    pos += n
                }
            }
            java.io.RandomAccessFile(input, "r").use { src ->
                java.io.RandomAccessFile(output, "rw").use { dst ->
                    dst.setLength(0)
                    if (magicOffset > 0) copyRange(src, dst, 0, magicOffset)
                    // "fLaC" 魔数必须显式拷(实测首版漏拷:块拷从魔数之后起,产出文件
                    // 连头 4 字节都没有——size 断言拦下后退了原始直落)
                    copyRange(src, dst, magicOffset, magicOffset + 4)
                    blocks.filter { it.type != 4 && it.type != 6 }.forEach { b ->
                        copyRange(src, dst, b.headerOffset, b.headerOffset + 4 + b.len, maskFirst = true)
                    }
                    picturePayload?.let { pic ->
                        // PICTURE 非末块(last=0|type=6)
                        dst.write(6)
                        dst.write((pic.size shr 16) and 0xFF)
                        dst.write((pic.size shr 8) and 0xFF)
                        dst.write(pic.size and 0xFF)
                        dst.write(pic)
                    }
                    // 新 block header: last=1 + type=4(VORBIS_COMMENT) + 24bit 长度
                    dst.write(0x80 or 4)
                    dst.write((vorbisPayload.size shr 16) and 0xFF)
                    dst.write((vorbisPayload.size shr 8) and 0xFF)
                    dst.write(vorbisPayload.size and 0xFF)
                    dst.write(vorbisPayload)
                    // 音频帧区逐块拷
                    copyRange(src, dst, framesStart, input.length())
                    val expected = input.length() - strippedBytes + 4L + vorbisPayload.size +
                        (picturePayload?.let { 4L + it.size } ?: 0L)
                    require(dst.length() == expected) {
                        "size mismatch: ${dst.length()} vs $expected"
                    }
                }
            }
            Logger.w(
                TAG,
                "flac tags rewritten: entries=${entries.size} picture=${picturePayload?.size ?: 0}B " +
                    "id3Prefix=${magicOffset > 0} stripped=${blocks.count { it.type == 4 || it.type == 6 }} in=${input.length()} out=${output.length()}",
            )
            true
        }.getOrElse {
            Logger.w(TAG, "writeFlacTags failed: ${it.message}")
            false
        }

    // ============================================================ 路径与文件名

    /** 主艺人(艺人列表第一个);非法字符清理与空名兜底同 sanitizeDownloadFileName 口径 */
    private fun mainArtist(song: SongEntity): String? =
        song.artistName?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sanitizeFileName(it, song.videoId) }

    /** RELATIVE_PATH:目录开关开=Music/SimpMusic/主艺人/专辑(缺专辑/缺艺人回落上层) */
    private suspend fun computeAudioRelPath(song: SongEntity): String {
        if (dataStoreManager.downloadArtistAlbumFolder.first() != DataStoreManager.TRUE) return AUDIO_ROOT_REL
        val artist = mainArtist(song) ?: return AUDIO_ROOT_REL
        val album = song.albumName?.takeIf { it.isNotBlank() }?.let { sanitizeFileName(it, song.videoId) }
        return if (album != null) "$AUDIO_ROOT_REL/$artist/$album" else "$AUDIO_ROOT_REL/$artist"
    }

    /** 文件名:三选格式(主艺人/标题,连接符 - 无空格)+非法字符清理+同名冲突追加 videoId */
    private suspend fun computeAudioFileName(
        song: SongEntity,
        ext: String,
    ): String {
        val artist = mainArtist(song)
        val title = sanitizeFileName(song.title, song.videoId)
        val stem =
            when (dataStoreManager.downloadFileNameFormat.first()) {
                DataStoreManager.DOWNLOAD_FILE_NAME_TITLE_ONLY -> title
                DataStoreManager.DOWNLOAD_FILE_NAME_ARTIST_TITLE ->
                    if (artist != null) "$artist-$title" else title
                else -> if (artist != null) "$title-$artist" else title
            }
        var name = "$stem.$ext"
        // 同名冲突:目标绝对路径被另一首歌占用(videoId 不同)→ 追加 videoId
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), computeAudioRelPath(song).removePrefix("Music/"))
        val occupiedBy = runCatching { songRepository.getSongIdByDownloadedPath(File(dir, name).absolutePath) }.getOrNull()
        if (occupiedBy != null && occupiedBy != song.videoId) {
            name = "$stem-${song.videoId}.$ext"
        }
        return name
    }

    /** 与 Info 导出同口径:非法字符清理、控制字符滤除、尾部点号修剪、空名兜底、100 字符截断 */
    private fun sanitizeFileName(
        raw: String,
        fallback: String,
    ): String =
        raw
            .replace(Regex("[/|\\\\?*<\":>\\x00-\\x1f]"), "")
            .trimEnd('.')
            .take(100)
            .ifBlank { "download_$fallback" }

    /** relPath(如 Music/SimpMusic/艺人) → 该卷根下的绝对路径目录 */
    private fun relPathToDir(relPath: String): File =
        File(
            when (relPath.substringBefore('/')) {
                "Movies" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
                "Download" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                else -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
            },
            relPath.substringAfter('/'),
        )

    /** MediaStore 插入 + 写流 + 解除 pending,返回实际落盘文件(读 DATA 列)。 */
    private fun insertMediaStore(
        source: File,
        relPath: String,
        displayName: String,
        mime: String,
        collection: android.net.Uri,
    ): File? =
        runCatching {
            val resolver = context.contentResolver
            // 同路径残行先清:文件被外部删但 MediaStore 行还在(root rm/FUSE 残占),或
            // 转存被二次触发——insert 会撞 files._data 唯一索引(实测 2026-10-01
            // SQLITE_CONSTRAINT_UNIQUE)。自己贡献的行免权限可删。
            val absolutePath = File(relPathToDir(relPath), displayName).absolutePath
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(absolutePath), null)?.use { c ->
                val stale = mutableListOf<android.net.Uri>()
                while (c.moveToNext()) {
                    stale += android.net.Uri.withAppendedPath(collection, c.getLong(0).toString())
                }
                stale.forEach { runCatching { resolver.delete(it, null, null) } }
            }
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri = resolver.insert(collection, values) ?: error("insert returned null")
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("openOutputStream null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) File(c.getString(0) ?: return@runCatching null) else null
            } ?: error("no DATA column")
        }.onFailure { Logger.e(TAG, "insertMediaStore failed: ${it.message}") }
            .getOrNull()

    /** lrc 落盘:同目录同名,三级尝试,失败仅 log 不阻塞下载 */
    private fun writeLrc(
        dir: File?,
        name: String,
        text: String,
    ) {
        val dir_ = dir ?: return
        val target = File(dir_, name)
        // ① 直写(staged .part→rename:防 FUSE 同名残占 EEXIST)——Download/ 根或部分 OEM 有效
        runCatching {
            val part = File(dir_, "$name.part")
            part.writeText(text)
            try {
                if (!part.renameTo(target)) {
                    target.delete()
                    check(part.renameTo(target)) { "lrc commit failed" }
                }
            } finally {
                part.delete()
            }
            Logger.i(TAG, "lrc written: $target")
            return
        }.onFailure { Logger.w(TAG, "lrc direct write failed (${it.message})") }
        // ② 媒体扩展名暂存再改名:API 30+ FUSE 对 Music/ 下非媒体扩展名(.lrc/.part)的
        // File API 新建直接 EPERM;先用媒体扩展名(.mp3)创建(过创建门),写完 rename 成
        // .lrc——应用是文件的贡献者,对自家文件持有 FUSE 写权(2026-10-01 实测验证)
        var staged: File? = null
        runCatching {
            val s = File(dir_, "$name.mp3")
            staged = s
            s.writeText(text)
            if (!s.renameTo(target)) {
                target.delete()
                check(s.renameTo(target)) { "lrc rename failed" }
            }
            Logger.i(TAG, "lrc written via staged rename: $target")
            return
        }.onFailure {
            staged?.delete()
            Logger.w(TAG, "lrc staged rename failed (${it.message})")
        }
        // ③ MediaStore Files 兜底:MediaProvider 仅放行 Download/Documents 主目录
        // (Music/ 拒非媒体,"Primary directory Music not allowed"),留作目录规则变化时兜底
        runCatching {
            val resolver = context.contentResolver
            val relDir = dir_.absolutePath.substringAfter("/storage/emulated/0/")
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: error("insert null")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("stream null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "unpend failed" }
            Logger.i(TAG, "lrc written via MediaStore: $relDir/$name")
        }.onFailure { Logger.w(TAG, "lrc MediaStore failed: ${it.message}") }
    }

    // ============================================================ 封面

    /** 与 ui/utils/HiResArtwork 同款升档:网易 param→1080,YT 方形 wNNN-hNNN→1080(宽横幅不动) */
    private fun hiResArtworkUrl(url: String): String =
        when {
            "126.net" in url || "music.163.com" in url -> url.substringBefore('?') + "?param=1080y1080"
            else -> {
                val square = Regex("=w(\\d+)-h(\\d+)").find(url) ?: return url
                if (square.groupValues[1] == square.groupValues[2]) {
                    url.replaceRange(square.range, "=w1080-h1080")
                } else {
                    url
                }
            }
        }

    private val artworkClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .build()

    /** okhttp 拉封面字节(转存协程本就在 IO);上限 8MB 防异常大图,失败返回 null 只丢封面 */
    private fun downloadArtwork(url: String): ByteArray? =
        runCatching {
            artworkClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.bytes() ?: return@runCatching null
                bytes.takeIf { it.isNotEmpty() && it.size <= 8 * 1024 * 1024 }
            }
        }.onFailure { Logger.w(TAG, "artwork download failed: ${it.message}") }.getOrNull()

    companion object {
        const val AUDIO_ROOT_REL = "Music/SimpMusic"
        private const val C_LENGTH_UNSET = -1L
    }
}
