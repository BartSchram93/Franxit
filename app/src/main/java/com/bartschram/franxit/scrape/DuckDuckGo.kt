package com.bartschram.franxit.scrape

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A shop's page for a product, found on DuckDuckGo's simple results page ("name site:store.nintendo.com"): for a shop
 * Google Shopping lists with a price but without a link (Nintendo's store), so its page can be read (sold out?) and
 * the Buy button opens it. Only read, nothing followed but the shop's own page.
 */
object DuckDuckGo {

    private const val BASE = "https://html.duckduckgo.com/html/?q="

    /** A result's link on the page: DuckDuckGo's redirect, with the address itself in "uddg". */
    private val RESULT = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"""")

    fun searchUrl(query: String, site: String) = BASE + URLEncoder.encode("${query.trim()} site:$site", "UTF-8")

    /**
     * The first result on [site] in a results page; null when there is none (nothing found, or a check page instead
     * of results). Ads (whose link is no "uddg" redirect) are passed over.
     */
    internal fun firstResult(html: String, site: String): String? {
        val wanted = site.lowercase().removePrefix("www.")
        return RESULT.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1].replace("&amp;", "&")
            val target = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?: href.takeIf { it.startsWith("https://") && "duckduckgo.com" !in it }
            target?.takeIf { url ->
                val host = url.substringAfter("//").substringBefore('/').lowercase().removePrefix("www.")
                host == wanted || host.endsWith(".$wanted")
            }
        }.firstOrNull()
    }

    /** The product page of [query] on [site], as DuckDuckGo finds it first; null when it finds none. */
    fun productPage(query: String, site: String): String? =
        firstResult(Scraper.download(searchUrl(query, site), "text/html"), site)
}
