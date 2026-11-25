package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SuggestionsTest {

    private val today = LocalDate.of(2026, 10, 30)

    private fun product(site: List<Double>) = Product(
        id = 1, url = "u", name = "X", lastChecked = null, lastError = null,
        history = listOf(PricePoint(today, 400.0, "Shop", 1, 0)),
        siteHistory = site.mapIndexed { i, p -> SitePoint(today.minusDays((site.size - i).toLong()), p) },
    )

    @Test
    fun usesTheYearsHistory() {
        // 100 days: 80 at 450, 20 at 350, one day at 300.
        val site = List(79) { 450.0 } + List(20) { 350.0 } + 300.0
        val s = Suggestions.forProduct(product(site), today)
        assertEquals(listOf("10% off" to 360.0, "Good deal" to 350.0, "Year low" to 300.0), s.map { it.label to it.price })
    }

    @Test
    fun onlyTenPercentWithoutHistory() {
        assertEquals(listOf("10% off" to 360.0), Suggestions.forProduct(product(emptyList()), today).map { it.label to it.price })
    }
}
