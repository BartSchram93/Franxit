package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long saved results are used, with the cases that went wrong. */
class OfferCacheTest {

    @Test
    fun aDaysResultsAreUsedThatDayOnly() {
        val until = OfferCache.until("2026-11-20", 1)
        assertEquals("2026-11-20", until)
        assertTrue(OfferCache.usable(4, 4, "2026-11-20", until, "2026-11-20"))
        assertFalse(OfferCache.usable(4, 4, "2026-11-20", until, "2026-11-21"))
    }

    @Test
    fun googlesNothingFoundIsKeptThreeDays() {
        val until = OfferCache.until("2026-11-20", OfferCache.GOOGLE_NOTHING_DAYS)
        assertTrue(OfferCache.usable(6, 6, "2026-11-20", until, "2026-11-22"))
        assertFalse(OfferCache.usable(6, 6, "2026-11-20", until, "2026-11-23"))
    }

    @Test
    fun shoparizesNothingFoundIsAskedAgainTheNextDay() {
        // Version 16 kept it 3 days, and an empty answer of a moment hid Shoparize's shops for days.
        assertEquals(1, OfferCache.SHOPARIZE_DAYS)
        val until = OfferCache.until("2026-11-20", OfferCache.SHOPARIZE_DAYS)
        assertFalse(OfferCache.usable(4, 4, "2026-11-20", until, "2026-11-21"))
    }

    @Test
    fun anotherVersionDropsWhatWasSaved() {
        assertFalse(OfferCache.usable(3, 4, "2026-11-20", "2026-11-20", "2026-11-20"))
        assertFalse(OfferCache.usable(3, 4, "2026-11-20", "2026-11-20", null))
    }

    @Test
    fun forTheBuyButtonAnyDayWillDo() {
        assertTrue(OfferCache.usable(4, 4, "2026-11-01", "2026-11-01", null))
    }

    @Test
    fun resultsSavedLaterThanTheDayAskedAreNotUsed() {
        assertFalse(OfferCache.usable(4, 4, "2026-11-21", "2026-11-21", "2026-11-20"))
    }
}
