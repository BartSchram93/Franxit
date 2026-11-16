package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BuyAdviceTest {

    private val today = LocalDate.of(2026, 11, 1)

    /** A price line from (days ago, price) changes. */
    private fun line(vararg changes: Pair<Long, Double>) = changes.map { (ago, price) -> today.minusDays(ago) to price }

    @Test
    fun tooLittleHistoryIsInsufficient() {
        val advice = BuyAdvisor.advise(line(10L to 100.0, 5L to 95.0, 1L to 90.0), 90.0, today)
        assertEquals(Verdict.INSUFFICIENT_DATA, advice.verdict)
        assertNull(advice.score)
        assertTrue(advice.reasons.isEmpty())
    }

    @Test
    fun noPriceIsInsufficient() {
        assertEquals(Verdict.INSUFFICIENT_DATA, BuyAdvisor.advise(line(200L to 100.0, 100L to 90.0, 10L to 95.0), null, today).verdict)
    }

    @Test
    fun aPriceWellBelowTypicalAndAtTheLowIsBuyNow() {
        // A year mostly around € 930, now € 799 (a new low).
        val advice = BuyAdvisor.advise(line(360L to 930.0, 200L to 949.0, 120L to 920.0, 40L to 899.0, 3L to 799.0), 799.0, today)
        assertEquals(Verdict.BUY_NOW, advice.verdict)
        assertTrue(advice.score!! >= 80)
        assertTrue(advice.reasons.any { it is Reason.BelowTypical })
        assertTrue(advice.reasons.any { it is Reason.LowestInMonths })
        assertNull(advice.target)
    }

    @Test
    fun recentlyMuchCheaperIsWaitWithATarget() {
        // Typically € 1.000, was € 850 for weeks until recently, now back to € 1.000.
        val advice = BuyAdvisor.advise(line(360L to 1000.0, 70L to 850.0, 20L to 1000.0), 1000.0, today)
        assertTrue(advice.verdict == Verdict.WAIT || advice.verdict == Verdict.WAIT_EXPENSIVE)
        assertTrue(advice.reasons.any { it is Reason.CheaperRecently && it.price == 850.0 })
        assertNotNull(advice.target)
        val target = advice.target!!
        assertTrue(target.endInclusive < 1000.0)
        assertTrue(target.start >= 850.0)
    }

    @Test
    fun aOneDayBlipIsNotTheLow() {
        // € 500 for one day only: not a price you could really get.
        val advice = BuyAdvisor.advise(line(300L to 900.0, 150L to 500.0, 149L to 900.0, 60L to 880.0), 880.0, today)
        assertEquals(880.0, advice.historicalLow!!, 0.001)
        // The low's date: the last day it had that price (today, as 880 still holds).
        assertEquals(today, advice.historicalLowDate)
    }

    @Test
    fun aStablePriceSaysSoInsteadOfNearTheLow() {
        val advice = BuyAdvisor.advise(line(300L to 249.0, 200L to 249.0, 100L to 249.0), 249.0, today)
        assertTrue(advice.reasons.any { it is Reason.Stable })
        assertTrue(advice.reasons.none { it is Reason.NearLow })
        // Never cheaper, never dearer: buying now is as good as any day.
        assertEquals(Verdict.CONSIDER_BUYING, advice.verdict)
    }

    @Test
    fun alwaysTwoToFourReasons() {
        val advice = BuyAdvisor.advise(line(360L to 930.0, 200L to 949.0, 120L to 920.0, 40L to 899.0, 3L to 799.0), 799.0, today)
        assertTrue(advice.reasons.size in 2..4)
    }
}
