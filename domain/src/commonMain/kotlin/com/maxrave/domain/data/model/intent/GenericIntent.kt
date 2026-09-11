package com.maxrave.domain.data.model.intent

import com.eygraber.uri.Uri

data class GenericIntent(
    val action: String? = null,
    val data: Uri? = null,
    val type: String? = null
) {
    companion object {
        // Launcher shortcuts (androidApp res/xml/shortcuts.xml) carry only an action, no data.
        const val ACTION_HOME = "com.maxrave.simpmusic.action.HOME"
        const val ACTION_SEARCH = "com.maxrave.simpmusic.action.SEARCH"
        const val ACTION_LIBRARY = "com.maxrave.simpmusic.action.LIBRARY"

        fun isShortcutAction(action: String?): Boolean =
            action == ACTION_HOME || action == ACTION_SEARCH || action == ACTION_LIBRARY
    }
}