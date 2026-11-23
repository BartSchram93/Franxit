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
        return firstResult(html) { host -> host == wanted || host.endsWith(".$wanted") }
    }

    /** The first result in a results page whose site ([accept] gets its host, without "www.") is wanted. */
    internal fun firstResult(html: String, accept: (String) -> Boolean): String? =
        RESULT.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1].replace("&amp;", "&")
            val target = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?: href.takeIf { it.startsWith("https://") && "duckduckgo.com" !in it }
            target?.takeIf { url -> accept(url.substringAfter("//").substringBefore('/').lowercase().removePrefix("www.")) }
        }.firstOrNull()

    /**
     * Whether a site ("kamera-express.be") is the shop called [shop] ("Kamera Express"): its name without the
     * domain ending, letters and digits only, is the shop's name or holds it ("fotodevakman.be" for "Fotodevakman").
     */
    internal fun isShopsSite(host: String, shop: String): Boolean {
        val site = host.lowercase().removePrefix("www.").substringBeforeLast('.').substringAfterLast('.').filter { it.isLetterOrDigit() }
        val name = shop.lowercase().filter { it.isLetterOrDigit() }
        return name.length >= 4 && site.isNotEmpty() && (site == name || site.contains(name) || name.contains(site) && site.length >= 5)
    }

    /** The product page of [query] at the shop called [shop] (no site known): the first result on that shop's site. */
    fun shopPage(query: String, shop: String): String? =
        firstResult(Scraper.download(BASE + URLEncoder.encode("${query.trim()} ${shop.trim()}", "UTF-8"), "text/html")) { isShopsSite(it, shop) }

    /** The product page of [query] on [site], as DuckDuckGo finds it first; null when it finds none. */
    fun productPage(query: String, site: String): String? =
        firstResult(Scraper.download(searchUrl(query, site), "text/html"), site)
}
