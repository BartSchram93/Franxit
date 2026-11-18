package com.bartschram.franxit.data

import android.app.Application

/**
 * The Google Play build has no usage statistics at all: this stand-in does nothing and the Aptabase
 * library is not part of that build. (The copy for the APK on the download page is in src/web.)
 */
object Analytics {
    @Suppress("UNUSED_PARAMETER")
    fun init(app: Application) {}

    @Suppress("UNUSED_PARAMETER")
    fun track(name: String, props: Map<String, String> = emptyMap()) {}
}
