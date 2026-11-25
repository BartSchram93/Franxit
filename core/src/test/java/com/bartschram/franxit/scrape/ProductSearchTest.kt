package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductSearchTest {

    private val answer = """{"products":[
        {"title":"PlayStation 5 Slim Disc Edition","description":"Sony PlayStation 5 console","url":"https://www.coolblue.be/nl/product/950000/sony-playstation-5-slim.html","imageUrl":"https://image.coolblue.be/ps5.jpg","price":null,"currency":null,"retailer":"coolblue.be","availability":null,"score":0.81},
        {"title":"PS5 Pro","url":"https://www.bol.com/be/nl/p/playstation-5-pro/9300000190000000/","imageUrl":"http://insecure.example/p.jpg"},
        {"title":"Duplicate","url":"https://www.bol.com/be/nl/p/playstation-5-pro/9300000190000000/"},
        {"title":"","url":"https://www.fnac.be/a1"},
        {"title":"No address"},
        {"title":"Consoles","url":"https://www.mediamarkt.be/nl/category/consoles-123.html"},
        {"title":"Zoeken naar ps5","url":"https://www.krefel.be/nl/search?q=ps5"},
        {"title":"Fnac PS5","url":"https://www.fnac.be/gaming/playstation5/q"},
        {"title":"Coolblue","url":"https://www.coolblue.be/"},
        {"title":"DualSense controller","url":"https://www.nedgame.nl/playstation-5/dualsense-wireless-controller/123/"}
    ]}"""

    @Test
    fun theServicesAnswerIsReadWithOnlyWhatItGives() {
        val products = ProductSearch.parse(answer).products
        val first = products.first()
        assertEquals("PlayStation 5 Slim Disc Edition", first.title)
        assertEquals("coolblue.be", first.retailer)
        assertEquals(0.81, first.score!!, 0.0001)
        // Nothing made up: no price, currency or stock where the answer has none.
        assertNull(first.price)
        assertNull(first.currency)
        assertNull(first.availability)
        // A photo only from a secure address; one page once; no product without a title or an address.
        assertNull(products[1].imageUrl)
        assertEquals(1, products.count { it.url.contains("9300000190000000") })
        assertEquals(listOf("PlayStation 5 Slim Disc Edition", "PS5 Pro", "Consoles", "Zoeken naar ps5", "Fnac PS5", "Coolblue", "DualSense controller"), products.map { it.title })
    }

    @Test
    fun onlyProductPagesAreKept() {
        val pages = ProductSearch.productPages(ProductSearch.parse(answer).products)
        // Not a category, a search, Fnac's theme page or a home page: products only (an accessory is a product too).
        assertEquals(listOf("PlayStation 5 Slim Disc Edition", "PS5 Pro", "DualSense controller"), pages.map { it.title })
    }

    @Test
    fun aQueryIsCheckedBeforeItIsSent() {
        assertNull(ProductSearch.clean(" "))
        assertNull(ProductSearch.clean("a"))
        assertNull(ProductSearch.clean("x".repeat(151)))
        assertEquals("cheap wireless headphones", ProductSearch.clean("  cheap   wireless headphones "))
        assertEquals("""{"query":"PlayStation 5","limit":20}""", ProductSearch.Request("PlayStation 5").toJson())
    }

    @Test
    fun suggestionsAreWordsOtherThanWhatYouTyped() {
        val json = """{"suggestions":["playstation 5","PlayStation 5","play","playstation 5 pro"," "]}"""
        assertEquals(listOf("playstation 5", "playstation 5 pro"), ProductSearch.parseSuggestions(json, "play"))
        assertEquals(emptyList<String>(), ProductSearch.parseSuggestions("{}", "play"))
    }
}
