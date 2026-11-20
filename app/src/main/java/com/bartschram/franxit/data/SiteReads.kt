package com.bartschram.franxit.data

import java.net.URI

/**
 * When the app may read vergelijk.be. It does so as little as possible: once, when a product is added
 * (its shops and long-term price history). Every check after that only asks Google Shopping.
 */
object SiteReads {
    /** A product's first checks that may read the site's price history (a failed attempt is retried, twice at most). */
    const val HISTORY_ATTEMPTS = 3

    fun isVergelijk(url: String): Boolean =
        runCatching { URI(url).host.orEmpty().removePrefix("www.").endsWith("vergelijk.be") }.getOrDefault(false)

    /** A vergelijk.be product that has been checked before: its daily check leaves the site alone. */
    fun detached(url: String, isSearch: Boolean, checks: Int): Boolean = !isSearch && checks > 0 && isVergelijk(url)

    /** Only a product's first checks read the site's price history, and only while it has none. */
    fun readsHistory(url: String, checks: Int, hasHistory: Boolean): Boolean =
        !hasHistory && checks < HISTORY_ATTEMPTS && isVergelijk(url)
}
