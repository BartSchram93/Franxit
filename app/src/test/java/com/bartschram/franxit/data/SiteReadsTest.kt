package com.bartschram.franxit.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteReadsTest {
    private val product = "https://www.vergelijk.be/koptelefoons/sony/wh-1000xm5/"

    @Test fun `a new product is read from the site`() {
        assertFalse(SiteReads.detached(product, isSearch = false, checks = 0))
    }

    @Test fun `once checked, the site is left alone`() {
        assertTrue(SiteReads.detached(product, isSearch = false, checks = 1))
        assertTrue(SiteReads.detached(product, isSearch = false, checks = 400))
    }

    @Test fun `a Google Shopping or other shop product is never about the site`() {
        assertFalse(SiteReads.detached(product, isSearch = true, checks = 5))
        assertFalse(SiteReads.detached("https://www.coolblue.be/nl/product/1", isSearch = false, checks = 5))
    }

    @Test fun `the price history is read at the start only`() {
        assertTrue(SiteReads.readsHistory(product, checks = 0, hasHistory = false))
        assertTrue(SiteReads.readsHistory(product, checks = 2, hasHistory = false))
        assertFalse(SiteReads.readsHistory(product, checks = 3, hasHistory = false))
        assertFalse(SiteReads.readsHistory(product, checks = 0, hasHistory = true))
        assertFalse(SiteReads.readsHistory("https://www.coolblue.be/nl/product/1", checks = 0, hasHistory = false))
    }
}
