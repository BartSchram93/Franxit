package com.bartschram.franxit.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * "Buy or wait": a transparent, rule-based reading of a product's price history. It says how the
 * current price compares with what the product has cost, never what it will cost: every number it
 * returns comes straight from the history, and every [Reason] carries the metric behind it.
 *
 * The score starts at [BuyAdviceConfig.START] and moves with four factors (typical price, distance
 * from the low, recent trend, recent cheaper moments); a fifth, data quality, decides whether there
 * is enough history to say anything at all, and how sure the advice is. The factors are smooth: a
 * price a cent either side of a border moves the score a hair, not a step.
 */
object BuyAdviceConfig {
    const val START = 50

    // Points are read off these tables, linear in between, so a price a cent either side of a
    // border never flips the advice. Each node sits in the middle of the band it replaced.

    // Factor 1: current price vs the typical (median) price, as a fraction (negative: below).
    val TYPICAL_POINTS = listOf(-0.20 to 30.0, -0.115 to 20.0, -0.055 to 10.0, 0.0 to 0.0, 0.065 to -10.0, 0.15 to -18.0, 0.25 to -25.0)
    /** A price this far (as a fraction) off the typical one is worth saying in the reasons. */
    const val BELOW_MEDIAN_SLIGHT = 0.03
    const val ABOVE_MEDIAN_SLIGHT = 0.03

    // Factor 2: how far above the (solid) historical low, as a fraction.
    val LOW_POINTS = listOf(0.0 to 25.0, 0.015 to 25.0, 0.04 to 20.0, 0.075 to 10.0, 0.175 to 0.0, 0.30 to -10.0)
    const val NEAR_LOW_5 = 0.05
    /** A price range this narrow means the price barely moves: being "near the low" says little. */
    const val FLAT_RANGE = 0.03
    /** A price that never really moves: buying now is as good as any other day. */
    const val POINTS_STABLE = 15

    // Factor 3: recent trend, current price vs the median of the last 30 days.
    val TREND_POINTS = listOf(-0.10 to 5.0, 0.0 to 0.0, 0.10 to -5.0)
    /** The change that counts as "dropped" or "rose" in the reasons. */
    const val TREND_CHANGE = 0.05

    // Factor 4: the product was clearly cheaper within the last 90 days, as a fraction of today's price.
    val RECENT_CHEAPER_POINTS = listOf(0.025 to 0.0, 0.075 to -8.0, 0.15 to -15.0)
    const val RECENT_CHEAPER = 0.05

    // Factor 5: data quality.
    const val MIN_HISTORY_DAYS = 30
    const val MIN_OBSERVATIONS = 3
    const val HIGH_CONFIDENCE_DAYS = 180
    const val HIGH_CONFIDENCE_OBSERVATIONS = 10
    const val MEDIUM_CONFIDENCE_DAYS = 60
    /** Only the last year counts: an old low says little about today's market. */
    const val WINDOW_DAYS = 365L
    /**
     * The last three months weigh more than the whole year (people don't wait a year to buy): the
     * typical price and the low are read off both, [RECENT_WEIGHT] of the points from the quarter.
     * The quarter needs [RECENT_MIN_DAYS] days of its own, or only the year counts.
     */
    const val RECENT_DAYS = 90
    const val RECENT_WEIGHT = 0.6
    const val RECENT_MIN_DAYS = 45
    /** Daily prices outside these multiples of the median are treated as errors, not history. */
    const val OUTLIER_LOW = 0.5; const val OUTLIER_HIGH = 2.0
    /**
     * A low must have lasted this many days to count: a price that held only a day or two isn't one
     * you could count on getting (a flash sale, a shop's mistake). Where a product goes on sale again
     * and again ([RECURRING_DIPS] dips), even [MIN_LOW_DAYS] days is a real moment to wait for.
     */
    const val SOLID_LOW_DAYS = 5
    const val MIN_LOW_DAYS = 2
    /** A dip: at least this many days [DIP_BELOW_MEDIAN] under the typical price; [RECURRING_DIPS] of them in a year: it happens again. */
    const val DIP_BELOW_MEDIAN = 0.08
    const val DIP_MIN_DAYS = 2
    const val RECURRING_DIPS = 2

