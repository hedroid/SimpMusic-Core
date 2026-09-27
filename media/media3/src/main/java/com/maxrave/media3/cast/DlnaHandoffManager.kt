package com.maxrave.media3.cast

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.maxrave.logger.Logger
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter
import com.maxrave.media3.exoplayer.RemotePlaybackController
import com.maxrave.media3.exoplayer.RemotePlaybackOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.simpmusic.cast.DlnaControlPoint
import org.simpmusic.cast.DlnaDevice
import org.simpmusic.cast.DlnaSessionBridge
import org.simpmusic.cast.DlnaSessionHandler

/** Local-player handoff to a UPnP AV MediaRenderer. */
internal class DlnaHandoffManager(
    private val adapter: CrossfadeExoPlayerAdapter,
    private val resolver: CastStreamResolver,
    private val coroutineScope: CoroutineScope,
) : DlnaSessionHandler {
    private var device: DlnaDevice? = null
    private var controlPoint: DlnaControlPoint? = null
    private var remotePlayer: DlnaRemotePlaybackController? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var lastKnownPositionMs = 0L
    private var stoppedPolls = 0

    fun start() {
        DlnaSessionBridge.register(this)
    }

    override fun connect(device: DlnaDevice) {
        coroutineScope.launch {
            if (this@DlnaHandoffManager.device != null) disconnectInternal(stopRenderer = true, resumeLocal = false)
            val startIndex = adapter.currentMediaItemIndex
            val startPosition = adapter.currentPosition
            val shouldPlay = adapter.isPlaying || adapter.playWhenReady
            val control = DlnaControlPoint(device)
            val player = DlnaRemotePlaybackController(control, coroutineScope)
            this@DlnaHandoffManager.device = device
            controlPoint = control
            remotePlayer = player
            lastKnownPositionMs = startPosition
            DlnaSessionBridge.updateActiveDevice(device)
            adapter.setRemotePlaybackActive(RemotePlaybackOwner.DLNA, player, device.name, ::pushCurrentItem)
            startPolling()
            if (startIndex >= 0) pushCurrentItem(startIndex, startPosition, shouldPlay)
            Logger.w(TAG, "DLNA connected to ${device.name} at index=$startIndex position=${startPosition}ms")
        }
    }

    override fun disconnect() {
        coroutineScope.launch { disconnectInternal(stopRenderer = true, resumeLocal = true) }
    }

    private suspend fun disconnectInternal(
        stopRenderer: Boolean,
        resumeLocal: Boolean,
    ) {
        val resumeIndex = adapter.currentMediaItemIndex
        val resumePosition = lastKnownPositionMs
        loadJob?.cancel()
        loadJob = null
        pollJob?.cancel()
        pollJob = null
        if (stopRenderer) runCatching { controlPoint?.stop() }
        device = null
        controlPoint = null
        remotePlayer = null
        stoppedPolls = 0
        DlnaSessionBridge.updateActiveDevice(null)
        val released = adapter.setRemotePlaybackActive(RemotePlaybackOwner.DLNA, null, null)
        if (released && resumeLocal && resumeIndex >= 0) adapter.seekTo(resumeIndex, resumePosition)
        Logger.w(TAG, "DLNA disconnected — resuming locally at ${resumePosition}ms")
    }

    private fun pushCurrentItem(
        playlistIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        val control = controlPoint ?: return
        val player = remotePlayer ?: return
        loadJob?.cancel()
        loadJob =
            coroutineScope.launch {
                try {
                    val item = adapter.getMediaItemAt(playlistIndex) ?: return@launch
                    player.update(playbackState = Player.STATE_BUFFERING, isPlaying = false)
                    adapter.notifyRemotePlaybackState(Player.STATE_BUFFERING)
                    val stream = resolver.resolve(item.mediaId)
                    if (stream == null) {
                        onResolveFailed()
                        return@launch
                    }
                    control.setMedia(
                        url = stream.url,
                        title = item.metadata.title,
                        artist = item.metadata.artist,
                        album = item.metadata.albumTitle,
                        artworkUrl = item.metadata.artworkUri,
                        mimeType = stream.mimeType,
                    )
                    if (startPositionMs > 1_000) runCatching { control.seek(startPositionMs) }
                    lastKnownPositionMs = startPositionMs
                    player.update(
                        positionMs = startPositionMs,
                        playbackState = Player.STATE_READY,
                        isPlaying = playWhenReady,
                    )
                    adapter.notifyRemoteTransition(playlistIndex)
                    adapter.notifyRemotePlaybackState(Player.STATE_READY)
                    if (playWhenReady) control.play()
                    adapter.notifyRemoteIsPlaying(playWhenReady)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "DLNA load failed: ${e.message}", e)
                    player.update(playbackState = Player.STATE_IDLE, isPlaying = false)
                    adapter.notifyRemotePlaybackState(Player.STATE_IDLE)
                    adapter.notifyRemoteIsPlaying(false)
                }
            }
    }

    private fun onResolveFailed() {
        Logger.e(TAG, "DLNA could not resolve the current stream — skipping")
        if (adapter.hasNextMediaItem()) {
            adapter.seekToNext()
        } else {
            remotePlayer?.update(playbackState = Player.STATE_ENDED, isPlaying = false)
            adapter.notifyRemotePlaybackState(Player.STATE_ENDED)
            adapter.notifyRemoteIsPlaying(false)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob =
            coroutineScope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val control = controlPoint ?: break
                    val player = remotePlayer ?: break
                    try {
                        val position = control.positionInfo()
                        val transport = control.transportState()
                        if (position.positionMs > 0 || lastKnownPositionMs == 0L) {
                            lastKnownPositionMs = position.positionMs
                        }
                        val isPlaying = transport == DlnaControlPoint.TransportState.PLAYING
                        val playbackState =
                            when (transport) {
                                DlnaControlPoint.TransportState.TRANSITIONING -> Player.STATE_BUFFERING
                                DlnaControlPoint.TransportState.STOPPED -> Player.STATE_READY
                                else -> Player.STATE_READY
                            }
                        player.update(
                            positionMs = lastKnownPositionMs,
                            durationMs = position.durationMs,
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                        )
                        adapter.notifyRemotePlaybackState(playbackState)
                        adapter.notifyRemoteIsPlaying(isPlaying)

                        val ended =
                            transport == DlnaControlPoint.TransportState.STOPPED &&
                                position.durationMs > 0 &&
                                lastKnownPositionMs >= position.durationMs - END_TOLERANCE_MS
                        stoppedPolls = if (ended) stoppedPolls + 1 else 0
                        if (stoppedPolls >= REQUIRED_STOPPED_POLLS) {
                            stoppedPolls = 0
                            if (adapter.hasNextMediaItem()) adapter.seekToNext() else adapter.notifyRemotePlaybackState(Player.STATE_ENDED)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "DLNA status poll failed: ${e.message}", e)
                    }
                }
            }
    }

    private class DlnaRemotePlaybackController(
        private val controlPoint: DlnaControlPoint,
        private val scope: CoroutineScope,
    ) : RemotePlaybackController {
        @Volatile
        private var remoteIsPlaying = false

        @Volatile
        private var remotePosition = 0L

        @Volatile
        private var remoteDuration = 0L

        @Volatile
        private var remotePlaybackState = Player.STATE_IDLE

        override val isPlaying: Boolean get() = remoteIsPlaying
        override val currentPosition: Long get() = remotePosition
        override val duration: Long get() = remoteDuration
        override val bufferedPosition: Long get() = remoteDuration
        override val contentPosition: Long get() = remotePosition
        override val playbackState: Int get() = remotePlaybackState
        override var volume: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                scope.launch { runCatching { controlPoint.setVolume(field) } }
            }
        override var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

        override fun play() {
            remoteIsPlaying = true
            remotePlaybackState = Player.STATE_READY
            scope.launch { runCatching { controlPoint.play() } }
        }

        override fun pause() {
            remoteIsPlaying = false
            scope.launch { runCatching { controlPoint.pause() } }
        }

        override fun stop() {
            remoteIsPlaying = false
            scope.launch { runCatching { controlPoint.stop() } }
        }

        override fun seekTo(positionMs: Long) {
            remotePosition = positionMs
            scope.launch { runCatching { controlPoint.seek(positionMs) } }
        }

        fun update(
            positionMs: Long = remotePosition,
            durationMs: Long = remoteDuration,
            playbackState: Int = remotePlaybackState,
            isPlaying: Boolean = remoteIsPlaying,
        ) {
            remotePosition = positionMs
            remoteDuration = durationMs
            remotePlaybackState = playbackState
            remoteIsPlaying = isPlaying
        }
    }

    companion object {
        private const val TAG = "DlnaHandoffManager"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val END_TOLERANCE_MS = 2_000L
        private const val REQUIRED_STOPPED_POLLS = 2
    }
}
