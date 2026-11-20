package com.bartschram.franxit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.bartschram.franxit.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.PricePoint
import com.bartschram.franxit.data.SitePoint
import com.bartschram.franxit.ui.theme.ChartLine
import java.time.LocalDate
import kotlin.math.abs

/** A (day, price) series, the common shape of our own checks and the site's history. */
typealias Series = List<Pair<LocalDate, Double>>

@JvmName("pricePointSeries")
fun List<PricePoint>.series(): Series = map { it.day to it.price }

@JvmName("sitePointSeries")
fun List<SitePoint>.series(): Series = map { it.day to it.price }

/**
 * One price line: vergelijk.be's history up to our first own check, then our own checks.
 * (The site's history leaves Amazon out, so it's only used for the days before we have our own.)
 * The chart draws the site's part in grey and ours in blue.
 */
fun priceLine(points: List<PricePoint>, site: List<SitePoint>): Series {
    val firstOwn = points.firstOrNull()?.day ?: return site.series()
    return site.filter { it.day.isBefore(firstOwn) }.series() + points.series()
}

/** Maps days and prices into a drawing rectangle. Missed days keep their real width. */
private class Frame(
    series: List<Series>,
    /** The prices the scale must fit: only the ones that count, so a left-out odd price doesn't squash the rest. */
    prices: List<Double>,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    private val firstDay = series.flatten().minOf { it.first.toEpochDay() }
    private val span = (series.flatten().maxOf { it.first.toEpochDay() } - firstDay).coerceAtLeast(1)
    val min: Double
    val max: Double

    init {
        val lo = prices.min()
        val hi = prices.max()
        val pad = if (hi - lo < 0.01) lo * 0.05 else (hi - lo) * 0.15
        min = lo - pad
        max = hi + pad
    }

    fun x(day: LocalDate) = left + (right - left) * (day.toEpochDay() - firstDay) / span
    fun y(price: Double) = bottom - (bottom - top) * ((price - min) / (max - min)).toFloat()
}

