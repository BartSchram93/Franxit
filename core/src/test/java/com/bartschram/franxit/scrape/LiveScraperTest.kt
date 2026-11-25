package com.bartschram.franxit.scrape

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Hits the real sites, to notice when one changes its pages. Skipped unless
 * FRANXIT_LIVE_URL is set (one or more product urls, separated by whitespace):
 *
 *     FRANXIT_LIVE_URL="https://www.vergelijk.be/..." ./gradlew testDebugUnitTest
 *
 * The daily "Site check" workflow runs this with the urls in canary-urls.txt.
 */
class LiveScraperTest {

    @Test
    fun readsLivePages() {
        val urls = System.getenv("FRANXIT_LIVE_URL").orEmpty().split(Regex("\\s+")).filter { it.startsWith("http") }
        assumeTrue("FRANXIT_LIVE_URL not set", urls.isNotEmpty())

        val problems = mutableListOf<String>()
        fun check(what: String, block: () -> Unit) {
            runCatching(block).onFailure { problems += "$what: ${it.message ?: it.javaClass.simpleName}" }
        }

        for (url in urls) {
            check("price on $url") {
                val result = Scraper.fetch(url)
                println("${result.name}: ${result.cheapest.price} at ${result.cheapest.seller} (${result.offers.size} offers)")
                result.offers.forEach { println("  ${it.seller}: ${it.price}${if (it.link != null) " (shop link)" else ""}") }
                require(result.cheapest.price > 0) { "no price" }
                require(result.name.isNotBlank()) { "no product name" }
            }
            if (VergelijkHistory.historyUrl(url) == null) continue
            check("discounted offers on $url") {
                val offers = NuxtOffers.parse(Scraper.download(url, "text/html"))
                require(offers.isNotEmpty()) { "page app state has no offers (layout changed?)" }
                require(offers.any { it.link != null }) { "offers have no shop links" }
            }
            check("history on $url") {
                val points = VergelijkHistory.fetch(url).orEmpty()
                println("history: ${points.size} days, last ${points.lastOrNull()}")
                require(points.size > 30) { "only ${points.size} days" }
            }
        }
        if (urls.any { VergelijkHistory.historyUrl(it) != null }) {
            // A product that isn't (or no longer is) on vergelijk.be must not get another page's prices.
            check("removed vergelijk.be product") {
                val gone = runCatching { Scraper.fetch("https://www.vergelijk.be/tablet_pc/tcl/bestaat_niet_buysense_123456789/") }
                println("removed product: ${gone.exceptionOrNull()?.message ?: gone.getOrNull()}")
                require(gone.isFailure) { "read prices: ${gone.getOrNull()?.offers?.take(3)}" }
            }
            check("vergelijk.be search") {
                val hits = VergelijkSearch.search("nxtpaper")
                println("search: ${hits.size} hits, first ${hits.firstOrNull()}")
                require(hits.isNotEmpty()) { "no results" }
            }
        }
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n", prefix = "Site check failed:\n"))
    }
}
