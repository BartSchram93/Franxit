package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.Scraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real cases that showed a wrong price, as the sources gave them (November 2026). */
class ShopPricesTest {

    private fun shown(offers: List<ShopOffer>) = offers.map { it.seller to it.price }

    @Test
    fun interproxTheCheapShopsOfShoparizeAndNotAmazonsOtherPack() {
        // vergelijk.be (prices without shipping) and Shoparize for Interprox Ragers Mini Conical 1.2 mm, 6 stuks.
        val own = listOf(
            ShopOffer("Farmaline.be", 5.75, true), ShopOffer("Tandenborstel.com", 4.95, true),
            ShopOffer("Plein.be", 5.2, true), ShopOffer("Amazon Marketplace", 54.29, true),
        )
        val shoparize = ShopPrices.fromShoparize(
            listOf(
                Scraper.Offer("bol.com", 8.08), Scraper.Offer("pazzox.be", 5.4), Scraper.Offer("pharmamarket.be", 5.43),
                Scraper.Offer("farmaline.be", 6.19), Scraper.Offer("plein.be", 5.2),
            ),
        )
        val offers = ShopPrices.combine(own, emptyList(), shoparize, googleProduct = false)
        assertEquals(
            listOf("Tandenborstel.com" to 4.95, "Plein.be" to 5.2, "Pazzox.be" to 5.4, "Pharmamarket.be" to 5.43, "Farmaline.be" to 6.19, "Bol.com" to 8.08),
            shown(offers),
        )
    }

    @Test
    fun dashAGoogleShoppingPriceThatRunsBehindIsOutvoted() {
        val own = listOf(ShopOffer("Bol Plaza", 38.99, true))
        val google = listOf(ShopOffer("Bol.com", 26.95))
        val shoparize = ShopPrices.fromShoparize(listOf(Scraper.Offer("bol.com", 38.99)))
        val offers = ShopPrices.combine(own, google, shoparize, googleProduct = false)
        assertEquals(1, offers.size)
        assertEquals(38.99, offers.single().price, 0.001)
    }

    @Test
    fun withoutASecondSourceGoogleShoppingComesBeforeTheProductsOwnPage() {
        val own = listOf(ShopOffer("Bol Plaza", 26.95, true))
        val google = listOf(ShopOffer("Bol.com", 38.99))
        assertEquals(38.99, ShopPrices.combine(own, google, emptyList(), googleProduct = false).single().price, 0.001)
    }

    @Test
    fun aGoogleShoppingProductKeepsGooglesShopsAndGetsShoparizesOthers() {
        // For a product tracked through Google Shopping, its own shops are Google's.
        val own = listOf(ShopOffer("Bol.com", 30.0), ShopOffer("Coolblue.be", 32.0))
        val shoparize = ShopPrices.fromShoparize(listOf(Scraper.Offer("bol.com", 25.0), Scraper.Offer("pazzox.be", 28.0)))
        val offers = ShopPrices.combine(own, emptyList(), shoparize, googleProduct = true)
        assertEquals(listOf("Pazzox.be" to 28.0, "Bol.com" to 30.0, "Coolblue.be" to 32.0), shown(offers))
    }

    @Test
    fun anExtraPriceFarBelowTheProductIsAnotherItem() {
        // A € 23 case found for a € 349 tablet.
        val own = listOf(ShopOffer("Coolblue.be", 349.0, true))
        val google = listOf(ShopOffer("Bol.com", 23.0), ShopOffer("MediaMarkt.be", 359.0))
        assertEquals(listOf("Coolblue.be" to 349.0, "MediaMarkt.be" to 359.0), shown(ShopPrices.combine(own, google, emptyList(), googleProduct = false)))
    }

    @Test
    fun aSoldOutShopStaysOutWhileAnotherHasIt() {
        val own = listOf(ShopOffer("Coolblue.be", 99.0, false), ShopOffer("Bol.com", 109.0, true))
        assertEquals(listOf("Bol.com" to 109.0), shown(ShopPrices.combine(own, emptyList(), emptyList(), googleProduct = false)))
    }

    @Test
    fun whenEveryShopIsSoldOutTheProductStillHasAPrice() {
        val own = listOf(ShopOffer("Coolblue.be", 99.0, false))
        assertEquals(listOf("Coolblue.be" to 99.0), shown(ShopPrices.combine(own, emptyList(), emptyList(), googleProduct = false)))
    }

    @Test
    fun shoparizesMarketplacesAreLeftOutAndItsShopsNamedAsUsual() {
        val offers = ShopPrices.fromShoparize(listOf(Scraper.Offer("joom.be", 19.66), Scraper.Offer("pazzox.be", 5.4)))
        assertEquals(listOf("Pazzox.be"), offers.map { it.seller })
        assertTrue(offers.all { it.inStock == null })
    }
}
