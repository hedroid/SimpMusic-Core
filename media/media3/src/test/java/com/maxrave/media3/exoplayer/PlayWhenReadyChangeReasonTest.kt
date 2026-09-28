package com.maxrave.media3.exoplayer

import android.media.AudioManager
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayWhenReadyChangeReasonTest {
    @Test
    fun knownReasonsHaveDiagnosticNames() {
        assertEquals(
            "audio-focus-loss",
            playWhenReadyChangeReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
        assertEquals(
            "audio-becoming-noisy",
            playWhenReadyChangeReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
        )
        assertEquals(
            "remote",
            playWhenReadyChangeReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE),
        )
    }

    @Test
    fun unknownReasonRemainsExplicit() {
        assertEquals("unknown", playWhenReadyChangeReasonName(Int.MAX_VALUE))
    }

    @Test
    fun audioFocusChangesHaveDiagnosticNames() {
        assertEquals("gain", audioFocusChangeName(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals("loss", audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(
            "loss-transient",
            audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            "loss-transient-can-duck",
            audioFocusChangeName(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }
}
