package com.bartschram.franxit.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraShopPricesTest {

    @Test
    fun aShopSomewhatDearerThanTheCheapestStillCounts() {
        // bol.com at € 437,52 against € 349,00: 125,4%, which used to fall just outside.
        assertTrue(ExtraShopPrices.plausible(437.52, 349.0))
        assertTrue(ExtraShopPrices.plausible(523.0, 349.0))
    }

    @Test
    fun farTooDearIsNoAlternative() {
        assertFalse(ExtraShopPrices.plausible(620.0, 361.0))
        assertFalse(ExtraShopPrices.plausible(524.0, 349.0))
    }

    @Test
    fun tooGoodToBeTrueIsNoAlternative() {
        assertFalse(ExtraShopPrices.plausible(37.99, 219.0))
        assertTrue(ExtraShopPrices.plausible(109.5, 219.0))
        assertFalse(ExtraShopPrices.plausible(109.4, 219.0))
    }

    @Test
    fun withoutACheapestPriceEverythingCounts() {
        assertTrue(ExtraShopPrices.plausible(1000.0, null))
    }

    @Test
    fun aReliableShopMayBeDearerButNotFarBelow() {
        assertTrue(ExtraShopPrices.plausible(600.0, 349.0, reliable = true))
        assertFalse(ExtraShopPrices.plausible(23.49, 349.0, reliable = true))
        assertFalse(ExtraShopPrices.plausible(23.49, 349.0))
    }
}
