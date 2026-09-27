package com.maxrave.domain.data.player

/**
 * Protocol-neutral remote playback state wrapper (Google Cast or DLNA; no SDK dependencies).
 */
data class GenericCastState(
    val isRemote: Boolean = false,
    val deviceName: String? = null,
) {
    companion object {
        val NOT_CASTING = GenericCastState()
    }
}
