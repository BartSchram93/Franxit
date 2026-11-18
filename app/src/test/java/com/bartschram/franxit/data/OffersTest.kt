package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun prefersCheapestInStock() {
        val offers = listOf(
            ShopOffer("Amazon Marketplace", 380.0, inStock = false),
            ShopOffer("Amazon", 386.1, true),
            ShopOffer("Bol.", 445.82, true),
        )
        assertEquals("Amazon", Offers.cheapest(offers)?.seller)
    }

    @Test
    fun nullWithoutOffers() {
        assertNull(Offers.cheapest(emptyList()))
    }
}
