package com.bartschram.franxit.data

import java.time.LocalDate

/** The cheapest price seen on one day. A later check on the same day overwrites it. */
data class PricePoint(
    val day: LocalDate,
    val price: Double,
    val seller: String,
    val offers: Int,
    val checkedAt: Long,
    /** False when even the cheapest offer was out of stock; null when the site doesn't say. */
    val inStock: Boolean? = null,
)

/** One shop's offer from the latest check. */
data class ShopOffer(val seller: String, val price: Double, val inStock: Boolean? = null)

/** A day's price from the site's own price history (vergelijk.be). */
data class SitePoint(val day: LocalDate, val price: Double)

/** A daily check is "missed" when the last successful one is older than this. */
const val STALE_AFTER_MS = 30L * 60 * 60 * 1000

data class Product(
    val id: Long,
    val url: String,
    val name: String,
    val lastChecked: Long?,
    val lastError: String?,
    /** Oldest first. */
    val history: List<PricePoint>,
    /** Oldest first. Empty for sites that don't publish history. */
    val siteHistory: List<SitePoint> = emptyList(),
    /** Set by the user; replaces the (often very long) name from the site. */
    val customName: String? = null,
    /** Notify when the price falls to or below this. */
    val targetPrice: Double? = null,
    /** Price drop, lowest-price and target notifications for this product. */
    val notify: Boolean = true,
    /** Every shop's offer from the latest check, cheapest first. */
    val offers: List<ShopOffer> = emptyList(),
) {
    /** The user's name, else the site's name up to the first comma ("TCL NXTPAPER 14, 36,3 cm, …"). */
    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: Names.short(name)

    val latest: PricePoint? get() = history.lastOrNull()
    val first: PricePoint? get() = history.firstOrNull()
    val lowest: PricePoint? get() = history.minByOrNull { it.price }
    val highest: PricePoint? get() = history.maxByOrNull { it.price }

    /** The most recent point from an earlier day than [latest]. */
    val previous: PricePoint?
        get() = latest?.let { last -> history.lastOrNull { it.day < last.day } }

    /** True when the daily check hasn't succeeded for more than a day. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        val lastSuccess = latest?.checkedAt ?: return false
        return now - lastSuccess > STALE_AFTER_MS
    }
}

/** Why a check is worth a notification, most important first. */
enum class AlertKind { TARGET, LOWEST, DROP }

data class Alert(val product: Product, val kind: AlertKind, val from: Double?, val to: PricePoint)

data class CheckSummary(val checked: Int, val failed: Int, val alerts: List<Alert>)
