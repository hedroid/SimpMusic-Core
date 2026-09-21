package com.maxrave.media3.di

import android.app.Activity
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.maxrave.common.Config.CANVAS_CACHE
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.cast.CastHandoffManager
import com.maxrave.media3.cast.CastStreamResolver
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter
import com.maxrave.media3.extension.isFullyCached
import com.maxrave.media3.repository.CacheRepositoryImpl
import com.maxrave.media3.service.SimpleMediaService
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.service.download.DownloadUtils
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.simpmusic.cast.initCast
import org.simpmusic.cast.wrapWithCastPlayer
import java.net.Proxy
import kotlin.time.Duration.Companion.seconds

/**
 * Required repository first initialization
 */
@UnstableApi
private val mediaServiceModule =
    module {
        // Service
        // CoroutineScope for service
        single<CoroutineScope>(
            createdAtStart = true,
            qualifier = named(SERVICE_SCOPE),
        ) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        // Cache
        single<DatabaseProvider>(
            createdAtStart = true,
        ) {
            provideDatabaseProvider(androidContext())
        }
        // Player Cache
        single<SimpleCache>(qualifier = named(PLAYER_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "exoplayer",
                cacheSize = runBlocking { get<DataStoreManager>().maxSongCacheSize.first() },
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // Download Cache
        single<SimpleCache>(qualifier = named(DOWNLOAD_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "download",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // Spotify Canvas Cache
        single<SimpleCache>(qualifier = named(CANVAS_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "spotifyCanvas",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // DownloadUtils
        single<DownloadHandler>(createdAtStart = true) {
            DownloadUtils(
                context = androidContext(),
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                dataStoreManager = get(),
                databaseProvider = get(),
                streamRepository = get(),
                songRepository = get(),
            )
        }

        // AudioAttributes
        single<AudioAttributes>(createdAtStart = true) {
            provideAudioAttributes()
        }

        single<MergingMediaSourceFactory>(createdAtStart = true) {
            provideMergingMediaSource(
                androidContext(),
                get(named(DOWNLOAD_CACHE)),
                get(named(PLAYER_CACHE)),
                get(),
                get(named(SERVICE_SCOPE)),
                get(),
            )
        }

        single<DefaultRenderersFactory>(createdAtStart = true) {
            provideRendererFactory(androidContext())
        }

        // Player exposed for MediaSession + UI (video rendering via PlayerView/PlayerSurface).
        // The adapter's ForwardingPlayer (delegating to the active ExoPlayer) is wrapped with
        // Cast support in the full build; org.simpmusic.cast no-ops back to the same instance
        // in the FOSS build, so this stays the stable session-level player either way.
        single<Player>(qualifier = named(MAIN_PLAYER)) {
            val adapter = get<MediaPlayerInterface>() as CrossfadeExoPlayerAdapter
            initCast(androidContext())
            wrapWithCastPlayer(androidContext(), adapter.forwardingPlayer)
        }

        // CoilBitmapLoader
        single<CoilBitmapLoader>(createdAtStart = true) {
            provideCoilBitmapLoader(androidContext(), get(named(SERVICE_SCOPE)))
        }

        single<MediaPlayerInterface>(createdAtStart = true) {
            CrossfadeExoPlayerAdapter(
                context = androidContext(),
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                mediaSourceFactory = get(),
                audioAttributes = get(),
                streamRepository = get(),
            )
        }

        // Local ↔ Cast receiver handoff. No-op when wrapWithCastPlayer returned the plain
        // ForwardingPlayer (FOSS build or no GMS on the device).
        single<CastHandoffManager>(createdAtStart = true) {
            CastHandoffManager(
                adapter = get<MediaPlayerInterface>() as CrossfadeExoPlayerAdapter,
                sessionPlayer = get(qualifier = named(MAIN_PLAYER)),
                resolver = CastStreamResolver(get(), get()),
                coroutineScope = get(qualifier = named(SERVICE_SCOPE)),
            ).also { it.start() }
        }

        // MediaSession Callback for main player
        single<MediaLibrarySession.Callback>(createdAtStart = true) {
            SimpleMediaSessionCallback(
                androidApplication(),
                get<CoroutineScope>(named(SERVICE_SCOPE)),
                get<MediaPlayerHandler>(),
                get<SearchRepository>(),
                get<SongRepository>(),
                get<LocalPlaylistRepository>(),
                get<PlaylistRepository>(),
                get<HomeRepository>(),
                get<StreamRepository>(),
            )
        }

        single<CacheRepository>(createdAtStart = true) {
            CacheRepositoryImpl(
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                canvasCache = get(named(CANVAS_CACHE)),
            )
        }
    }

@UnstableApi
private fun provideResolvingDataSourceFactory(
    cacheDataSourceFactory: CacheDataSource.Factory,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    dataStoreManager: DataStoreManager,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
): DataSource.Factory {
    return ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        Logger.w("Stream", mediaId)
        Logger.w("Stream", mediaId.startsWith(MERGING_DATA_TYPE.VIDEO).toString())
        val fullyCached =
            downloadCache.isFullyCached(mediaId, dataSpec.position) ||
                playerCache.isFullyCached(mediaId, dataSpec.position)
        if (fullyCached) {
            // Once per track, not once per open: updateFormat is a fire-and-forget
            // youTube.player() call with no in-flight dedup.
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    streamRepository.updateFormat(
                        if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                            mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                        } else {
                            mediaId
                        },
                    )
                }
            }
            Logger.w("Stream", "Cached $mediaId")
            // 缓存命中也不提前返回:和下面的网络路径一样解析出真实 URL 且**不封顶**。
            // 曾经这里 subrange 截 5MiB 分块——Media3 把"读满声明长度"当流结束(与网络路径
            // 7532dec 修掉的病同源),整首被缓存的歌(网易 320k mp3/flac 全部 >5MiB)在
            // chunk1 解码完后就静音到曲尾,"缓存过的歌反而没声音"即此。缓存命中时
            // CacheDataSource(dataSpec.key=mediaId)全程读盘不走网络;中途被 LRU 驱逐则
            // 透明回退 OkHttp 上游——比裸 id(驱逐时 FileNotFound 不可恢复)更稳。
        }
        var dataSpecReturn: DataSpec = dataSpec
        var resolved = false
        runBlocking(Dispatchers.IO) {
            fun networkSpec(url: String): DataSpec =
                // 网络路径不封顶:一次 open 流完剩余整首。曾经这里像缓存路径一样截成 5MiB
                // 分块,但 Media3 把"读满截断长度的 EOF"当流结束,第二个分块永远不会装载
                // ——网易 320k mp3 普遍 9-12MB(>5MiB),每首歌在 ~2 分钟(chunk1 解码完)
                // 静音到曲尾,表现即"播放一会就没声音"(OkHttp 日志实锤:每曲仅发
                // Range:0-5242879 一个请求)。YT 多数曲 <5MiB 故长期未暴露。
                dataSpec.withUri(url.toUri())
            if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                val id = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                streamRepository.getNewFormat(id).lastOrNull()?.let {
                    val videoUrl = it.videoUrl
                    if (videoUrl != null && it.expiredTime > now()) {
                        Logger.d("Stream", videoUrl)
                        Logger.w("Stream", "Video from format")
                        val is403Url = streamRepository.is403Url(videoUrl).firstOrNull() != false
                        Logger.d("Stream", "is 403 $is403Url")
                        if (!is403Url) {
                            dataSpecReturn = networkSpec(videoUrl)
                            resolved = true
                            return@runBlocking
                        }
                    }
                }
                streamRepository
                    .getStream(
                        dataStoreManager,
                        id,
                        isDownloading = false,
                        isVideo = true,
                    ).lastOrNull()
                    ?.let {
                        Logger.d("Stream", it)
                        Logger.w("Stream", "Video")
                        dataSpecReturn = networkSpec(it)
                        resolved = true
                    }
            } else {
                streamRepository.getNewFormat(mediaId).lastOrNull()?.let {
                    val audioUrl = it.audioUrl
                    if (audioUrl != null && it.expiredTime > now()) {
                        Logger.d("Stream", audioUrl)
                        Logger.w("Stream", "Audio from format")
                        val is403Url = streamRepository.is403Url(audioUrl).firstOrNull() != false
                        Logger.d("Stream", "is 403 $is403Url")
                        if (!is403Url) {
                            dataSpecReturn = networkSpec(audioUrl)
                            resolved = true
                            return@runBlocking
                        }
                    }
                }
                streamRepository
                    .getStream(
                        dataStoreManager,
                        mediaId,
                        isDownloading = false,
                        isVideo = false,
                    ).lastOrNull()
                    ?.let {
                        Logger.d("Stream", it)
                        Logger.w("Stream", "Audio")
                        dataSpecReturn = networkSpec(it)
                        resolved = true
                    }
            }
        }
        if (!resolved) {
            if (fullyCached) {
                // 灰歌等取不到流但整首还在缓存:裸 id 不封顶兜底直读缓存(仅此兜底路径
                // 存在"读盘中被驱逐"风险,可接受——取不到 URL 的歌没别的播法)
                Logger.w("Stream", "unresolved but fully cached, serving cache: $mediaId")
                return@Factory dataSpec
            }
            Logger.e("Stream", "Failed to resolve stream URL for $mediaId")
            throw java.io.IOException("Failed to resolve stream URL for $mediaId")
        }
        return@Factory dataSpecReturn
    }
}

@UnstableApi
private fun provideExtractorFactory(): ExtractorsFactory =
    ExtractorsFactory {
        arrayOf(
            FlacExtractor(
                FlacExtractor.FLAG_DISABLE_ID3_METADATA,
            ),
            // 网易云取流是 mp3(320k)/flac(无损档),YT 从不下发 mp3,上游因此没注册
            Mp3Extractor(),
            MatroskaExtractor(
                DefaultSubtitleParserFactory(),
            ),
            FragmentedMp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
            Mp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
        )
    }

@UnstableApi
private fun provideMediaSourceFactory(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    dataStoreManager: DataStoreManager,
    coroutineScope: CoroutineScope,
): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(
        provideResolvingDataSourceFactory(
            provideCacheDataSource(
                downloadCache,
                playerCache,
                context,
                dataStoreManager.getJVMProxy()?.let {
                    Proxy(
                        when (it.type) {
                            DataStoreManager.ProxyType.PROXY_TYPE_HTTP -> Proxy.Type.HTTP
                            DataStoreManager.ProxyType.PROXY_TYPE_SOCKS -> Proxy.Type.SOCKS
                        },
                        java.net.InetSocketAddress(it.host, it.port),
                    )
                },
            ),
            downloadCache,
            playerCache,
            dataStoreManager,
            streamRepository,
            coroutineScope,
        ),
        provideExtractorFactory(),
    )

@OptIn(UnstableApi::class)
private fun provideMergingMediaSource(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
    dataStoreManager: DataStoreManager,
): MergingMediaSourceFactory =
    MergingMediaSourceFactory(
        provideMediaSourceFactory(
            context,
            downloadCache,
            playerCache,
            streamRepository,
            dataStoreManager,
            coroutineScope,
        ),
        dataStoreManager,
    )

@UnstableApi
private fun provideRendererFactory(context: Context): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray(),
                        SilenceSkippingAudioProcessor(
                            2_000_000,
                            (20_000 / 2_000_000).toFloat(),
                            2_000_000,
                            0,
                            256,
                        ),
                        SonicAudioProcessor(),
                    ),
                ).build()
    }

