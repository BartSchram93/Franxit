package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The names a product is searched and matched by, with the cases that went wrong. */
class SearchNamesTest {

    private fun product(name: String, custom: String? = null) =
        Product(id = 1, url = "https://www.vergelijk.be/x/", name = name, lastChecked = null, lastError = null, history = emptyList(), customName = custom)

    private val pampers = product("Pampers Baby-Dry Pants Maat 6, 138 Luierbroekjes, 14-19kg Maandbox")

    @Test
    fun pampersTheMatchNameKeepsThePackThatTheShortNameDrops() {
        assertEquals("Pampers Baby-Dry Pants Maat 6", pampers.displayName)
        assertEquals("Pampers Baby-Dry Pants Maat 6 138 Luierbroekjes", SearchNames.match(pampers))
    }

    @Test
    fun aModelCodeAfterTheCommaIsPartOfTheMatchName() {
        val watch = product("Casio G-Shock, GM-2100YM-8AER")
        assertEquals("${watch.displayName} GM-2100YM-8AER", SearchNames.match(watch))
    }

    @Test
    fun googleIsSearchedWithTheSavedWordingFirstThenTheShortTheFullAndKopen() {
        assertEquals(
            listOf(
                "Pampers pants 6", "Pampers Baby-Dry Pants Maat 6", "Pampers Baby-Dry Pants Maat 6 138 Luierbroekjes",
                "Pampers Baby-Dry Pants Maat 6 kopen",
            ),
            SearchNames.googleTerms(pampers, saved = "Pampers pants 6"),
        )
    }

    @Test
    fun specsAfterTheCommaAreNoSecondWording() {
        val tablet = product("TCL NXTPAPER 14, 36,3 cm, grijs")
        assertNull(SearchNames.fullName(tablet))
        assertEquals(listOf(tablet.displayName, "${tablet.displayName} kopen"), SearchNames.googleTerms(tablet, saved = null))
    }

    @Test
    fun shoparizeSearchesTheTypedWordsOfAGoogleShoppingProductElseTheMatchName() {
        assertEquals("pampers baby-dry", SearchNames.shoparize(pampers, query = "pampers baby-dry"))
        assertEquals("Pampers Baby-Dry Pants Maat 6 138 Luierbroekjes", SearchNames.shoparize(pampers, query = null))
    }

    @Test
    fun aCustomNameStillKeepsThePackOfTheSitesName() {
        val renamed = product("Pampers Baby-Dry Pants Maat 6, 138 Luierbroekjes, 14-19kg Maandbox", custom = "Luiers Lena")
        assertEquals("Luiers Lena 138 Luierbroekjes", SearchNames.match(renamed))
    }

    @Test
    fun patchworkIsSearchedWithItsBrand() {
        val patchwork = product("Patchwork")
        assertEquals("999 Games Patchwork", SearchNames.withBrand("Patchwork", "999 Games"))
        assertEquals("999 Games Patchwork", SearchNames.withBrand("999 Games Patchwork", "999 Games"))
        assertEquals("Sony WH-1000XM5", SearchNames.withBrand("Sony WH-1000XM5", "SONY"))
        assertEquals("Patchwork", SearchNames.withBrand("Patchwork", null))
        assertEquals(listOf("999 Games Patchwork", "Patchwork", "Patchwork kopen"), SearchNames.googleTerms(patchwork, saved = null, brand = "999 Games"))
        assertEquals("999 Games Patchwork", SearchNames.shoparize(patchwork, query = null, brand = "999 Games"))
    }
}
