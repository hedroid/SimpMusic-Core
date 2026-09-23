package com.maxrave.data.mediaservice

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import com.maxrave.common.ASC
import com.maxrave.common.CUSTOM_ORDER
import com.maxrave.common.Config.ALBUM_CLICK
import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.common.Config.RADIO_CLICK
import com.maxrave.common.Config.RECOVER_TRACK_QUEUE
import com.maxrave.common.Config.SHARE
import com.maxrave.common.Config.SONG_CLICK
import com.maxrave.common.Config.VIDEO_CLICK
import com.maxrave.common.DESC
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.common.LOCAL_PLAYLIST_ID_SAVED_QUEUE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.NETEASE_FM_PLAYLIST_ID
import com.maxrave.common.NETEASE_RADIO_APPEND_BATCH
import com.maxrave.common.NETEASE_RADIO_PLAYLIST_ID_PREFIX
import com.maxrave.common.SPONSOR_BLOCK_MIN_SEGMENT_SECONDS
import com.maxrave.common.SPONSOR_BLOCK_SKIP_MARGIN_MS
import com.maxrave.common.TITLE
import com.maxrave.common.songRadioPlaylistId
import com.maxrave.data.db.Converters
import com.maxrave.data.lastfm.LastfmScrobbler
import com.maxrave.data.repository.NeteasePlayability
import com.maxrave.data.repository.NeteaseRepositoryImpl
import com.maxrave.data.repository.SearchRepositoryImpl
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.source.MusicSource
import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.DelayEffect
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.GenericTracks
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.data.player.ReverbEffect
import com.maxrave.domain.data.player.ReverbPreset
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.extension.toSongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.FALSE
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.my.kizzy.DiscordRPC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import com.maxrave.domain.data.model.metadata.Line

private val TAG = "Media3ServiceHandlerImpl"

