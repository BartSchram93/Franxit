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
    fun aScreenSizeInTheNameIsNotRequiredInTheTitle() {
        assertTrue(Matching.isSameProduct("TCL NXTPAPER 14", "TCL NXTPAPER 14 14,3\""))
        assertTrue(Matching.isSameProduct("TCL NXTPAPER 14", "TCL NXTPAPER 14 14.3 inch"))
        // Other models still differ by their number.
        assertFalse(Matching.isSameProduct("TCL Smartphone 60 Se Nxtpaper 5g", "TCL NXTPAPER 14 14,3\""))
    }

    @Test
    fun aReliableShopMaySellItWithACaseAndStylus() {
        val title = "TCL NXTPAPER 14 - 14 inch - 8GB RAM 256GB - Grijs + Flip Case & Stylus"
        assertFalse(Matching.isSameProduct(title, "TCL Nxtpaper 14"))
        assertTrue(Matching.isSameProduct(title, "TCL Nxtpaper 14", lenient = true))
        // The title may name a case; a loose case is kept out by its price (see ExtraShopPrices), not by its title.
        assertTrue(Matching.isSameProduct("Flip Case voor TCL NXTPAPER 14", "TCL Nxtpaper 14", lenient = true))
        assertFalse(com.bartschram.franxit.data.ExtraShopPrices.plausible(23.49, 349.0, reliable = true))
    }

    @Test
    fun storageAndOtherSpecsInATitleDoNotMakeItAnotherProduct() {
        assertTrue(Matching.isSameProduct("TCL E-reader Tablet - 14.3 inch - NXTPAPER 14 - 8GB RAM 256GB - Grijs", "TCL Nxtpaper 14"))
        assertTrue(Matching.isSameProduct("TCL NXTPAPER 14 Tablet 8GB 256GB WiFi", "TCL Nxtpaper 14"))
        // A different model code still is another product.
        assertFalse(Matching.isSameProduct("Hercules KB210B keyboardbank 8GB", "Hercules KB200B"))
    }

    @Test
    fun anotherColourIsAnotherProduct() {
        val wanted = "Casio G-shock G-steel Heren Horloge Zilverkleurig GM-2100YM-8AER"
        assertFalse(Matching.isSameProduct("Casio G-Shock G-Steel Heren Horloge Zwart", wanted))
        assertTrue(Matching.isSameProduct("Casio G-Shock GM-2100YM-8AER Heren Horloge Zilverkleurig", wanted))
    }

    @Test
    fun aShopGoogleGroupsMustCarryTheModelCodeAndTheColour() {
        val wanted = "Casio G-shock G-steel Heren Horloge Zilverkleurig GM-2100YM-8AER"
        assertTrue(Matching.isOtherVariant("Casio G-Shock GM-2100-1AER zwart", wanted))
        assertTrue(Matching.isOtherVariant("Casio G-Shock Heren Horloge Zwart", wanted))
        assertTrue(Matching.isOtherVariant("Casio G-Shock Heren Horloge", wanted))
        assertFalse(Matching.isOtherVariant("Casio G-SHOCK GM-2100YM-8AER", wanted))
        // Without a model code in the name, titles are not held to one.
        assertFalse(Matching.isOtherVariant("TCL e-reader tablet 14.3 inch NXTPAPER 14", "TCL Nxtpaper 14"))
    }

    @Test
    fun aSinglePackIsNotTheMultipack() {
        val wanted = "Dash 2x Waspoeder Alpenfris - 2 x 5kg - 200 wasbeurten - Voordeelverpakking -2 stuks"
        // The 5,5 kg pack bol.com lists for less: another product, also when bundles are allowed.
        assertEquals(false, Matching.isSameProduct("Dash XXL PACK Alpenfris Universeel Waspoeder - 5,5 kg (110 Wasbeurten )", wanted, lenient = true))
        assertEquals(true, Matching.isSameProduct("2x Dash Waspoeder Alpenfris - 2 X 5kg - 200 Wasbeurten", wanted, lenient = true))
    }

    @Test
    fun aMultipackIsNotTheSinglePack() {
        assertEquals(false, Matching.isSameProduct("2x Dash Waspoeder Alpenfris 5kg", "Dash Waspoeder Alpenfris 5kg", lenient = true))
        assertEquals(true, Matching.isSameProduct("Dash Waspoeder Alpenfris 5kg - 100 wasbeurten", "Dash Waspoeder Alpenfris 5kg", lenient = true))
    }

    @Test
    fun anotherNumberOfPiecesIsAnotherProduct() {
        assertEquals(false, Matching.isSameProduct("Gladi Tiles 60 Stuks - Magnetisch Speelgoed", "Gladi Tiles 100 stuks", lenient = true))
        assertEquals(true, Matching.isSameProduct("Gladi Tiles 100 Stuks - Magnetisch Speelgoed", "Gladi Tiles 100 stuks", lenient = true))
        // Another number of pieces, even when the wanted number appears elsewhere in the title.
        assertEquals(false, Matching.isSameProduct("Gladi Tiles 60 Stuks - 100% veilig", "Gladi Tiles 100 stuks", lenient = true))
        assertEquals(false, Matching.isSameProduct("Dash Waspoeder Alpenfris - 110 Wasbeurten", "Dash Waspoeder Alpenfris - 200 wasbeurten", lenient = true))
    }

    @Test
    fun aSetOfSeveralIsAMultipack() {
        assertEquals(setOf(6), Matching.multipliers("Interprox Mini Conical - Rood - PHD 1, 2 Mm - 6 Ragers - Set Van 6"))
        assertEquals(setOf(3), Matching.multipliers("Lenor 3-pack"))
        assertEquals(false, Matching.isSameProduct("Interprox Mini Conical - Rood - PHD 1, 2 Mm - 6 Ragers - Set Van 6 - V", "Interprox Ragers Mini Conical Rood PHD 1.2 mm - 6 stuks", lenient = true))
        assertEquals(true, Matching.isSameProduct("Interprox Ragers Mini Conical Rood PHD 1.2 6 Stuks", "Interprox Ragers Mini Conical Rood PHD 1.2 mm - 6 stuks"))
    }

    @Test
    fun anotherPackGoesDownTheList() {
        val hits = listOf(
            VergelijkSearch.Hit("Magnetic Tiles - Magnetisch Speelgoed - 60 Stuks", "https://www.vergelijk.be/a_1000001/", null),
            VergelijkSearch.Hit("Magnetic Tiles - Magnetisch Speelgoed - 100 Stuks", "https://www.vergelijk.be/b_1000002/", null),
        )
        assertEquals("https://www.vergelijk.be/b_1000002/", Matching.rank(hits, "Gladi Magnetic Tiles 100 stuks Magnetisch Speelgoed").first().url)
    }

    @Test
    fun theShortNameKeepsThePackOfTheFullName() {
        val full = "Pampers Baby-Dry Pants Maat 6, 138 Luierbroekjes, 14-19kg Maandbox"
        assertEquals("Pampers Baby-Dry Pants Maat 6 138 Luierbroekjes", Matching.withPack("Pampers Baby-Dry Pants Maat 6", full))
        // Nothing to add when the short name has it already, or the full name names no pack.
        assertEquals("Dash 2x Waspoeder", Matching.withPack("Dash 2x Waspoeder", "Dash 2x Waspoeder, Alpenfris"))
        assertEquals("Sony WH-1000XM6", Matching.withPack("Sony WH-1000XM6", "Sony WH-1000XM6, Zwart"))
    }

    @Test
    fun anotherNumberOfDiaperPantsIsAnotherPack() {
        val wanted = Matching.withPack("Pampers Baby-Dry Pants Maat 6", "Pampers Baby-Dry Pants Maat 6, 138 Luierbroekjes, 14-19kg Maandbox")
        assertEquals(false, Matching.isSameProduct("Pampers Baby-Dry Pants Maat 6 - 160 Luierbroekjes - Maandbox", wanted, lenient = true))
        assertEquals(true, Matching.isSameProduct("Pampers Baby-Dry Pants Maat 6 - 138 luierbroekjes - 14-19 kg", wanted, lenient = true))
    }
}
