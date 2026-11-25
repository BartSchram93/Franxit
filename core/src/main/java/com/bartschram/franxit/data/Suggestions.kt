package com.bartschram.franxit.data

import java.time.LocalDate
import kotlin.math.floor

/** One-tap price alert amounts based on how the price moved over the past year. */
object Suggestions {

    /** [explanation] is shown in the price alert dialog once the suggestion is picked. */
    data class Suggestion(val label: String, val price: Double, val explanation: String = "")

    fun forProduct(product: Product, today: LocalDate = LocalDate.now()): List<Suggestion> {
        val current = product.latest?.price ?: return emptyList()
        val from = today.minusDays(365)
        // One price per day: our own check where we have one, otherwise vergelijk.be's history.
        val byDay = product.siteHistory.filter { !it.day.isBefore(from) }.associate { it.day to it.price } +
            product.history.filter { !it.day.isBefore(from) }.associate { it.day to it.price }
        val prices = byDay.values.sorted()

        val out = mutableListOf<Suggestion>()
        fun add(label: String, price: Double, explanation: String) {
            val rounded = floor(price)
            // Only amounts below today's price, and not almost the same as one already offered.
            if (rounded < current - 0.5 && out.none { kotlin.math.abs(it.price - rounded) < 2 }) {
                out += Suggestion(label, rounded, explanation)
            }
        }
        if (prices.size >= 14) {
            // Cheaper than on 4 out of 5 days this year: good, but it does happen.
            add("Good deal", prices[(prices.size * 0.2).toInt()], "Cheaper than on 4 out of 5 days this past year.")
            add("Year low", prices.first(), "The lowest price of the past year.")
        }
        add("10% off", current * 0.9, "10% below today's price.")
        return out.sortedByDescending { it.price }
    }
}
