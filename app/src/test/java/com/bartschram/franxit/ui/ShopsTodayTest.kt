package com.bartschram.franxit.ui

import com.bartschram.franxit.data.ShopOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ShopsTodayTest {

    @Test
    fun mergesTheSameShopAtTheSamePrice() {
        val offers = listOf(
            ShopOffer("Amazon Marketplace", 386.10),
            ShopOffer("Amazon", 386.10),
            ShopOffer("Bol.", 445.82),
        )
        assertEquals(listOf("Amazon", "Bol."), mergeSameShop(offers).map { it.seller })
    }

    @Test
    fun keepsDifferentPricesAndShops() {
        val offers = listOf(
            ShopOffer("Amazon", 386.10),
            ShopOffer("Amazon Marketplace", 399.00),
            ShopOffer("MediaMarkt", 386.10),
        )
        assertEquals(listOf("Amazon", "MediaMarkt", "Amazon Marketplace"), mergeSameShop(offers).map { it.seller })
    }
}
