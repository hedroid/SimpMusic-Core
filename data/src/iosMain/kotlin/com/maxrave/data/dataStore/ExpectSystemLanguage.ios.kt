package com.maxrave.data.dataStore

import platform.Foundation.NSLocale

actual fun systemLanguageTag(): String {
    // Preferred languages carry script/region ("zh-Hans-CN"), which the target-code
    // collapse needs to tell Simplified from Traditional.
    val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
    if (!preferred.isNullOrEmpty()) return preferred
    return NSLocale.currentLocale.languageCode
}