    // Recommendation thresholds on the final score.
    const val BUY_NOW = 78
    const val CONSIDER = 60
    const val WAIT = 40
    /** "Skip it" says the price is high, so it needs a price at least this far above the typical one. */
    const val SKIP_ONLY_ABOVE_MEDIAN = 0.03

    // Suggested target when waiting: the middle half of the recent prices that were below today's.
    /** Only the last half year: a price from long ago says little about what comes back. */
    const val TARGET_WINDOW_DAYS = 180
    const val TARGET_LOW_PERCENTILE = 0.25
    const val TARGET_HIGH_PERCENTILE = 0.75
    /** At least this much below today's price, and on at least [TARGET_MIN_DAYS] days, to count. */
    const val TARGET_BELOW = 0.02
    const val TARGET_MIN_DAYS = 7
}

enum class Verdict { BUY_NOW, CONSIDER_BUYING, WAIT, WAIT_EXPENSIVE, INSUFFICIENT_DATA }

enum class Confidence { HIGH, MEDIUM, LOW }

enum class Trend { DOWN, STABLE, UP }

/** One reason behind the advice, with the metric it comes from. [positive] is good for buying now. */
sealed interface Reason {
    val positive: Boolean

    data class BelowTypical(val percent: Double, val typical: Double) : Reason { override val positive = true }
    data class AboveTypical(val percent: Double, val typical: Double) : Reason { override val positive = false }
    data class BelowRecent(val percent: Double, val typical: Double) : Reason { override val positive = true }
    data class AboveRecent(val percent: Double, val typical: Double) : Reason { override val positive = false }
    data class NearLow(val percent: Double, val low: Double) : Reason { override val positive = true }
    data class FarAboveLow(val percent: Double, val low: Double) : Reason { override val positive = false }
    data class LowestInMonths(val months: Int) : Reason { override val positive = true }
    data class DroppedRecently(val percent: Double, val days: Int) : Reason { override val positive = true }
    data class RoseRecently(val percent: Double, val days: Int) : Reason { override val positive = false }
    data class CheaperRecently(val price: Double, val percent: Double, val days: Int) : Reason { override val positive = false }
    data class Stable(val typical: Double) : Reason { override val positive = true }
    data class HistoryLength(val months: Int) : Reason { override val positive = true }
}

data class BuyAdvice(
    val verdict: Verdict,
    /** 0–100, how attractive buying now is compared with the history; null without enough data. */
    val score: Int?,
    val confidence: Confidence?,
    val currentPrice: Double?,
    val medianPrice: Double?,
    val historicalLow: Double?,
    /** Negative when below the median: −0.14 is 14% below. */
    val vsMedian: Double?,
    val aboveLow: Double?,
    val trend: Trend?,
    /** A more attractive price range, only when the advice is to wait. */
    val target: ClosedFloatingPointRange<Double>?,
    val reasons: List<Reason>,
    /** The last day the product had [historicalLow]. */
    val historicalLowDate: LocalDate? = null,
    /** Today's price against the typical price of the last three months (negative: below); null without enough days. */
    val vsRecent: Double? = null,
    val recentMedian: Double? = null,
)

object BuyAdvisor {

    private val C = BuyAdviceConfig

    fun advise(product: Product, today: LocalDate = LocalDate.now()): BuyAdvice {
        // vergelijk.be's history up to our first own check, then our own checks (as in the chart).
        val firstOwn = product.history.firstOrNull()?.day
        val line = product.siteHistory.filter { firstOwn == null || it.day.isBefore(firstOwn) }.map { it.day to it.price } +
            product.history.map { it.day to it.price }
        return advise(line, product.latest?.price, today)
    }

