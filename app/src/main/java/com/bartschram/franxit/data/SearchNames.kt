package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.Matching

/**
 * The names a product is searched and matched by at Google Shopping and Shoparize, without anything Android,
 * so the cases that went wrong are tested (Pampers: the short name lost "138 Luierbroekjes", and a pack of 160
 * passed for it).
 */
internal object SearchNames {

    private val CODE = Regex("""[A-Za-z0-9][A-Za-z0-9./-]{3,}""")
    private val SPEC = Regex("""\d\s?(cm|inch|"|gb|mb|tb|mm|hz|mah|w|x)\b|^[\d.\s]+$""", RegexOption.IGNORE_CASE)

    /** What follows the first comma of the full name ("138 Luierbroekjes", "GM-2100YM-8AER"). */
    private fun afterComma(product: Product) = product.name.split(',').getOrNull(1)?.trim().orEmpty()

    /**
     * What results are held against: the short name, with a model code from after the comma ("Casio G-Shock" with
     * "GM-2100YM-8AER", so another colour's code is out), and with the pack the full name names ("138 Luierbroekjes").
     */
    fun match(product: Product): String {
        val extra = afterComma(product)
        val isCode = CODE.matches(extra) && extra.any(Char::isDigit) && extra.any(Char::isLetter)
        val base = if (isCode) "${product.displayName} $extra" else product.displayName
        return Matching.withPack(base, product.name)
    }

    /**
     * The short name with the first thing after the comma ("Bose QuietComfort Ultra Earbuds" with "(2e gen) Zwart"),
     * as a second wording to search for; null when that is no short variant but specs ("36,3 cm", "256 GB").
     */
    fun fullName(product: Product): String? {
        val extra = afterComma(product)
        if (extra.isEmpty() || extra.split(Regex("\\s+")).size > 4 || SPEC.containsMatchIn(extra)) return null
        return "${product.displayName} $extra"
    }

    /** The wordings to search Google Shopping with, in order: the one that worked last time ([saved]) first. */
    fun googleTerms(product: Product, saved: String?): List<String> =
        listOfNotNull(saved, product.displayName, fullName(product), "${product.displayName} kopen")

    /** What Shoparize is searched with: the words typed for a Google Shopping product ([query]), else the match name. */
    fun shoparize(product: Product, query: String?): String = query ?: match(product)
}
