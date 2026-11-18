package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OffersTest {
    @Test
    fun aShopKnownToHaveItBeatsACheaperUnknownOne() {
        val offers = listOf(ShopOffer("Dreamland", 499.99), ShopOffer("Coolblue", 699.99, true), ShopOffer("Bol.com", 489.0, false))
        assertEquals("Coolblue", Offers.cheapest(offers)!!.seller)
    }

    @Test
    fun unknownStockCountsWhenNothingIsKnownToBeInStock() {
        val offers = listOf(ShopOffer("Dreamland", 499.99), ShopOffer("Bol.com", 489.0, false))
        assertEquals("Dreamland", Offers.cheapest(offers)!!.seller)
    }

    @Test
    fun outOfStockOnlyWhenThereIsNothingElse() {
        assertEquals("Bol.com", Offers.cheapest(listOf(ShopOffer("Bol.com", 489.0, false)))!!.seller)
    }
}
