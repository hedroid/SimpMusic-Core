package com.maxrave.data.download

import DatabaseDao
import com.maxrave.data.repository.NeteaseRepositoryImpl
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadEnricher
import com.maxrave.domain.mediaservice.handler.EnrichedDownloadContent
import com.maxrave.logger.Logger
import com.maxrave.netease.albumDetail
import com.maxrave.netease.songDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.aiservice.AiClient
import org.simpmusic.lastfm.getTopTags
import kotlin.time.Duration.Companion.seconds

private const val TAG = "DownloadEnricher"

/**
 * 文件式下载的转存内容收集器。契约见 [DownloadEnricher]:任何失败都吞掉留空,绝不阻塞下载本体。
 *
 * 数据源一览(v1):
 * - 网易:songDetail 的 no/cd/publishTime + albumDetail(eapi)的 company/专辑艺人;歌词=官方专线原文
 * - YT:作词/作曲无公开源(留空);歌词=本地 lyrics 表缓存(拉过才有,没有就跳过 lrc)
 * - 流派:Last.fm toptags(有 key 的构建)→ AI 兜底(开关)
 * - 语言:歌词字符脚本判别(本地,零成本)→ AI 兜底(开关)
 * - 作词/作曲:公开通道(weapi/明文)已探无字段,暂留空;后续 eapi 探针有果再补
 */
