package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Photos of price tags in a shop (November 2026), as the text reader gives their lines, top to bottom. */
class PriceTagTest {

    private fun lines(vararg text: String) = text.mapIndexed { i, t -> PriceTag.Line(t, top = i * 40, height = 30) }

    @Test
    fun singerTheBrandAndModelLineAndNotTheCategoryOrPrices() {
        val tag = lines(
            "SINGER - M3405", "8259771", "Naaimachine", "BLACK", "FRIDAY", "289€00", "229€00",
            "Recupelbijdrage van 1.00 Euro inbegrepen", "23 - P - 1 - 30 - G - Geldig t.e.m. 30.11.26",
        )
        assertEquals("Singer M3405", PriceTag.name(tag))
    }

    @Test
    fun rowentaAModelCodeStaysAsItIs() {
        val tag = lines(
            "ROWENTA - RO3134EA", "7880057", "SLEDESTOFZUIGER MET ZAK", "BLACK FRIDAY", "159€00", "119€00",
            "Recupelbijdrage van 0.06 Euro inbegrepen", "12 - P - 1 - G - Geldig t.e.m. 30.11.26",
        )
        assertEquals("Rowenta RO3134EA", PriceTag.name(tag))
    }

    @Test
    fun dreameAllTheWordsOfTheModel() {
        val tag = lines(
            "DREAME - AQUA 10 ULTRA ROL BLANC", "7656335", "Robot Stofzuiger", "BLACK", "FRIDAY", "699€00", "649€00",
            "Recupelbijdrage van 0.06 Euro inbegrepen", "65 - P - 1 - G - Geldig t.e.m. 30.11.26",
        )
        assertEquals("Dreame Aqua 10 Ultra Rol Blanc", PriceTag.name(tag))
    }

    @Test
    fun theArticleNumberOnTheSameLineIsLeftOut() {
        assertEquals("Singer M3405", PriceTag.name(lines("SINGER - M3405 8259771", "Naaimachine", "229€00")))
    }

    @Test
    fun theOrderOnThePhotoCountsNotTheOrderOfTheLines() {
        val tag = listOf(
            PriceTag.Line("229€00", top = 200, height = 60),
            PriceTag.Line("Naaimachine", top = 80, height = 20),
            PriceTag.Line("SINGER - M3405", top = 20, height = 30),
        )
        assertEquals("Singer M3405", PriceTag.name(tag))
    }

    @Test
    fun withoutADashALineWithAModelCode() {
        assertEquals("Philips Airfryer XXL HD9650/90", PriceTag.name(lines("Airfryer", "Philips Airfryer XXL HD9650/90", "€ 199,99")))
    }

    @Test
    fun withoutAModelCodeTheTallestText() {
        val tag = listOf(
            PriceTag.Line("Gratis levering", top = 10, height = 14),
            PriceTag.Line("Lego Botanicals Bonsai", top = 40, height = 40),
            PriceTag.Line("49,99", top = 100, height = 60),
        )
        assertEquals("Lego Botanicals Bonsai", PriceTag.name(tag))
    }

    @Test
    fun aPhotoWithoutANameGivesNone() {
        assertNull(PriceTag.name(lines("BLACK FRIDAY", "229€00", "Geldig t.e.m. 30.11.26", "8259771")))
        assertNull(PriceTag.name(emptyList()))
    }

    @Test
    fun aBrandThePhotoSplitIsJoinedAgain() {
        // As read on the phone from a Krëfel tag: "ROWEN TA - RO3134EA".
        assertEquals("Rowenta RO3134EA", PriceTag.name(lines("ROWEN TA - RO3134EA 7880057", "SLEDESTOFZUIGER MET ZAK", "119€00")))
        // Two real words stay apart.
        assertEquals("BLACK DECKER - X", PriceTag.joinBrand("BLACK DECKER - X"))
        assertEquals("LG - OLED55C4", PriceTag.joinBrand("LG - OLED55C4"))
    }
}
