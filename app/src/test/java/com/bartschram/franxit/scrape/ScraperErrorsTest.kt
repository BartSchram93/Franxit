package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ScraperErrorsTest {

    private val vtech = "https://www.vtechnl.com/product/123"

    @Test
    fun aCheckSaysWhyAPageCouldntBeReadInWords() {
        // What a tester saw: "Last check failed: Too many follow-up requests: 21".
        assertEquals("Vtechnl.com only allows browsers", Scraper.explain(ProtocolException("Too many follow-up requests: 21"), vtech))
        assertEquals("Vtechnl.com didn't answer in time", Scraper.explain(SocketTimeoutException("timeout"), vtech))
        assertEquals("No internet connection, or Vtechnl.com can't be reached", Scraper.explain(UnknownHostException("www.vtechnl.com"), vtech))
        assertEquals("Vtechnl.com had a problem, tried again at the next check", Scraper.explain(IOException("HTTP 503"), vtech))
        // The app's own messages pass unchanged.
        assertEquals("Vtechnl.com only allows browsers", Scraper.explain(IOException("Vtechnl.com only allows browsers"), vtech))
    }

    @Test
    fun aSoldOutPageIsToldApartFromOneTheAppCantRead() {
        assertTrue(Scraper.isUnavailable("""<script type="application/ld+json">{"availability":"https://schema.org/OutOfStock"}</script>"""))
        assertTrue(Scraper.isUnavailable("<div>Niet leverbaar</div>"))
        assertFalse(Scraper.isUnavailable("<div>Op voorraad, morgen in huis</div>"))
    }

    @Test
    fun cookiesSetOnTheWayGoBackToTheSameSiteOnly() {
        val jar = mutableMapOf<String, MutableMap<String, String>>()
        Scraper.keepCookies(jar, "www.vtechnl.com", listOf("session=abc; Path=/; HttpOnly", "consent=yes; Max-Age=3600", "; broken"))
        assertEquals("session=abc; consent=yes", Scraper.cookieHeader(jar, "vtechnl.com"))
        assertEquals("session=abc; consent=yes", Scraper.cookieHeader(jar, "www.vtechnl.com"))
        assertNull(Scraper.cookieHeader(jar, "www.other.be"))
        // A newer value replaces the old one.
        Scraper.keepCookies(jar, "vtechnl.com", listOf("session=def"))
        assertEquals("session=def; consent=yes", Scraper.cookieHeader(jar, "www.vtechnl.com"))
    }
}
