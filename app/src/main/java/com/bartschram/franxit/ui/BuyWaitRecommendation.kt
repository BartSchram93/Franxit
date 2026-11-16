package com.bartschram.franxit.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.BuyAdvice
import com.bartschram.franxit.data.Confidence
import com.bartschram.franxit.data.Reason
import com.bartschram.franxit.data.Verdict
import com.bartschram.franxit.ui.theme.ChartLine
import com.bartschram.franxit.ui.theme.PriceDown
import com.bartschram.franxit.ui.theme.PriceUp
import com.bartschram.franxit.ui.theme.Warning
import kotlin.math.roundToInt

/**
 * "Buy or wait" at the bottom of the detail screen. It only renders a [BuyAdvice] (see
 * [com.bartschram.franxit.data.BuyAdvisor]); all numbers come from the price history.
 * [onSetAlert] sets a price alert at the suggested target; null hides that button.
 */
@Composable
fun BuyWaitRecommendation(
    advice: BuyAdvice,
    currentAlert: Double?,
    onSetAlert: ((Double) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // The status is the title: "Buy or wait" above it would say the same twice.
            RecommendationHeader(advice)
            advice.score?.let {
                Spacer(Modifier.height(12.dp))
                BuyScore(it, color(advice.verdict))
            }
            Spacer(Modifier.height(12.dp))
            RecommendationSummary(advice)
            if (advice.reasons.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                RecommendationReasons(advice.reasons)
            }
            advice.target?.let {
                Spacer(Modifier.height(12.dp))
                SuggestedTargetPrice(it, currentAlert, onSetAlert)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Based on past prices${advice.confidence?.let { " · ${confidenceLabel(it)}" } ?: ""}.\nNot a forecast.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A coloured dot, the verdict and what it means. */
@Composable
fun RecommendationHeader(advice: BuyAdvice) {
    val color = color(advice.verdict)
    // The dot lines up with the status word, not the middle of both lines.
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title(advice.verdict), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
            Text(subtitle(advice.verdict), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "91 / 100" with a bar: how attractive buying now is compared with the history (not a probability). */
@Composable
fun BuyScore(score: Int, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$score", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(" / 100", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        // A plain bar: Material's progress bar adds an end marker that reads like a second value.
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)) {
            Box(Modifier.fillMaxWidth(score / 100f).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

/** One plain sentence from the metrics: "€ 799 is 14% below the typical price and € 12 above the lowest price." */
@Composable
fun RecommendationSummary(advice: BuyAdvice) {
    Text(summary(advice), style = MaterialTheme.typography.bodyMedium)
}

/** ✓ for what speaks for buying now, ⚠ against, each from one metric. */
@Composable
fun RecommendationReasons(reasons: List<Reason>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reasons.forEach { reason ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (reason.positive) "✓" else "⚠",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (reason.positive) PriceDown else Warning,
                    modifier = Modifier.width(22.dp),
                )
                Text(text(reason), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * When waiting: a price the product really had, and a button to put an alert on it. When your
 * alert is already at or below that price, it says so instead (the button would only raise it).
 */
@Composable
fun SuggestedTargetPrice(target: ClosedFloatingPointRange<Double>, currentAlert: Double?, onSetAlert: ((Double) -> Unit)?) {
    val range = if (target.start == target.endInclusive) target.start.euroRounded()
    else "${target.start.euroRounded()}–${target.endInclusive.euroRounded()}"
    Column {
        Text("A more attractive price would be: $range", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (currentAlert != null && currentAlert <= target.endInclusive + 0.005) {
            Row(verticalAlignment = Alignment.Top) {
                Text("✓", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = PriceDown, modifier = Modifier.width(22.dp))
                Text(
                    if (currentAlert >= target.start - 0.005) "Your alert (${currentAlert.euroRounded()}) is already in this range"
                    else "Your alert (${currentAlert.euroRounded()}) is already below this range",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            onSetAlert?.let {
                OutlinedButton(
                    onClick = { it(target.endInclusive) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Default.Notifications, null, Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set alert at ${target.endInclusive.euroRounded()}")
                }
            }
        }
    }
}

/** "● Wait · 50" under the price at the top; a tap scrolls down to the full advice. */
@Composable
fun VerdictHint(advice: BuyAdvice, onClick: () -> Unit) {
    val color = color(advice.verdict)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            title(advice.verdict),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Text(" ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun verdictColor(verdict: Verdict): Color = color(verdict)

/** The verdict in one word or two, for the Overview. */
internal fun shortTitle(verdict: Verdict) = when (verdict) {
    Verdict.BUY_NOW -> "Buy now"
    Verdict.CONSIDER_BUYING -> "Consider"
    Verdict.WAIT -> "Wait"
    Verdict.WAIT_EXPENSIVE -> "Expensive"
    Verdict.INSUFFICIENT_DATA -> "No advice yet"
}

private fun color(verdict: Verdict): Color = when (verdict) {
    Verdict.BUY_NOW -> PriceDown
    Verdict.CONSIDER_BUYING -> ChartLine
    Verdict.WAIT -> Warning
    Verdict.WAIT_EXPENSIVE -> PriceUp
    Verdict.INSUFFICIENT_DATA -> Color(0xFF9A9A9A)
}

private fun title(verdict: Verdict) = when (verdict) {
    Verdict.BUY_NOW -> "Buy now"
    Verdict.CONSIDER_BUYING -> "Consider buying"
    Verdict.WAIT -> "Wait"
    Verdict.WAIT_EXPENSIVE -> "Wait — currently expensive"
    Verdict.INSUFFICIENT_DATA -> "Insufficient data"
}

private fun subtitle(verdict: Verdict) = when (verdict) {
    Verdict.BUY_NOW -> "The current price is historically very attractive"
    Verdict.CONSIDER_BUYING -> "The current price is good, but not exceptional"
    Verdict.WAIT -> "The current price isn't particularly attractive"
    Verdict.WAIT_EXPENSIVE -> "The current price is high compared with its history"
    Verdict.INSUFFICIENT_DATA -> "Not enough reliable price history to judge"
}

private fun confidenceLabel(confidence: Confidence) = when (confidence) {
    Confidence.HIGH -> "plenty of history"
    Confidence.MEDIUM -> "a few months of history"
    Confidence.LOW -> "little history"
}

private fun pct(fraction: Double) = "${(fraction * 100).roundToInt()}%"

internal fun summary(advice: BuyAdvice): String {
    val current = advice.currentPrice
    val median = advice.medianPrice
    val low = advice.historicalLow
    val vs = advice.vsMedian
    val above = advice.aboveLow
    if (advice.verdict == Verdict.INSUFFICIENT_DATA || current == null || median == null || low == null || vs == null || above == null) {
        return "There isn't enough price history yet to compare today's price with. It builds up with every daily check."
    }
    val typical = when {
        vs <= -0.03 -> "${current.euro()} is ${pct(-vs)} below the typical price"
        vs >= 0.03 -> "${current.euro()} is ${pct(vs)} above the typical price"
        else -> "${current.euro()} is about the typical price"
    }
    val lowPart = when {
        advice.reasons.any { it is Reason.Stable } -> ", which has hardly changed in the past year"
        above < 0.005 -> " and the lowest price recorded"
        else -> " and ${(current - low).euro()} above the lowest recorded price"
    }
    return "$typical$lowPart."
}

internal fun text(reason: Reason): String = when (reason) {
    is Reason.BelowTypical -> "${pct(reason.percent)} below the typical price (${reason.typical.euroRounded()})"
    is Reason.AboveTypical -> "${pct(reason.percent)} above the typical price (${reason.typical.euroRounded()})"
    is Reason.NearLow -> if (reason.percent < 0.005) "At the lowest recorded price"
    else "Within ${pct(reason.percent).let { if (it == "0%") "1%" else it }} of the lowest price (${reason.low.euro()})"
    is Reason.FarAboveLow -> "${pct(reason.percent)} above the lowest price (${reason.low.euro()})"
    is Reason.LowestInMonths -> "Lowest price in the last ${reason.months} months"
    is Reason.DroppedRecently -> "Dropped ${pct(reason.percent)} compared with the last ${reason.days} days"
    is Reason.RoseRecently -> "Up ${pct(reason.percent)} compared with the last ${reason.days} days"
    is Reason.CheaperRecently -> "Was ${reason.price.euro()} (${pct(reason.percent)} cheaper) within the last ${reason.days} days"
    is Reason.Stable -> "The price has been stable at about ${reason.typical.euroRounded()}"
    is Reason.HistoryLength -> "Based on ${reason.months} month${if (reason.months == 1) "" else "s"} of price history"
}
