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

    /** Until when DuckDuckGo's simple pages are taken as blocking the app (an hour after they did). */
    @Volatile
    private var blockedUntil = 0L
    private const val BLOCKED_FOR_MS = 60 * 60 * 1000L

    /** DuckDuckGo's other simple results page, asked when the first answers with a check page. */
    private const val LITE = "https://lite.duckduckgo.com/lite/?q="

    /** A result's link on the page (html: class "result__a", lite: "result-link"): DuckDuckGo's redirect, the address in "uddg". */
    private val RESULT = Regex("""<a\b[^>]*(?:result__a|result-link)[^>]*>""")
    private val HREF = Regex("""\bhref=["']([^"']+)["']""")

    /** What a search gave: the shop's [page], nothing ([page] null), or a check page instead of results ([blocked]). */
    data class Lookup(val page: String?, val blocked: Boolean = false)

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
            val href = HREF.find(match.value)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return@mapNotNull null
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
    fun shopPage(query: String, shop: String, browser: ((String) -> String?)? = null): Lookup =
        search("${query.trim()} ${shop.trim()}", { isShopsSite(it, shop) }, browser)

    /**
     * A results page read: the first wanted result, or nothing, or "blocked" when it is no results page at all (a
     * check page: DuckDuckGo sometimes answers apps with one).
     */
    internal fun lookup(html: String, accept: (String) -> Boolean): Lookup {
        firstResult(html, accept)?.let { return Lookup(it) }
        val isResults = RESULT.containsMatchIn(html) || Regex("""no-results|No results|Geen resultaten""", RegexOption.IGNORE_CASE).containsMatchIn(html)
        return Lookup(null, blocked = !isResults)
    }

    /** Any link on a page to a wanted site: DuckDuckGo's full page, as a browser shows it, links results directly. */
    private val ANY_LINK = Regex("""<a\b[^>]*\bhref=["'](https?://[^"']+)["']""")

    /** Whether DuckDuckGo's full page, in a browser, shows its results (or that there are none) yet. */
    fun hasResults(html: String): Boolean =
        "result-title-a" in html || "data-testid=\"result\"" in html || Regex("""No results|Geen resultaten|no-results""", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /** The full results page, read in a browser (its links point straight at the sites). */
    internal fun lookupInBrowserPage(html: String, accept: (String) -> Boolean): Lookup {
        lookup(html, accept).page?.let { return Lookup(it) }
        val page = ANY_LINK.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }
            .filter { "duckduckgo.com" !in it.substringAfter("//").substringBefore('/') }
            .firstOrNull { url -> accept(url.substringAfter("//").substringBefore('/').lowercase().removePrefix("www.")) }
        return Lookup(page)
    }

    /**
     * [words] searched on the simple results page, on the other one when the first gave a check page, and then in a
     * [browser] (DuckDuckGo blocks an app's searches after a few, a browser's much less): the page's HTML, or null.
     */
    private fun search(words: String, accept: (String) -> Boolean, browser: ((String) -> String?)?): Lookup {
        val q = URLEncoder.encode(words.trim(), "UTF-8")
        // Blocked a moment ago: straight to the browser, without two searches that would be blocked again.
        if (browser == null || System.currentTimeMillis() >= blockedUntil) {
            val first = runCatching { lookup(Scraper.download(BASE + q, "text/html"), accept) }.getOrElse { Lookup(null, blocked = true) }
            if (!first.blocked) return first
            val lite = runCatching { lookup(Scraper.download(LITE + q, "text/html"), accept) }.getOrElse { Lookup(null, blocked = true) }
            if (!lite.blocked || browser == null) return lite
            blockedUntil = System.currentTimeMillis() + BLOCKED_FOR_MS
        }
        val html = runCatching { browser("https://duckduckgo.com/?q=$q&ia=web") }.getOrNull() ?: return Lookup(null, blocked = true)
        return lookupInBrowserPage(html, accept)
    }

    /** The product page of [query] on [site], as DuckDuckGo finds it first. */
    fun productPage(query: String, site: String, browser: ((String) -> String?)? = null): Lookup {
        val wanted = site.lowercase().removePrefix("www.")
        return search("${query.trim()} site:$site", { host -> host == wanted || host.endsWith(".$wanted") }, browser)
    }
}
