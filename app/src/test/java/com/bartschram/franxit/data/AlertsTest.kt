package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AlertsTest {

    private val today = LocalDate.of(2026, 11, 2)

    private fun point(daysAgo: Long, price: Double) = PricePoint(today.minusDays(daysAgo), price, "Shop", 1, 0)

    private fun product(vararg prices: Double, target: Double? = null, site: List<SitePoint> = emptyList()) = Product(
        id = 1, url = "u", name = "Tablet, 14 inch", lastChecked = null, lastError = null,
        history = prices.mapIndexed { i, p -> point((prices.size - i).toLong(), p) },
        siteHistory = site, targetPrice = target,
    )

    @Test
    fun targetFiresOnceWhenCrossed() {
        assertEquals(AlertKind.TARGET, Alerts.decide(product(400.0, 390.0, target = 350.0), point(0, 349.0))?.kind)
        // Already below target yesterday: no repeat, but still a normal drop.
        assertEquals(AlertKind.DROP, Alerts.decide(product(330.0, 345.0, target = 350.0), point(0, 340.0))?.kind)
    }

    @Test
    fun lowestUsesSiteHistoryToo() {
        val site = listOf(SitePoint(today.minusDays(200), 300.0))
        assertEquals(AlertKind.DROP, Alerts.decide(product(400.0, 390.0, site = site), point(0, 380.0))?.kind)
        assertEquals(AlertKind.LOWEST, Alerts.decide(product(400.0, 390.0, site = site), point(0, 299.0))?.kind)
    }

    @Test
    fun noAlertWhenPriceRisesOrNoHistory() {
        assertNull(Alerts.decide(product(380.0), point(0, 390.0)))
        assertNull(Alerts.decide(product(), point(0, 390.0)))
    }

    @Test
    fun displayNameShortensSiteName() {
        assertEquals("Tablet", product().displayName)
        assertEquals("My tab", product().copy(customName = "My tab").displayName)
    }
}
