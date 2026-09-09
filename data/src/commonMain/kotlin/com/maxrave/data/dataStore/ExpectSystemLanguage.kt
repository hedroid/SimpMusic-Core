package com.maxrave.data.dataStore

/**
 * The runtime system locale as a BCP-47 tag (e.g. "zh-CN", "zh-Hant-TW"). Needed while the
 * stored app language is empty ("follow system") — commonMain cannot read the locale itself.
 */
expect fun systemLanguageTag(): String
