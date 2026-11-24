package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCheckTest {

    @Test
    fun recognisesCheckPages() {
        assertEquals(true, Scraper.isBrowserCheck("<html><head><title>Just a moment...</title></head><body></body></html>"))
        // What beslist.be sent: a tiny page without a title.
        assertEquals(true, Scraper.isBrowserCheck("<html><head><script>/* 2 KB of script */</script></head></html>"))
    }

    @Test
    fun aRealPageIsNotACheck() {
        val page = "<html><head><title>Hercules KB200B keyboardbank</title></head><body>" + "x".repeat(10_000) + "</body></html>"
        assertEquals(false, Scraper.isBrowserCheck(page))
    }

    @Test
    fun aFullShopPageWithACaptchaOrCloudflareScriptIsNotACheck() {
        val page = "<html><head><title>Fender Player Stratocaster | Music Vanderheyden</title>" +
            "<script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script></head><body>" +
            "x".repeat(80_000) + "<div class=\"g-recaptcha\">captcha</div><noscript>Please enable JavaScript</noscript></body></html>"
        assertEquals(false, Scraper.isBrowserCheck(page))
        // A real check page stays one: small, with its text.
        assertEquals(true, Scraper.isBrowserCheck("<html><head><title>Attention Required</title></head><body>" + "y".repeat(8_000) + "Please complete the captcha</body></html>"))
    }
}
