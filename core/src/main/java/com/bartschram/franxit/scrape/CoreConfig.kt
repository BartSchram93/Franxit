package com.bartschram.franxit.scrape

/**
 * Settings the app hands this module at start (it has no BuildConfig of its own). [appToken]: the token the
 * search service asks for (X-Franxit-Token); blank when the build has none.
 */
object CoreConfig {
    @Volatile
    var appToken: String = ""

    /** A random id the app made for itself once (a UUID, nothing about you): the search service counts searches by it. */
    @Volatile
    var installId: String = ""
}
