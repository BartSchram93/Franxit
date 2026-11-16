package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffersTest {

    @Test
    fun prefersCheapestInStock() {
        val offers = listOf(
            ShopOffer("Amazon Marketplace", 380.0, inStock = false),
            ShopOffer("Amazon", 386.1),
            ShopOffer("Bol.", 445.82),
        )
        assertEquals("Amazon", Offers.cheapest(offers)?.seller)
    }

    @Test
    fun nullWithoutOffers() {
        assertNull(Offers.cheapest(emptyList()))
    }
}
