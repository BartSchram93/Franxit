package com.bartschram.franxit.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The link in a shared advice: franxit.be/p/?u=<the product's address>. On a phone with Franxit it
 * opens the app (an Android app link), which offers to track that product; anywhere else it opens a
 * page that says what Franxit is and where to get it.
 */
object ShareLink {

    private const val HOST = "franxit.be"
    private const val PAGE = "https://franxit.be/p/"

    /** The link for a product, or just the site when the product has no web address. */
    fun forProduct(url: String): String =
        if (isWebAddress(url)) PAGE + "?u=" + URLEncoder.encode(url, "UTF-8") else "https://franxit.be/"

    /** The product address in such a link; null for any other link, so nothing else is ever opened or tracked from it. */
    fun productUrl(link: String?): String? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != HOST || uri.path?.startsWith("/p") != true) return null
        val value = uri.rawQuery.orEmpty().split("&")
            .firstOrNull { it.startsWith("u=") }?.removePrefix("u=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: return null
        return value.takeIf { isWebAddress(it) && it.length <= 2000 }
    }

    private fun isWebAddress(s: String) = s.startsWith("https://") || s.startsWith("http://")
}