@UnstableApi
private fun provideCacheDataSource(
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    context: Context,
    proxy: Proxy? = null,
): CacheDataSource.Factory =
    CacheDataSource
        .Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    DefaultDataSource
                        .Factory(
                            context,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .connectTimeout(30.seconds)
                                    .readTimeout(30.seconds)
                                    .proxy(
                                        proxy,
                                    ).addInterceptor(
                                        HttpLoggingInterceptor()
                                            .apply {
                                                level = HttpLoggingInterceptor.Level.HEADERS
                                            },
                                    ).build(),
                            ),
                        ),
                ),
        ).setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

@UnstableApi
private fun provideLoadControl(): LoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(
            DEFAULT_MIN_BUFFER_MS * 4,
            DEFAULT_MAX_BUFFER_MS * 4,
            // bufferForPlaybackMs=
            0,
            // bufferForPlaybackAfterRebufferMs=
            0,
        ).build()

@UnstableApi
private fun provideAudioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

@UnstableApi
private fun provideDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)

@UnstableApi
private fun provideSimpleCache(
    context: Context,
    cacheName: String,
    cacheSize: Int = -1,
    databaseProvider: DatabaseProvider,
) = SimpleCache(
    context.filesDir.resolve(cacheName),
    when (cacheSize) {
        -1 -> NoOpCacheEvictor()
        else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
    },
    databaseProvider,
)

@UnstableApi
private fun provideCoilBitmapLoader(
    context: Context,
    coroutineScope: CoroutineScope,
): CoilBitmapLoader = CoilBitmapLoader(context, coroutineScope)

@OptIn(UnstableApi::class)
fun loadMediaService() {
    loadKoinModules(mediaServiceModule)
}

@OptIn(UnstableApi::class)
fun startService(
    context: Context,
    serviceConnection: ServiceConnection,
) {
    val intent = Intent(context, SimpleMediaService::class.java)
    try {
        context.startService(intent)
    } catch (e: IllegalStateException) {
        // BackgroundServiceStartNotAllowedException (Android 12+)
        ContextCompat.startForegroundService(context, intent)
    }
    context.bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    Logger.d("Service", "Service started")
}

@OptIn(UnstableApi::class)
fun stopService(context: Context) {
    context.stopService(Intent(context, SimpleMediaService::class.java))
}

@OptIn(UnstableApi::class)
fun setServiceActivitySession(
    context: Context,
    cls: Class<out Activity>,
    musicService: IBinder?,
) {
    (musicService as? SimpleMediaService.MusicBinder)?.setActivitySession(context, cls)
}