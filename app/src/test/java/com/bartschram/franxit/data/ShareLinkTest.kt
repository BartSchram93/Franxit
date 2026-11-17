package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {

    @Test
    fun aProductSurvivesTheRoundTrip() {
        val url = "https://www.vergelijk.be/p/sony-playstation-5-slim-digital-edition/123?x=1&y=a b"
        assertEquals(url, ShareLink.productUrl(ShareLink.forProduct(url)))
    }

    @Test
    fun aGoogleShoppingProductSurvivesToo() {
        val url = "https://www.google.be/search?tbm=shop&q=TCL+NXTPAPER+14"
        assertEquals(url, ShareLink.productUrl(ShareLink.forProduct(url)))
    }

    @Test
    fun aProductWithoutAWebAddressSharesJustTheSite() {
        assertEquals("https://franxit.be/", ShareLink.forProduct("serpapi-token:abc"))
    }

    @Test
    fun onlyFranxitLinksAreAccepted() {
        assertNull(ShareLink.productUrl("https://evil.example/p/?u=https%3A%2F%2Fa.example"))
        assertNull(ShareLink.productUrl("http://franxit.be/p/?u=https%3A%2F%2Fa.example"))
        assertNull(ShareLink.productUrl("https://franxit.be/other?u=https%3A%2F%2Fa.example"))
        assertNull(ShareLink.productUrl("https://franxit.be/p/"))
        assertNull(ShareLink.productUrl(null))
    }

    @Test
    fun onlyWebAddressesAreAcceptedAsAProduct() {
        assertNull(ShareLink.productUrl("https://franxit.be/p/?u=file%3A%2F%2F%2Fdata%2Fsecret"))
        assertNull(ShareLink.productUrl("https://franxit.be/p/?u=javascript%3Aalert(1)"))
        assertNull(ShareLink.productUrl("https://franxit.be/p/?u=" + "https%3A%2F%2Fa.example%2F" + "x".repeat(2100)))
    }
}
