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

    /**
     * [name] with the [brand] its page names in front, when the name doesn't hold it already: "999 Games Patchwork"
     * for "Patchwork", which on its own finds poufs, other editions and other games. Only to search with: shops often
     * leave the brand out of their titles, so results are still held against the name without it.
     */
    fun withBrand(name: String, brand: String?): String {
        val b = brand?.trim().orEmpty()
        if (b.isEmpty()) return name
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (name.lowercase().contains(b.lowercase()) || words(name).containsAll(words(b))) name else "$b $name"
    }

    /**
     * The wordings to search Google Shopping with, in order: the one that worked last time ([saved]) first, then
     * the name with its [brand] when that says more.
     */
    fun googleTerms(product: Product, saved: String?, brand: String? = null): List<String> =
        listOfNotNull(saved, withBrand(product.displayName, brand), product.displayName, fullName(product), "${product.displayName} kopen")
            .distinct()

    /**
     * What Shoparize is searched with: the words typed for a Google Shopping product ([query]), else the match
     * name with its [brand].
     */
    fun shoparize(product: Product, query: String?, brand: String? = null): String = query ?: withBrand(match(product), brand)
}
