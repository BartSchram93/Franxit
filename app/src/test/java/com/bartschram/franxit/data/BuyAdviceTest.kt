package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(advice.score!! >= 78)
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

    @Test
    fun aOneOffFlashSaleDoesNotMakeANormalPriceRed() {
        // € 100 all year, two days at € 70 three weeks ago: that sale is not coming back, today's price is normal.
        val advice = BuyAdvisor.advise(line(365L to 100.0, 23L to 70.0, 21L to 100.0, 10L to 100.5), 100.0, today)
        assertTrue(advice.verdict.toString(), advice.verdict == Verdict.CONSIDER_BUYING || advice.verdict == Verdict.BUY_NOW)
        assertTrue(advice.reasons.none { it is Reason.CheaperRecently })
    }

    @Test
    fun aProductThatKeepsGoingOnSaleStillMakesYouWait() {
        // Three sales of a few days in the year, the last one 30 days ago, now the normal price again.
        val advice = BuyAdvisor.advise(
            line(365L to 100.0, 300L to 75.0, 296L to 100.0, 200L to 78.0, 196L to 100.0, 33L to 74.0, 29L to 100.0),
            100.0, today,
        )
        assertTrue(advice.verdict == Verdict.WAIT || advice.verdict == Verdict.WAIT_EXPENSIVE)
        assertTrue(advice.reasons.any { it is Reason.CheaperRecently })
    }

    @Test
    fun skipItNeedsAPriceAboveTheTypicalOne() {
        // Typically € 100, was € 70 for ten days two months ago, now € 100: recently cheaper, so wait, but the price is not high.
        val advice = BuyAdvisor.advise(line(365L to 100.0, 70L to 70.0, 60L to 100.0, 20L to 100.5), 100.0, today)
        assertEquals(Verdict.WAIT, advice.verdict)
    }

    @Test
    fun aPriceReallyAboveTheTypicalOneIsSkipIt() {
        val advice = BuyAdvisor.advise(line(365L to 100.0, 250L to 102.0, 120L to 99.0, 40L to 125.0), 125.0, today)
        assertEquals(Verdict.WAIT_EXPENSIVE, advice.verdict)
    }

    @Test
    fun aMonthOfHistoryNeverSaysBuyNow() {
        // Only 35 days, now far below that month's prices: good, but not enough history for "buy now".
        val advice = BuyAdvisor.advise(line(34L to 100.0, 25L to 101.0, 15L to 100.0, 5L to 86.0), 86.0, today)
        assertEquals(Confidence.LOW, advice.confidence)
        assertEquals(Verdict.CONSIDER_BUYING, advice.verdict)
        assertTrue(advice.score!! >= 78)
    }

    @Test
    fun aCentEitherSideOfABorderMovesTheScoreAHair() {
        val history = line(365L to 100.0, 250L to 104.0, 150L to 96.0, 60L to 100.0)
        val a = BuyAdvisor.advise(history, 102.9, today).score!!
        val b = BuyAdvisor.advise(history, 103.1, today).score!!
        assertTrue("$a vs $b", kotlin.math.abs(a - b) <= 2)
    }

    @Test
    fun theTargetOnlyLooksAtTheLastHalfYear() {
        // A price that rose all year from € 100 to € 130: the target is not a price from a year ago.
        val advice = BuyAdvisor.advise(
            line(365L to 100.0, 300L to 105.0, 240L to 110.0, 180L to 115.0, 120L to 120.0, 60L to 125.0, 3L to 130.0),
            130.0, today,
        )
        val target = advice.target
        assertTrue("target $target", target == null || target.start >= 115.0)
    }

    @Test
    fun theLowShownIsNeverAboveTodaysPrice() {
        // Just dropped to a new low: it has not held long, but it is still the lowest price there was.
        val advice = BuyAdvisor.advise(line(360L to 930.0, 200L to 949.0, 120L to 920.0, 40L to 899.0, 3L to 799.0), 799.0, today)
        assertEquals(799.0, advice.historicalLow!!, 0.001)
        assertFalse(advice.aboveLow!! > 0.0)
    }

    @Test
    fun theScoreFollowsTheTablesInBetweenTheirNodes() {
        val table = listOf(0.0 to 0.0, 0.1 to -10.0)
        assertEquals(-5.0, BuyAdvisor.points(0.05, table), 1e-9)
        assertEquals(0.0, BuyAdvisor.points(-1.0, table), 1e-9)
        assertEquals(-10.0, BuyAdvisor.points(9.0, table), 1e-9)
    }
}
