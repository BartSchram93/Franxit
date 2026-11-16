package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class VergelijkHistoryTest {

    @Test
    fun buildsHistoryUrlFromProductPage() {
        assertEquals(
            "https://www.vergelijk.be/api/v1/product/history?category=tablet_pc&brand=tcl&product=nxtpaper_14_grijs_784877634",
            VergelijkHistory.historyUrl("https://www.vergelijk.be/tablet_pc/tcl/nxtpaper_14_grijs_784877634/?catId=3900"),
        )
        assertNull(VergelijkHistory.historyUrl("https://www.coolblue.be/nl/product/973396/wisberg.html"))
    }

    @Test
    fun parsesMinSeriesAsBrusselsDays() {
        // Timestamps are local midnight in Brussels: 2025-10-23 (CEST) and 2025-10-27 (CET).
        val json = """{"priceHistoryData":[
            {"productHistoryMetricLabel":"avg","productHistoryItems":[{"date":1761177600000,"price":999}]},
            {"productHistoryMetricLabel":"min","productHistoryItems":[
              {"date":1761170400000,"price":373.47},{"date":1761519600000,"price":373.26}]}]}"""
        val points = VergelijkHistory.parse(json)
        assertEquals(listOf(LocalDate.of(2025, 10, 23), LocalDate.of(2025, 10, 27)), points.map { it.day })
        assertEquals(373.26, points.last().price, 0.001)
    }
}
