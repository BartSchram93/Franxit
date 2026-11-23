package com.bartschram.franxit.scrape

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A shop's page for a product, found on DuckDuckGo's simple results page ("name site:store.nintendo.com"): for a shop
 * Google Shopping lists with a price but without a link (Nintendo's store), so its page can be read (sold out?) and
 * the Buy button opens it. Only read, nothing followed but the shop's own page.
 */
object DuckDuckGo {

    /** Results for Belgium, in Dutch ("kl=be-nl"): Belgian shops' pages first, wherever the phone is. */
    private const val REGION = "kl=be-nl"

    private const val BASE = "https://html.duckduckgo.com/html/?$REGION&q="

    /** Until when DuckDuckGo's simple pages are taken as blocking the app (an hour after they did). */
    @Volatile
    private var blockedUntil = 0L
    private const val BLOCKED_FOR_MS = 60 * 60 * 1000L

    /** DuckDuckGo's other simple results page, asked when the first answers with a check page. */
    private const val LITE = "https://lite.duckduckgo.com/lite/?$REGION&q="

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
    internal fun firstResult(html: String, site: String, fits: (String) -> Boolean = { true }): String? {
        val wanted = site.lowercase().removePrefix("www.")
        return firstResult(html, fits) { host -> host == wanted || host.endsWith(".$wanted") }
    }

    /** A path for another country than Belgium: "/en-gb/…", "/de-de/…", "/nl_NL/…" (and not "/nl-be/", "/fr-be/"). */
    private val COUNTRY = Regex("""^/([a-z]{2})[-_]([a-z]{2})(/|$)""", RegexOption.IGNORE_CASE)

    /** Site endings of other countries: amazon.nl, fotodevakman.nl, amazon.de … for a Belgian shopper. */
    private val FOREIGN_ENDINGS = listOf(".nl", ".de", ".fr", ".co.uk", ".uk", ".it", ".es", ".at", ".ch", ".pl", ".lu", ".se", ".dk")

    /** Whether [url] is on another country's site (amazon.nl): found by a search, it is no Belgian shop's page. */
    fun isForeignSite(url: String): Boolean {
        val host = url.substringAfter("//").substringBefore('/').substringBefore('?').lowercase()
        return FOREIGN_ENDINGS.any { host.endsWith(it) }
    }

    /** Whether [url] is a shop's page for another country (Nintendo's UK store for a Belgian shopper). */
    fun isOtherCountry(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        COUNTRY.find(path)?.let { return !it.groupValues[2].equals("be", ignoreCase = true) }
        // Country, then language: bol.com's "/nl/nl/" (the Netherlands) against "/be/nl/".
        val pair = COUNTRY_LANGUAGE.find(path) ?: return false
        return pair.groupValues[1].lowercase() != "be" && pair.groupValues[1].lowercase() in COUNTRIES
    }

    private val COUNTRY_LANGUAGE = Regex("""^/([a-z]{2})/([a-z]{2})(/|$)""", RegexOption.IGNORE_CASE)
    private val COUNTRIES = setOf("nl", "de", "fr", "uk", "gb", "lu", "at", "ch", "es", "it", "us", "pl")

    /** A shop's page for Belgium where its address says the Netherlands and a Belgian one exists: bol.com's "/nl/nl/" as "/be/nl/". */
    fun belgian(url: String): String =
        if (url.substringAfter("//").substringBefore('/').removePrefix("www.") == "bol.com") url.replaceFirst("bol.com/nl/nl/", "bol.com/be/nl/").replaceFirst("bol.com/nl/fr/", "bol.com/be/fr/") else url

    /** The first result in a results page whose site ([accept] gets its host, without "www.") is wanted. */
    internal fun firstResult(html: String, fits: (String) -> Boolean = { true }, accept: (String) -> Boolean): String? =
        RESULT.findAll(html).mapNotNull { match ->
            val href = HREF.find(match.value)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return@mapNotNull null
            val target = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?: href.takeIf { it.startsWith("https://") && "duckduckgo.com" !in it }
            // A page for another country is passed over: the next result may be Belgium's.
            // Another country, or (by its address) another colour or product: the next result may be the one.
            target?.takeIf { url -> !isOtherCountry(url) && !isForeignSite(url) && accept(url.substringAfter("//").substringBefore('/').lowercase().removePrefix("www.")) && fits(url) }
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
    fun shopPage(query: String, shop: String, browser: ((String, (String) -> Boolean) -> String?)? = null, fits: (String) -> Boolean = { true }): Lookup =
        search("${query.trim()} ${shop.trim()}", { isShopsSite(it, shop) }, browser, fits)

    /**
     * A results page read: the first wanted result, or nothing, or "blocked" when it is no results page at all (a
     * check page: DuckDuckGo sometimes answers apps with one).
     */
    internal fun lookup(html: String, fits: (String) -> Boolean = { true }, accept: (String) -> Boolean): Lookup {
        firstResult(html, fits, accept)?.let { return Lookup(it) }
        val isResults = RESULT.containsMatchIn(html) || Regex("""no-results|No results|Geen resultaten""", RegexOption.IGNORE_CASE).containsMatchIn(html)
        return Lookup(null, blocked = !isResults)
    }

    /** Any link on a page to a wanted site: DuckDuckGo's full page, as a browser shows it, links results directly. */
    private val ANY_LINK = Regex("""<a\b[^>]*\bhref=["'](https?://[^"']+)["']""")

    /** Whether DuckDuckGo's full page, in a browser, shows its results (or that there are none) yet. */
    fun hasResults(html: String): Boolean =
        "result-title-a" in html || "data-testid=\"result\"" in html || Regex("""No results|Geen resultaten|no-results""", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /** The full results page, read in a browser (its links point straight at the sites). */
    internal fun lookupInBrowserPage(html: String, fits: (String) -> Boolean = { true }, accept: (String) -> Boolean): Lookup {
        lookup(html, fits, accept).page?.let { return Lookup(it) }
        val page = ANY_LINK.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }
            .filter { url -> val host = url.substringAfter("//").substringBefore('/'); "duckduckgo.com" !in host && "bing." !in host && "microsoft." !in host && !isOtherCountry(url) && !isForeignSite(url) }
            .firstOrNull { url -> accept(url.substringAfter("//").substringBefore('/').lowercase().removePrefix("www.")) && fits(url) }
        return Lookup(page)
    }

    /**
     * [words] searched on the simple results page, on the other one when the first gave a check page, and then in a
     * [browser] (DuckDuckGo blocks an app's searches after a few, a browser's much less): the page's HTML, or null.
     */
    private fun search(words: String, accept: (String) -> Boolean, browser: ((String, (String) -> Boolean) -> String?)?, fits: (String) -> Boolean): Lookup {
        val q = URLEncoder.encode(words.trim(), "UTF-8")
        // Blocked a moment ago: straight to the browser, without two searches that would be blocked again.
        if (browser == null || System.currentTimeMillis() >= blockedUntil) {
            val first = runCatching { lookup(Scraper.download(BASE + q, "text/html"), fits, accept) }.getOrElse { Lookup(null, blocked = true) }
            if (!first.blocked) return first
            val lite = runCatching { lookup(Scraper.download(LITE + q, "text/html"), fits, accept) }.getOrElse { Lookup(null, blocked = true) }
            if (!lite.blocked || browser == null) return lite
            blockedUntil = System.currentTimeMillis() + BLOCKED_FOR_MS
        }
        runCatching { browser("https://duckduckgo.com/?q=$q&ia=web&$REGION", ::hasResults) }.getOrNull()
            ?.let { return lookupInBrowserPage(it, fits, accept) }
        // DuckDuckGo shows no results even in a browser: Bing's results page, in the browser too.
        val bing = runCatching { browser("https://www.bing.com/search?q=$q&setlang=nl&cc=BE", ::hasBingResults) }.getOrNull()
            ?: return Lookup(null, blocked = true)
        return lookupInBrowserPage(bing, fits, accept)
    }

    /** Whether Bing's results page shows its results (or that there are none) yet. */
    fun hasBingResults(html: String): Boolean = "b_algo" in html || "b_no" in html

    /** The product page of [query] on [site], as DuckDuckGo finds it first. */
    fun productPage(query: String, site: String, browser: ((String, (String) -> Boolean) -> String?)? = null, fits: (String) -> Boolean = { true }): Lookup {
        val wanted = site.lowercase().removePrefix("www.")
        return search("${query.trim()} site:$site", { host -> host == wanted || host.endsWith(".$wanted") }, browser, fits)
    }
}
