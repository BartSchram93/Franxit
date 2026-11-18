package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {

    private fun hit(title: String) = VergelijkSearch.Hit(title, "https://www.vergelijk.be/x/${title.hashCode()}/", null)

    // What vergelijk.be returned for "Sony WF-1000XM6".
    private val sony = listOf(
        hit("Hoes geschikt voor Sony WF-1000XM6 - Siliconen cover"),
        hit("Hoes geschikt voor Sony WF-1000XM6 - Siliconen case"),
        hit("WF-1000XM6 Zwart"),
        hit("WF-1000XM6 Zilver"),
        hit("WF-1000XM6 Draadloze Bluetooth-koptelefoon met ruisonderdrukking"),
    )

    @Test
    fun picksTheSameColourAsTheShop() {
        assertEquals("WF-1000XM6 Zwart", Matching.clearMatch(sony, "Sony WF-1000XM6 Zwart")?.title)
        assertEquals("WF-1000XM6 Zilver", Matching.clearMatch(sony, "Sony WF-1000XM6 Zilver")?.title)
    }

    @Test
    fun noPickWhenItIsNotClear() {
        // Without a colour, black and silver fit equally well.
        assertNull(Matching.clearMatch(sony, "Sony WF-1000XM6"))
    }

    @Test
    fun accessoriesGoToTheBottom() {
        val ranked = Matching.rank(sony, "Sony WF-1000XM6")
        assertEquals(listOf("WF-1000XM6 Zwart", "WF-1000XM6 Zilver"), ranked.take(2).map { it.title })
        assertEquals(true, ranked.takeLast(2).all { it.title.startsWith("Hoes") })
    }

    @Test
    fun accessoriesStayWhenYouLookForOne() {
        assertEquals(true, Matching.rank(sony, "hoes Sony WF-1000XM6").first().title.startsWith("Hoes"))
    }

    @Test
    fun aPageStillShowsTheTrackedProduct() {
        assertTrue(Matching.isStillTheSame("Hercules KB200B keyboardbank", "Hercules Stands KB200B keyboardbank zwart"))
        assertFalse(Matching.isStillTheSame("Hercules KB200B keyboardbank", "Pokémon Scarlet & Violet Booster Box"))
    }

    @Test
    fun theNumberInTheNameMustMatch() {
        assertFalse(Matching.isSameProduct("TCL Smartphone 60 Se Nxtpaper 5g", "TCL Nxtpaper 14"))
        assertTrue(Matching.isSameProduct("TCL NXTPAPER 14 (14.3\") tablet", "TCL Nxtpaper 14"))
    }

    @Test
    fun aReliableShopMaySellItWithACaseAndStylus() {
        val title = "TCL NXTPAPER 14 - 14 inch - 8GB RAM 256GB - Grijs + Flip Case & Stylus"
        assertFalse(Matching.isSameProduct(title, "TCL Nxtpaper 14"))
        assertTrue(Matching.isSameProduct(title, "TCL Nxtpaper 14", lenient = true))
        // But a case for it is still no tablet.
        assertFalse(Matching.isSameProduct("Flip Case voor TCL NXTPAPER 14", "TCL Nxtpaper 14", lenient = true))
        assertFalse(Matching.isSameProduct("Hoes geschikt voor TCL NXTPAPER 14", "TCL Nxtpaper 14", lenient = true))
    }

    @Test
    fun storageAndOtherSpecsInATitleDoNotMakeItAnotherProduct() {
        assertTrue(Matching.isSameProduct("TCL E-reader Tablet - 14.3 inch - NXTPAPER 14 - 8GB RAM 256GB - Grijs", "TCL Nxtpaper 14"))
        assertTrue(Matching.isSameProduct("TCL NXTPAPER 14 Tablet 8GB 256GB WiFi", "TCL Nxtpaper 14"))
        // A different model code still is another product.
        assertFalse(Matching.isSameProduct("Hercules KB210B keyboardbank 8GB", "Hercules KB200B"))
    }
}
