package com.bartschram.franxit.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The link in a shared advice: franxit.be/p/?v=<the product's path>. On a phone with Franxit
 * it opens the app (an Android app link), which asks whether to track that product; anywhere else it opens
 * a page that says what Franxit is and where to get it.
 *
 * The product's address is kept short and never shows the price site's name: for a product on that site
 * only its path travels (v), any other web address travels whole (u). The name is not in the link: it is
 * already in the message next to it. (Older links carried it as n; those still open.)
 */
object ShareLink {

    private const val HOST = "franxit.be"
    private const val PAGE = "https://franxit.be/p/"

    /** The price site's own address, left out of links: only the path after it is sent. */
    private const val SITE = "https://www.vergelijk.be"

    /** A product someone shared: where it is, and what it is called (blank when the link didn't say). */
    data class Shared(val url: String, val name: String)

    /** The link for a product, or just the site when the product has no web address. */
    fun forProduct(url: String): String {
        if (!isWebAddress(url)) return "https://franxit.be/"
        val where = if (url.startsWith("$SITE/")) "v=" + enc(url.removePrefix(SITE)) else "u=" + enc(url)
        return "$PAGE?$where"
    }

    /** The product in such a link; null for any other link, so nothing else is ever opened or tracked from it. */
    fun parse(link: String?): Shared? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != HOST || uri.path?.startsWith("/p") != true) return null
        val query = uri.rawQuery.orEmpty().split("&").mapNotNull { part ->
            val at = part.indexOf('=')
            if (at < 0) null else part.substring(0, at) to runCatching { URLDecoder.decode(part.substring(at + 1), "UTF-8") }.getOrNull()
        }.toMap()
        val url = when {
            query["v"] != null -> query["v"]!!.takeIf { isSitePath(it) }?.let { SITE + it }
            query["u"] != null -> query["u"]!!.takeIf { isWebAddress(it) }
            else -> null
        } ?: return null
        if (url.length > 2000) return null
        // The name is only shown: no control characters, and not longer than a name.
        val name = (query["n"] ?: "").filter { !it.isISOControl() }.trim().take(MAX_NAME)
        return Shared(url, name)
    }

    private const val MAX_NAME = 80

    /** Percent-encoded, except the "/" and ":" that make an address readable (a query may hold them as they are). */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("%2F", "/").replace("%3A", ":")

    private fun isWebAddress(s: String) = s.startsWith("https://") || s.startsWith("http://")

    /** "/p/some-product/123/": a path on the price site, never another address in disguise. */
    private fun isSitePath(s: String) = s.startsWith("/") && !s.startsWith("//") && !s.contains('\\') && !s.contains('@') && !s.contains("://")
}