@Composable
fun PriceChart(
    points: List<PricePoint>,
    site: List<SitePoint> = emptyList(),
    modifier: Modifier = Modifier,
    /** The prices that count (see BuyAdvisor.plausibleRange); others are data errors and leave a gap. Null: all count. */
    plausible: ClosedFloatingPointRange<Double>? = null,
) {
    val line = remember(points, site) { priceLine(points, site) }
    if (line.isEmpty()) return
    val all = listOf(line)
    // Which points count: the plausible prices, and always the last one (today's price). Another pack in the
    // site's history (€ 13,99 for 138 Pampers pants that cost € 49) is no price: the line stops there and a
    // gap follows, rather than a made-up flat line.
    val valid = remember(line, plausible) { BooleanArray(line.size) { i -> plausible == null || i == line.lastIndex || line[i].second in plausible } }
    val validPrices = remember(line, valid) { line.filterIndexed { i, _ -> valid[i] }.map { it.second } }

    /** The price on [day], as it held from the latest point on or before it; null in a gap. */
    fun priceOn(day: LocalDate): Double? {
        val i = line.indexOfLast { !it.first.isAfter(day) }
        return if (i >= 0 && valid[i]) line[i].second else null
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val dotFill = MaterialTheme.colorScheme.surfaceContainerLowest
    val siteColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    // The line's first own point; everything before it is vergelijk.be's history.
    val firstOwn = remember(line, points) { points.firstOrNull()?.let { p -> line.indexOfFirst { it.first == p.day } } ?: line.size }
    val days = remember(line) { line.map { it.first }.distinct() }
    var selected by remember(points, site) { mutableStateOf<Int?>(null) }
    // Day x positions from the last draw, used to map touches to the nearest day.
    val xs = remember(days) { FloatArray(days.size) }

    fun nearest(x: Float) = xs.indices.minBy { abs(xs[it] - x) }

    fun select(x: Float) {
        selected = nearest(x)
    }

    Column(modifier) {
        val day = days[selected ?: days.lastIndex]
        Text(
            text = buildString {
                append(day.long())
                priceOn(day)?.let { append("  ·  ${it.euro()}") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(days) {
                    // The point stays selected after you let go; a short tap on it again goes back to today.
                    detectTapGestures(onPress = {
                        val before = selected
                        val start = System.currentTimeMillis()
                        select(it.x)
                        val released = tryAwaitRelease()
                        if (released && before == selected && System.currentTimeMillis() - start < SHORT_TAP_MS) selected = null
                    })
                }
                .pointerInput(days) {
                    detectHorizontalDragGestures(
                        onDragStart = { select(it.x) },
                        onHorizontalDrag = { change, _ -> select(change.position.x); change.consume() },
                    )
                }
        ) {
            val probe = Frame(all, validPrices, 0f, 0f, 1f, 1f)
            val ticks = (0..3).map { probe.min + (probe.max - probe.min) * it / 3 }
            val tickLabels = ticks.map { measurer.measure(it.euroRounded(), labelStyle) }
            val left = tickLabels.maxOf { it.size.width } + 10.dp.toPx()
            val bottomLabelHeight = tickLabels.first().size.height + 8.dp.toPx()
            val frame = Frame(all, validPrices, left, 8.dp.toPx(), size.width - 6.dp.toPx(), size.height - bottomLabelHeight)

            /**
             * The step paths of [indices], one per run of points that count, with the run's first and last x.
             * A run's last price held until the next point, which doesn't count: the line goes to there and stops.
             */
            fun runs(indices: IntRange): List<Triple<Path, Float, Float>> {
                val out = mutableListOf<Triple<Path, Float, Float>>()
                var i = indices.first
                while (i <= indices.last) {
                    if (!valid[i]) { i++; continue }
                    var j = i
                    while (j + 1 <= indices.last && valid[j + 1]) j++
                    val endX = if (j + 1 <= indices.last) frame.x(line[j + 1].first) else frame.x(line[j].first)
                    val path = Path().apply {
                        moveTo(frame.x(line[i].first), frame.y(line[i].second))
                        for (k in i + 1..j) {
                            lineTo(frame.x(line[k].first), frame.y(line[k - 1].second))
                            lineTo(frame.x(line[k].first), frame.y(line[k].second))
                        }
                        if (j + 1 <= indices.last) lineTo(endX, frame.y(line[j].second))
                    }
                    out += Triple(path, frame.x(line[i].first), endX)
                    i = j + 1
                }
                return out
            }

            ticks.forEachIndexed { i, tick ->
                val y = frame.y(tick)
                drawLine(grid, Offset(frame.left, y), Offset(frame.right, y), 1.dp.toPx())
                val label = tickLabels[i]
                drawText(label, topLeft = Offset(left - 10.dp.toPx() - label.size.width, y - label.size.height / 2f))
            }
            days.forEachIndexed { i, d -> xs[i] = frame.x(d) }

            listOf(0, days.lastIndex / 2, days.lastIndex).distinct().forEach { i ->
                val label = measurer.measure(days[i].short(), labelStyle)
                val x = (xs[i] - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, frame.bottom + 6.dp.toPx()))
            }

            // Grey up to (and stepping into) our first check, so the two parts join up.
            val siteEnd = minOf(firstOwn, line.lastIndex)
            val ownStart = minOf(firstOwn, line.size)
            if (firstOwn > 0 && siteEnd >= 1) {
                runs(0..siteEnd).forEach { (path, _, _) -> drawPath(path, siteColor, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round)) }
            }
            when {
                line.size - ownStart >= 2 -> {
                    runs(ownStart..line.lastIndex).forEach { (path, startX, endX) ->
                        val fill = Path().apply {
                            addPath(path)
                            lineTo(endX, frame.bottom)
                            lineTo(startX, frame.bottom)
                            close()
                        }
                        drawPath(fill, Brush.verticalGradient(listOf(ChartLine.copy(alpha = 0.20f), ChartLine.copy(alpha = 0f)), frame.top, frame.bottom))
                        drawPath(path, ChartLine, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                line.size == 1 -> {
                    val y = frame.y(line[0].second)
                    drawLine(if (firstOwn == 0) ChartLine else siteColor, Offset(frame.left, y), Offset(frame.right, y), 2.dp.toPx())
                }
            }

            val dayShown = days[selected ?: days.lastIndex]
            // The selected day's dashed line stays until you let go of the point, also in a gap (there without a dot).
            if (selected != null) {
                val x = frame.x(dayShown)
                drawLine(
                    ChartLine.copy(alpha = 0.5f), Offset(x, frame.top), Offset(x, frame.bottom), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            val dotPrice = priceOn(dayShown)
            if (dotPrice != null) {
                val dot = Offset(frame.x(dayShown), frame.y(dotPrice))
                val ours = line.indexOfLast { !it.first.isAfter(dayShown) } >= firstOwn
                drawCircle(dotFill, 5.dp.toPx(), dot)
                drawCircle(if (ours) ChartLine else siteColor, 5.dp.toPx(), dot, style = Stroke(2.dp.toPx()))
            }
        }
        if (firstOwn in 1 until line.size) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(siteColor, "Earlier history")
                Legend(ChartLine, "Your checks")
            }
        }
    }
}

/** A tap shorter than this on the selected point lets go of it; a longer press keeps it. */
private const val SHORT_TAP_MS = 300L

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