internal class MediaServiceHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler,
    MediaPlayerListener {
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context: Context = getKoin().get()
    override val player: MediaPlayerInterface = getKoin().get()

    /** 随机播放快照:开着随机时保存队列原序(空列表=装载完成后待洗牌的哨兵),关闭后清空。 */
    private var shuffleRestoreListTracks: List<Track>? = null

    /**
     * 无尽队列快照:普通队列首次转电台前记下原 playlistId/续页令牌/原曲目集,
     * [restoreOriginalQueueAfterEndless](关无尽开关)据此裁回原队列。原生电台/FM 队列不快照。
     */
    private var endlessRestore: EndlessRestore? = null

    private data class EndlessRestore(
        val playlistId: String?,
        val continuation: String?,
        val originalTrackIds: List<String>,
    )

    @Volatile
    private var discordRPC: DiscordRPC? = null

    /**
     * Built here rather than injected: it needs nothing this handler does not already hold, and
     * threading it through [createMediaServiceHandler] would mean changing that expect signature
     * and all three actuals for one dependency.
     */
    private val lastfmScrobbler = LastfmScrobbler(dataStoreManager)

    /** 灰歌探针/回退搜索走 Koin 懒取:构造签名保持不动(expect/actual 三端免改) */
    private val neteaseRepository: NeteaseRepositoryImpl by lazy { getKoin().get<NeteaseRepositoryImpl>() }
    private val searchRepository: SearchRepositoryImpl by lazy { getKoin().get<SearchRepositoryImpl>() }

    /** 连续"无版权动作"计数(防整队灰歌/REPEAT_ALL 绕圈跳不停),STATE_READY 归零 */
    private var unavailableChainCount = 0

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(player.currentMediaItem)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData =
        MutableStateFlow<QueueData>(
            QueueData(
                queueState = QueueData.StateSource.STATE_CREATED,
                data = QueueData.Data(),
            ),
        )
    override val queueData = _queueData.asStateFlow()

    private val _controlState =
        MutableStateFlow<ControlState>(
            ControlState(
                isPlaying = player.isPlaying,
                isShuffle = player.shuffleModeEnabled,
                repeatState =
                    when (player.repeatMode) {
                        PlayerConstants.REPEAT_MODE_ONE -> {
                            RepeatState.One
                        }

                        PlayerConstants.REPEAT_MODE_ALL -> {
                            RepeatState.All
                        }

                        PlayerConstants.REPEAT_MODE_OFF -> {
                            RepeatState.None
                        }

                        else -> {
                            RepeatState.None
                        }
                    },
                isLiked = false,
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
                isCrossfading = false,
                volume = 1f,
            ),
        )

    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow<NowPlayingTrackState>(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState(false, 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    // SponsorBlock skip segments
    private val _skipSegments: MutableStateFlow<List<SponsorSkipSegments>?> = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format: MutableStateFlow<NewFormatEntity?> = MutableStateFlow<NewFormatEntity?>(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex: MutableStateFlow<Int> = MutableStateFlow(player.currentMediaItemIndex)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _castState = MutableStateFlow(GenericCastState.NOT_CASTING)
    override val castState: StateFlow<GenericCastState> = _castState.asStateFlow()

    // Notification lyrics: the active line is written into the current media item's artist slot
    // (the second line every media renderer reads) via an in-place metadata swap; the adapter's
    // stored queue keeps the real artist, so a null line simply restores it.
    private var lyricLines: List<Line>? = null
    private var currentLyricLine: String? = null

    // MediaId the current line override was applied to. An in-place swap can itself surface
    // as a transition event, so resets must key off the mediaId, not off transition events.
    private var lyricMediaId: String? = null

    override fun updateLyricLines(lines: List<Line>?) {
        lyricLines = lines
        if (lines == null) applyLyricLine(null)
    }

    private fun resetLyricLineForNewTrack(newItem: GenericMediaItem?) {
        lyricLines = null
        if (newItem?.mediaId != null && newItem.mediaId == lyricMediaId) return
        lyricMediaId = null
        applyLyricLine(null)
    }

    /** Last non-empty line whose start time has passed — keeps showing it through instrumental gaps. */
    private fun currentLyricLineAt(positionMs: Long): String? {
        val lines = lyricLines ?: return null
        var text: String? = null
        for (line in lines) {
            val start = line.startTimeMs.toLongOrNull() ?: continue
            if (start <= positionMs) {
                // Some providers leave raw LRC time tags embedded in the line — line-level
                // "[00:12.34]" and word-level "<00:14.5>" (enhanced LRC), decimal comma
                // variants included. They must never reach a notification line.
                val words =
                    line.words
                        .replace(LRC_TIME_TAG, "")
                        .replace(Regex("\\s{2,}"), " ")
                        .trim()
                if (words.isNotEmpty()) text = words
            } else {
                break
            }
        }
        return text
    }

    private companion object {
        val LRC_TIME_TAG = Regex("""[\[<]\d{1,3}:\d{1,2}(?:[.:,]\d{1,3})?[\]>]""")
    }

    private fun applyLyricLine(text: String?) {
        if (text == currentLyricLine) return
        val hadOverride = currentLyricLine != null
        currentLyricLine = text
        // Nothing was overridden, so a null line has nothing to restore — skipping also avoids
        // the redundant item replace (and its transition bounce) on every fresh track.
        if (text == null && !hadOverride) return
        val item = player.currentMediaItem ?: return
        val metadata = item.metadata ?: return
        lyricMediaId = text?.let { item.mediaId }
        if (text != null) {
            // Lyric on top (title slot — the line every renderer, capsule layouts included,
            // shows first), real title and artist demoted to the second line.
            val secondLine =
                listOfNotNull(
                    metadata.title?.takeIf { it.isNotBlank() },
                    metadata.artist?.takeIf { it.isNotBlank() },
                ).joinToString(" - ")
            player.updateCurrentItemTexts(title = text, artist = secondLine)
        } else {
            player.updateCurrentItemTexts(
                title = metadata.title.orEmpty(),
                artist = metadata.artist.orEmpty(),
            )
        }
    }

    // List of Specific variables

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var secondLoudnessEnhancer: LoudnessEnhancer? = null

    private var skipSilent = false

    private var normalizeVolume = false

    private var watchTimeList: ArrayList<Float> = arrayListOf()

    private var volumeNormalizationJob: Job? = null

    private var sleepTimerJob: Job? = null

    /** How long the sleep timer spends ramping the volume down before it stops playback. */
    private val sleepFadeDurationMs = 5_000L

    /** Steps in that ramp — 50, matching the crossfade ramp, so 100ms per step at 5 seconds. */
    private val sleepFadeSteps = 50

    /**
     * Silence held after the ramp before playback is actually stopped.
     *
     * The gain is applied in the audio pipeline, ahead of the sink, so it reaches the speaker one
     * buffer late — AudioTrack alone holds 250–750 ms. Pausing the moment the ramp hits zero would
     * therefore cut while the audible level is still around -12 dBFS, which is exactly the abrupt
     * stop this feature exists to remove. Sized above the largest buffer media3 will allocate.
     */
    private val sleepFadeTailMs = 800L

    private var getSkipSegmentsJob: Job? = null

    private var getFormatJob: Job? = null

    private var progressJob: Job? = null

    private var bufferedJob: Job? = null

    private var updateNotificationJob: Job? = null

    private var toggleLikeJob: Job? = null

    private var loadJob: Job? = null

    private var songEntityJob: Job? = null

    private var jobWatchtime: Job? = null

    private var getDataOfNowPlayingTrackStateJob: Job? = null

    // Discord Rich Presence is pushed event-driven (song change, resume, seek, speed) instead of on
    // the 100ms progress tick: the gateway only tolerates a few presence updates per minute, so the
    // old 10Hz spam kept disconnecting the socket and froze presence on the previous song (#2236).
    // Ordering uses a monotonic sequence (rpcEventSeq), NOT wall-clock time, since the wall clock can
    // step backward (NTP/manual) and would otherwise freeze presence. Snapshots are written with a
    // compare-and-keep-newest update (never overwriting a newer `seq` with an older one, regardless of
    // suspend-resume interleaving) and conflated through a single sender (rpcSenderJob), which drops
    // any snapshot older than the last one it handled and drops snapshots while playback isn't active
    // (per controlState.isPlaying) so a stale in-flight send can't resurrect presence after pause/close.
    private val rpcEventSeq = AtomicLong(0L)

    private data class RpcSnapshot(
        val song: SongEntity,
        val progressMs: Long,
        val durationMs: Long,
        val speed: Float,
        val seq: Long,
    )

    private val rpcSnapshotFlow = MutableStateFlow<RpcSnapshot?>(null)

    @Volatile
    private var rpcSenderJob: Job? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private fun fromListIntToString(list: List<Int>?): String? = list?.let { json.encodeToString(list) }

    private fun fromStringToListInt(value: String?): List<Int>? =
        try {
            value?.let { json.decodeFromString<List<Int>>(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    //
    init {
        player.addListener(this)
        progressJob = Job()
        bufferedJob = Job()
        sleepTimerJob = Job()
        volumeNormalizationJob = Job()
        updateNotificationJob = Job()
        toggleLikeJob = Job()
        loadJob = Job()
        songEntityJob = Job()
        getSkipSegmentsJob = Job()
        getFormatJob = Job()
        jobWatchtime = Job()
        skipSilent = runBlocking { dataStoreManager.skipSilent.first() == TRUE }
        // Collected rather than read once like the settings around it: the equalizer is adjusted
        // while music is playing, and a curve that only takes effect after a restart is useless
        // for judging what you just changed.
        backgroundScope.launch {
            combine(
                dataStoreManager.equalizerEnabled,
                dataStoreManager.equalizerBands,
                dataStoreManager.equalizerPreamp,
            ) { enabled, bands, preamp -> Triple(enabled == TRUE, bands, preamp) }
                .distinctUntilChanged()
                .collect { (enabled, bands, preamp) ->
                    // Switched off sends a flat curve rather than skipping the call: the filter
                    // has to actually come out of the audio chain, and the stored bands are left
                    // alone so switching back on returns to the user's own shape.
                    player.setEqualizer(
                        bandsDb =
                            if (enabled) bands.split(",").mapNotNull { it.trim().toFloatOrNull() } else emptyList(),
                        preampDb = if (enabled) preamp else 0f,
                    )
                }
        }
        // A collector of its own rather than more legs on the equalizer's: `combine` takes at most
        // five flows with a lambda, and these seven fold into two halves that each stand alone.
        backgroundScope.launch {
            val delayEffects =
                combine(
                    dataStoreManager.delayEnabled,
                    dataStoreManager.delayTimeMs,
                    dataStoreManager.delayFeedback,
                    dataStoreManager.delayMix,
                ) { enabled, timeMs, feedback, mix ->
                    // Off is null rather than a zero mix: the filter has to actually come out of
                    // the audio chain, and the stored values are left alone so switching back on
                    // returns to the user's own settings.
                    if (enabled == TRUE) DelayEffect(timeMs = timeMs, feedback = feedback, mix = mix) else null
                }
            val reverbEffects =
                combine(
                    dataStoreManager.reverbEnabled,
                    dataStoreManager.reverbPreset,
                    dataStoreManager.reverbMix,
                ) { enabled, presetName, mix ->
                    if (enabled == TRUE) {
                        ReverbEffect(
                            // A room written by a newer build is a name this one has never heard
                            // of; falling back beats letting valueOf take the whole collector down.
                            preset = runCatching { ReverbPreset.valueOf(presetName) }.getOrDefault(ReverbPreset.HALL),
                            mix = mix,
                        )
                    } else {
                        null
                    }
                }
            combine(delayEffects, reverbEffects) { echo, room -> AudioEffects(delay = echo, reverb = room) }
                .distinctUntilChanged()
                .collect { effects -> player.setAudioEffects(effects) }
        }
        normalizeVolume =
            runBlocking { dataStoreManager.normalizeVolume.first() == TRUE }
        _nowPlaying.value = player.currentMediaItem
        if (runBlocking { dataStoreManager.saveStateOfPlayback.first() } == TRUE) {
            Logger.d(TAG, "SaveStateOfPlayback TRUE")
            val shuffleKey = runBlocking { dataStoreManager.shuffleKey.first() }
            val repeatKey = runBlocking { dataStoreManager.repeatKey.first() }
            Logger.d(TAG, "Shuffle: $shuffleKey")
            Logger.d(TAG, "Repeat: $repeatKey")
            val restoredShuffle = shuffleKey == TRUE
            val restoredRepeatMode =
                when (repeatKey) {
                    DataStoreManager.REPEAT_ONE -> PlayerConstants.REPEAT_MODE_ONE
                    DataStoreManager.REPEAT_ALL -> PlayerConstants.REPEAT_MODE_ALL
                    else -> PlayerConstants.REPEAT_MODE_OFF
                }
            // 物理随机(网易官方同款):恢复只置哨兵,等队列装载完成(load 尾部)统一洗牌
            if (restoredShuffle) shuffleRestoreListTracks = emptyList()
            player.repeatMode = restoredRepeatMode
            // Ensure controlState is in sync after restore, regardless of listener callbacks
            _controlState.value =
                _controlState.value.copy(
                    isShuffle = restoredShuffle,
                    repeatState =
                        when (restoredRepeatMode) {
                            PlayerConstants.REPEAT_MODE_ONE -> RepeatState.One
                            PlayerConstants.REPEAT_MODE_ALL -> RepeatState.All
                            else -> RepeatState.None
                        },
                )
        }
        mayBeRestoreQueue()
        coroutineScope.launch {
            val controlStateJob =
                launch {
                    controlState.collectLatest {
                        updateNotification()
                    }
                }
            val skipSegmentsJob =
                launch {
                    simpleMediaState
                        .filter { it is SimpleMediaState.Progress }
                        .map {
                            val current = (it as SimpleMediaState.Progress).progress
                            val duration = player.duration
                            if (duration > 0L) {
                                (current.toFloat() / player.duration) * 100
                            } else {
                                -1f
                            }
                        }.filter { it >= 0f }
                        .distinctUntilChanged()
                        .collect { current ->
                            if (dataStoreManager.sponsorBlockEnabled.first() == TRUE) {
                                if (player.duration > 0L) {
                                    val skipSegments = skipSegments.value
                                    val listCategory = dataStoreManager.getSponsorBlockCategories()
                                    if (skipSegments != null) {
                                        for (skip in skipSegments) {
                                            if (listCategory.contains(skip.category)) {
                                                if (skip.segment[1] - skip.segment[0] < SPONSOR_BLOCK_MIN_SEGMENT_SECONDS) {
                                                    continue
                                                }
                                                val firstPart = ((skip.segment[0] / skip.videoDuration) * 100).toFloat()
                                                val secondPart =
                                                    ((skip.segment[1] / skip.videoDuration) * 100).toFloat()
                                                if (current in firstPart..secondPart) {
                                                    Logger.w(TAG, "Seek to $secondPart")
                                                    Logger.d(TAG, "Seek to Cr: $current, First: $firstPart, Second: $secondPart")
                                                    skipSegment(
                                                        (secondPart * player.duration).toLong() / 100 + SPONSOR_BLOCK_SKIP_MARGIN_MS,
                                                    )
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            val notificationLyricsJob =
                launch {
                    simpleMediaState
                        .filter { it is SimpleMediaState.Progress }
                        .map { (it as SimpleMediaState.Progress).progress }
                        .collect { position ->
                            if (dataStoreManager.notificationLyrics.first() == TRUE) {
                                applyLyricLine(currentLyricLineAt(position))
                            } else if (currentLyricLine != null) {
                                applyLyricLine(null)
                            }
                        }
                }
            val playbackJob =
                launch {
                    format.collectLatest { formatTemp ->
                        if (dataStoreManager.sendBackToGoogle.first() == TRUE) {
                            if (formatTemp != null) {
                                println("format in viewModel: $formatTemp")
                                Logger.d(TAG, "Collect format ${formatTemp.videoId}")
                                Logger.w(TAG, "Format expire at ${formatTemp.expiredTime}")
                                Logger.i(TAG, "AtrUrl ${formatTemp.playbackTrackingAtrUrl}")
                                initPlayback(
                                    formatTemp.playbackTrackingVideostatsPlaybackUrl,
                                    formatTemp.playbackTrackingAtrUrl,
                                    formatTemp.playbackTrackingVideostatsWatchtimeUrl,
                                    formatTemp.cpn,
                                )
                            }
                        }
                    }
                }
            val playbackSpeedPitchJob =
                launch {
                    combine(dataStoreManager.playbackSpeed, dataStoreManager.pitch) { speed, pitch ->
                        Pair(speed, pitch)
                    }.collectLatest { pair ->
                        Logger.w(TAG, "Playback speed: ${pair.first}, Pitch: ${pair.second}")
                        player.playbackParameters =
                            GenericPlaybackParameters(
                                pair.first,
                                2f.pow(pair.second.toFloat() / 12),
                            )
                        Logger.w(TAG, "Playback current speed: ${player.playbackParameters.speed}, Pitch: ${player.playbackParameters.pitch}")
                        // A speed change shifts the RPC start/end timestamps (Discord renders the bar
                        // from timestamps client-side), so refresh presence while actively playing.
                        if (player.isPlaying) {
                            nowPlayingState.value.songEntity?.let { updateDiscordRpc(it) }
                        }
                    }
                }
            val discordRPCEnabledJob =
                launch {
                    // Run Rich Presence only when it's enabled AND the user is logged in (non-blank
                    // token). Building DiscordRPC("") makes the gateway loop connect→identify-fail→
                    // reconnect forever and drains the battery (issue #2157). Combining both flows also
                    // tears the RPC down the moment the token is cleared on logout.
                    combine(
                        dataStoreManager.richPresenceEnabled,
                        dataStoreManager.discordToken,
                    ) { enabled, token ->
                        enabled == TRUE && token.isNotBlank()
                    }.distinctUntilChanged().collectLatest { shouldRun ->
                        if (shouldRun) {
                            // Both branches below are independently idempotent: a toggle on→off→on
                            // race must not skip (re)creating whichever of discordRPC/rpcSenderJob
                            // dropped out (#Fix 6).
                            if (discordRPC == null) {
                                discordRPC = DiscordRPC(dataStoreManager.discordToken.first())
                            }
                            if (rpcSenderJob?.isActive != true) {
                                // One sender for the whole RPC lifetime: collectLatest cancels an
                                // in-flight send (socket spin-wait or artwork HTTP) the moment a newer
                                // snapshot arrives, giving both ordering and latest-wins.
                                rpcSenderJob =
                                    coroutineScope.launch(Dispatchers.IO) {
                                        var lastHandledSeq = 0L
                                        rpcSnapshotFlow.filterNotNull().collectLatest { snap ->
                                            if (snap.seq < lastHandledSeq) return@collectLatest
                                            lastHandledSeq = snap.seq
                                            // Drop it if playback stopped meanwhile — e.g. a seek's
                                            // updateDiscordRpc() suspends at playbackSpeed.first() and
                                            // its snapshot lands here after onIsPlayingChanged(false)
                                            // already closed the RPC (Fix 1). controlState.value is a
                                            // safe field read from Dispatchers.IO, unlike player.isPlaying.
                                            if (!controlState.value.isPlaying) return@collectLatest
                                            discordRPC
                                                ?.updateSong(snap.progressMs, snap.durationMs, snap.speed, snap.song)
                                                ?.onFailure { Logger.e(TAG, "Discord RPC update failed: ${it.message}") }
                                        }
                                    }
                                nowPlayingState.value.songEntity?.let { song ->
                                    backgroundScope.launch {
                                        updateDiscordRpc(song)
                                    }
                                }
                            }
                        } else {
                            // NonCancellable: this cleanup must run to completion even if a newer
                            // upstream emission cancels this collectLatest action mid-flight, otherwise
                            // the next `shouldRun` pass could see a half-torn-down state (Fix 6).
                            withContext(NonCancellable) {
                                rpcSenderJob?.cancel()
                                rpcSenderJob = null
                                if (discordRPC?.isRpcRunning() == true) {
                                    discordRPC?.closeRPC()
                                }
                                discordRPC = null
                                // Drop any retained snapshot so a relaunched sender (fresh
                                // lastHandledSeq = 0) can't replay a stale update to the freshly created
                                // socket on an off→on toggle (Fix B). rpcEventSeq itself is NOT reset —
                                // it must stay monotonic across toggles.
                                rpcSnapshotFlow.value = null
                            }
                        }
                    }
                }
            controlStateJob.join()
            skipSegmentsJob.join()
            playbackJob.join()
            playbackSpeedPitchJob.join()
            discordRPCEnabledJob.join()
        }
    }

    private fun getDataOfNowPlayingState(mediaItem: GenericMediaItem) {
        val videoId =
            if (mediaItem.isVideo()) {
                mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
            } else {
                mediaItem.mediaId
            }
        val track =
            queueData.value.data.listTracks
                ?.find { it.videoId == videoId }
        _nowPlayingState.update {
            it.copy(
                mediaItem = mediaItem,
                track = track,
            )
        }
        _format.value = null
        _skipSegments.value = null
        getDataOfNowPlayingTrackStateJob?.cancel()
        getDataOfNowPlayingTrackStateJob =
            coroutineScope.launch {
                Logger.w(TAG, "getDataOfNowPlayingState: $videoId")
                Logger.w(TAG, "getDataOfNowPlayingState: ${track?.thumbnails}")
                songRepository.getSongById(videoId).cancellable().singleOrNull().let { songEntity ->
                    if (songEntity != null) {
                        _controlState.update { it.copy(isLiked = songEntity.liked) }
                        var thumbUrl =
                            track?.thumbnails?.lastOrNull()?.url
                                ?: songEntity.thumbnails
                                ?: "http://i.ytimg.com/vi/${songEntity.videoId}/maxresdefault.jpg"
                        Logger.w(TAG, "getDataOfNowPlayingState before: $thumbUrl")
                        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                        Logger.w(TAG, "getDataOfNowPlayingState: $thumbUrl")
                        if (songEntity.thumbnails != thumbUrl) {
                            songRepository.updateThumbnailsSongEntity(thumbUrl, songEntity.videoId).singleOrNull()?.let {
                                Logger.w(TAG, "getDataOfNowPlayingState: Updated thumbs $it")
                            }
                        }
                        // Rows written before the parsers carried YouTube's real MUSIC_VIDEO_TYPE_*
                        // hold an invented label ("Song", "video", a view count). They are corrected
                        // here as the user plays them rather than by a migration. normalize() drops
                        // anything that is not a real type, so an unknown never overwrites a known one.
                        MusicVideoType.normalize(track?.videoType)?.let { freshVideoType ->
                            if (songEntity.videoType != freshVideoType) {
                                songRepository.updateVideoTypeSongEntity(freshVideoType, songEntity.videoId).singleOrNull()?.let {
                                    Logger.w(TAG, "getDataOfNowPlayingState: Updated videoType $it")
                                }
                            }
                        }
                        songRepository.updateSongInLibrary(now(), songEntity.videoId).singleOrNull().let {
                            Logger.w(TAG, "getDataOfNowPlayingState: $it")
                        }
                        songRepository.updateListenCount(songEntity.videoId)
                        Logger.w(TAG, "getDataOfNowPlayingState: $songEntity")
                        Logger.w(TAG, "getDataOfNowPlayingState: $track")
                        _nowPlayingState.update {
                            it.copy(
                                songEntity =
                                    songEntity.copy(
                                        thumbnails = thumbUrl,
                                    ),
                            )
                        }
                        updateDiscordRpc(songEntity)
                        // Launched separately: "now playing" is a network round trip, and this job
                        // still has the rest of the track state to publish.
                        coroutineScope.launch { lastfmScrobbler.onTrackStarted(songEntity) }
                    } else {
                        _controlState.update { it.copy(isLiked = false) }
                        var thumbUrl =
                            track?.thumbnails?.lastOrNull()?.url
                                ?: "http://i.ytimg.com/vi/${track?.videoId}/maxresdefault.jpg"
                        Logger.w(TAG, "getDataOfNowPlayingState before: $thumbUrl")
                        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                        val songEntity =
                            (track?.toSongEntity() ?: mediaItem.toSongEntity()).copy(
                                thumbnails = thumbUrl,
                            )
                        songRepository
                            .insertSong(
                                songEntity,
                            ).singleOrNull()
                            ?.let {
                                Logger.w(TAG, "getDataOfNowPlayingState: $it")
                            }
                        Logger.w(TAG, "getDataOfNowPlayingState: $songEntity")
                        _nowPlayingState.update {
                            it.copy(
                                songEntity = songEntity,
                            )
                        }
                        updateDiscordRpc(songEntity)
                        // Launched separately: "now playing" is a network round trip, and this job
                        // still has the rest of the track state to publish.
                        coroutineScope.launch { lastfmScrobbler.onTrackStarted(songEntity) }
                    }
                    Logger.w(TAG, "getDataOfNowPlayingState: ${nowPlayingState.value}")
                }
                songEntityJob?.cancel()
                songEntityJob =
                    coroutineScope.launch {
                        songRepository.getSongAsFlow(videoId).cancellable().filterNotNull().collectLatest { songEntity ->
                            if (dataStoreManager.explicitContentEnabled.first() == FALSE && songEntity.isExplicit) {
                                showToast(ToastType.ExplicitContent)
                                if (player.hasNextMediaItem()) {
                                    player.seekToNext()
                                } else if (player.hasPreviousMediaItem()) {
                                    player.seekToPrevious()
                                } else {
                                    player.stop()
                                }
                                return@collectLatest
                            }
                            _nowPlayingState.update {
                                it.copy(
                                    songEntity = songEntity,
                                )
                            }
                            _controlState.update {
                                it.copy(
                                    isLiked = songEntity.liked,
                                )
                            }
                        }
                    }
                // SponsorBlock 只索引 YT videoId,网易数字 ID 查询恒空,别白发请求
                if (dataStoreManager.sponsorBlockEnabled.first() == TRUE && videoId.toLongOrNull() == null) {
                    getSkipSegments(videoId)
                } else {
                    _skipSegments.value = null
                }
                if (dataStoreManager.sendBackToGoogle.first() == TRUE) {
                    getFormat(videoId)
                }
            }
    }

    private fun getSkipSegments(videoId: String) {
        _skipSegments.value = null
        coroutineScope.launch {
            streamRepository.getSkipSegments(videoId).collect { response ->
                when (response) {
                    is Resource.Success -> {
                        _skipSegments.value = response.data
                    }

                    is Resource.Error -> {
                        Logger.e(TAG, "getSkipSegments: ${response.message}")
                        _skipSegments.value = null
                    }
                }
            }
        }
    }

    private fun getFormat(mediaId: String?) {
        getFormatJob?.cancel()
        getFormatJob =
            coroutineScope.launch {
                if (mediaId != null) {
                    streamRepository.getFormatFlow(mediaId).cancellable().collectLatest { f ->
                        Logger.w(TAG, "Get format for $mediaId: $f")
                        if (f != null) {
                            _format.emit(f)
                        } else {
                            _format.emit(null)
                        }
                    }
                }
            }
    }

    private fun initPlayback(
        playback: String?,
        atr: String?,
        watchTime: String?,
        cpn: String?,
    ) {
        jobWatchtime?.cancel()
        coroutineScope.launch {
            if (playback != null && atr != null && watchTime != null && cpn != null) {
                watchTimeList = arrayListOf()
                streamRepository
                    .initPlayback(playback, atr, watchTime, cpn, queueData.value.data.playlistId)
                    .collect {
                        if (it.first == 204) {
                            Logger.d("Check initPlayback", "Success")
                            watchTimeList.add(0f)
                            watchTimeList.add(5.54f)
                            watchTimeList.add(it.second)
                            updateWatchTime()
                        }
                    }
            }
        }
    }

    private fun updateWatchTime() {
        coroutineScope.launch {
            jobWatchtime =
                launch {
                    simpleMediaState.collect { state ->
                        if (state is SimpleMediaState.Progress) {
                            val value = state.progress
                            if (value > 0 && watchTimeList.isNotEmpty()) {
                                val second = (value / 1000).toFloat()
                                if (second in watchTimeList.last()..watchTimeList.last() + 1.2f) {
                                    val watchTimeUrl =
                                        _format.value?.playbackTrackingVideostatsWatchtimeUrl
                                    val cpn = _format.value?.cpn
                                    if (second + 20.23f < (player.duration / 1000).toFloat()) {
                                        watchTimeList.add(second + 20.23f)
                                        if (watchTimeUrl != null && cpn != null) {
                                            streamRepository
                                                .updateWatchTime(
                                                    watchTimeUrl,
                                                    watchTimeList,
                                                    cpn,
                                                    queueData.value.data.playlistId,
                                                ).collect { response ->
                                                    if (response == 204) {
                                                        Logger.d("Check updateWatchTime", "Success")
                                                    }
                                                }
                                        }
                                    } else {
                                        watchTimeList.clear()
                                        if (watchTimeUrl != null && cpn != null) {
                                            streamRepository
                                                .updateWatchTimeFull(
                                                    watchTimeUrl,
                                                    cpn,
                                                    queueData.value.data.playlistId,
                                                ).collect { response ->
                                                    if (response == 204) {
                                                        Logger.d("Check updateWatchTimeFull", "Success")
                                                    }
                                                }
                                        }
                                    }
                                    Logger.w("Check updateWatchTime", watchTimeList.toString())
                                }
                            }
                        }
                    }
                }
            jobWatchtime?.join()
        }
    }

    private fun updateNextPreviousTrackAvailability() {
        _controlState.value =
            _controlState.value.copy(
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
            )
    }

    private fun addMediaItemNotSet(
        mediaItem: GenericMediaItem,
        index: Int? = null,
    ) {
        index?.let {
            player.addMediaItem(it, mediaItem)
        } ?: player.addMediaItem(mediaItem)
        if (player.mediaItemCount == 1) {
            player.prepare()
            player.playWhenReady = true
        }
        updateNextPreviousTrackAvailability()
    }

    private fun moveMediaItem(
        fromIndex: Int,
        newIndex: Int,
    ) {
        player.moveMediaItem(fromIndex, newIndex)
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    private fun skipSegment(position: Long) {
        if (position in 0..player.duration) {
            player.seekTo(position)
        } else if (position > player.duration) {
            player.seekToNext()
        }
    }

    @SuppressLint("PrivateResource")
    private fun updateNotification() {
        updateNotificationJob?.cancel()
        updateNotificationJob =
            coroutineScope.launch {
                var id = (player.currentMediaItem?.mediaId ?: "")
                if (id.contains("Video")) {
                    id = id.removePrefix("Video")
                }
                val liked =
                    songRepository
                        .getSongById(id)
                        .singleOrNull()
                        ?.liked ?: false
                Logger.w("Check liked", liked.toString())
                _controlState.value = _controlState.value.copy(isLiked = liked)
                onUpdateNotification.invoke(
                    listOf(
                        GenericCommandButton.Like(liked),
                        GenericCommandButton.Shuffle(isShuffled = _controlState.value.isShuffle),
                        GenericCommandButton.Repeat(repeatState = _controlState.value.repeatState),
                        GenericCommandButton.Radio,
                    ),
                )
            }
    }

    // Region: Override functions
    override fun startProgressUpdate() {
        // Cancel any previous loop first: onIsPlayingChanged(true) can fire repeatedly
        // (crossfade swap, rebuffer→ready, resume-on-focus-gain) and a leaked loop would
        // otherwise multiply both the UI updates and the periodic position writes (#2152).
        progressJob?.cancel()
        progressJob =
            coroutineScope.launch {
                // Persist the playback position to DataStore on this interval so a sudden
                // service/process kill while playing in the background (aggressive OEMs)
                // still restores the correct position instead of restarting the track from
                // the beginning (#2152). The position is otherwise only saved on pause /
                // track change / release, which misses uninterrupted background playback.
                val positionPersistIntervalMs = 5_000L
                var sinceLastPositionSaveMs = 0L
                while (true) {
                    delay(100)
                    _simpleMediaState.value = SimpleMediaState.Progress(player.currentPosition)
                    sinceLastPositionSaveMs += 100
                    if (sinceLastPositionSaveMs >= positionPersistIntervalMs) {
                        sinceLastPositionSaveMs = 0
                        mayBeSaveRecentPosition()
                        // Riding the existing 5s tick instead of adding one: the scrobble point is
                        // half the track or four minutes, so five seconds of granularity is plenty
                        // and the 100ms loop stays as cheap as it was.
                        lastfmScrobbler.onProgress(player.currentPosition)
                    }
                }
            }
    }

    override fun startBufferedUpdate() {
        // Same reason as startProgressUpdate above: this is reached once per track load and once
        // per stall, and stopBufferedUpdate only cancels the newest job — so every earlier loop
        // survives and keeps pushing Loading every 500 ms for the rest of the session.
        bufferedJob?.cancel()
        bufferedJob =
            coroutineScope.launch {
                while (true) {
                    delay(500)
                    _simpleMediaState.value =
                        SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
                    val current = nowPlayingState.value.songEntity
                    if (current?.durationSeconds == 0 && player.duration > 0L) {
                        _nowPlayingState.update {
                            it.copy(
                                songEntity =
                                    current.copy(
                                        durationSeconds = (player.duration / 1000).toInt(),
                                    ),
                            )
                        }
                    }
                }
            }
    }

    override fun stopProgressUpdate() {
        progressJob?.cancel()
        Logger.w(TAG, "stopProgressUpdate: ${progressJob?.isActive}")
    }

    override fun stopBufferedUpdate() {
        bufferedJob?.cancel()
        // Deliberately emits nothing: this runs when buffering *ends*, so publishing Loading here
        // said the opposite of what happened.
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            is PlayerEvent.UpdateVolume -> {}

            PlayerEvent.Backward -> {
                player.seekBack()
            }

            PlayerEvent.Forward -> {
                player.seekForward()
            }

            PlayerEvent.PlayPause -> {
                if (player.isPlaying) {
                    player.pause()
                    stopProgressUpdate()
                } else {
                    player.play()
                    startProgressUpdate()
                }
            }

            PlayerEvent.Next -> {
                resetCrossfade()
                player.seekToNext()
            }

            PlayerEvent.Previous -> {
                resetCrossfade()
                player.seekToPrevious()
            }

            PlayerEvent.SkipToPrevious -> {
                resetCrossfade()
                player.seekToPreviousMediaItem()
            }

            PlayerEvent.Stop -> {
                stopProgressUpdate()
                player.stop()
                _nowPlayingState.value = NowPlayingTrackState.initial()
            }

            is PlayerEvent.UpdateProgress -> {
                player.seekTo((player.duration * playerEvent.newProgress / 100).toLong())
            }

            PlayerEvent.Shuffle -> {
                // 随机播放=物理洗牌队列(网易官方同款,2026-09-22 定案):开=快照原序+
                // 当前曲置首+其余随机,关=按当前曲恢复快照。封面闪动已在 UI 层根治
                // (pager 内容 key+直跳,不再依赖"列表不重排"这个约束)。
                if (shuffleRestoreListTracks != null) {
                    restoreQueueFromShuffle()
                } else {
                    shuffleQueuePhysical()
                }
            }

            PlayerEvent.Repeat -> {
                when (player.repeatMode) {
                    PlayerConstants.REPEAT_MODE_OFF -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                    }

                    PlayerConstants.REPEAT_MODE_ONE -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_OFF
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.None)
                    }

                    PlayerConstants.REPEAT_MODE_ALL -> {
                        player.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                        _controlState.value = _controlState.value.copy(repeatState = RepeatState.One)
                    }

                    else -> {
                        when (controlState.first().repeatState) {
                            RepeatState.None -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                            }

                            RepeatState.One -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.All)
                            }

                            RepeatState.All -> {
                                player.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                                _controlState.value = _controlState.value.copy(repeatState = RepeatState.One)
                            }
                        }
                    }
                }
            }

            PlayerEvent.ToggleLike -> {
                toggleLike()
            }
        }
    }

    override fun toggleRadio() {
        coroutineScope.launch {
            val currentSong = nowPlayingState.value.songEntity ?: return@launch
            Logger.d(TAG, "toggleRadio: ${currentSong.title}")
            songRepository
                .getRadioFromEndpoint(
                    YouTubeWatchEndpoint(
                        videoId = currentSong.videoId,
                        playlistId = songRadioPlaylistId(currentSong.videoId),
                    ),
                ).collectLatest { res ->
                    val data = res.data
                    when (res) {
                        is Resource.Success if (data != null && data.first.isNotEmpty()) -> {
                            setQueueData(
                                QueueData.Data(
                                    listTracks = data.first,
                                    firstPlayedTrack = data.first.first(),
                                    playlistId = songRadioPlaylistId(currentSong.videoId),
                                    playlistName = "\"${currentSong.title}\" Radio",
                                    playlistType = PlaylistType.RADIO,
                                    continuation = data.second,
                                ),
                            )
                            clearMediaItems()
                            currentSong.durationSeconds.let {
                                songRepository.updateDurationSeconds(it, currentSong.videoId)
                            }
                            addMediaItem(currentSong.toGenericMediaItem(), playWhenReady = true)
                            loadPlaylistOrAlbum(0)
                        }

                        else -> {
                            Logger.e(TAG, "toggleRadio: ${res.message}")
                        }
                    }
                }
        }
    }

    override fun toggleLike() {
        // 红心=云端账号状态:通知栏与播放页同一条路径——直接切换云端,成功后镜像
        // 本地缓存行并同步通知栏图标。
        Logger.w(TAG, "toggleLike: ${nowPlayingState.value.mediaItem.mediaId}")
        toggleLikeJob?.cancel()
        toggleLikeJob =
            coroutineScope.launch {
                var id = (player.currentMediaItem?.mediaId ?: "")
                if (id.contains("Video")) {
                    id = id.removePrefix("Video")
                }
                val likedNow = songRepository.getSongById(id).singleOrNull()?.liked == true
                val ok = songRepository.setRemoteLikeStatus(id, !likedNow)
                if (ok) {
                    songRepository.setLikedLocal(id, if (!likedNow) 1 else 0)
                    _controlState.value = _controlState.value.copy(isLiked = !likedNow)
                }
                delay(200)
            }
    }

    override fun like(liked: Boolean) {
        _controlState.value = _controlState.value.copy(isLiked = liked)
    }

    override fun resetSongAndQueue() {
        player.clearMediaItems()
        _queueData.value = QueueData()
    }

    override fun sleepStart(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob =
            coroutineScope.launch(Dispatchers.Main) {
                var stoppedPlayback = false
                try {
                    if (minutes == Int.MAX_VALUE) {
                        // "End of current song" mode: use sentinel -1 to indicate this special state
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = -1)
                        }
                        // Poll until player duration is available (may be -1 initially)
                        var duration = player.duration
                        while (duration <= 0L) {
                            delay(500)
                            duration = player.duration
                        }
                        val remaining = (duration - player.currentPosition).coerceAtLeast(0L)
                        // Fade over the tail of the track rather than after it, so the song is
                        // already silent by the time it ends. A track with less time left than the
                        // fade gets a shorter one instead of bleeding into whatever plays next.
                        // Fade and tail together must fit inside what is left, or the timer would
                        // run past the end of the track and pause somewhere inside the next one.
                        val fadeMs = sleepFadeDurationMs.coerceAtMost(remaining)
                        val tailMs = sleepFadeTailMs.coerceAtMost(remaining - fadeMs)
                        delay(remaining - fadeMs - tailMs)
                        fadeOutForSleep(fadeMs)
                        delay(tailMs)
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    } else {
                        _sleepTimerState.update {
                            it.copy(isDone = false, timeRemaining = minutes)
                        }
                        var count = minutes
                        while (count > 0) {
                            // The fade belongs inside the final minute, so shorten that wait by its length.
                            val isFinalMinute = count == 1
                            delay(
                                if (isFinalMinute) 60 * 1000L - sleepFadeDurationMs - sleepFadeTailMs else 60 * 1000L,
                            )
                            if (isFinalMinute) {
                                fadeOutForSleep(sleepFadeDurationMs)
                                delay(sleepFadeTailMs)
                            }
                            count--
                            _sleepTimerState.update {
                                it.copy(isDone = false, timeRemaining = count)
                            }
                        }
                        player.pause()
                        stoppedPlayback = true
                        _sleepTimerState.update {
                            it.copy(isDone = true, timeRemaining = 0)
                        }
                    }
                } finally {
                    // Only the cancelled path clears the attenuation here — sleepStop(), or the
                    // scope going away mid-fade. When the timer runs to completion the adapter
                    // clears it instead, from inside the pause it queued, because pause() is
                    // asynchronous and this coroutine cannot tell when playback actually stopped.
                    // Restoring it from here would lift the volume back over the last of the audio.
                    if (!stoppedPlayback) player.sleepFadeFactor = 1f
                }
            }
    }

    /**
     * Ramps [player]'s sleep-fade attenuation down to silence over [durationMs].
     *
     * Uses the same equal-power (cosine) curve as the crossfade ramp: loudness is perceived
     * logarithmically, so a linear ramp sounds like it drops away early and then lingers near the
     * bottom. Leaves the factor at zero: the caller holds that silence for [sleepFadeTailMs] so the
     * attenuated audio can drain through the sink, and only restores the factor after pausing.
     */
    private suspend fun fadeOutForSleep(durationMs: Long) {
        if (durationMs <= 0L) return
        // Fewer steps than the nominal 50 for a very short fade, so the ramp cannot outlast the
        // budget it was given: 50 steps at the 1ms floor would take 50ms regardless of duration.
        val steps = sleepFadeSteps.toLong().coerceAtMost(durationMs).toInt()
        val delayPerStep = (durationMs / steps).coerceAtLeast(1L)
        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            player.sleepFadeFactor = cos(progress * PI / 2).toFloat()
            delay(delayPerStep)
        }
    }

    override fun sleepStop() {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(false, 0)
    }

    override fun removeMediaItem(position: Int) {
        player.removeMediaItem(position)
        val temp =
            _queueData.value.data.listTracks
                .toMutableList()
        temp.removeAt(position)
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = temp,
                    ),
            )
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        for (mediaItem in mediaItemList) {
            addMediaItemNotSet(mediaItem)
        }
    }

    override fun playMediaItemInMediaSource(index: Int) {
        // 物理随机:queueData 与 player playlist 同序,UI 行号即 player 索引,无需换算
        player.seekTo(index, 0)
        player.prepare()
        player.playWhenReady = true
    }

    override fun currentSongIndex(): Int = player.currentMediaItemIndex

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
//        if (from < to) {
//            for (i in from until to) {
//                moveItemDown(i)
//            }
//        } else {
//            for (i in from downTo to + 1) {
//                moveItemUp(i)
//            }
//        }
        moveMediaItem(from, to)
    }

    override fun resetCrossfade() {
        _controlState.update {
            it.copy(
                isCrossfading = false,
            )
        }
    }

    override fun shufflePlaylist(randomTrackIndex: Int) {
        val playlistId = _queueData.value.data.playlistId ?: return
        val firstPlayedTrack = _queueData.value.data.firstPlayedTrack ?: return
        coroutineScope.launch {
            if (playlistId.startsWith(LOCAL_PLAYLIST_ID)) {
                songRepository.insertSong(firstPlayedTrack.toSongEntity()).collect {
                    Logger.w(TAG, "Inserted song: ${firstPlayedTrack.title}")
                }
                clearMediaItems()
                firstPlayedTrack.durationSeconds?.let {
                    songRepository.updateDurationSeconds(it, firstPlayedTrack.videoId)
                }
                addMediaItem(firstPlayedTrack.toGenericMediaItem(), playWhenReady = true)
                val longId = playlistId.replace(LOCAL_PLAYLIST_ID, "").toLong()
                val localPlaylist = localPlaylistRepository.getLocalPlaylist(longId).lastOrNull()?.data
                if (localPlaylist != null) {
                    Logger.w(TAG, "shufflePlaylist: Local playlist track size ${localPlaylist.tracks?.size}")
                    val trackCount = localPlaylist.tracks?.size ?: return@launch
                    val listPosition =
                        (0 until trackCount).toMutableList().apply {
                            remove(randomTrackIndex)
                        }
                    if (listPosition.isEmpty()) return@launch
                    listPosition.shuffle()
                    _queueData.update {
                        it.copy(
                            // After shuffle prefix is offset and list position
                            data =
                                it.data.copy(
                                    continuation = "SHUFFLE0_${fromListIntToString(listPosition)}",
                                ),
                        )
                    }
                    loadMore()
                }
            }
        }
    }

    override fun loadMore() {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        // Separate local and remote data
        // Local Add Prefix to PlaylistID to differentiate between local and remote
        // Local: LC-PlaylistID
        val playlistId = _queueData.value.data.playlistId
        if (playlistId == null) {
            // 无 playlistId 的队列(mix 页分区起播等):无尽开着时下拉也换尾曲种子续几首,
            // 别在这里直接 return 把无尽钩子挡死。开关关闭时 startEndlessRadio 自己早退。
            startEndlessRadio()
            return
        }
        Logger.w("Check loadMore", playlistId.toString())
        val continuation = _queueData.value.data.continuation
        Logger.w("Check loadMore", continuation.toString())
        // 网易私人FM:批批连播,直取 personalRadio 追加。不走下面 YT 的 RDAMVM 电台路径——
        // 数字 videoId 进 getRelated 必失败,且 RDAMVM 前缀判等会误伤语义。
        if (playlistId == NETEASE_FM_PLAYLIST_ID) {
            getNeteaseFmBatch()
            return
        }
        // 网易单曲电台(NETEASE_RADIO_<songId>):按 continuation 里的 item offset 续批 simiSong。
        if (playlistId.startsWith(NETEASE_RADIO_PLAYLIST_ID_PREFIX)) {
            getNeteaseRadioBatch()
            return
        }
        if (continuation != null) {
            if (playlistId.startsWith(LOCAL_PLAYLIST_ID)) {
                coroutineScope.launch {
                    _queueData.update {
                        it.copy(
                            queueState = QueueData.StateSource.STATE_INITIALIZING,
                        )
                    }
                    val longId =
                        try {
                            playlistId.replace(LOCAL_PLAYLIST_ID, "").toLong()
                        } catch (e: NumberFormatException) {
                            return@launch
                        }
                    Logger.w("Check loadMore", longId.toString())
                    if (continuation.startsWith("SHUFFLE")) {
                        val regex = Regex("(?<=SHUFFLE)\\d+(?=_)")
                        var offset = regex.find(continuation)?.value?.toInt() ?: return@launch
                        val posString = continuation.removePrefix("SHUFFLE${offset}_")
                        val listPosition = fromStringToListInt(posString) ?: return@launch
                        val theLastLoad = 50 * (offset + 1) >= listPosition.size
                        localPlaylistRepository
                            .getPlaylistPairSongByListPosition(
                                longId,
                                listPosition.subList(50 * offset, if (theLastLoad) listPosition.size else 50 * (offset + 1)),
                            ).singleOrNull()
                            ?.let { pair ->
                                Logger.w("Check loadMore response", pair.size.toString())
                                songRepository.getSongsByListVideoId(pair.map { it.songId }).lastOrNull()?.let { songs ->
                                    if (songs.isNotEmpty()) {
                                        delay(300)
                                        loadMoreCatalog(songs.toArrayListTrack())
                                        offset++
                                        _queueData.update {
                                            it.copy(
                                                data =
                                                    it.data.copy(
                                                        continuation =
                                                            if (!theLastLoad) {
                                                                "SHUFFLE${offset}_$posString"
                                                            } else {
                                                                null
                                                            },
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                    } else if (
                        continuation.startsWith(ASC) ||
                        continuation.startsWith(DESC) ||
                        continuation.startsWith(CUSTOM_ORDER) ||
                        continuation.startsWith(TITLE)
                    ) {
                        val filter =
                            if (continuation.startsWith(ASC)) {
                                FilterState.OlderFirst
                            } else if (continuation.startsWith(DESC)) {
                                FilterState.NewerFirst
                            } else if (continuation.startsWith(CUSTOM_ORDER)) {
                                FilterState.CustomOrder
                            } else {
                                FilterState.Title
                            }
                        val converters = Converters()

                        when (filter) {
                            FilterState.NewerFirst, FilterState.OlderFirst -> {
                                val localDateTime =
                                    try {
                                        val timestampString =
                                            if (filter == FilterState.OlderFirst) {
                                                continuation.removePrefix(ASC)
                                            } else {
                                                continuation.removePrefix(DESC)
                                            }
                                        val timestamp = timestampString.toLong()
                                        converters.fromTimestamp(timestamp)
                                            ?: return@launch
                                    } catch (e: Exception) {
                                        Logger.e(TAG, "loadMore: Failed to parse timestamp", e)
                                        return@launch
                                    }
                                localPlaylistRepository
                                    .getPlaylistPairSongByTime(
                                        longId,
                                        filter,
                                        localDateTime,
                                    ).lastOrNull()
                                    ?.let { pair ->
                                        Logger.w("Check loadMore response", pair.size.toString())
                                        songRepository.getSongsByListVideoId(pair.map { it.songId }).single().let { songs ->
                                            if (songs.isNotEmpty()) {
                                                delay(300)
                                                loadMoreCatalog(songs.toArrayListTrack())
                                                _queueData.update {
                                                    it.copy(
                                                        data =
                                                            it.data.copy(
                                                                continuation =
                                                                    if (filter ==
                                                                        FilterState.OlderFirst
                                                                    ) {
                                                                        ASC +
                                                                            pair.lastOrNull()?.inPlaylist?.let { inPlaylist ->
                                                                                converters.dateToTimestamp(inPlaylist)
                                                                            }
                                                                    } else {
                                                                        DESC +
                                                                            pair.lastOrNull()?.inPlaylist?.let { inPlaylist ->
                                                                                converters.dateToTimestamp(inPlaylist)
                                                                            }
                                                                    },
                                                            ),
                                                    )
                                                }
                                            } else {
                                                _queueData.update {
                                                    it.copy(
                                                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                                                    )
                                                }
                                                reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                            }
                                        }
                                    }
                            }

                            FilterState.Title, FilterState.CustomOrder -> {
                                val offset =
                                    if (filter == FilterState.CustomOrder) {
                                        continuation.removePrefix(CUSTOM_ORDER).toInt()
                                    } else {
                                        continuation.removePrefix(TITLE).toInt()
                                    }
                                localPlaylistRepository
                                    .getPlaylistPairSongByOffset(
                                        longId,
                                        offset,
                                        filter,
                                    ).lastOrNull()
                                    ?.let { pair ->
                                        Logger.w("Check loadMore response", pair.size.toString())
                                        songRepository.getSongsByListVideoId(pair.map { it.songId }).single().let { songs ->
                                            if (songs.isNotEmpty()) {
                                                delay(300)
                                                loadMoreCatalog(songs.toArrayListTrack())
                                                _queueData.update {
                                                    it.copy(
                                                        data =
                                                            it.data.copy(
                                                                continuation =
                                                                    if (filter ==
                                                                        FilterState.CustomOrder
                                                                    ) {
                                                                        CUSTOM_ORDER + (offset + 1)
                                                                    } else {
                                                                        TITLE + (offset + 1).toString()
                                                                    },
                                                            ),
                                                    )
                                                }
                                            } else {
                                                _queueData.update {
                                                    it.copy(
                                                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                                                    )
                                                }
                                                reorderShuffledQueue(player.getCurrentMediaTimeLine())
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            } else {
                coroutineScope.launch {
                    _queueData.update {
                        it.copy(
                            queueState = QueueData.StateSource.STATE_INITIALIZING,
                        )
                    }
                    Logger.w(TAG, "Check loadMore continuation $continuation")
                    songRepository
                        .getContinueTrack(playlistId, continuation)
                        .lastOrNull()
                        .let { response ->
                            val list = response?.first
                            if (list != null) {
                                Logger.w(TAG, "Check loadMore response $response")
                                loadMoreCatalog(list)
                                _queueData.update {
                                    it.copy(
                                        data =
                                            it.data.copy(
                                                continuation = response.second,
                                            ),
                                    )
                                }
                            } else {
                                _queueData.update {
                                    it.copy(
                                        // 先复位:无尽开关关闭时 startEndlessRadio 直接早退,
                                        // 不置回 INITIALIZED 会让队列尾永久转圈、loadMore 被锁死
                                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                                        data =
                                            it.data.copy(
                                                continuation = null,
                                            ),
                                    )
                                }
                                startEndlessRadio()
                            }
                        }
                }
            }
        } else {
            startEndlessRadio()
        }
    }

    /**
     * 无尽队列钩子：队列内容耗尽时以当前尾曲为种子转入电台模式，续播不停。种子按 ID 形状
     * 分流（[songRadioPlaylistId]）——YT 走 RDAMVM + getRelated 原路径；网易纯数字 ID 走
     * NETEASE_RADIO_ 哨兵 + simiSong 首批（[getNeteaseRadioBatch] 读 playlistId 当种子、
     * continuation 当偏移，首批判 "0"）。种子与当前 playlistId 相同视为已在该电台模式，早退。
     */
    private fun startEndlessRadio() {
        if (runBlocking { dataStoreManager.endlessQueue.first() } != TRUE) return
        Logger.w(TAG, "loadMore: Endless Queue")
        val lastTrack =
            queueData.value.data.listTracks
                .lastOrNull() ?: return
        val radioId = songRadioPlaylistId(lastTrack.videoId)
        if (radioId == queueData.value.data.playlistId) {
            Logger.w(TAG, "loadMore: Already in radio mode")
            return
        }
        // 转电台前快照原队列身份+曲目集:关无尽开关时裁回原队列用(对齐 YTM autoplay
        // 关掉即移除已追加的推荐歌;随机快照同思路)。原生电台队列在上面早退,不会进来。
        if (endlessRestore == null) {
            endlessRestore =
                EndlessRestore(
                    playlistId = queueData.value.data.playlistId,
                    continuation = queueData.value.data.continuation,
                    originalTrackIds = queueData.value.data.listTracks.map { it.videoId },
                )
        }
        if (radioId.startsWith(NETEASE_RADIO_PLAYLIST_ID_PREFIX)) {
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                    data = it.data.copy(playlistId = radioId, continuation = "0"),
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
            getNeteaseRadioBatch()
        } else {
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                    data = it.data.copy(playlistId = radioId),
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
            getRelated(lastTrack.videoId)
        }
    }

    /**
     * 关无尽开关:裁掉电台追加的歌、恢复原队列身份(playlistId/续页令牌),对齐 YTM
     * autoplay 关掉即移除未播的推荐歌。正在播的追加歌保留为队尾(不打断播放);
     * 随机快照同步收缩,防之后"关随机"把已裁的歌复活。无可快照(原生电台/FM/未转换)时无操作。
     */
    override fun restoreOriginalQueueAfterEndless() {
        val snap = endlessRestore ?: return
        endlessRestore = null
        val keepIds = snap.originalTrackIds.toHashSet()
        val currentId = player.currentMediaItem?.mediaId
        val kept =
            queueData.value.data.listTracks
                .filter { it.videoId in keepIds || it.videoId == currentId }
        if (kept.isEmpty()) return
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZED,
                data =
                    it.data.copy(
                        playlistId = snap.playlistId,
                        continuation = snap.continuation,
                        listTracks = kept.toCollection(ArrayList()),
                    ),
            )
        }
        shuffleRestoreListTracks =
            shuffleRestoreListTracks
                ?.filter { it.videoId in keepIds || it.videoId == currentId }
                ?.takeIf { it.isNotEmpty() }
        val newIds = kept.map { it.videoId }
        // 原子裁剪:逐个 removeMediaItem 是异步的,同步读 mediaItemCount 自旋等待会 ANR
        player.trimQueueTo(newIds)
        updateNextPreviousTrackAvailability()
        Logger.w(TAG, "restoreOriginalQueueAfterEndless: ${kept.size} tracks, playlistId=${snap.playlistId}")
    }

    /**
     * simiSong 电台见底后的无尽续链：以**当前**尾曲换一个新种子接着开电台（YT 的 RDAMVM
     * 钩子每次耗尽也天然换尾曲重播种子）。种子没变——整批撞重、队列没长——就不再续，
     * 与 FM 批次的重试上限同理，防"同一批相似歌反复拉"的死循环。
     */
    private fun reseedNeteaseRadioIfEndless() {
        if (runBlocking { dataStoreManager.endlessQueue.first() } != TRUE) return
        val lastTrack =
            queueData.value.data.listTracks
                .lastOrNull() ?: return
        val nextRadioId = songRadioPlaylistId(lastTrack.videoId)
        if (nextRadioId == queueData.value.data.playlistId) return
        Logger.w(TAG, "getNeteaseRadioBatch: endless reseed to $nextRadioId")
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZED,
                data = it.data.copy(playlistId = nextRadioId, continuation = "0"),
            )
        }
        getNeteaseRadioBatch()
    }

    override fun getRelated(videoId: String) {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        coroutineScope.launch {
            songRepository.getRelatedData(videoId).collect { response ->
                when (response) {
                    is Resource.Success -> {
                        loadMoreCatalog(response.data?.first?.toCollection(arrayListOf()) ?: arrayListOf())
                        _queueData.update {
                            it.copy(
                                data =
                                    it.data.copy(
                                        continuation = response.data?.second,
                                    ),
                            )
                        }
                    }

                    is Resource.Error -> {
                        Logger.d("Check Related", "getRelated: ${response.message}")
                        _queueData.update {
                            it.copy(
                                queueState = QueueData.StateSource.STATE_INITIALIZED,
                                data =
                                    it.data.copy(
                                        continuation = null,
                                    ),
                            )
                        }
                        reorderShuffledQueue(player.getCurrentMediaTimeLine())
                    }
                }
            }
        }
    }

    /**
     * 网易私人FM 续批：拉一批 personalRadio、按现有队列去重后追加。与 YT 电台不同，FM 不做
     * "已在电台模式"早退——每批耗尽都继续拉。一批常只有 3 首，整批撞上队列里已有的歌不罕见，
     * 去重为空时最多再拉 2 次换批；仍为空则置回 INITIALIZED 等下次触发，不无限重试。
     * 仓库经 Koin 懒取（player 同款），避免为这一个依赖改构造签名。
     */
    private fun getNeteaseFmBatch() {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        coroutineScope.launch {
            _queueData.update {
                it.copy(queueState = QueueData.StateSource.STATE_INITIALIZING)
            }
            val repository = runCatching { getKoin().get<NeteaseRepositoryImpl>() }.getOrNull()
            if (repository == null) {
                Logger.w(TAG, "getNeteaseFmBatch: repository unavailable")
                _queueData.update {
                    it.copy(
                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                        data = it.data.copy(continuation = null),
                    )
                }
                return@launch
            }
            var fresh: List<Track> = emptyList()
            // SERVICE_SCOPE 是 Dispatchers.Main:YT 各路径靠仓库内部 flowOn(IO) 兜底,
            // personalRadio 是裸 suspend,必须自己切 IO,否则网络卡主线程。
            withContext(Dispatchers.IO) {
                repeat(3) {
                    val existingIds = _queueData.value.data.listTracks.map { track -> track.videoId }.toSet()
                    val batch =
                        repository.getPersonalRadio()
                            ?.songs
                            ?.map { it.toTrack() }
                            .orEmpty()
                            .filter { track -> track.videoId !in existingIds }
                    if (batch.isNotEmpty()) {
                        fresh = batch
                        return@repeat
                    }
                }
            }
            if (fresh.isNotEmpty()) {
                loadMoreCatalog(fresh.toCollection(arrayListOf()))
            } else {
                Logger.w(TAG, "getNeteaseFmBatch: no fresh songs after retries, stop extending this round")
                _queueData.update {
                    it.copy(
                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                        data = it.data.copy(continuation = null),
                    )
                }
                reorderShuffledQueue(player.getCurrentMediaTimeLine())
            }
        }
    }

    /**
     * 网易单曲电台续批：取 continuation 里的 item offset 拉一批 simiSong、按现有队列去重后
     * 追加。返回不足一整批、或整批撞重复，都算服务端见底：清掉 continuation 收尾（电台播完
     * 即止，不像 YT 电台那样再滑到 RDAMVM 关联续播——那边对数字 ID 同样无解）。
     */
    private fun getNeteaseRadioBatch() {
        if (queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        val songId =
            queueData.value.data.playlistId
                ?.removePrefix(NETEASE_RADIO_PLAYLIST_ID_PREFIX)
                ?: return
        coroutineScope.launch {
            _queueData.update {
                it.copy(queueState = QueueData.StateSource.STATE_INITIALIZING)
            }
            val repository = runCatching { getKoin().get<NeteaseRepositoryImpl>() }.getOrNull()
            if (repository == null) {
                Logger.w(TAG, "getNeteaseRadioBatch: repository unavailable")
                _queueData.update {
                    it.copy(
                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                        data = it.data.copy(continuation = null),
                    )
                }
                return@launch
            }
            val offset = queueData.value.data.continuation?.toIntOrNull()
            if (offset == null) {
                // 已收尾的电台再次触底：先复位状态，再由无尽钩子决定是否换尾曲种子续链
                _queueData.update {
                    it.copy(queueState = QueueData.StateSource.STATE_INITIALIZED)
                }
                reseedNeteaseRadioIfEndless()
                return@launch
            }
            var batch: List<Track> = emptyList()
            // SERVICE_SCOPE 是 Dispatchers.Main,getSongRadio 是裸 suspend,必须自己切 IO
            withContext(Dispatchers.IO) {
                batch =
                    repository
                        .getSongRadio(songId, limit = NETEASE_RADIO_APPEND_BATCH, offset = offset)
                        ?.songs
                        ?.map { it.toTrack() }
                        .orEmpty()
            }
            val existingIds = _queueData.value.data.listTracks.map { track -> track.videoId }.toSet()
            val fresh = batch.filter { track -> track.videoId !in existingIds }
            if (fresh.isNotEmpty()) {
                loadMoreCatalog(fresh.toCollection(arrayListOf()))
                // loadMoreCatalog 末尾已把状态复位为 INITIALIZED,这里只推进偏移
                _queueData.update {
                    it.copy(
                        data =
                            it.data.copy(
                                continuation =
                                    if (batch.size >= NETEASE_RADIO_APPEND_BATCH) {
                                        (offset + batch.size).toString()
                                    } else {
                                        null
                                    },
                            ),
                    )
                }
            } else {
                Logger.w(TAG, "getNeteaseRadioBatch: exhausted at offset $offset, radio ends")
                _queueData.update {
                    it.copy(
                        queueState = QueueData.StateSource.STATE_INITIALIZED,
                        data = it.data.copy(continuation = null),
                    )
                }
                reorderShuffledQueue(player.getCurrentMediaTimeLine())
                // 无尽队列开着：simiSong 见底不收摊，换尾曲种子接着续
                reseedNeteaseRadioIfEndless()
            }
        }
    }

    override fun setQueueData(queueData: QueueData.Data) {
        // 新队列装载=上一队列的无尽快照作废(不清理的话,之后关开关会把新队列裁成旧队列的形状)
        endlessRestore = null
        _queueData.update {
            it.copy(
                data = queueData,
            )
        }
        // Snapshot which tracks came from the album, for the crossfade rule. Taken at load time
        // because endless queue appends to this same queue afterwards, and those additions are not
        // album tracks — that boundary is exactly where crossfade should resume.
        player.albumTrackIds =
            if (queueData.playlistType == PlaylistType.ALBUM) {
                queueData.listTracks.map { it.videoId }.toSet()
            } else {
                emptySet()
            }
        Logger.w(TAG, "setQueueData: $queueData")
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = player.currentMediaItem

    override suspend fun moveItemUp(position: Int) {
        moveMediaItem(position, position - 1)
        queueData.value.data.listTracks.toMutableList().let { list ->
            val temp = list[position]
            list[position] = list[position - 1]
            list[position - 1] = temp
            _queueData.update {
                it.copy(
                    data = it.data.copy(listTracks = list),
                )
            }
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override suspend fun moveItemDown(position: Int) {
        moveMediaItem(position, position + 1)
        queueData.value.data.listTracks.toMutableList().let { list ->
            val temp = list[position]
            list[position] = list[position + 1]
            list[position + 1] = temp
            _queueData.update {
                it.copy(
                    data = it.data.copy(listTracks = list),
                )
            }
        }
        _currentSongIndex.value = player.currentMediaItemIndex
    }

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {
        if (mediaItem != null) {
            Logger.d("MusicSource", "addFirstMediaItem: ${mediaItem.mediaId}")
            moveMediaItem(0, index)
        }
    }

    override fun reset() {
        _queueData.value = QueueData()
    }

    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) {
        updateCatalog(downloaded, index).let {
            if (index != 0 && index != null) {
                moveMediaItem(0, index)
            }
            updateNextPreviousTrackAvailability()
            _queueData.update {
                it.copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                )
            }
            reorderShuffledQueue(player.getCurrentMediaTimeLine())
            // 随机开着(恢复哨兵或上轮未关):新队列装载完成后物理洗牌。
            // 追加型 loadMore 不走 load(),已洗牌队列的追加歌保持队尾,不触发重洗。
            if (shuffleRestoreListTracks != null) {
                shuffleQueuePhysical()
            }
        }
    }

    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) {
        Logger.d("Queue", listTrack.map { it.title }.toString())
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val catalogMetadata: ArrayList<Track> = arrayListOf()
        for (i in 0 until listTrack.size) {
            val track = listTrack[i]
            var thumbUrl =
                track.thumbnails?.lastOrNull()?.url
                    ?: "http://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
            thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
            val artistName: String = track.artists.toListName().connectArtists()
            val isSong =
                (
                    track.thumbnails?.lastOrNull()?.height != 0 &&
                        track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                        track.thumbnails?.lastOrNull()?.height != null
                ) &&
                    (
                        !thumbUrl
                            .contains("hq720") &&
                            !thumbUrl
                                .contains("maxresdefault") &&
                            !thumbUrl.contains("sddefault")
                    )
            if (track.artists.isNullOrEmpty()) {
                songRepository
                    .getSongInfo(track.videoId)
                    .lastOrNull()
                    .let { songInfo ->
                        if (songInfo != null) {
                            catalogMetadata.add(
                                track.copy(
                                    artists =
                                        listOf(
                                            Artist(
                                                songInfo.authorId,
                                                songInfo.author ?: "",
                                            ),
                                        ),
                                ),
                            )
                            addMediaItemNotSet(
                                GenericMediaItem(
                                    mediaId = track.videoId,
                                    uri = track.videoId,
                                    metadata =
                                        GenericMediaMetadata(
                                            title = track.title,
                                            artist = songInfo.author ?: "",
                                            albumTitle = track.album?.name,
                                            artworkUri = thumbUrl,
                                            description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                        ),
                                    customCacheKey = track.videoId,
                                ),
                            )
                        } else {
                            val mediaItem =
                                GenericMediaItem(
                                    mediaId = track.videoId,
                                    uri = track.videoId,
                                    metadata =
                                        GenericMediaMetadata(
                                            title = track.title,
                                            artist = "Various Artists",
                                            albumTitle = track.album?.name,
                                            artworkUri = thumbUrl,
                                            description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                        ),
                                    customCacheKey = track.videoId,
                                )
                            addMediaItemNotSet(mediaItem)
                            catalogMetadata.add(
                                track.copy(
                                    artists = listOf(Artist("", "Various Artists")),
                                ),
                            )
                        }
                    }
            } else {
                addMediaItemNotSet(
                    GenericMediaItem(
                        mediaId = track.videoId,
                        uri = track.videoId,
                        metadata =
                            GenericMediaMetadata(
                                title = track.title,
                                artist = artistName,
                                albumTitle = track.album?.name,
                                artworkUri = thumbUrl,
                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                            ),
                        customCacheKey = track.videoId,
                    ),
                )
                catalogMetadata.add(track)
            }
            Logger.d(
                "MusicSource",
                "updateCatalog: ${track.title}, ${catalogMetadata.size}",
            )
            Logger.d("MusicSource", "updateCatalog: ${track.title}")
        }
        // Intent, not observed: isPlaying is also false while paused OR while the next track
        // is still preparing, so reading it here let a background queue append strip a live
        // play-intent mid-load and the incoming track came up silent.
        if (!player.playWhenReady && isAddToQueue) {
            player.playWhenReady = false
        }
        _queueData.update {
            it
                .copy(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                ).addTrackList(catalogMetadata)
        }
        reorderShuffledQueue(player.getCurrentMediaTimeLine())
    }

    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean {
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val tempQueue: ArrayList<Track> = arrayListOf()
        tempQueue.addAll(queueData.value.data.listTracks)
        val chunkedList = tempQueue.chunked(100)
        // Reset queue
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = arrayListOf(),
                    ),
            )
        }
        val current = if (index != null) tempQueue.getOrNull(index) else null
        chunkedList.forEach { list ->
            val catalogMetadata: ArrayList<Track> = arrayListOf()
            Logger.w("SimpleMediaServiceHandler", "Catalog size: ${tempQueue.size}")
            Logger.w("SimpleMediaServiceHandler", "Skip index: $index")
            for (i in list.indices) {
                val track = list[i]
                if (track == current) continue
                var thumbUrl =
                    track.thumbnails?.lastOrNull()?.url
                        ?: "http://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
                thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
                val isSong =
                    (
                        track.thumbnails?.lastOrNull()?.height != 0 &&
                            track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                            track.thumbnails?.lastOrNull()?.height != null
                    ) &&
                        (
                            !thumbUrl
                                .contains("hq720") &&
                                !thumbUrl
                                    .contains("maxresdefault") &&
                                !thumbUrl.contains("sddefault")
                        )
                if (downloaded == 1) {
                    if (track.artists.isNullOrEmpty()) {
                        songRepository.getSongInfo(track.videoId).lastOrNull().let { songInfo ->
                            if (songInfo != null) {
                                val mediaItem =
                                    GenericMediaItem(
                                        mediaId = track.videoId,
                                        uri = track.videoId,
                                        metadata =
                                            GenericMediaMetadata(
                                                title = track.title,
                                                artist = songInfo.author ?: "",
                                                albumTitle = track.album?.name,
                                                artworkUri = thumbUrl,
                                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                            ),
                                        customCacheKey = track.videoId,
                                    )
                                addMediaItemNotSet(mediaItem)
                                catalogMetadata.add(
                                    track.copy(
                                        artists =
                                            listOf(
                                                Artist(
                                                    songInfo.authorId,
                                                    songInfo.author ?: "",
                                                ),
                                            ),
                                    ),
                                )
                            } else {
                                val mediaItem =
                                    GenericMediaItem(
                                        mediaId = track.videoId,
                                        uri = track.videoId,
                                        metadata =
                                            GenericMediaMetadata(
                                                title = track.title,
                                                artist = "Various Artists",
                                                albumTitle = track.album?.name,
                                                artworkUri = thumbUrl,
                                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                            ),
                                        customCacheKey = track.videoId,
                                    )
                                addMediaItemNotSet(mediaItem)
                                catalogMetadata.add(
                                    track.copy(
                                        artists = listOf(Artist("", "Various Artists")),
                                    ),
                                )
                            }
                        }
                    } else {
                        val mediaItem =
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = track.artists.toListName().connectArtists(),
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                    ),
                                customCacheKey = track.videoId,
                            )
                        addMediaItemNotSet(mediaItem)
                        catalogMetadata.add(track)
                    }
                    Logger.d("MusicSource", "updateCatalog: ${track.title}, ${catalogMetadata.size}")
                } else {
                    val artistName: String = track.artists.toListName().connectArtists()
                    if (track.artists.isNullOrEmpty()) {
                        songRepository
                            .getSongInfo(track.videoId)
                            .cancellable()
                            .lastOrNull()
                            .let { songInfo ->
                                if (songInfo != null) {
                                    catalogMetadata.add(
                                        track.copy(
                                            artists =
                                                listOf(
                                                    Artist(
                                                        songInfo.authorId,
                                                        songInfo.author ?: "",
                                                    ),
                                                ),
                                        ),
                                    )
                                    addMediaItemNotSet(
                                        GenericMediaItem(
                                            mediaId = track.videoId,
                                            uri = track.videoId,
                                            metadata =
                                                GenericMediaMetadata(
                                                    title = track.title,
                                                    artist = songInfo.author ?: "",
                                                    albumTitle = track.album?.name,
                                                    artworkUri = thumbUrl,
                                                    description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                                ),
                                            customCacheKey = track.videoId,
                                        ),
                                    )
                                } else {
                                    val mediaItem =
                                        GenericMediaItem(
                                            mediaId = track.videoId,
                                            uri = track.videoId,
                                            metadata =
                                                GenericMediaMetadata(
                                                    title = track.title,
                                                    artist = "Various Artists",
                                                    albumTitle = track.album?.name,
                                                    artworkUri = thumbUrl,
                                                    description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                                ),
                                            customCacheKey = track.videoId,
                                        )
                                    addMediaItemNotSet(mediaItem)
                                    catalogMetadata.add(
                                        track.copy(
                                            artists = listOf(Artist("", "Various Artists")),
                                        ),
                                    )
                                }
                            }
                    } else {
                        addMediaItemNotSet(
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = artistName,
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                    ),
                                customCacheKey = track.videoId,
                            ),
                        )
                        catalogMetadata.add(track)
                    }
                    Logger.d(
                        "MusicSource",
                        "updateCatalog: ${track.title}, ${catalogMetadata.size}",
                    )
                    Logger.d("MusicSource", "updateCatalog: ${track.title}")
                }
            }
            _queueData.update {
                it.addTrackList(catalogMetadata)
            }
            delay(200)
        }
        if (current != null && index != null) {
            _queueData.update {
                it.addToIndex(current, index)
            }
        }
        Logger.w("SimpleMediaServiceHandler", "current queue: ${player.mediaItemCount}")
        return true
    }

    override fun addQueueToPlayer() {
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                load()
            }
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                load(index = index)
            }
    }

    override fun currentOrderIndex(): Int =
        if (shuffleRestoreListTracks != null) {
            queueData.value.data.listTracks.indexOfLast {
                it.videoId == player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
            }
        } else {
            currentSongIndex()
        }

    override fun setCurrentSongIndex(index: Int) {
        _currentSongIndex.value = index
    }

    override suspend fun playNext(track: Track) {
        _queueData.update {
            it.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZING,
            )
        }
        val catalogMetadata: ArrayList<Track> =
            queueData.value.data.listTracks
                .toCollection(arrayListOf())
        var thumbUrl =
            track.thumbnails?.lastOrNull()?.url
                ?: "http://i.ytimg.com/vi/${track.videoId}/maxresdefault.jpg"
        thumbUrl = Regex("=w\\d+-h\\d+").replace(thumbUrl, "=w544-h544")
        val artistName: String = track.artists.toListName().connectArtists()
        val isSong =
            (
                track.thumbnails?.lastOrNull()?.height != 0 &&
                    track.thumbnails?.lastOrNull()?.height == track.thumbnails?.lastOrNull()?.width &&
                    track.thumbnails?.lastOrNull()?.height != null
            ) &&
                (
                    !thumbUrl
                        .contains("hq720") &&
                        !thumbUrl
                            .contains("maxresdefault") &&
                        !thumbUrl.contains("sddefault")
                )
        if ((player.currentMediaItemIndex + 1 in 0..queueData.value.data.listTracks.size)) {
            if (track.artists.isNullOrEmpty()) {
                songRepository.getSongInfo(track.videoId).cancellable().lastOrNull().let { songInfo ->
                    if (songInfo != null) {
                        catalogMetadata.add(
                            player.currentMediaItemIndex + 1,
                            track.copy(
                                artists =
                                    listOf(
                                        Artist(
                                            songInfo.authorId,
                                            songInfo.author ?: "",
                                        ),
                                    ),
                            ),
                        )
                        addMediaItemNotSet(
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = songInfo.author ?: "",
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                    ),
                                customCacheKey = track.videoId,
                            ),
                            player.currentMediaItemIndex + 1,
                        )
                    } else {
                        val mediaItem =
                            GenericMediaItem(
                                mediaId = track.videoId,
                                uri = track.videoId,
                                metadata =
                                    GenericMediaMetadata(
                                        title = track.title,
                                        artist = "Various Artists",
                                        albumTitle = track.album?.name,
                                        artworkUri = thumbUrl,
                                        description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                                    ),
                                customCacheKey = track.videoId,
                            )
                        addMediaItemNotSet(mediaItem, player.currentMediaItemIndex + 1)
                        catalogMetadata.add(
                            player.currentMediaItemIndex + 1,
                            track.copy(
                                artists = listOf(Artist("", "Various Artists")),
                            ),
                        )
                    }
                }
            } else {
                addMediaItemNotSet(
                    GenericMediaItem(
                        mediaId = track.videoId,
                        uri = track.videoId,
                        metadata =
                            GenericMediaMetadata(
                                title = track.title,
                                artist = artistName,
                                albumTitle = track.album?.name,
                                artworkUri = thumbUrl,
                                description = if (isSong) MERGING_DATA_TYPE.SONG else MERGING_DATA_TYPE.VIDEO,
                            ),
                        customCacheKey = track.videoId,
                    ),
                    player.currentMediaItemIndex + 1,
                )
                catalogMetadata.add(player.currentMediaItemIndex + 1, track)
            }
            Logger.d(
                "MusicSource",
                "updateCatalog: ${track.title}, ${catalogMetadata.size}",
            )
            Logger.d("MusicSource", "updateCatalog: ${track.title}")
        }
        _queueData.update {
            it
                .copy(
                    data =
                        it.data.copy(
                            listTracks = catalogMetadata,
                        ),
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                )
        }
        reorderShuffledQueue(player.getCurrentMediaTimeLine())
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        val track =
            when (anyTrack) {
                is Track -> anyTrack
                is SongEntity -> anyTrack.toTrack()
                else -> return
            }
        if (track.isExplicit && dataStoreManager.explicitContentEnabled.first() == FALSE) {
            showToast(ToastType.ExplicitContent)
            return
        }
        songRepository.insertSong(track.toSongEntity()).singleOrNull()?.let {
            Logger.d(TAG, "Inserted song: ${track.title}")
        }
        clearMediaItems()
        track.durationSeconds?.let {
            songRepository.updateDurationSeconds(it, track.videoId)
        }
        addMediaItem(track.toGenericMediaItem(), playWhenReady = type != RECOVER_TRACK_QUEUE)
        when (type) {
            SONG_CLICK, VIDEO_CLICK, SHARE -> {
                getRelated(track.videoId)
            }

            PLAYLIST_CLICK, ALBUM_CLICK, RADIO_CLICK -> {
                loadPlaylistOrAlbum(index)
            }
        }
    }

    override fun getPlayerDuration(): Long = player.duration

    override fun getProgress(): Long = player.currentPosition

    override fun mayBeSaveRecentSong(runBlocking: Boolean) {
        val unit =
            suspend {
                if (dataStoreManager.saveRecentSongAndQueue.first() == TRUE) {
                    // Skip while the playing song is unknown or the queue is mid-rebuild:
                    // updateCatalog clears listTracks and re-inserts the current track only at
                    // the end, so saving in that window persists a queue missing the current
                    // track (plus a blank media id), which desyncs the next restore.
                    val videoId = nowPlayingState.value.songEntity?.videoId
                    if (videoId != null && queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZED) {
                        dataStoreManager.saveRecentSong(
                            videoId,
                            player.contentPosition,
                        )
                        dataStoreManager.setPlaylistFromSaved(queueData.value.data.playlistName ?: "")
                        Logger.d(
                            "Check saved",
                            player.currentMediaItem
                                ?.metadata
                                ?.title
                                .toString(),
                        )
                        val temp: ArrayList<Track> = ArrayList()
                        temp.clear()
                        temp.addAll(_queueData.value.data.listTracks)
                        Logger.w("Check recover queue", temp.toString())
                        songRepository.recoverQueue(temp)
                    }
                }
            }
        if (runBlocking) {
            runBlocking { unit() }
        } else {
            coroutineScope.launch { unit() }
        }
    }

    /**
     * Lightweight periodic persistence of just the playback position (#2152).
     * Unlike [mayBeSaveRecentSong] this does NOT rewrite the whole saved queue, so it is
     * cheap enough to call every few seconds while a track plays uninterrupted. The saved
     * media id + position are what [mayBeRestoreQueue] reads to resume after a process kill.
     */
    private fun mayBeSaveRecentPosition() {
        coroutineScope.launch {
            if (dataStoreManager.saveRecentSongAndQueue.first() == TRUE) {
                val videoId = nowPlayingState.value.songEntity?.videoId ?: return@launch
                dataStoreManager.saveRecentSong(videoId, player.contentPosition)
            }
        }
    }

    override fun mayBeNormalizeVolume() {
        runBlocking {
            normalizeVolume = dataStoreManager.normalizeVolume.first() == TRUE
        }
        if (!normalizeVolume) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            player.volume = 1f
            return
        }

        // Always recreate LoudnessEnhancer because CrossfadeExoPlayerAdapter creates new
        // ExoPlayer instances per track, each with a different audio session ID.
        // The old LoudnessEnhancer becomes attached to a released session and has no effect.
        // Skip entirely while casting: a Cast session has no local audio session, and
        // constructing a LoudnessEnhancer with session id 0 (AUDIO_SESSION_ID_UNSET) throws.
        if (!_castState.value.isRemote && player.audioSessionId != PlayerConstants.AUDIO_SESSION_ID_UNSET) {
            try {
                loudnessEnhancer?.release()
            } catch (_: Exception) {
            }
            try {
                loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "mayBeNormalizeVolume: ${e.message}")
                e.printStackTrace()
            }
        }

        player.currentMediaItem?.mediaId?.let { songId ->
            val videoId =
                if (songId.contains("Video")) {
                    songId.removePrefix("Video")
                } else {
                    songId
                }
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob =
                coroutineScope.launch(Dispatchers.Main) {
                    fun Float?.toMb() = ((this ?: 0f) * 100).toInt()
                    streamRepository
                        .getFormatFlow(videoId)
                        .cancellable()
                        .distinctUntilChanged()
                        .collectLatest { format ->
                            if (format != null) {
                                val loudnessMb =
                                    format.loudnessDb.toMb().let {
                                        if (it !in -2000..2000) {
                                            0
                                        } else {
                                            it
                                        }
                                    }
                                Logger.d(TAG, "Loudness: ${format.loudnessDb} db, $loudnessMb")
                                try {
                                    loudnessEnhancer?.setTargetGain(0f.toMb() - loudnessMb)
                                    loudnessEnhancer?.enabled = true
                                    Logger.w(
                                        TAG,
                                        "mayBeNormalizeVolume: ${loudnessEnhancer?.targetGain}",
                                    )
                                } catch (e: Exception) {
                                    Logger.e(TAG, "mayBeNormalizeVolume: ${e.message}")
                                    e.printStackTrace()
                                }
                                try {
                                    secondLoudnessEnhancer?.setTargetGain(0f.toMb() - loudnessMb)
                                    secondLoudnessEnhancer?.enabled = true
                                    Logger.w(
                                        TAG,
                                        "mayBeNormalizeVolume: ${secondLoudnessEnhancer?.targetGain}",
                                    )
                                } catch (e: Exception) {
                                    Logger.e(TAG, "mayBeNormalizeVolume: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        }
                }
        }
    }

    override fun mayBeSavePlaybackState(runBlocking: Boolean) {
        // onIsPlayingChanged(false) lands on the main thread on every pause and crossfade
        // transition; blocking there on DataStore's serialized write queue ANRs the app
        // (5s+ while writes pile up). Default path defers to the service scope (Main,
        // suspending); release() passes runBlocking = true so the state is on disk before
        // the process can go away.
        val unit = suspend {
            if (dataStoreManager.saveStateOfPlayback.first() == TRUE) {
                dataStoreManager.recoverShuffleAndRepeatKey(
                    shuffleRestoreListTracks != null,
                    player.repeatMode,
                )
            }
        }
        if (runBlocking) {
            runBlocking { unit() }
        } else {
            coroutineScope.launch { unit() }
        }
    }

    override fun mayBeRestoreQueue() {
        coroutineScope.launch {
            if (dataStoreManager.saveRecentSongAndQueue.first() == TRUE) {
                val currentPlayingTrack = songRepository.getSongById(dataStoreManager.recentMediaId.first()).lastOrNull()?.toTrack()
                if (currentPlayingTrack != null) {
                    // Cross-source backstop: the saved playback state must belong to the
                    // currently selected source. Source switches no longer clear the saved
                    // queue (they keep playing), so this guard is what skips restoring a
                    // queue left over from the other source.
                    val savedIsNetease = currentPlayingTrack.videoId.toLongOrNull() != null
                    if (savedIsNetease != (dataStoreManager.selectedSource.first() == MusicSource.NETEASE.name)) {
                        Logger.w(TAG, "Skip queue restore: saved track ${currentPlayingTrack.videoId} is from the other music source")
                        return@launch
                    }
                    // Snapshot the position before touching the player: loading the queue fires
                    // onMediaItemTransition -> mayBeSaveRecentSong, which rewrites the stored
                    // position before the seek below would otherwise read it.
                    val savedPosition = dataStoreManager.recentPosition.first().toLongOrNull() ?: 0L
                    val savedTracks =
                        songRepository
                            .getSavedQueue()
                            .singleOrNull()
                            ?.firstOrNull()
                            ?.listTrack
                            .orEmpty()
                    // The saved queue may not contain the saved track (e.g. persisted while the
                    // queue was being rebuilt). Put the track at the front then: updateCatalog
                    // skips listTracks[index] as "already in the player", so index must point at
                    // the playing track or the UI queue and the player playlist end up shifted
                    // against each other.
                    var index = savedTracks.indexOfFirst { it.videoId == currentPlayingTrack.videoId }
                    val listTracks =
                        if (index == -1) {
                            index = 0
                            (listOf(currentPlayingTrack) + savedTracks).toCollection(arrayListOf())
                        } else {
                            savedTracks.toCollection(arrayListOf())
                        }
                    setQueueData(
                        QueueData.Data(
                            listTracks = listTracks,
                            firstPlayedTrack = currentPlayingTrack,
                            playlistId = LOCAL_PLAYLIST_ID_SAVED_QUEUE,
                            playlistName = dataStoreManager.playlistFromSaved.first(),
                            playlistType = PlaylistType.PLAYLIST,
                            continuation = null,
                        ),
                    )
                    addMediaItem(currentPlayingTrack.toGenericMediaItem(), playWhenReady = false)
                    loadPlaylistOrAlbum(index = index)
                    loadJob?.join()
                    resetCrossfade()
                    player.seekTo(index, savedPosition)
                    // Announce the restored position once. Nothing plays after a restore
                    // (playWhenReady = false above), and startProgressUpdate only runs while
                    // isPlaying — so no state is ever published and the UI sits at 0:00 on a
                    // queue the user left half-finished, until they press play.
                    _simpleMediaState.value = SimpleMediaState.Progress(savedPosition)
                }
            }
        }
    }

    override fun shouldReleaseOnTaskRemoved() =
        runBlocking {
            dataStoreManager.killServiceOnExit.first() == TRUE
        }

    override fun release() {
        Logger.w("ServiceHandler", "Starting release process")
        try {
            if (discordRPC?.isRpcRunning() == true) {
                discordRPC?.closeRPC()
            }
            discordRPC = null
            // Save state first
            mayBeSaveRecentSong(true)
            mayBeSavePlaybackState(true)

            // Stop and release player
            player.removeListener(this)

            // Release audio effects
            try {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null

                secondLoudnessEnhancer?.enabled = false
                secondLoudnessEnhancer?.release()
                secondLoudnessEnhancer = null
            } catch (e: Exception) {
                Logger.e("ServiceHandler", "Error releasing audio effects ${e.message}")
            }

            // Cancel all jobs
            progressJob?.cancel()
            progressJob = null
            bufferedJob?.cancel()
            bufferedJob = null
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob = null
            toggleLikeJob?.cancel()
            toggleLikeJob = null
            updateNotificationJob?.cancel()
            updateNotificationJob = null
            loadJob?.cancel()
            loadJob = null
            songEntityJob?.cancel()
            songEntityJob = null
            getSkipSegmentsJob?.cancel()
            getSkipSegmentsJob = null
            getFormatJob?.cancel()
            getFormatJob = null
            jobWatchtime?.cancel()
            jobWatchtime = null
            getDataOfNowPlayingTrackStateJob?.cancel()
            getDataOfNowPlayingTrackStateJob = null
            rpcSenderJob?.cancel()
            rpcSenderJob = null

            // Cancel coroutine scope
            coroutineScope.cancel()
            backgroundScope.cancel()

            Logger.w("ServiceHandler", "Handler released successfully. Scope active: ${coroutineScope.isActive}")
        } catch (e: Exception) {
            Logger.e("ServiceHandler", "Error during release ${e.message}")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val loaded =
            player.bufferedPosition.let {
                if (it > 0) {
                    it
                } else {
                    0
                }
            }
        val current =
            player.currentPosition.let {
                if (it > 0) {
                    it
                } else {
                    0
                }
            }
        when (playbackState) {
            PlayerConstants.STATE_IDLE -> {
                _simpleMediaState.value = SimpleMediaState.Initial
                Logger.d(TAG, "onPlaybackStateChanged: Idle")
            }

            PlayerConstants.STATE_ENDED -> {
                _simpleMediaState.value = SimpleMediaState.Ended
                Logger.d(TAG, "onPlaybackStateChanged: Ended")
            }

            PlayerConstants.STATE_READY -> {
                Logger.d(TAG, "onPlaybackStateChanged: Ready")
                // 有歌真的播起来了:灰歌连续跳过/换源的护栏计数归零
                unavailableChainCount = 0
                _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
            }

            else -> {
                if (current >= loaded) {
                    _simpleMediaState.value = SimpleMediaState.Buffering(player.currentPosition)
                    Logger.d(TAG, "onPlaybackStateChanged: Buffering")
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _controlState.value = _controlState.value.copy(isPlaying = isPlaying)
        if (isPlaying) {
            startProgressUpdate()
            nowPlayingState.value.songEntity?.let { updateDiscordRpc(it) }
        } else {
            stopProgressUpdate()
            mayBeSaveRecentSong()
            mayBeSavePlaybackState()
            if (discordRPC?.isRpcRunning() == true) {
                discordRPC?.closeRPC()
            }
        }
        updateNextPreviousTrackAvailability()
    }

    override fun onSeeked(positionMs: Long) {
        // System-panel / notification seeks bypass the handler and land straight on ExoPlayer;
        // refresh the RPC timestamps so Discord's progress bar follows the new position.
        if (player.isPlaying) {
            nowPlayingState.value.songEntity?.let { updateDiscordRpc(it) }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) {
        Logger.w(TAG, "Checking current state before transition ${simpleMediaState.value}")
        val lastPlayed = nowPlayingState.value.songEntity
        val currentState = simpleMediaState.value
        if (currentState is SimpleMediaState.Progress && lastPlayed != null && lastPlayed.durationSeconds > 0) {
            mayBeTrackingListeningLocal(lastPlayed, currentState.progress)
        }
        Logger.w(TAG, "Smooth Switching Transition Current Position: ${player.currentPosition}")
        mayBeNormalizeVolume()
        Logger.w(TAG, "REASON onMediaItemTransition: $reason")
        Logger.d(TAG, "Media Item Transition Media Item: ${mediaItem?.metadata?.title}")
        resetLyricLineForNewTrack(mediaItem)
        if (mediaItem?.mediaId != _nowPlaying.value?.mediaId) {
            _nowPlaying.value = mediaItem
        }
        if (mediaItem?.mediaId != nowPlayingState.value.mediaItem.mediaId) {
            Logger.w(TAG, "onMediaItemTransition: ${mediaItem?.mediaId}")
            if (mediaItem != null) {
                getDataOfNowPlayingState(mediaItem)
            } else {
                _nowPlayingState.update {
                    NowPlayingTrackState
                        .initial()
                }
            }
        } else if (mediaItem != null) {
            // Repeat-one replays the same mediaId without reloading now-playing data, so the RPC
            // timestamps would otherwise keep the previous play's start/end. Refresh them so Discord's
            // progress bar restarts with the track.
            nowPlayingState.value.songEntity?.let { updateDiscordRpc(it) }
        }
        queueData.value.data.listTracks.let { list ->
            // 网易私人FM 一批只有 3 首,天然过不了 list.size > 3 的门;FM 语义即无限电台,
            // 不依赖 endlessQueue 开关,凭哨兵 playlistId 放行。
            if ((list.size > 3 ||
                    queueData.value.data.playlistId == NETEASE_FM_PLAYLIST_ID ||
                    runBlocking { dataStoreManager.endlessQueue.first() == TRUE }) &&
                list.size - player.currentMediaItemIndex < 3 &&
                list.size - player.currentMediaItemIndex >= 0 &&
                queueData.value.queueState == QueueData.StateSource.STATE_INITIALIZED
            ) {
                Logger.d("Check loadMore", "loadMore")
                loadMore()
            }
        }
        updateNextPreviousTrackAvailability()
        updateNotification()
        if (player.currentMediaItemIndex == 0) {
            resetCrossfade()
        }
        mayBeSaveRecentSong()
    }

    private fun mayBeTrackingListeningLocal(
        song: SongEntity,
        currentPositionMillis: Long,
    ) {
        coroutineScope.launch {
            val trackingEnabled = dataStoreManager.localTrackingEnabled.first() == TRUE
            val percent = (currentPositionMillis / (song.durationSeconds * 1000f))
            // 网易播放上报:与本地统计同阈值(听满 20%),但不依赖 localTrackingEnabled——
            // 云端推荐数据,开关独立(neteasePlayReport);repo 懒取同 getNeteaseFmBatch,
            // 未注册/未登录/失败一律静默跳过
            if (percent >= 0.2f && song.videoId.toLongOrNull() != null &&
                dataStoreManager.neteasePlayReport.first() == TRUE
            ) {
                runCatching { getKoin().get<NeteaseRepositoryImpl>() }.getOrNull()
                    ?.takeIf { it.isLoggedIn.first() }
                    ?.let { repo ->
                        try {
                            val reported =
                                repo.scrobble(
                                    songId = song.videoId,
                                    timeMs = if (percent >= 0.8f) song.durationSeconds * 1000L else currentPositionMillis,
                                ).getOrDefault(false)
                            if (!reported) Logger.w(TAG, "netease scrobble rejected for ${song.videoId}")
                        } catch (e: Exception) {
                            Logger.w(TAG, "netease scrobble failed for ${song.videoId}: ${e.message}")
                        }
                    }
            }
            if (!trackingEnabled) {
                return@launch
            }
            Logger.w(TAG, "${song.title} - $currentPositionMillis ms listened, duration: ${song.durationSeconds * 1000} ms, percent: $percent")
            if (percent < 0.2f) {
                Logger.d(TAG, "Not enough listening time for ${song.title}, skipping tracking")
                return@launch
            }
            Logger.d(TAG, "Tracking listening for ${song.title} at position $currentPositionMillis ms")
            analyticsRepository
                .insertPlaybackEvent(
                    videoId = song.videoId,
                    channelIds = song.artistId ?: emptyList(),
                    albumBrowseId = song.albumId,
                    durationSecond = song.durationSeconds.toLong(),
                    listenedSecond =
                        if (percent >= 0.8f) {
                            song.durationSeconds.toLong()
                        } else {
                            (currentPositionMillis / 1000)
                        },
                ).collect {
                    Logger.d(TAG, "Inserted playback event for ${song.title}: $it")
                }
        }
    }

    private fun updateDiscordRpc(song: SongEntity) {
        coroutineScope.launch {
            // Grab the sequence number as the FIRST statement — before any suspension point — so it
            // reflects true event order. A monotonic counter (not wall-clock time) so an NTP/manual
            // clock step backward can't freeze the ordering guard in the sender (Fix A).
            val seq = rpcEventSeq.incrementAndGet()
            val snapshot =
                RpcSnapshot(
                    song = song,
                    progressMs = getProgress(),
                    durationMs = getPlayerDuration(),
                    speed = dataStoreManager.playbackSpeed.first(),
                    seq = seq,
                )
            // Compare-and-keep-newest: the playbackSpeed.first() suspend above means two calls to
            // updateDiscordRpc() can interleave and resolve out of order, so a plain `.value = ...`
            // write could let an older call clobber a newer one. Keep whichever has the higher seq.
            rpcSnapshotFlow.update { cur -> if (cur == null || seq >= cur.seq) snapshot else cur }
        }
    }

    override fun onTracksChanged(tracks: GenericTracks) {
        Logger.d(TAG, "onTracksChanged: ${tracks.groups.size}")
    }

    override fun onPlayerError(error: PlayerError) {
        when (error.errorCode) {
            PlayerConstants.ERROR_CODE_TIMEOUT -> {
                Logger.e("Player Error", "onPlayerError (${error.errorCode}): ${error.message}")
                if (isAppInForeground()) {
                    showToast(ToastType.PlayerError(error.errorCodeName))
                } else {
                    Logger.w("Player Error", "App is not in foreground, skipping toast")
                }
                player.pause()
            }

            else -> {
                Logger.e("Player Error", "onPlayerError (${error.errorCode}): ${error.message}")
                // 网易歌取不到流:resolver 对灰歌/付费墙抛 IOException(→IO_UNSPECIFIED 2000,
                // 不进适配器重试集);其余 IO/解析错误也可能在适配器重试耗尽后到这。
                // 探针分流后才决定按"无版权歌曲动作"设置处理(网络故障别误跳歌)。
                val mediaId = player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO)
                val isNeteaseSourceError =
                    mediaId?.toLongOrNull() != null && error.errorCode in NETEASE_UNAVAILABLE_ERROR_CODES
                if (isNeteaseSourceError && mediaId != null) {
                    coroutineScope.launch { handleNeteaseUnavailableSong(mediaId, error) }
                } else {
                    legacyPlaybackError(error)
                }
            }
        }
    }

    /** 既有播放错误路径:上报 + 前台 toast + 暂停 */
    private fun legacyPlaybackError(error: PlayerError) {
        pushPlayerError(error)
        if (isAppInForeground()) {
            showToast(ToastType.PlayerError(error.errorCodeName))
        } else {
            Logger.w("Player Error", "App is not in foreground, skipping toast")
        }
        player.pause()
    }

    /** 视作"网易歌取不到流"的错误码(与适配器可重试集同族 + 直通的 IO_UNSPECIFIED) */
    private val NETEASE_UNAVAILABLE_ERROR_CODES =
        setOf(
            2000, // ERROR_CODE_IO_UNSPECIFIED — resolver 抛 IOException(灰歌主路径)
            2001, // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            2004, // ERROR_CODE_IO_BAD_HTTP_STATUS
            2005, // ERROR_CODE_IO_FILE_NOT_FOUND
            3001, // ERROR_CODE_PARSING_CONTAINER_MALFORMED
        )

    /**
     * 网易灰歌/付费墙的处理(neteaseUnavailableAction 设置):自动跳过 / 暂停 /
     * 跨源回退 YT 同名曲。探针(songDetail privilege)失败或判可播 → 走既有错误路径。
     */
    private suspend fun handleNeteaseUnavailableSong(
        mediaId: String,
        error: PlayerError,
    ) {
        val probe = runCatching { neteaseRepository.probeNeteasePlayable(mediaId) }.getOrNull()
        if (probe != NeteasePlayability.NO_COPYRIGHT && probe != NeteasePlayability.PAYWALLED) {
            Logger.w(TAG, "netease unavailable probe=$probe for $mediaId, falling back to legacy error path")
            legacyPlaybackError(error)
            return
        }
        Logger.w(TAG, "netease song unplayable ($probe): $mediaId, applying unavailable action")
        val queueSize = maxOf(queueData.value.data.listTracks.size, player.mediaItemCount)
        // 防循环护栏:整队连续不可播(REPEAT_ALL 会绕圈,或全是灰歌的电台队列)时停下
        if (unavailableChainCount >= queueSize) {
            unavailableChainCount = 0
            Logger.w(TAG, "unavailable chain guard hit ($queueSize consecutive), pausing")
            player.pause()
            showUnavailableToast(ToastType.UnavailableQueueExhausted)
            return
        }
        unavailableChainCount++
        when (dataStoreManager.neteaseUnavailableAction.first()) {
            DataStoreManager.Values.NETEASE_UNAVAILABLE_ACTION_PAUSE -> {
                player.pause()
                showUnavailableToast(ToastType.UnavailableSongPaused)
            }

            DataStoreManager.Values.NETEASE_UNAVAILABLE_ACTION_SWITCH_YT -> {
                val index = player.currentMediaItemIndex
                val current = queueData.value.data.listTracks.getOrNull(index)
                val replacement = current?.let { searchYouTubeReplacement(it) }
                if (replacement == null) {
                    Logger.w(TAG, "no YouTube Music match for \"${current?.title}\", skipping instead")
                    showUnavailableToast(ToastType.UnavailableSongSwitchFailed)
                    skipForwardOrPause()
                } else {
                    Logger.w(TAG, "switching \"${current?.title}\" to YouTube Music ${replacement.videoId}")
                    _queueData.update { q ->
                        q.copy(
                            data =
                                q.data.copy(
                                    listTracks =
                                        q.data.listTracks.toMutableList().apply {
                                            set(index, replacement)
                                        },
                                ),
                        )
                    }
                    // 落库供队列恢复/统计读;失败不阻塞换源播放
                    runCatching { songRepository.insertSong(replacement.toSongEntity()).first() }
                    // replaceMediaItem 对当前曲自动重载续播(尊重 playWhenReady)
                    player.replaceMediaItem(index, replacement.toGenericMediaItem())
                    showUnavailableToast(ToastType.UnavailableSongSwitched)
                }
            }

            else -> {
                // 有下一首才说"已跳过";搜索点击这类单曲队列跳无可跳(退化为暂停),
                // 按不可播放提示,别说"跳过了"什么都没跳
                if (player.hasNextMediaItem()) {
                    showUnavailableToast(ToastType.UnavailableSongSkipped)
                } else {
                    showUnavailableToast(ToastType.UnavailableSongPaused)
                }
                skipForwardOrPause()
            }
        }
    }

    /** 队列里还有下一首就跳,没有(单曲/队尾+REPEAT_OFF)就停下 */
    private fun skipForwardOrPause() {
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else {
            player.pause()
        }
    }

    private fun showUnavailableToast(type: ToastType) {
        if (isAppInForeground()) {
            showToast(type)
        } else {
            Logger.w("Player Error", "App is not in foreground, skipping toast")
        }
    }

    /** 按网易云歌的 标题+艺人 搜 YT 同名曲:时长 ±4s 过滤 + 艺人/标题相似度择优 */
    private suspend fun searchYouTubeReplacement(track: Track): Track? {
        val query =
            buildString {
                append(track.title)
                track.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(it)
                }
            }
        val candidates =
            runCatching { searchRepository.searchYouTubeSongsOnce(query) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return null
        val wantedSeconds = track.durationSeconds ?: 0
        val normalizedWant = normalizeTitleForMatch(track.title)
        val artistWant =
            track.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let(::normalizeTitleForMatch)
        var best: com.maxrave.domain.data.model.searchResult.songs.SongsResult? = null
        var bestArtistHit = false
        var bestScore = -1
        var bestDelta = Int.MAX_VALUE
        for (candidate in candidates) {
            val seconds = candidate.durationSeconds ?: continue
            val delta = kotlin.math.abs(seconds - wantedSeconds)
            if (wantedSeconds > 0 && delta > 4) continue
            val normalized = normalizeTitleForMatch(candidate.title ?: continue)
            val score =
                when {
                    normalized == normalizedWant -> 3
                    normalized.contains(normalizedWant) || normalizedWant.contains(normalized) -> 2
                    else -> 1
                }
            // 无时长参照(=0)时只信标题精确/包含匹配,防换到 live/合集/串烧
            if (wantedSeconds <= 0 && score < 2) continue
            // 艺人校验:同名不同歌手是回退最常见的错配;候选艺人名与目标互相包含才算命中。
            // 无艺人信息的结果(音乐合集频道等)不算命中——只有标题精确匹配时才容忍
            val artistHit =
                if (artistWant == null) {
                    true
                } else {
                    candidate.artists.orEmpty().any { artist ->
                        val a = artist.name?.takeIf { it.isNotBlank() }?.let(::normalizeTitleForMatch)
                        a != null && (a.contains(artistWant) || artistWant.contains(a))
                    }
                }
            if (!artistHit && score < 3) continue
            // 优先级:艺人命中 > 标题相似分 > 时长差
            val better =
                best == null ||
                    (artistHit && !bestArtistHit) ||
                    (artistHit == bestArtistHit && score > bestScore) ||
                    (artistHit == bestArtistHit && score == bestScore && delta < bestDelta)
            if (better) {
                best = candidate
                bestArtistHit = artistHit
                bestScore = score
                bestDelta = delta
            }
        }
        return best?.toTrack()
    }

    /** 标题匹配前归一:去括号段/版本噪声词/标点空白,全小写 */
    private fun normalizeTitleForMatch(value: String): String =
        value
            .lowercase()
            .replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
            .replace(
                Regex("""(?<![\p{L}\p{N}_])(official|audio|video|music|lyrics?|mv|visualizer|hd|hq|4k)(?![\p{L}\p{N}_])"""),
                " ",
            )
            .replace(Regex("""[\p{Punct}\p{IsWhite_Space}]+"""), "")

    override fun onShuffleModeEnabledChanged(
        shuffleModeEnabled: Boolean,
        list: List<GenericMediaItem>,
    ) {
        when (shuffleModeEnabled) {
            true -> {
                _controlState.value = _controlState.value.copy(isShuffle = true)
            }

            false -> {
                _controlState.value = _controlState.value.copy(isShuffle = false)
            }
        }
        reorderShuffledQueue(list)
        updateNextPreviousTrackAvailability()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNextPreviousTrackAvailability()
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_OFF -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.None)
            }

            PlayerConstants.REPEAT_MODE_ONE -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.One)
            }

            PlayerConstants.REPEAT_MODE_ALL -> {
                _controlState.value =
                    _controlState.value.copy(repeatState = RepeatState.All)
            }
        }
    }

    /**
     * Publishes exactly one state, and it matches the argument.
     *
     * It used to write Loading unconditionally and then call stopBufferedUpdate, which wrote
     * Loading again. `_simpleMediaState` is a StateFlow collected from another thread, so those
     * writes conflate and the UI settled on Loading even when buffering had just *finished* —
     * leaving a spinner over playback that was already running.
     */
    override fun onIsLoadingChanged(isLoading: Boolean) {
        if (isLoading) {
            startBufferedUpdate()
            // Already holding more than the playhead needs: the stall is nominal, so do not put a
            // spinner over playback that is about to continue.
            if (player.bufferedPosition > player.currentPosition) {
                _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
            } else {
                _simpleMediaState.value =
                    SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
            }
        } else {
            stopBufferedUpdate()
            _simpleMediaState.value = SimpleMediaState.Ready(player.duration)
        }
    }

    override fun onCrossfadeStateChanged(isCrossfading: Boolean) {
        _controlState.update {
            it.copy(isCrossfading = isCrossfading)
        }
    }

    override fun onTimelineChanged(
        list: List<GenericMediaItem>,
        reason: String,
    ) {
        super.onTimelineChanged(list, reason)
        Logger.d(TAG, "onTimelineChanged: Reason: $reason, Items: ${list.size}")
        reorderShuffledQueue(list)
    }

    override fun onCastStateChanged(castState: GenericCastState) {
        _castState.value = castState
    }

    private fun reorderShuffledQueue(list: List<GenericMediaItem>) {
        val listTrack = queueData.value.data.listTracks
        if (list.isEmpty()) {
            run {
                Logger.d(TAG, "Reordering shuffled queue: empty list")
                return
            }
        }
        // 消费式匹配:队列里同 videoId 的重复条目按 player 顺序逐个取用,不因 firstOrNull
        // 重复命中同一条导致 size 对不上而整次放弃(旧实现=随机对含重复歌的队列静默失效)
        val pool = listTrack.toMutableList()
        val sorted = list.mapNotNull { item ->
            val idx = pool.indexOfFirst { it.videoId == item.mediaId }
            if (idx >= 0) pool.removeAt(idx) else null
        }
        if (sorted.size != listTrack.size || pool.isNotEmpty()) return
        Logger.d(TAG, "Reordering shuffled queue: ${sorted.map { it.title }}")
        _queueData.update {
            it.copy(
                data =
                    it.data.copy(
                        listTracks = sorted.toCollection(ArrayList()),
                    ),
            )
        }
    }

/**
     * 开随机(NeriPlayer 同款):快照当前队列原序 → 当前曲置首、其余随机 → player 与
     * queueData 同步物理重排。不打断正在播放的曲目。
     */
    private fun shuffleQueuePhysical() {
        val tracks = queueData.value.data.listTracks
        if (tracks.size < 2) return
        val currentId = player.currentMediaItem?.mediaId
        if (shuffleRestoreListTracks.isNullOrEmpty()) {
            // 哨兵(空)或首次进入才记快照;恢复流程 467 处只置哨兵,由这里补真快照
            shuffleRestoreListTracks = tracks.toList()
        }
        val currentTrack = tracks.firstOrNull { it.videoId == currentId }
        val shuffled =
            listOfNotNull(currentTrack) + tracks.filter { it !== currentTrack }.shuffled()
        player.reorderQueueByMediaIds(shuffled.map { it.videoId })
        _controlState.value = _controlState.value.copy(isShuffle = true)
        updateNextPreviousTrackAvailability()
    }

/** 关随机:恢复洗牌前的队列原序,当前曲用最新播放状态回填。 */
    private fun restoreQueueFromShuffle() {
        val snapshot = shuffleRestoreListTracks
        shuffleRestoreListTracks = null
        // 恢复哨兵(随机开着但从未真正洗过牌,如恢复播放后队列一直没装载)只关开关
        if (snapshot.isNullOrEmpty()) {
            _controlState.value = _controlState.value.copy(isShuffle = false)
            return
        }
        val currentId = player.currentMediaItem?.mediaId
        val currentTrack = queueData.value.data.listTracks.firstOrNull { it.videoId == currentId }
        val restored =
            snapshot.map { track ->
                if (currentTrack != null && track.videoId == currentId) currentTrack else track
            }
        player.reorderQueueByMediaIds(restored.map { it.videoId })
        _controlState.value = _controlState.value.copy(isShuffle = false)
        updateNextPreviousTrackAvailability()
    }

}

private fun isAppInForeground(): Boolean {
    val appProcessInfo = RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(appProcessInfo)
    return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}