class DownloadEnricherImpl(
    private val dataStoreManager: DataStoreManager,
    private val dao: DatabaseDao,
    private val neteaseRepository: NeteaseRepositoryImpl,
    private val aiClient: AiClient,
) : DownloadEnricher {
    override suspend fun enrich(
        song: SongEntity,
        artworkUrl: String?,
    ): EnrichedDownloadContent =
        runCatching {
            coroutineScope {
                val mainArtist = song.artistName?.firstOrNull().orEmpty()
                val isNetease = song.videoId.toLongOrNull() != null
                val aiEnabled = dataStoreManager.downloadAiTags.first() == DataStoreManager.TRUE

                val neteaseDeferred = if (isNetease) async { fetchNeteaseMetadata(song) } else async { null }
                val lyricsDeferred = async { fetchLyrics(song, isNetease) }
                val lastfmGenreDeferred =
                    async {
                        if (mainArtist.isNotBlank()) {
                            runCatching { getTopTags(artist = mainArtist, track = song.title) }.getOrNull()
                                ?.firstOrNull()
                        } else {
                            null
                        }
                    }

                val netease = neteaseDeferred.await()
                val lyrics = lyricsDeferred.await()
                val lastfmGenre = lastfmGenreDeferred.await()

                // 语言:脚本判别优先(零成本);流派/语言任一缺失且 AI 开着才问一次 AI
                val scriptLanguage = lyrics?.let { detectLanguageByScript(it) }
                val aiPair =
                    if (aiEnabled && (lastfmGenre == null || scriptLanguage == null)) {
                        enrichGenreLanguageByAi(
                            song, mainArtist,
                            wantGenre = lastfmGenre == null,
                            wantLanguage = scriptLanguage == null,
                        )
                    } else {
                        null
                    }

                EnrichedDownloadContent(
                    genre = lastfmGenre ?: aiPair?.first,
                    language = scriptLanguage ?: aiPair?.second,
                    lyricist = null,
                    composer = null,
                    copyright = netease?.company?.takeIf { it.isNotBlank() },
                    trackNumber = netease?.trackNumber,
                    discNumber = netease?.discNumber,
                    year = netease?.year,
                    albumArtist = netease?.albumArtist?.takeIf { it.isNotBlank() } ?: mainArtist,
                    lrcText = lyrics?.toLrcText(),
                )
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Logger.e(TAG, "enrich failed for ${song.videoId}: ${it.message}")
        }.getOrElse { EnrichedDownloadContent() }

    // ===== 网易元数据 =====

    private data class NeteaseMetadata(
        val trackNumber: Int?,
        val discNumber: String?,
        val year: String?,
        val company: String?,
        val albumArtist: String?,
    )

    private suspend fun fetchNeteaseMetadata(song: SongEntity): NeteaseMetadata {
        val id = song.videoId.toLongOrNull() ?: return NeteaseMetadata(null, null, null, null, null)
        val detail =
            neteaseRepository.client.songDetail(listOf(id)).getOrNull()?.firstOrNull()
                ?: return NeteaseMetadata(null, null, null, null, null)
        val year = detail.publishTimeMs?.let { ms ->
            kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toString().take(4)
        }
        // 专辑公司/专辑艺人:有 albumId 补一次专辑详情;失败留空
        val albumMeta =
            detail.albumId?.let { albumId ->
                runCatching { neteaseRepository.client.albumDetail(albumId).getOrNull() }.getOrNull()
            }
        return NeteaseMetadata(
            trackNumber = detail.trackNumber,
            discNumber = detail.discNumber,
            year = year,
            company = albumMeta?.first?.company,
            albumArtist = albumMeta?.first?.artistName,
        )
    }

    // ===== 歌词 =====

    private suspend fun fetchLyrics(
        song: SongEntity,
        isNetease: Boolean,
    ): Lyrics? =
        if (isNetease) {
            // 网易官方专线:原文槽即 LRC 源(翻译/罗马音不写入文件)
            neteaseRepository.getNeteaseLyricsData(song.videoId).getOrNull()?.first
                ?.takeIf { !it.error && !it.lines.isNullOrEmpty() }
        } else {
            // YT:本地 lyrics 表(切歌拉过的才有;没有就跳过 lrc,不算失败)
            runCatching { dao.getLyrics(song.videoId) }.getOrNull()
                ?.takeIf { !it.error && !it.lines.isNullOrEmpty() }
                ?.let { Lyrics(error = false, lines = it.lines, syncType = it.syncType) }
        }

    /** Lines → LRC 文本([mm:ss.xx]一行一条;取不到时间戳按 0) */
    private fun Lyrics.toLrcText(): String? {
        val lines = lines ?: return null
        return lines.joinToString("\n") { line ->
            val startMs = line.startTimeMs.toLongOrNull() ?: 0L
            val min = startMs / 60000
            val sec = (startMs % 60000) / 1000.0
            "[%02d:%05.2f]%s".format(min, sec, line.words)
        }
    }

    // ===== 语言(脚本判别) =====

    /**
     * 从歌词字符判语言:假名→日语,谚文→韩语,汉字(无假名)→中文,西里尔→俄语,拉丁→英语。
     * 国语/粤语这类同文字差异判不出(返回 zh),交给 AI 兜底或如实写 zh。
     */
    private fun detectLanguageByScript(lyrics: Lyrics): String? {
        val text = lyrics.lines?.joinToString(" ") { it.words }?.take(4000) ?: return null
        var kana = 0
        var hangul = 0
        var cjk = 0
        var cyrillic = 0
        var latin = 0
        text.forEach { ch ->
            when {
                ch in '\u3040'..'\u30FF' -> kana++
                ch in '\uAC00'..'\uD7AF' -> hangul++
                ch in '\u4E00'..'\u9FFF' -> cjk++
                ch in '\u0400'..'\u04FF' -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        val total = kana + hangul + cjk + cyrillic + latin
        if (total < 20) return null // 样本太少,判别不可靠
        return when {
            kana * 100 / total >= 5 -> "ja"
            hangul * 100 / total >= 20 -> "ko"
            cjk * 100 / total >= 20 -> "zh"
            cyrillic * 100 / total >= 20 -> "ru"
            latin * 100 / total >= 40 -> "en"
            else -> null
        }
    }

    // ===== AI 兜底 =====

    private suspend fun enrichGenreLanguageByAi(
        song: SongEntity,
        mainArtist: String,
        wantGenre: Boolean,
        wantLanguage: Boolean,
    ): Pair<String?, String?>? {
        val prompt =
            buildString {
                append("Song title: ${song.title}\nArtist: $mainArtist")
                song.albumName?.let { append("\nAlbum: $it") }
                append("\n\nAnswer in one line of plain text, no explanation. Format: ")
                when {
                    wantGenre && wantLanguage -> append("genre=<english genre word like pop/rock/folk/hip-hop/electronic>; language=<ISO 639-1 code like zh/en/ja/ko>")
                    wantGenre -> append("genre=<english genre word>")
                    else -> append("language=<ISO 639-1 code>")
                }
            }
        val answer =
            withTimeoutOrNull(30.seconds) {
                aiClient.complete(
                    systemPrompt = "You tag music files. Reply ONLY with the requested key=value pairs. If unsure, omit that key.",
                    userPrompt = prompt,
                ).getOrNull()
            } ?: return null
        val genre =
            Regex("genre\\s*=\\s*([\\w\\- ]+)", RegexOption.IGNORE_CASE)
                .find(answer)?.groupValues?.get(1)?.trim()?.take(24)
        val language =
            Regex("language\\s*=\\s*(\\w{2})", RegexOption.IGNORE_CASE)
                .find(answer)?.groupValues?.get(1)?.trim()?.lowercase()
        return genre to language
    }
}
