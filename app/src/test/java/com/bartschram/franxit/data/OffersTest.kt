package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffersTest {
    @Test
    fun aShopTheFirstSourceHasIsLeftOutOfTheSecond() {
        val google = listOf(ShopOffer("Coolblue.be", 100.0), ShopOffer("MediaMarkt.be", 120.0))
        val shoparize = listOf(ShopOffer("coolblue.be", 90.0), ShopOffer("mediamarkt.be", 110.0), ShopOffer("bol.com", 130.0))
        // Coolblue is cheaper at Shoparize, but Google comes first: only the shop Google lacks stays.
        assertEquals(listOf("bol.com"), Offers.withoutShopsOf(shoparize, google).map { it.seller })
    }

    @Test
    fun withoutAFirstSourceTheSecondStaysAsItIs() {
        val shoparize = listOf(ShopOffer("bol.com", 130.0))
        assertEquals(shoparize, Offers.withoutShopsOf(shoparize, emptyList()))
    }

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
        // For a shop both list, the extra source's price counts, the dearer one too.
        assertEquals(listOf(127.2, 170.0, 180.0), merged.map { it.price })
    }

    @Test
    fun anOldLowPriceOnTheComparisonPageDoesNotBeatTheShopsCurrentPrice() {
        val own = listOf(ShopOffer("Bol.com", 26.95, true), ShopOffer("Coolblue.be", 41.0, true))
        val extra = listOf(ShopOffer("Bol.com", 38.99))
        val merged = Offers.merge(own, extra)
        assertEquals(38.99, merged.first { it.seller == "Bol.com" }.price, 0.001)
        assertEquals(38.99, Offers.cheapest(merged)!!.price, 0.001)
    }

    @Test
    fun aShopOnlyVergelijkListsKeepsItsOwnPrice() {
        val own = listOf(ShopOffer("Coolblue.be", 41.0, true))
        val merged = Offers.merge(own, listOf(ShopOffer("Bol.com", 38.99)))
        assertEquals(listOf("Bol.com", "Coolblue.be"), merged.map { it.seller })
        assertEquals(41.0, merged.last().price, 0.001)
    }

    @Test
    fun withoutGoogleVergelijksShopsAreAllShown() {
        val own = listOf(ShopOffer("A", 10.0, true), ShopOffer("B", 12.0, true), ShopOffer("C", 11.0, true))
        assertEquals(listOf("A", "C", "B"), Offers.merge(own, emptyList()).map { it.seller })
    }
}
