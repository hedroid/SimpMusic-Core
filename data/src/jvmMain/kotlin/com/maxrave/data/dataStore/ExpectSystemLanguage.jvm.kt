package com.maxrave.data.dataStore

import java.util.Locale

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()
