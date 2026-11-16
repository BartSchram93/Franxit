package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscountsTest {

    private val offers = listOf(
        ShopOffer("Amazon", 389.98),
        ShopOffer("Bol.", 395.00),
        ShopOffer("Bol Plaza", 380.00, inStock = false),
    )

    @Test
    fun aDiscountCanMakeAnotherShopTheBestBuy() {
        val discounts = mapOf("bol." to 5.0)
        assertEquals(375.25, Discounts.yourPrice(offers[1], discounts), 0.001)
        assertEquals("Bol.", Discounts.bestForYou(offers, discounts)?.seller)
    }

    @Test
    fun withoutDiscountsTheCheapestInStockShopWins() {
        assertEquals("Amazon", Discounts.bestForYou(offers, emptyMap())?.seller)
        assertEquals(389.98, Discounts.yourPrice(offers[0], mapOf("Bol." to 5.0)), 0.001)
    }

    @Test
    fun labels() {
        assertEquals("5", Discounts.label(5.0))
        assertEquals("2,5", Discounts.label(2.5))
    }

    @Test
    fun aShopsDiscountAlsoCountsForItsPartners() {
        val discounts = mapOf("Bol." to 5.0)
        assertEquals(5.0, Discounts.percentFor("Bol Plaza", discounts)!!, 0.001)
        assertEquals(10.0, Discounts.percentFor("Amazon Marketplace", mapOf("Amazon" to 10.0))!!, 0.001)
        assertEquals(null, Discounts.percentFor("Coolblue", discounts))
    }
}
