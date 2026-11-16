package com.bartschram.franxit.ui

import com.bartschram.franxit.data.PricePoint
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.SitePoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class OverviewTest {

    private val today = LocalDate.of(2026, 10, 31)

    private fun product(id: Long, own: List<Double>, site: Map<Long, Double> = emptyMap()) = Product(
        id = id, url = "u$id", name = "P$id", lastChecked = null, lastError = null,
        history = own.mapIndexed { i, p -> PricePoint(today.minusDays((own.size - 1 - i).toLong()), p, "Shop", 1, 0) },
        siteHistory = site.map { (daysAgo, p) -> SitePoint(today.minusDays(daysAgo), p) }.sortedBy { it.day },
    )

    @Test
    fun sortsByPriceBothWaysWithUnpricedLast() {
        val products = listOf(product(1, listOf(500.0)), product(2, emptyList()), product(3, listOf(200.0)))
        assertEquals(listOf(3L, 1L, 2L), SortOrder.PRICE_UP.apply(products).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), SortOrder.PRICE_DOWN.apply(products).map { it.id })
    }
}
