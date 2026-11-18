package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleShoppingTest {

    private val json = """
        {"shopping_results": [
          {"title": "Hercules KB200B Keyboardbank", "source": "Bax-shop.be", "price": "€ 39,00", "extracted_price": 39.0,
           "product_link": "https://www.google.be/shopping/product/1", "thumbnail": "https://img/1.jpg"},
          {"title": "Hercules Stands KB200B keyboardbank zwart", "source": "Gear4music.be", "extracted_price": 41.5,
           "product_link": "https://www.google.be/shopping/product/2"},
          {"title": "Hercules KB200B Keyboardbank", "source": "Bax-shop.be", "extracted_price": 44.0},
          {"title": "Hoes geschikt voor Hercules KB200B", "source": "Bol.com", "extracted_price": 9.99},
          {"title": "Yamaha keyboardbank BC-100", "source": "Thomann", "extracted_price": 29.0},
          {"title": "Hercules KB210B Keyboardbank", "source": "Krefel.be", "extracted_price": 35.0},
          {"title": "Hercules KB 200B keyboard bench", "source": "Muziekcentrum.be", "extracted_price": 43.0},
          {"title": "Hercules KB200B Keyboardbank", "source": "Thomann.de", "extracted_price": 30.0},
          {"title": "Hercules KB200B Keyboardbank + koptelefoon", "source": "Coolblue.be", "extracted_price": 60.0},
          {"title": "Hercules KB200B Height Adjustable Foldable Keyboard Bench", "source": "Gigstore", "price": "€ 88,99",
           "extracted_price": 88.99, "alternative_price": {"price": "AED 366", "extracted_price": 366, "currency": "AED"}}
        ]}
    """.trimIndent()

    @Test
    fun keepsTheProductCheapestPerShop() {
        val found = GoogleShopping.parse(json, "Hercules KB200B keyboardbank")
        assertEquals(
            listOf("Thomann.de" to 30.0, "Bax-shop.be" to 39.0, "Gear4music.be" to 41.5, "Muziekcentrum.be" to 43.0),
            found.result.offers.map { it.seller to it.price },
        )
        // No shop page in the result: a Google Shopping search for the product at that shop.
        assertEquals(GoogleShopping.urlFor("Hercules KB200B keyboardbank Bax-shop.be"), found.links["Bax-shop.be"])
    }

    @Test
    fun productUrlsRoundTrip() {
        val url = GoogleShopping.urlFor("Hercules KB200B keyboardbank")
        assertEquals("Hercules KB200B keyboardbank", GoogleShopping.queryOf(url))
        assertNull(GoogleShopping.queryOf("https://www.vergelijk.be/x/"))
    }

    @Test(expected = java.io.IOException::class)
    fun nothingMatchingIsAnError() {
        GoogleShopping.parse(json, "Sony WF-1000XM6")
    }

    @Test
    fun onlyTheSameModelFromBelgianShops() {
        // KB210B is another model, "+ koptelefoon" a bundle, Gigstore sells in dirhams.
        // Thomann.de stays: Google Shopping Belgium only lists shops that ship to Belgium.
        val shops = GoogleShopping.parse(json, "Hercules KB200B keyboardbank").result.offers.map { it.seller }
        assertEquals(listOf("Thomann.de", "Bax-shop.be", "Gear4music.be", "Muziekcentrum.be"), shops)
    }

    @Test
    fun sellersNamedAfterAnotherCountryAreLeftOut() {
        assertEquals(false, GoogleShopping.isForeignShop("bol.com"))
        assertEquals(false, GoogleShopping.isForeignShop("Thomann.de"))
        assertEquals(true, GoogleShopping.isForeignShop("eBay - sobreruedas-spain"))
    }

    @Test
    fun trustedByNameOrByTheSiteItLinksTo() {
        val json = """
            {"shopping_results": [
              {"title": "Hercules KB200B", "source": "Bax Music", "link": "https://www.bax-shop.be/keyboardbank/hercules-kb200b", "extracted_price": 39.0},
              {"title": "Hercules KB200B", "source": "Thomann", "link": "https://www.thomann.de/be/hercules_kb200b.htm", "extracted_price": 35.0},
              {"title": "Hercules KB200B", "source": "Garantie2ans.com", "link": "https://garantie2ans.com/kb200b", "extracted_price": 19.0},
              {"title": "Hercules KB200B", "source": "Muziekwinkel XL", "link": "https://www.muziekwinkelxl.de/kb200b", "extracted_price": 36.0}
            ]}
        """.trimIndent()
        val trusted = { shop: String -> com.bartschram.franxit.data.TrustedShops.name(shop, emptySet()) }
        val found = GoogleShopping.parse(json, "Hercules KB200B", trusted)
        assertEquals(listOf("Thomann.de", "Bax-shop.be"), found.result.offers.map { it.seller })
        assertEquals("https://www.bax-shop.be/keyboardbank/hercules-kb200b", found.links["Bax-shop.be"])
    }

    @Test
    fun noMarketplacesSellersFromOtherCountriesOrOddPrices() {
        val json = """
            {"shopping_results": [
              {"title": "TCL NXTPAPER 14", "source": "Amazon.com.be", "extracted_price": 361.05},
              {"title": "TCL NXTPAPER 14", "source": "Coolblue.be", "extracted_price": 379.0},
              {"title": "TCL NXTPAPER 14", "source": "Krefel.be", "extracted_price": 389.0},
              {"title": "TCL NXTPAPER 14", "source": "U-buy.be", "extracted_price": 620.0},
              {"title": "TCL NXTPAPER 14", "source": "eBay - sobreruedas-spain", "extracted_price": 815.8},
              {"title": "TCL NXTPAPER 14", "source": "MegaStore", "extracted_price": 900.0}
            ]}
        """.trimIndent()
        val shops = GoogleShopping.parse(json, "TCL NXTPAPER 14").result.offers.map { it.seller }
        assertEquals(listOf("Amazon.com.be", "Coolblue.be", "Krefel.be"), shops)
    }

    @Test
    fun onlyNewAndNoTooGoodToBeTrue() {
        val json = """
            {"shopping_results": [
              {"title": "JBL Tour Pro 3 TWS", "source": "Voomy", "extracted_price": 37.99},
              {"title": "JBL Tour Pro 3 TWS", "source": "Used Products Rotterdam zuid", "extracted_price": 189.99},
              {"title": "JBL Tour Pro 3 TWS", "source": "Coolblue.be", "extracted_price": 205.0, "second_hand_condition": "refurbished"},
              {"title": "JBL Tour Pro 3 TWS Refurbished", "source": "Krefel.be", "extracted_price": 199.0},
              {"title": "JBL Tour Pro 3 TWS", "source": "Amazon.com.be", "extracted_price": 219.0},
              {"title": "JBL Tour Pro 3 TWS", "source": "MediaMarkt.be", "extracted_price": 219.0},
              {"title": "JBL Tour Pro 3 TWS", "source": "JBL Official", "extracted_price": 221.99}
            ]}
        """.trimIndent()
        val shops = GoogleShopping.parse(json, "JBL Tour Pro 3 TWS").result.offers.map { it.seller }
        assertEquals(listOf("Amazon.com.be", "MediaMarkt.be", "JBL Official"), shops)
    }

    @Test
    fun withoutAShopLinkItKeepsTheTokenToLookItUp() {
        val json = """
            {"shopping_results": [
              {"title": "Hercules KB200B", "source": "Gear4music.be", "extracted_price": 45.0, "immersive_product_page_token": "abc"}
            ]}
        """.trimIndent()
        assertEquals(GoogleShopping.TOKEN + "abc", GoogleShopping.parse(json, "Hercules KB200B").links["Gear4music.be"])
    }

    @Test
    fun theStoreLinkIsThatShopsOwnPage() {
        val json = """
            {"product_results": {"stores": [
              {"name": "Bax Music", "link": "https://www.bax-shop.be/kb200b"},
              {"name": "Gear4music", "link": "https://www.gear4music.be/nl/Keyboards-and-Pianos/Hercules-KB200B/1UG2"}
            ]}}
        """.trimIndent()
        val same = { a: String, b: String -> com.bartschram.franxit.data.TrustedShops.key(a) == com.bartschram.franxit.data.TrustedShops.key(b) }
        assertEquals("https://www.gear4music.be/nl/Keyboards-and-Pianos/Hercules-KB200B/1UG2", GoogleShopping.storeLinkIn(json, "Gear4music.be", same))
        assertEquals("https://www.bax-shop.be/kb200b", GoogleShopping.storeLinkIn(json, "Bax-shop.be", same))
        assertNull(GoogleShopping.storeLinkIn(json, "Coolblue.be", same))
    }

    @Test
    fun googlesProductPageAddsShopsWithTheirOwnLinks() {
        // Real results for the Hercules KB200B: the search has only Bax ("Bax Music BEFR"); Google's
        // page for it also lists Gear4music (through Google's redirect) and bol.com.
        val search = """
            {"shopping_results": [
              {"title": "Hercules Stands KB-200B keyboardbank", "source": "Bax Music BEFR", "extracted_price": 135.0,
               "immersive_product_page_token": "tok"},
              {"title": "Hercules Stands SS200BB", "source": "Omega Music", "extracted_price": 98.0, "immersive_product_page_token": "other"}
            ]}
        """.trimIndent()
        val page = """
            {"product_results": {"stores": [
              {"name": "Bax Music BEFR", "link": "https://www.bax-shop.be/keyboardbank/hercules-stands-kb-200b", "title": "Hercules Stands KB-200B keyboardbank",
               "logo": "https://encrypted-tbn0.gstatic.com/faviconV2?url=https://www.bax-shop.be&client=SHOPPING", "extracted_price": 135.0},
              {"name": "Gear4music.be", "link": "/goto?url=CAES1", "title": "Hercules KB200B Keyboard Bench",
               "logo": "https://encrypted-tbn0.gstatic.com/faviconV2?url=https://www.gear4music.be&client=SHOPPING", "extracted_price": 137.25},
              {"name": "bol.com", "link": "https://www.bol.com/be/nl/p/hercules-hckb-200b/9300000188957608/", "title": "HERCULES HCKB-200B Keyboardbank",
               "logo": "https://encrypted-tbn0.gstatic.com/faviconV2?url=https://www.bol.com&client=SHOPPING", "extracted_price": 172.46},
              {"name": "Garantie2ans.com", "link": "https://garantie2ans.com/kb200b", "extracted_price": 99.0}
            ]}}
        """.trimIndent()
        val trusted = { shop: String -> com.bartschram.franxit.data.TrustedShops.name(shop, emptySet()) }
        val searched = GoogleShopping.parse(search, "Hercules KB200B", trusted)
        assertEquals("tok", searched.token)
        val found = GoogleShopping.addStores(searched, page, "Hercules KB200B", trusted)
        assertEquals(
            listOf("Bax-shop.be" to 135.0, "Gear4music.be" to 137.25, "Bol.com" to 172.46),
            found.result.offers.map { it.seller to it.price },
        )
        assertEquals("https://www.bax-shop.be/keyboardbank/hercules-stands-kb-200b", found.links["Bax-shop.be"])
        assertEquals("https://www.google.com/goto?url=CAES1", found.links["Gear4music.be"])
    }

    @Test
    fun googleHavingNothingForAWordingIsNotFound() {
        // Real answer for "Bose QuietComfort Ultra Earbuds": the fuller wording gets results, this one none.
        val e = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            GoogleShopping.parse("""{"error": "Google hasn't returned any results for this query."}""", "Bose QuietComfort Ultra Earbuds")
        }
        assertEquals(GoogleShopping.NOT_FOUND, e.message)
    }

    @Test
    fun shopsGoogleShoppersRateWellAreTrustedToo() {
        // Real stores for the TCL NXTPAPER 14 (more_stores=true): CSMobiles and Movertix aren't on any
        // list but have good, plentiful reviews; GreaTecno has too few stars; another has no reviews.
        val search = """
            {"shopping_results": [
              {"title": "TCL NXTPAPER 14", "source": "Amazon.com.be", "extracted_price": 349.0, "immersive_product_page_token": "tok"}
            ]}
        """.trimIndent()
        val page = """
            {"product_results": {"stores": [
              {"name": "Amazon.com.be", "link": "https://www.amazon.com.be/TCL-NXTPAPER/dp/B0", "extracted_price": 349.0},
              {"name": "CSMobiles", "link": "https://csmobiles.com/nl/tcl-nxtpaper-14", "extracted_price": 348.0, "rating": 4.4, "reviews": 171},
              {"name": "Movertix", "link": "https://www.movertix.com/nl/tcl-nxtpaper-14", "extracted_price": 344.0, "rating": 4.7, "reviews": 69},
              {"name": "Galaxus", "link": "https://www.galaxus.be/nl/s1/product/tcl-nxtpaper-14", "extracted_price": 437.93, "rating": 4.8, "reviews": 41},
              {"name": "GreaTecno", "link": "https://greatecno.com/fr/tablette", "extracted_price": 407.76, "rating": 3.8, "reviews": 32},
              {"name": "Garantie2ans.com", "link": "https://garantie2ans.com/x", "extracted_price": 299.0}
            ]}}
        """.trimIndent()
        val trusted = { shop: String -> com.bartschram.franxit.data.TrustedShops.name(shop, emptySet()) }
        val found = GoogleShopping.addStores(GoogleShopping.parse(search, "NXTPAPER 14", trusted), page, "NXTPAPER 14", trusted)
        assertEquals(
            listOf("Movertix", "CSMobiles", "Amazon.com.be", "Galaxus.be"),
            found.result.offers.map { it.seller },
        )
    }

    @Test
    fun onlyDutchOrEnglishPagesAndShopsSellingHere() {
        // Real stores for the TCL NXTPAPER 14: French pages (by their address or title) are left out,
        // bol.com's Dutch page stays; a well-reviewed shop on a German address is left out too.
        val search = """
            {"shopping_results": [
              {"title": "TCL NXTPAPER 14", "source": "Amazon.com.be", "extracted_price": 349.0, "immersive_product_page_token": "tok"}
            ]}
        """.trimIndent()
        val page = """
            {"product_results": {"stores": [
              {"name": "Amazon.com.be", "link": "https://www.amazon.com.be/TCL-NXTPAPER/dp/B0", "title": "TCL NXTPAPER 14 +flip case +T-pen - Tablette Wi-Fi", "extracted_price": 349.0},
              {"name": "Amazon.com.be", "link": "https://www.amazon.com.be/-/nl/TCL-NXTPAPER/dp/B0", "title": "TCL NXTPAPER 14 + Flip Case + T-pen - Tablet", "extracted_price": 349.0},
              {"name": "CSMobiles", "link": "https://csmobiles.com/fr/Tablette-tactile/158270", "title": "TCL NxtPaper 14 8Go 256Go Wifi Gris", "extracted_price": 348.0, "rating": 4.4, "reviews": 171},
              {"name": "bol", "link": "https://www.bol.com/be/fr/p/tablette-liseuse-tcl-14", "title": "Tablet TCL NXTPAPER 14 WiFi 8GB 256GB Gris Stylus", "extracted_price": 441.13},
              {"name": "bol.com", "link": "https://www.bol.com/be/nl/p/tcl-e-reader-tablet-14", "title": "TCL NXTPAPER 14 - 14 inch - 8GB RAM 256GB", "extracted_price": 441.13},
              {"name": "Technikshop", "link": "https://www.technikshop.de/tcl-nxtpaper-14", "title": "TCL NXTPAPER 14 Tablet", "extracted_price": 359.0, "rating": 4.6, "reviews": 900}
            ]}}
        """.trimIndent()
        val trusted = { shop: String -> com.bartschram.franxit.data.TrustedShops.name(shop, emptySet()) }
        val found = GoogleShopping.addStores(GoogleShopping.parse(search, "TCL NXTPAPER 14", trusted), page, "TCL NXTPAPER 14", trusted)
        assertEquals(listOf("Amazon.com.be", "Bol.com"), found.result.offers.map { it.seller })
        assertEquals("https://www.amazon.com.be/-/nl/TCL-NXTPAPER/dp/B0", found.links["Amazon.com.be"])
        assertEquals("https://www.bol.com/be/nl/p/tcl-e-reader-tablet-14", found.links["Bol.com"])
    }

    @Test
    fun aBrandLikeLeCreusetIsNoFrenchTitle() {
        assertEquals(false, GoogleShopping.isOtherLanguage("Le Creuset braadpan 24 cm", "https://www.bol.com/be/nl/p/x"))
        assertEquals(true, GoogleShopping.isOtherLanguage("Coque pour TCL NXTPAPER 14", null))
        assertEquals(true, GoogleShopping.isOtherLanguage("TCL NXTPAPER 14", "https://www.movertix.com/fr/tcl-nxtpaper-14"))
    }

    @Test
    fun reliableShopsAreAlwaysShownWhateverTheLanguageOrPrice() {
        val search = """
            {"shopping_results": [
              {"title": "TCL NXTPAPER 14 Tablet", "source": "Coolblue", "extracted_price": 349.0, "link": "https://www.coolblue.be/nl/p/1"},
              {"title": "TCL NXTPAPER 14 Tablet", "source": "Other shop", "extracted_price": 350.0, "link": "https://www.coolblue.be/nl/p/2"}
            ]}
        """.trimIndent()
        val page = """
            {"product_results": {"stores": [
              {"name": "Krëfel", "link": "https://www.krefel.be/fr/tcl-nxtpaper-14", "title": "Tablette TCL NXTPAPER 14", "extracted_price": 1200.0},
              {"name": "MediaMarkt", "link": "https://www.mediamarkt.be/fr/product/tcl", "title": "Tablette TCL NXTPAPER 14", "extracted_price": 120.0},
              {"name": "Fnac", "link": "https://www.fnac.be/tcl-nxtpaper-14", "title": "Tablette TCL NXTPAPER 14", "extracted_price": 380.0}
            ]}}
        """.trimIndent()
        val found = GoogleShopping.addStores(GoogleShopping.parse(search, "TCL NXTPAPER 14"), page, "TCL NXTPAPER 14") { it }
        val sellers = found.result.offers.map { it.seller.lowercase() }
        assertEquals(true, sellers.any { "krëfel" in it || "krefel" in it })
        assertEquals(true, sellers.any { "mediamarkt" in it })
        assertEquals(true, sellers.any { "fnac" in it })
        assertEquals(true, GoogleShopping.isReliableShop("bol", null))
        assertEquals(false, GoogleShopping.isReliableShop("Movertix", "movertix.com"))
    }

    @Test
    fun frenchPagesAreFineWhenTheAppIsFrench() {
        val fr = "https://www.example.be/fr/tablette-tcl"
        assertEquals(true, GoogleShopping.isOtherLanguage("Tablette TCL NXTPAPER 14", fr, frenchToo = false))
        assertEquals(false, GoogleShopping.isOtherLanguage("Tablette TCL NXTPAPER 14", fr, frenchToo = true))
        assertEquals(true, GoogleShopping.isOtherLanguage("TCL Tablet für Kinder", "https://www.example.de/de/x", frenchToo = true))
    }

    @Test
    fun aStoreThatSaysSoldOutIsMarkedOutOfStock() {
        val search = """
            {"shopping_results": [{"title": "Sony PlayStation 5 Slim", "source": "Coolblue", "extracted_price": 699.0, "link": "https://www.coolblue.be/nl/p/1"}]}
        """.trimIndent()
        val page = """
            {"product_results": {"stores": [
              {"name": "Dreamland", "link": "https://www.dreamland.be/nl/ps5", "title": "Sony PlayStation 5 Slim", "extracted_price": 499.99,
               "details_and_offers": ["Uitverkocht"]},
              {"name": "Fnac", "link": "https://www.fnac.be/nl/ps5", "title": "Sony PlayStation 5 Slim", "extracted_price": 549.0}
            ]}}
        """.trimIndent()
        val found = GoogleShopping.addStores(GoogleShopping.parse(search, "Sony PlayStation 5 Slim") { it }, page, "Sony PlayStation 5 Slim") { it }
        assertEquals(false, found.result.offers.first { it.seller == "Dreamland" }.inStock)
        assertNull(found.result.offers.first { it.seller == "Fnac" }.inStock)
    }
}
