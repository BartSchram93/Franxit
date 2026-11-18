package com.bartschram.franxit.data

import android.app.Application
import android.content.pm.ApplicationInfo
import com.aptabase.Aptabase

/**
 * Anonymous usage events, sent to Aptabase (EU): which parts of the app are used, so the maker knows
 * if the app is used at all. An event is only its name, how a product was added (search, Google Shopping or
 * a shared link) and what Aptabase's library adds itself: the app and Android version, the phone's
 * language and model, and a random session number kept in memory. Never a product, a name, a price, a
 * search, a link, a key, an account or a device number.
 *
 * Nothing is sent from debug builds (tests and screenshots on an emulator must not count), and a
 * failing send never reaches the person using the app.
 */
object Analytics {

    /** Aptabase's app key: it identifies the app, it is not a secret (it is in every copy of the app). */
    private const val APP_KEY = "A-EU-1741167035"

    @Volatile private var ready = false

    fun init(app: Application) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        ready = runCatching { Aptabase.instance.initialize(app, APP_KEY) }.isSuccess
    }

    /** One event. [props] holds text or whole numbers only; no personal or product data. */
    fun track(name: String, props: Map<String, String> = emptyMap()) {
        if (!ready) return
        runCatching { Aptabase.instance.trackEvent(name, props) }
    }
}
