package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductPhotoTest {

    @Test
    fun aPageTitleWithTheGuessGivesTheName() {
        val json = """{"guess":"dreame aqua 10 ultra roller","entities":[{"name":"Dreame","score":0.9}],
            "titles":["Robotstofzuigers kopen? | Coolblue","Dreame Aqua 10 Ultra Roller | Coolblue - Voor 23.59u, morgen in huis"]}"""
        assertEquals("Dreame Aqua 10 Ultra Roller", ProductPhoto.name(json))
    }

    @Test
    fun withoutSuchATitleTheGuessAsTyped() {
        assertEquals("Rowenta Power Xxl RO3134EA", ProductPhoto.name("""{"guess":"rowenta power xxl ro3134ea","entities":[],"titles":[]}"""))
    }

    @Test
    fun aOneWordGuessGivesWayToWhatVisionIsSureOf() {
        val json = """{"guess":"vacuum","entities":[{"name":"Rowenta","score":0.8},{"name":"Vacuum cleaner","score":0.6}],"titles":[]}"""
        assertEquals("Rowenta", ProductPhoto.name(json))
        assertNull(ProductPhoto.name("""{"guess":"","entities":[{"name":"Floor","score":0.2}],"titles":[]}"""))
        assertNull(ProductPhoto.name("not json"))
    }

    @Test
    fun aPriceTagIsClearAPhotoOfTheProductIsNot() {
        fun lines(vararg texts: String) = texts.mapIndexed { i, t -> PriceTag.Line(t, top = i * 50, height = 30) }
        assertTrue(PriceTag.read(lines("ROWENTA - RO3134EA 7880057", "SLEDESTOFZUIGER MET ZAK", "119€00")).clear)
        // The text on a box or a machine: a name maybe, but no price tag's.
        val box = PriceTag.read(lines("Robot Stofzuiger", "Smart cleaning"))
        assertFalse(box.clear)
        assertFalse(PriceTag.read(emptyList()).clear)
    }

    @Test
    fun googleLensTitlesAgreeOnTheName() {
        // Lens's matches for a photo of three Serax Molly tealight holders, as on a tester's phone.
        val titles = listOf(
            "Theelichthouder - Molly - geglazuurd - Serax",
            "Molly Glaseret Keramik - Kontrast Interiør",
            "SERAX - Marie Michielssen - Glazed tealight holder Molly",
            "Serax Marie Michielssen Molly bloempot 04",
        )
        assertEquals("Serax Marie Michielssen Molly", ProductPhoto.common(titles))
        val json = """{"guess":"","entities":[],"titles":["Theelichthouder - Molly - geglazuurd - Serax","Serax Marie Michielssen Molly bloempot 04"]}"""
        assertEquals("Molly Serax", ProductPhoto.name(json))
        // One title: its first part. Nothing shared: nothing.
        assertEquals("Dreame Aqua 10 Ultra Roller", ProductPhoto.common(listOf("Dreame Aqua 10 Ultra Roller | Coolblue")))
        assertNull(ProductPhoto.common(listOf("Blue vase", "Red chair")))
        assertNull(ProductPhoto.common(emptyList()))
    }

    @Test
    fun theShopsInLensTitlesAreNoPartOfTheName() {
        // A tester's photo of Maltesers gave "Maltesers maxi 300 Amazon com be", which found Maxi-Cosi strollers.
        val titles = listOf("Maltesers Maxi 300 g - Amazon.com.be", "Maltesers Maxi 300g | Amazon.com.be", "Maltesers chocolade 300 g - Delhaize")
        assertEquals("Maltesers Maxi 300", ProductPhoto.common(titles, listOf("Amazon.com.be", "Amazon.com.be", "Delhaize")))
        // Also without the sources (a search service not updated yet): known shops and web words are left out.
        assertEquals("Maltesers Maxi 300", ProductPhoto.common(titles))
        assertEquals("Maltesers Chocolade 300 gr", ProductPhoto.common(listOf("Maltesers Chocolade 300 gr Delhaize")))
    }
}
