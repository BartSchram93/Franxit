package com.bartschram.franxit.data

/**
 * Turns a shop's product title into a short name that says what the product is:
 * "Lenovo Laptop Lenovo Chromebook Duet 10,95" Kompanio 838 8 GB RAM 128 GB SSD Qwerty Spaans"
 * becomes "Lenovo Chromebook Duet 10,95"".
 *
 * Brand and model come first in almost every title, the specs after; so it keeps the words up to
 * the first spec, with a screen size as the last word kept (it often tells models apart).
 */
object Names {

    private const val MAX_WORDS = 6

    /** Where the name part of a title usually ends. */
    private val SEPARATORS = listOf(", ", " - ", " – ", " | ", " (", " / ", " + ")

    /** Words that say what kind of product it is, not which one. */
    private val CATEGORIES = setOf(
        "laptop", "notebook", "tablet", "smartphone", "gsm", "telefoon", "televisie", "tv", "computer", "pc",
    )

    /** A screen size: 13", 10,95", 14.5″, 27-inch. Kept, as the last word. */
    private val SCREEN = Regex("""\d+([.,]\d+)?("|″|”|''|-?inch)""", RegexOption.IGNORE_CASE)

    /** An amount with a unit (8GB, 128 GB, 2,4GHz, 36,3 cm, 33W, 10.000 mAh) or a spec word. */
    private val SPEC_UNIT = Regex("""\d+([.,]\d+)?(gb|tb|mb|ghz|mhz|hz|w|mah|cm|mm|kg|g|l|ml)""", RegexOption.IGNORE_CASE)
    private val UNITS = setOf("gb", "tb", "mb", "ghz", "mhz", "hz", "w", "mah", "cm", "mm", "kg", "g", "l", "ml", "inch")
    private val SPEC_WORDS = setOf(
        "ram", "ssd", "hdd", "emmc", "qwerty", "azerty", "qwertz", "wifi", "wi-fi", "lte", "4g", "5g",
        "incl.", "inclusief", "met", "with", "+",
        // Colours: they tell versions apart, not which product it is.
        "zwart", "wit", "grijs", "zilver", "goud", "blauw", "rood", "groen", "roze", "paars", "geel", "oranje", "bruin",
        "black", "white", "grey", "gray", "silver", "gold", "blue", "red", "green", "pink", "purple", "yellow",
        "orange", "brown", "beige",
    )

    /**
     * A product title from a shop link, when the page itself can't be read: the longest dashed
     * part of the path ("…/wisberg-wbttvr55dnfw.html" → "wisberg wbttvr55dnfw"). Null if none.
     */
    fun fromUrl(url: String): String? {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return null
        val slug = path.split('/').map { it.substringBeforeLast('.') }
            .filter { it.count { c -> c == '-' || c == '_' } >= 1 }
            .maxByOrNull { it.length } ?: return null
        val words = slug.split('-', '_').filter { it.isNotBlank() && !(it.all(Char::isDigit) && it.length >= 5) }
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }

    fun short(title: String): String {
        var name = title.trim()
        for (sep in SEPARATORS) name = name.substringBefore(sep)
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() }

        val kept = mutableListOf<String>()
        for ((i, word) in words.withIndex()) {
            val w = word.lowercase()
            if (SCREEN.matches(word)) { kept += word; break }
            val nextIsUnit = words.getOrNull(i + 1)?.lowercase() in UNITS
            if (SPEC_UNIT.matches(word) || w in SPEC_WORDS || (w.first().isDigit() && nextIsUnit)) break
            if (w in CATEGORIES) continue
            if (kept.any { it.equals(word, ignoreCase = true) }) continue
            kept += word
            if (kept.size == MAX_WORDS) break
        }
        // Too little left (e.g. only a brand): the plain title cut is better than a guess.
        return kept.takeIf { it.size >= 2 }?.joinToString(" ") ?: name.ifBlank { title.trim() }
    }
}
