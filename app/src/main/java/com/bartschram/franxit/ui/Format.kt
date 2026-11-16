package com.bartschram.franxit.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val locale = Locale.forLanguageTag("nl-BE")

fun Double.euro(): String = NumberFormat.getCurrencyInstance(locale).format(this)

fun Double.euroRounded(): String = NumberFormat.getCurrencyInstance(locale)
    .apply { maximumFractionDigits = 0; minimumFractionDigits = 0 }
    .format(this)

/** "−€ 12,40" / "+€ 3,00" using a real minus sign. */
fun Double.signedEuro(): String = when {
    this > 0.005 -> "+" + euro()
    this < -0.005 -> "−" + abs(this).euro()
    else -> 0.0.euro()
}

fun percent(from: Double, to: Double): String {
    val pct = (to - from) / from * 100
    val sign = if (pct > 0.05) "+" else if (pct < -0.05) "−" else ""
    return sign + String.format(locale, "%.1f%%", abs(pct))
}

// Month names follow the app's language.
private fun shortDate() = DateTimeFormatter.ofPattern("d MMM", L10n.locale)
private fun longDate() = DateTimeFormatter.ofPattern("d MMM yyyy", L10n.locale)
private val clock = DateTimeFormatter.ofPattern("HH:mm")

fun LocalDate.short(): String =
    if (year == LocalDate.now().year) format(shortDate()) else format(longDate())

fun LocalDate.long(): String = format(longDate())

/** "3 hours ago", "2 days ago". */
fun Long.ageLabel(now: Long = System.currentTimeMillis()): String {
    val hours = ((now - this) / 3_600_000).coerceAtLeast(0)
    return when {
        hours < 1 -> t("just now")
        hours == 1L -> t("1 hour ago")
        hours < 48 -> t("%d hours ago", hours)
        else -> t("%d days ago", hours / 24)
    }
}

fun Long.checkedLabel(): String {
    val time = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    val day = time.toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> t("today %s", time.format(clock))
        today.minusDays(1) -> t("yesterday %s", time.format(clock))
        else -> "${day.short()} ${time.format(clock)}"
    }
}
