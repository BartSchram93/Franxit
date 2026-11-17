package com.bartschram.franxit.ui

import com.bartschram.franxit.data.PricePoint
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theSharedTextIsTheNameThePriceAndTheLinkOnly() {
        val p = product(List(25) { 300.0 } + List(20) { 280.0 } + List(15) { 240.0 })
        val text = shareText(p)
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Sony PlayStation 5 Slim: ") && lines[0].contains("240"))
        assertEquals(ShareLink.forProduct("https://example.test/p", "Sony PlayStation 5 Slim"), lines[1])
        // No advice and no shop.
        assertFalse(text.contains("Franxit says"))
        assertFalse(text.contains("Coolblue"))
    }

    @Test
    fun aProductWithoutAPriceSharesItsNameAndLink() {
        val p = product(emptyList())
        assertEquals(listOf("Sony PlayStation 5 Slim", ShareLink.forProduct("https://example.test/p", "Sony PlayStation 5 Slim")), shareText(p).lines())
    }
}
