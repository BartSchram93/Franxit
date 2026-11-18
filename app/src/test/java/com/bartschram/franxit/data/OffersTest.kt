package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffersTest {
    @Test
    fun aCheaperShopWithUnknownStockBeatsADearerOneInStock() {
        val offers = listOf(ShopOffer("Dreamland", 499.99), ShopOffer("Coolblue", 699.99, true), ShopOffer("Bol.com", 489.0, false))
        assertEquals("Dreamland", Offers.cheapest(offers)!!.seller)
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
    fun theCheapestShopWinsWhateverItsStockInformation() {
        val offers = listOf(
            ShopOffer("Amazon.com.be", 165.71, true),
            ShopOffer("Excluso", 159.0),
            ShopOffer("Bol.com", 127.2),
            ShopOffer("Decathlon.be", 190.11),
        )
        assertEquals("Bol.com", Offers.cheapest(offers)!!.seller)
    }

    @Test
    fun nullWithoutOffers() {
        assertNull(Offers.cheapest(emptyList()))
    }

    @Test
    fun googleNeverHidesAShopVergelijkGives() {
        val own = listOf(ShopOffer("Amazon.com.be", 165.71, true), ShopOffer("Decathlon.be", 190.11, true))
        val extra = listOf(ShopOffer("Bol.com", 127.2), ShopOffer("Amazon.com.be", 170.0), ShopOffer("Decathlon.be", 180.0))
        val merged = Offers.merge(own, extra)
        assertEquals(listOf("Bol.com", "Amazon.com.be", "Decathlon.be"), merged.map { it.seller })
        assertEquals(listOf(127.2, 165.71, 180.0), merged.map { it.price })
    }

    @Test
    fun withoutGoogleVergelijksShopsAreAllShown() {
        val own = listOf(ShopOffer("A", 10.0, true), ShopOffer("B", 12.0, true), ShopOffer("C", 11.0, true))
        assertEquals(listOf("A", "C", "B"), Offers.merge(own, emptyList()).map { it.seller })
    }
}
