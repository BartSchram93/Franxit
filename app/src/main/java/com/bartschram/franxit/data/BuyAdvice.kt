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
 * is enough history to say anything at all.
 */
object BuyAdviceConfig {
    const val START = 50

    // Factor 1: current price vs the typical (median) price, as a fraction. Below is good.
    const val BELOW_MEDIAN_STRONG = 0.15; const val POINTS_BELOW_MEDIAN_STRONG = 30
    const val BELOW_MEDIAN_CLEAR = 0.08; const val POINTS_BELOW_MEDIAN_CLEAR = 20
    const val BELOW_MEDIAN_SLIGHT = 0.03; const val POINTS_BELOW_MEDIAN_SLIGHT = 10
    const val ABOVE_MEDIAN_SLIGHT = 0.03; const val POINTS_ABOVE_MEDIAN_SLIGHT = -10
    const val ABOVE_MEDIAN_CLEAR = 0.10; const val POINTS_ABOVE_MEDIAN_CLEAR = -18
    const val ABOVE_MEDIAN_STRONG = 0.20; const val POINTS_ABOVE_MEDIAN_STRONG = -25

    // Factor 2: how far above the (reliable) historical low.
    const val NEAR_LOW_3 = 0.03; const val POINTS_NEAR_LOW_3 = 25
    const val NEAR_LOW_5 = 0.05; const val POINTS_NEAR_LOW_5 = 20
    const val NEAR_LOW_10 = 0.10; const val POINTS_NEAR_LOW_10 = 10
    const val FAR_FROM_LOW = 0.25; const val POINTS_FAR_FROM_LOW = -10
    /** A price range this narrow means the price barely moves: being "near the low" says little. */
    const val FLAT_RANGE = 0.03
    /** A price that never really moves: buying now is as good as any other day. */
    const val POINTS_STABLE = 15

    // Factor 3: recent trend, current price vs the median of the last 30 days.
    const val TREND_CHANGE = 0.05; const val POINTS_TREND = 5

    // Factor 4: the product was clearly cheaper within the last 90 days.
    const val RECENT_CHEAPER_STRONG = 0.10; const val POINTS_RECENT_CHEAPER_STRONG = -15
    const val RECENT_CHEAPER = 0.05; const val POINTS_RECENT_CHEAPER = -8

    // Factor 5: data quality.
    const val MIN_HISTORY_DAYS = 30
    const val MIN_OBSERVATIONS = 3
    const val HIGH_CONFIDENCE_DAYS = 180
    const val HIGH_CONFIDENCE_OBSERVATIONS = 10
    const val MEDIUM_CONFIDENCE_DAYS = 60
    /** Only the last year counts: an old low says little about today's market. */
    const val WINDOW_DAYS = 365L
    /** Daily prices outside these multiples of the median are treated as errors, not history. */
    const val OUTLIER_LOW = 0.5; const val OUTLIER_HIGH = 2.0
    /** A low must have lasted this many days to count; a one-day blip isn't a price you could get. */
    const val MIN_LOW_DAYS = 2

    // Recommendation thresholds on the final score.
    const val BUY_NOW = 80
    const val CONSIDER = 65
    const val WAIT = 40

    // Suggested target when waiting: the middle half of the past year's prices that were below today's.
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
        val low = reliableLow(prices)
        val vsMedian = (current - median) / median
        val aboveLow = (current - low) / low
        val flat = (prices.max() - prices.min()) / median < C.FLAT_RANGE

        var score = C.START
        val reasons = mutableListOf<Pair<Int, Reason>>()  // priority to reason

        // Factor 1: typical price.
        when {
            vsMedian <= -C.BELOW_MEDIAN_STRONG -> score += C.POINTS_BELOW_MEDIAN_STRONG
            vsMedian <= -C.BELOW_MEDIAN_CLEAR -> score += C.POINTS_BELOW_MEDIAN_CLEAR
            vsMedian <= -C.BELOW_MEDIAN_SLIGHT -> score += C.POINTS_BELOW_MEDIAN_SLIGHT
            vsMedian >= C.ABOVE_MEDIAN_STRONG -> score += C.POINTS_ABOVE_MEDIAN_STRONG
            vsMedian >= C.ABOVE_MEDIAN_CLEAR -> score += C.POINTS_ABOVE_MEDIAN_CLEAR
            vsMedian >= C.ABOVE_MEDIAN_SLIGHT -> score += C.POINTS_ABOVE_MEDIAN_SLIGHT
        }
        if (vsMedian <= -C.BELOW_MEDIAN_SLIGHT) reasons += 1 to Reason.BelowTypical(-vsMedian, median)
        if (vsMedian >= C.ABOVE_MEDIAN_SLIGHT) reasons += 1 to Reason.AboveTypical(vsMedian, median)

