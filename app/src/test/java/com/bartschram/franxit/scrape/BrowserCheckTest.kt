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
}
