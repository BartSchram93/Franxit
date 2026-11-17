package com.bartschram.franxit.ui

import com.bartschram.franxit.data.BuyAdvisor
import com.bartschram.franxit.data.PricePoint
import com.bartschram.franxit.data.ShareLink
import com.bartschram.franxit.data.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AdviceTextTest {

    private val today = LocalDate.now()

    private fun product(prices: List<Double>) = Product(
        id = 1, url = "https://example.test/p", name = "Sony PlayStation 5 Slim, 1 TB, wit", lastChecked = null, lastError = null,
        history = prices.mapIndexed { i, p -> PricePoint(today.minusDays((prices.size - 1 - i).toLong()), p, "Coolblue.be", 3, 0) },
    )

    @Test
    fun theSharedTextHasTheNameThePriceTheAdviceAndTheSite() {
        // Long enough for an advice: 60 days, three price levels.
        val p = product(List(25) { 300.0 } + List(20) { 280.0 } + List(15) { 240.0 })
        val lines = adviceText(p, BuyAdvisor.advise(p)).lines()
        assertEquals("Sony PlayStation 5 Slim", lines.first())
        assertTrue(lines[1].contains("Coolblue.be"))
        assertTrue(lines[2], lines[2].startsWith("Franxit says: "))
        assertEquals(ShareLink.forProduct("https://example.test/p", "Sony PlayStation 5 Slim"), lines.last())
    }

    @Test
    fun withoutEnoughHistoryItSaysSoWithoutAnExplanation() {
        val p = product(listOf(250.0, 249.0))
        val lines = adviceText(p, BuyAdvisor.advise(p)).lines()
        assertEquals("Franxit says: No advice yet.", lines[2])
    }
}
