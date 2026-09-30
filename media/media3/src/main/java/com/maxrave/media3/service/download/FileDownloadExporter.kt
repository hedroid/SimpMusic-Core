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
 * 音频落 Music/SimpMusic[/<主艺人>/<专辑>],视频落 Movies/SimpMusic。
 */
@UnstableApi
internal class FileDownloadExporter(
    private val context: Context,
    private val downloadCache: SimpleCache,
    private val songRepository: SongRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在转存中的 key(videoId 或 VIDEO+videoId),防 listener 重入/启动对账撞车 */
    private val exporting = ConcurrentHashMap.newKeySet<String>()

    // Koin 惰性拿:repositoryModule 与 mediaServiceModule 的加载顺序无保证
    private val enricher: DownloadEnricher?
        get() = runCatching { GlobalContext.get().get<DownloadEnricher>() }.getOrNull()

    fun close() = scope.cancel()

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
            val workDir = File(context.cacheDir, "dl_export").apply { mkdirs() }
            val rawInput = File(workDir, "$videoId.bin")
            try {
                // 1) 缓存 → 完整原始流(网易=mp3/flac 字节;YT=所选 itag 容器 webm/m4a)
                if (!dumpCacheTo(videoId, rawInput)) {
                    Logger.e(TAG, "exportAudio: cache miss/incomplete for $videoId")
                    return@withContext false
                }
                val isNetease = videoId.toLongOrNull() != null
                val isFlac = rawInput.inputStream().use { s -> ByteArray(4).also { s.read(it) }.decodeToString().startsWith("fLaC") }

                // 2) tag/歌词尽力收集(失败全空字段,不阻塞);封面(升档 1080)用 okhttp 拉字节
                val content: EnrichedDownloadContent =
                    runCatching { enricher?.enrich(song, song.thumbnails) ?: EnrichedDownloadContent() }
                        .getOrElse { EnrichedDownloadContent() }
                val coverFile =
                    song.thumbnails?.let { url -> downloadArtwork(hiResArtworkUrl(url)) }
                        ?.let { bytes -> File(workDir, "$videoId.jpg").apply { writeBytes(bytes) } }
                        ?.takeIf { it.length() > 0 }

                // 3) ffmpeg:网易原格式 -c:a copy;YT 一律转 mp3(-q:a 0);tag+封面一次写入
                val ext = if (isNetease && isFlac) "flac" else "mp3"
                val mime = if (ext == "flac") "audio/flac" else "audio/mpeg"
                val tagged = File(workDir, "$videoId-tagged.$ext")
                if (!writeTaggedAudio(rawInput, tagged, coverFile, song, content, isNetease && isFlac)) {
                    Logger.e(TAG, "exportAudio: ffmpeg failed for $videoId")
                    return@withContext false
                }

                // 4) 目标路径(文件名格式/目录规则/同名冲突)
                val fileName = computeAudioFileName(song, ext)
                val relPath = computeAudioRelPath(song)

                // 5) MediaStore 落盘 + Room 回写
                val stored = insertMediaStore(tagged, relPath, fileName, mime, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                if (stored == null) {
                    Logger.e(TAG, "exportAudio: MediaStore write failed for $videoId")
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

                val stored =
                    insertMediaStore(
                        merged, "Movies/SimpMusic", sanitizeFileName(song.title, videoId) + ".mp4",
                        "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    )
                if (stored == null) return@withContext false
                songRepository.updateDownloadedVideoFilePath(videoId, stored.absolutePath)
                // 视频完成也置下载完成态(音频文件可能没有,但音频播放可用 mp4 兜底)
                songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                downloadCache.removeResource(videoId)
                downloadCache.removeResource(MERGING_DATA_TYPE.VIDEO + videoId)
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
                        if (n <= 0) break
                        out.write(buf, 0, n)
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
        val meta = buildTags(song, content)
        meta.forEach { (k, v) -> cmd += listOf("-metadata", "$k=$v") }
        cmd.add(output.path)
        return runFfmpeg(cmd)
    }

    /** ffmpeg 通用 metadata 键(空值不写;注释 tag 固定为发布来源标记) */
    private fun buildTags(
        song: SongEntity,
        c: EnrichedDownloadContent,
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
            c.lrcText?.let { put("lyrics", it) }
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

    /** lrc 落盘:同目录同名;Music/ 下非媒体文件 MediaStore 不一定接收,先直写(FUSE 对自己建过文件的目录通常放行),失败仅 log */
    private fun writeLrc(
        dir: File?,
        name: String,
        text: String,
    ) {
        val target = File(dir ?: return, name)
        target.writeText(text)
        Logger.i(TAG, "lrc written: $target")
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
