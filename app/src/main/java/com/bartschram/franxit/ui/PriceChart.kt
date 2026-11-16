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

/** The price on [day]: the latest point on or before it, as prices hold until the next check. */
private fun Series.at(day: LocalDate): Double? = lastOrNull { !it.first.isAfter(day) }?.second

/** Maps days and prices into a drawing rectangle. Missed days keep their real width. */
private class Frame(
    series: List<Series>,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    private val firstDay = series.flatten().minOf { it.first.toEpochDay() }
    private val span = (series.flatten().maxOf { it.first.toEpochDay() } - firstDay).coerceAtLeast(1)
    val min: Double
    val max: Double

    init {
        val lo = series.flatten().minOf { it.second }
        val hi = series.flatten().maxOf { it.second }
        val pad = if (hi - lo < 0.01) lo * 0.05 else (hi - lo) * 0.15
        min = lo - pad
        max = hi + pad
    }

    fun x(day: LocalDate) = left + (right - left) * (day.toEpochDay() - firstDay) / span
    fun y(price: Double) = bottom - (bottom - top) * ((price - min) / (max - min)).toFloat()

    /** Price is held until the next check, so lines are drawn as steps (like the vergelijk.be chart). */
    fun stepPath(series: Series): Path = Path().apply {
        moveTo(x(series[0].first), y(series[0].second))
        for (i in 1 until series.size) {
            lineTo(x(series[i].first), y(series[i - 1].second))
            lineTo(x(series[i].first), y(series[i].second))
        }
    }
}

@Composable
fun PriceChart(points: List<PricePoint>, site: List<SitePoint> = emptyList(), modifier: Modifier = Modifier) {
    val line = remember(points, site) { priceLine(points, site) }
    if (line.isEmpty()) return
    val all = listOf(line)

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

    fun select(x: Float) {
        selected = xs.indices.minBy { abs(xs[it] - x) }
    }

    Column(modifier) {
        val day = days[selected ?: days.lastIndex]
        Text(
            text = buildString {
                append(day.long())
                line.at(day)?.let { append("  ·  ${it.euro()}") }
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
                    detectTapGestures(onPress = { select(it.x); tryAwaitRelease(); selected = null })
                }
                .pointerInput(days) {
                    detectHorizontalDragGestures(
                        onDragStart = { select(it.x) },
                        onDragEnd = { selected = null },
                        onDragCancel = { selected = null },
                        onHorizontalDrag = { change, _ -> select(change.position.x); change.consume() },
                    )
                }
        ) {
            val probe = Frame(all, 0f, 0f, 1f, 1f)
            val ticks = (0..3).map { probe.min + (probe.max - probe.min) * it / 3 }
            val tickLabels = ticks.map { measurer.measure(it.euroRounded(), labelStyle) }
            val left = tickLabels.maxOf { it.size.width } + 10.dp.toPx()
            val bottomLabelHeight = tickLabels.first().size.height + 8.dp.toPx()
            val frame = Frame(all, left, 8.dp.toPx(), size.width - 6.dp.toPx(), size.height - bottomLabelHeight)

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
            val sitePart = line.subList(0, minOf(firstOwn + 1, line.size))
            val ownPart = line.subList(minOf(firstOwn, line.size), line.size)
            if (firstOwn > 0 && sitePart.size >= 2) {
                drawPath(frame.stepPath(sitePart), siteColor, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            when {
                ownPart.size >= 2 -> {
                    val path = frame.stepPath(ownPart)
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(frame.x(ownPart.last().first), frame.bottom)
                        lineTo(frame.x(ownPart.first().first), frame.bottom)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(ChartLine.copy(alpha = 0.20f), ChartLine.copy(alpha = 0f)), frame.top, frame.bottom))
                    drawPath(path, ChartLine, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                line.size == 1 -> {
                    val y = frame.y(line[0].second)
                    drawLine(if (firstOwn == 0) ChartLine else siteColor, Offset(frame.left, y), Offset(frame.right, y), 2.dp.toPx())
                }
            }

            val dayShown = days[selected ?: days.lastIndex]
            val dotPrice = line.at(dayShown)
            if (dotPrice != null) {
                val dot = Offset(frame.x(dayShown), frame.y(dotPrice))
                if (selected != null) {
                    drawLine(
                        ChartLine.copy(alpha = 0.5f), Offset(dot.x, frame.top), Offset(dot.x, frame.bottom), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }
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

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
