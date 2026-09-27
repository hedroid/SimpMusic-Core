package com.maxrave.media3.exoplayer

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/** Small protocol-neutral surface the queue adapter needs from a remote renderer. */
internal interface RemotePlaybackController {
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val bufferedPosition: Long
    val contentPosition: Long
    val playbackState: Int
    var volume: Float
    var playbackParameters: PlaybackParameters

    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)
}

internal enum class RemotePlaybackOwner {
    GOOGLE_CAST,
    DLNA,
}

internal class Media3RemotePlaybackController(private val player: Player) : RemotePlaybackController {
    override val isPlaying: Boolean get() = player.isPlaying
    override val currentPosition: Long get() = player.currentPosition
    override val duration: Long get() = player.duration
    override val bufferedPosition: Long get() = player.bufferedPosition
    override val contentPosition: Long get() = player.contentPosition
    override val playbackState: Int get() = player.playbackState
    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }
    override var playbackParameters: PlaybackParameters
        get() = player.playbackParameters
        set(value) {
            player.playbackParameters = value
        }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
}