    /** [line]: (day, price) changes, oldest first, each price holding until the next. */
    fun advise(line: List<Pair<LocalDate, Double>>, current: Double?, today: LocalDate = LocalDate.now()): BuyAdvice {
        val from = today.minusDays(C.WINDOW_DAYS)
        val daily = daily(line, from, today)
        val spanDays = daily.size
        // Price points within the window, plus the one carried into it from before.
        val observations = line.count { !it.first.isBefore(from) } + if (line.any { it.first.isBefore(from) }) 1 else 0
        if (current == null || current <= 0 || spanDays < C.MIN_HISTORY_DAYS || observations < C.MIN_OBSERVATIONS) {
            return insufficient(current)
        }

        // Prices far off the median are data errors, not prices anyone could pay.
        val rawMedian = median(daily.map { it.second })
        val clean = daily.filter { it.second in rawMedian * C.OUTLIER_LOW..rawMedian * C.OUTLIER_HIGH }
        if (clean.size < C.MIN_HISTORY_DAYS) return insufficient(current)
        val prices = clean.map { it.second }
        val median = median(prices)

        // A short dip (a flash sale) is no price to wait for, unless this product keeps going on sale:
        // then even a short low counts. Otherwise a low must have held for several days.
        val recurring = dipEpisodes(clean, median) >= C.RECURRING_DIPS
        val lowDays = if (recurring) C.MIN_LOW_DAYS else C.SOLID_LOW_DAYS
        // Never above today's price: a new low is the low.
        val low = minOf(reliableLow(prices, lowDays), current)
        val vsMedian = (current - median) / median
        val aboveLow = (current - low) / low
        val held = levels(prices, lowDays).ifEmpty { prices }
        val flat = (held.max() - held.min()) / median < C.FLAT_RANGE

        // The last three months: they weigh more than the year.
        val quarter = clean.filter { ChronoUnit.DAYS.between(it.first, today) < C.RECENT_DAYS }.map { it.second }
        val recentMedian = if (quarter.size >= C.RECENT_MIN_DAYS) median(quarter) else null
        val vsRecent = recentMedian?.let { (current - it) / it }
        val weight = if (recentMedian != null) C.RECENT_WEIGHT else 0.0

        var score = C.START.toDouble()
        val reasons = mutableListOf<Pair<Int, Reason>>()  // priority to reason

        // Factor 1: typical price, of the year and of the last three months.
        val yearTypical = points(vsMedian, C.TYPICAL_POINTS)
        score += if (vsRecent != null) (1 - weight) * yearTypical + weight * points(vsRecent, C.TYPICAL_POINTS) else yearTypical
        if (vsRecent != null && recentMedian != null) {
            if (vsRecent <= -C.BELOW_MEDIAN_SLIGHT) reasons += 1 to Reason.BelowRecent(-vsRecent, recentMedian)
            if (vsRecent >= C.ABOVE_MEDIAN_SLIGHT) reasons += 1 to Reason.AboveRecent(vsRecent, recentMedian)
        }
        if (vsMedian <= -C.BELOW_MEDIAN_SLIGHT) reasons += 1 to Reason.BelowTypical(-vsMedian, median)
        if (vsMedian >= C.ABOVE_MEDIAN_SLIGHT) reasons += 1 to Reason.AboveTypical(vsMedian, median)

        // Factor 2: distance from the low (meaningless when the price hardly moves), of the year and of the last three months.
        val yearLow = if (flat) C.POINTS_STABLE.toDouble() else points(aboveLow, C.LOW_POINTS)
        val recentLow = recentMedian?.let { rm ->
            val low3 = minOf(reliableLow(quarter, lowDays), current)
            val flat3 = (quarter.max() - quarter.min()) / rm < C.FLAT_RANGE
            if (flat3) C.POINTS_STABLE.toDouble() else points((current - low3) / low3, C.LOW_POINTS)
        }
        score += if (recentLow != null) (1 - weight) * yearLow + weight * recentLow else yearLow
        if (!flat) {
            if (aboveLow <= C.NEAR_LOW_5) reasons += 2 to Reason.NearLow(maxOf(aboveLow, 0.0), low)
            // Above the low by more than "near": always worth saying, also when it doesn't cost points.
            if (aboveLow > C.NEAR_LOW_5) reasons += 2 to Reason.FarAboveLow(aboveLow, low)
            lowestInMonths(clean, current, today, spanDays)?.let { reasons += 0 to Reason.LowestInMonths(it) }
        } else {
            reasons += 3 to Reason.Stable(median)
        }

        // Factor 3: recent trend against the last 30 days.
        val month = clean.filter { ChronoUnit.DAYS.between(it.first, today) < 30 }.map { it.second }
        val trend = if (month.size >= 7) {
            val change = (current - median(month)) / median(month)
            score += points(change, C.TREND_POINTS)
            when {
                change <= -C.TREND_CHANGE -> { reasons += 3 to Reason.DroppedRecently(-change, 30); Trend.DOWN }
                change >= C.TREND_CHANGE -> { reasons += 3 to Reason.RoseRecently(change, 30); Trend.UP }
                else -> Trend.STABLE
            }
        } else null

        // Factor 4: clearly cheaper within the last 90 days (a price that held, not a blip).
        if (quarter.isNotEmpty()) {
            val cheapestLately = reliableLow(quarter, lowDays)
            val cheaper = (current - cheapestLately) / current
            score += points(cheaper, C.RECENT_CHEAPER_POINTS)
            if (cheaper >= C.RECENT_CHEAPER) reasons += 0 to Reason.CheaperRecently(cheapestLately, cheaper, C.RECENT_DAYS)
        }

        val finalScore = score.coerceIn(0.0, 100.0).roundToInt()
        var verdict = when {
            finalScore >= C.BUY_NOW -> Verdict.BUY_NOW
            finalScore >= C.CONSIDER -> Verdict.CONSIDER_BUYING
            finalScore >= C.WAIT -> Verdict.WAIT
            else -> Verdict.WAIT_EXPENSIVE
        }
        val confidence = when {
            spanDays >= C.HIGH_CONFIDENCE_DAYS && observations >= C.HIGH_CONFIDENCE_OBSERVATIONS -> Confidence.HIGH
            spanDays >= C.MEDIUM_CONFIDENCE_DAYS -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        // "Skip it" says the price is high: a price that is not above the typical one only waits.
        if (verdict == Verdict.WAIT_EXPENSIVE && vsMedian < C.SKIP_ONLY_ABOVE_MEDIAN) verdict = Verdict.WAIT
        // A month of history is too little to say "buy now".
        if (verdict == Verdict.BUY_NOW && confidence == Confidence.LOW) verdict = Verdict.CONSIDER_BUYING
        val recent = clean.filter { ChronoUnit.DAYS.between(it.first, today) < C.TARGET_WINDOW_DAYS }.map { it.second }
        val target = if (verdict == Verdict.WAIT || verdict == Verdict.WAIT_EXPENSIVE) target(recent, current) else null

        // 2 to 4 reasons, the most telling first; the history length fills up when there's little else.
        val chosen = reasons.sortedBy { it.first }.map { it.second }.distinctBy { it::class }.take(4).toMutableList()
        if (chosen.size < 2) chosen += Reason.HistoryLength((spanDays / 30.0).roundToInt().coerceAtLeast(1))

        return BuyAdvice(
            verdict, finalScore, confidence, current, median, low, vsMedian, aboveLow, trend, target, chosen,
            historicalLowDate = clean.lastOrNull { (it.second * 100).roundToInt() == (low * 100).roundToInt() }?.first,
            vsRecent = vsRecent, recentMedian = recentMedian,
        )
    }

    /** Points for [x], read off [table] (x to points, ascending in x), linear between its nodes and flat beyond them. */
    internal fun points(x: Double, table: List<Pair<Double, Double>>): Double {
        if (x <= table.first().first) return table.first().second
        if (x >= table.last().first) return table.last().second
        for (i in 0 until table.size - 1) {
            val (x0, y0) = table[i]
            val (x1, y1) = table[i + 1]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return table.last().second
    }

    /** How many separate dips (a few days clearly under the typical price) the product had. */
    internal fun dipEpisodes(clean: List<Pair<LocalDate, Double>>, median: Double): Int {
        var episodes = 0
        var run = 0
        for ((_, price) in clean) {
            if (price <= median * (1 - C.DIP_BELOW_MEDIAN)) run++ else { if (run >= C.DIP_MIN_DAYS) episodes++; run = 0 }
        }
        if (run >= C.DIP_MIN_DAYS) episodes++
        return episodes
    }

    private fun insufficient(current: Double?) =
        BuyAdvice(Verdict.INSUFFICIENT_DATA, null, null, current, null, null, null, null, null, null, emptyList())

    /** One price per day from [from] to [today], each day the last known price (a step line). */
    internal fun daily(line: List<Pair<LocalDate, Double>>, from: LocalDate, today: LocalDate): List<Pair<LocalDate, Double>> {
        if (line.isEmpty()) return emptyList()
        val start = maxOf(line.first().first, from)
        var i = 0
        var price: Double? = null
        val out = mutableListOf<Pair<LocalDate, Double>>()
        var day = line.first().first
        while (!day.isAfter(today)) {
            while (i < line.size && !line[i].first.isAfter(day)) { price = line[i].second; i++ }
            if (!day.isBefore(start)) price?.let { out += day to it }
            day = day.plusDays(1)
        }
        return out
    }

    /** The prices (in cents, as euros) that held for at least [minDays] days. */
    private fun levels(prices: List<Double>, minDays: Int): List<Double> {
        val days = prices.groupingBy { (it * 100).roundToInt() }.eachCount()
        return days.filter { it.value >= minDays }.keys.map { it / 100.0 }
    }

    /** The lowest price that held for at least [minDays] days (else for [BuyAdviceConfig.MIN_LOW_DAYS], else the lowest of all). */
    internal fun reliableLow(prices: List<Double>, minDays: Int = C.MIN_LOW_DAYS): Double =
        levels(prices, minDays).minOrNull() ?: levels(prices, C.MIN_LOW_DAYS).minOrNull() ?: prices.min()

    /** The current price is the lowest of the last 6 (or 3) months, when the history goes back that far. */
    private fun lowestInMonths(clean: List<Pair<LocalDate, Double>>, current: Double, today: LocalDate, span: Int): Int? {
        for (months in listOf(6, 3)) {
            if (span < months * 30) continue
            val window = clean.filter { ChronoUnit.DAYS.between(it.first, today) < months * 30 }.map { it.second }
            if (window.isNotEmpty() && current <= window.min() + 0.005) return months
        }
        return null
    }

    /**
     * A more attractive price that the product really had lately ([prices]: the last half year): the middle
     * half of the daily prices that were clearly below today's. Null when it was hardly ever cheaper.
     */
    private fun target(prices: List<Double>, current: Double): ClosedFloatingPointRange<Double>? {
        val cheaper = prices.filter { it <= current * (1 - C.TARGET_BELOW) }.sorted()
        if (cheaper.size < C.TARGET_MIN_DAYS) return null
        val low = floor(percentile(cheaper, C.TARGET_LOW_PERCENTILE))
        val high = floor(percentile(cheaper, C.TARGET_HIGH_PERCENTILE))
        return low..high
    }

    private fun percentile(sorted: List<Double>, p: Double): Double = sorted[((sorted.size - 1) * p).roundToInt()]

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
