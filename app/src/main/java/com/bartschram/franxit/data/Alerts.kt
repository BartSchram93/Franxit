package com.bartschram.franxit.data

import java.time.LocalDate

object Alerts {

    private const val CENT = 0.005

    /**
     * Decides whether today's [point] for [product] (whose history doesn't include today yet)
     * deserves a notification. Only the most important reason is returned.
     */
    fun decide(product: Product, point: PricePoint, today: LocalDate = point.day): Alert? {
        val ownBefore = product.history.filter { it.day < today }
        val siteBefore = product.siteHistory.filter { it.day < today }
        val previous = ownBefore.lastOrNull()
        val target = product.targetPrice

        // Target: only when crossing it, so it fires once instead of every day below target.
        if (target != null && point.price <= target + CENT && previous != null && previous.price > target + CENT) {
            return Alert(product, AlertKind.TARGET, previous.price, point)
        }
        // Lowest seen: below every earlier price we know, ours and the site's history.
        val lowestBefore = (ownBefore.map { it.price } + siteBefore.map { it.price }).minOrNull()
        if (lowestBefore != null && point.price < lowestBefore - CENT) {
            return Alert(product, AlertKind.LOWEST, lowestBefore, point)
        }
        if (previous != null && point.price < previous.price - CENT) {
            return Alert(product, AlertKind.DROP, previous.price, point)
        }
        return null
    }
}