        // Factor 2: distance from the low (meaningless when the price hardly moves).
        if (!flat) {
            when {
                aboveLow <= C.NEAR_LOW_3 -> score += C.POINTS_NEAR_LOW_3
                aboveLow <= C.NEAR_LOW_5 -> score += C.POINTS_NEAR_LOW_5
                aboveLow <= C.NEAR_LOW_10 -> score += C.POINTS_NEAR_LOW_10
                aboveLow > C.FAR_FROM_LOW -> score += C.POINTS_FAR_FROM_LOW
            }
            if (aboveLow <= C.NEAR_LOW_5) reasons += 2 to Reason.NearLow(maxOf(aboveLow, 0.0), low)
            // Above the low by more than "near": always worth saying, also when it doesn't cost points.
            if (aboveLow > C.NEAR_LOW_5) reasons += 2 to Reason.FarAboveLow(aboveLow, low)
            lowestInMonths(clean, current, today, spanDays)?.let { reasons += 0 to Reason.LowestInMonths(it) }
        } else {
            score += C.POINTS_STABLE
            reasons += 3 to Reason.Stable(median)
        }

        // Factor 3: recent trend against the last 30 days.
        val month = clean.filter { ChronoUnit.DAYS.between(it.first, today) < 30 }.map { it.second }
        val trend = if (month.size >= 7) {
            val change = (current - median(month)) / median(month)
            when {
                change <= -C.TREND_CHANGE -> { score += C.POINTS_TREND; reasons += 3 to Reason.DroppedRecently(-change, 30); Trend.DOWN }
                change >= C.TREND_CHANGE -> { score -= C.POINTS_TREND; reasons += 3 to Reason.RoseRecently(change, 30); Trend.UP }
                else -> Trend.STABLE
            }
        } else null

        // Factor 4: clearly cheaper within the last 90 days (a price that held, not a blip).
        val quarter = clean.filter { ChronoUnit.DAYS.between(it.first, today) < 90 }.map { it.second }
        if (quarter.isNotEmpty()) {
            val recentLow = reliableLow(quarter)
            val cheaper = (current - recentLow) / current
            when {
                cheaper >= C.RECENT_CHEAPER_STRONG -> score += C.POINTS_RECENT_CHEAPER_STRONG
                cheaper >= C.RECENT_CHEAPER -> score += C.POINTS_RECENT_CHEAPER
            }
            if (cheaper >= C.RECENT_CHEAPER) reasons += 0 to Reason.CheaperRecently(recentLow, cheaper, 90)
        }

        score = score.coerceIn(0, 100)
        val verdict = when {
            score >= C.BUY_NOW -> Verdict.BUY_NOW
            score >= C.CONSIDER -> Verdict.CONSIDER_BUYING
            score >= C.WAIT -> Verdict.WAIT
            else -> Verdict.WAIT_EXPENSIVE
        }
        val confidence = when {
            spanDays >= C.HIGH_CONFIDENCE_DAYS && observations >= C.HIGH_CONFIDENCE_OBSERVATIONS -> Confidence.HIGH
            spanDays >= C.MEDIUM_CONFIDENCE_DAYS -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val target = if (verdict == Verdict.WAIT || verdict == Verdict.WAIT_EXPENSIVE) target(prices, current) else null

        // 2 to 4 reasons, the most telling first; the history length fills up when there's little else.
        val chosen = reasons.sortedBy { it.first }.map { it.second }.distinctBy { it::class }.take(4).toMutableList()
        if (chosen.size < 2) chosen += Reason.HistoryLength((spanDays / 30.0).roundToInt().coerceAtLeast(1))

        return BuyAdvice(
            verdict, score, confidence, current, median, low, vsMedian, aboveLow, trend, target, chosen,
            historicalLowDate = clean.lastOrNull { (it.second * 100).roundToInt() == (low * 100).roundToInt() }?.first,
        )
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

    /** The lowest price that held for at least [BuyAdviceConfig.MIN_LOW_DAYS] days. */
    internal fun reliableLow(prices: List<Double>): Double {
        val days = prices.groupingBy { (it * 100).roundToInt() }.eachCount()
        return days.filter { it.value >= C.MIN_LOW_DAYS }.keys.minOrNull()?.let { it / 100.0 } ?: prices.min()
    }

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
     * A more attractive price that the product really had in the past year: the middle half of the
     * daily prices that were clearly below today's. Null when it was hardly ever cheaper.
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
