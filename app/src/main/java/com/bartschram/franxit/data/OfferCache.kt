package com.bartschram.franxit.data

import java.time.LocalDate

/**
 * How long saved results of Google Shopping and Shoparize are used, without anything Android, so the rules are
 * tested (Shoparize's "nothing found" was once kept for 3 days and hid its shops; a version change must drop old ones).
 */
internal object OfferCache {

    /** A Google Shopping search that found nothing is kept this many days: a search costs, so not asked every day. */
    const val GOOGLE_NOTHING_DAYS = 3

    /** Shoparize's results are kept for the day, also "nothing found": it sometimes answers with an empty page. */
    const val SHOPARIZE_DAYS = 1

    /** The last day results saved on [day] for [days] days are used. */
    fun until(day: String, days: Int): String = LocalDate.parse(day).plusDays(days - 1L).toString()

    /**
     * Whether results saved with [savedVersion] on [savedDay] (used until [until]) are to be used on [day]: the
     * same [version], and [day] within their days. [day] null: whatever was saved, whenever (the Buy button).
     */
    fun usable(savedVersion: Int, version: Int, savedDay: String, until: String, day: String?): Boolean =
        savedVersion == version && (day == null || (day >= savedDay && day <= until))
}
