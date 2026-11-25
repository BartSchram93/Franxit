package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {

    @Test
    fun aPriceSiteProductTravelsAsAReadablePathWithoutItsName() {
        val url = "https://www.vergelijk.be/p/sony-playstation-5-slim-digital-edition/123/"
        val link = ShareLink.forProduct(url)
        assertEquals("https://franxit.be/p/?v=/p/sony-playstation-5-slim-digital-edition/123/", link)
        assertEquals(ShareLink.Shared(url, ""), ShareLink.parse(link))
    }

    @Test
    fun linksWithTheNameOrEncodedSlashesStillOpen() {
        val url = "https://www.vergelijk.be/p/sony-ps5/123/"
        assertEquals(ShareLink.Shared(url, "Sony PS5"), ShareLink.parse("https://franxit.be/p/?v=%2Fp%2Fsony-ps5%2F123%2F&n=Sony+PS5"))
    }

    @Test
    fun theLinkNeverNamesThePriceSite() {
        val link = ShareLink.forProduct("https://www.vergelijk.be/p/sony-ps5/123/?x=1&y=a b")
        assertFalse(link, link.contains("vergelijk", ignoreCase = true))
        assertEquals("https://www.vergelijk.be/p/sony-ps5/123/?x=1&y=a b", ShareLink.parse(link)!!.url)
    }

    @Test
    fun aShopProductTravelsWhole() {
        val url = "https://www.coolblue.be/nl/product/941836/playstation-5-slim-digital-edition.html"
        assertEquals(url, ShareLink.parse(ShareLink.forProduct(url))!!.url)
    }

    @Test
    fun aGoogleShoppingProductSurvivesToo() {
        val url = "https://www.google.be/search?tbm=shop&q=TCL+NXTPAPER+14"
        assertEquals(url, ShareLink.parse(ShareLink.forProduct(url))!!.url)
    }

    @Test
    fun theNameIsOptionalAndKeptShort() {
        assertEquals("", ShareLink.parse(ShareLink.forProduct("https://a.example/p"))!!.name)
        assertEquals(80, ShareLink.parse("https://franxit.be/p/?u=https%3A%2F%2Fa.example%2Fp&n=" + "x".repeat(300))!!.name.length)
    }

    @Test
    fun aProductWithoutAWebAddressSharesJustTheSite() {
        assertEquals("https://franxit.be/", ShareLink.forProduct("serpapi-token:abc"))
    }

    @Test
    fun onlyFranxitLinksAreAccepted() {
        assertNull(ShareLink.parse("https://evil.example/p/?u=https%3A%2F%2Fa.example"))
        assertNull(ShareLink.parse("http://franxit.be/p/?u=https%3A%2F%2Fa.example"))
        assertNull(ShareLink.parse("https://franxit.be/other?u=https%3A%2F%2Fa.example"))
        assertNull(ShareLink.parse("https://franxit.be/p/"))
        assertNull(ShareLink.parse(null))
    }

    @Test
    fun onlyWebAddressesAreAcceptedAsAProduct() {
        assertNull(ShareLink.parse("https://franxit.be/p/?u=file%3A%2F%2F%2Fdata%2Fsecret"))
        assertNull(ShareLink.parse("https://franxit.be/p/?u=javascript%3Aalert(1)"))
        assertNull(ShareLink.parse("https://franxit.be/p/?u=" + "https%3A%2F%2Fa.example%2F" + "x".repeat(2100)))
    }

    @Test
    fun aPathCannotPointToAnotherSite() {
        // The price site's address is put in front of the path, so the path must stay a path.
        assertNull(ShareLink.parse("https://franxit.be/p/?v=%2F%2Fevil.example%2Fx"))
        assertNull(ShareLink.parse("https://franxit.be/p/?v=%2F%40evil.example"))
        assertNull(ShareLink.parse("https://franxit.be/p/?v=https%3A%2F%2Fevil.example"))
        assertNull(ShareLink.parse("https://franxit.be/p/?v=%5Cevil.example"))
        assertNull(ShareLink.parse("https://franxit.be/p/?v=//evil.example/x"))
        assertNull(ShareLink.parse("https://franxit.be/p/?v=/@evil.example"))
    }

    @Test
    fun theNameHasNoControlCharacters() {
        assertEquals("AB", ShareLink.parse("https://franxit.be/p/?u=https%3A%2F%2Fa.example&n=A%00%0AB")!!.name)
    }
}
