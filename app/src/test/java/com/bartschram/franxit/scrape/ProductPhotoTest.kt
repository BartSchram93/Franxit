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
}
