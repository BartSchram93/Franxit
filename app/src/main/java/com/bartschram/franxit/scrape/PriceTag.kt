package com.bartschram.franxit.scrape

/**
 * The product's name on a photo of a shop's price tag, from the lines of text read on it (top to bottom).
 * Only the name: the prices, the article number, "Black Friday", the Recupel line and "Geldig t.e.m." are left out.
 * A tag like "SINGER - M3405 / Naaimachine / 289,00 / 229,00" gives "Singer M3405", which is then searched as if typed.
 */
object PriceTag {

    /** A line of text read on the photo, with where it stands ([top]) and how tall it is ([height]), in pixels. */
    data class Line(val text: String, val top: Int = 0, val height: Int = 0)

    /** Words of the parts of a tag that are no name: sales, taxes, validity, prices. */
    private val NOISE = setOf(
        "black", "friday", "cyber", "monday", "recupel", "recupelbijdrage", "bijdrage", "inbegrepen", "geldig", "tem",
        "promo", "promotie", "korting", "actie", "solden", "sale", "btw", "tva", "incl", "excl", "euro", "eur",
        "prijs", "prix", "valable", "jusqu", "écotaxe", "ecotaxe", "comprise", "inclus", "per", "stuk", "pièce",
    )
    private val ARTICLE = Regex("""(?<![\p{L}\p{N}])\d{6,}(?![\p{L}\p{N}])""")
    private val DATE = Regex("""\d{1,2}[./-]\d{1,2}[./-]\d{2,4}""")
    private val PRICE = Regex("""€|\d+\s*[.,]\s*\d{2}(?!\d)""")

    /** "SINGER - M3405": a brand, a dash, then the model. */
    private val BRAND_MODEL = Regex("""^\p{L}[\p{L}&'.]+(?:\s+\p{L}[\p{L}&'.]*)?\s+[-–]\s+\S""")

    /** A model code: letters and digits together ("RO3134EA", "HD9650/90"). */
    private val MODEL = Regex("""(?=[\p{L}\p{N}/.-]*\p{L})(?=[\p{L}\p{N}/.-]*\p{N})[\p{L}\p{N}][\p{L}\p{N}/.-]{2,}""")

    /** The product's name, as it would be typed ("Singer M3405"); null when no line looks like a name. */
    fun name(lines: List<Line>): String? {
        val candidates = lines.sortedBy { it.top }
            .map { it.copy(text = it.text.replace(ARTICLE, " ").replace(Regex("\\s+"), " ").trim()) }
            .filter { isName(it.text) }
        // The brand and model line first ("SINGER - M3405"), else a line with a model code, else the tallest text.
        val best = candidates.firstOrNull { BRAND_MODEL.containsMatchIn(it.text) }
            ?: candidates.firstOrNull { line -> line.text.split(' ').any { MODEL.matches(it) } }
            ?: candidates.maxByOrNull { it.height }
            ?: return null
        return tidy(best.text).takeIf { it.count(Char::isLetter) >= 3 }
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }

    /** A line that can be a name: enough letters, no price, date or word of the tag's other parts. */
    private fun isName(text: String): Boolean {
        if (text.count(Char::isLetter) < 3) return false
        if (PRICE.containsMatchIn(text) || DATE.containsMatchIn(text)) return false
        return words(text).none { it in NOISE }
    }

    /**
     * "DREAME - AQUA 10 ULTRA ROL BLANC" as typed: "Dreame Aqua 10 Ultra Rol Blanc". Only a line in capitals
     * is changed, and model codes stay as they are; a line written normally ("Philips Airfryer XXL") is kept.
     */
    private fun tidy(text: String): String {
        val letters = text.filter { it.isLetter() }
        val shouting = letters.isNotEmpty() && letters.count { it.isUpperCase() } >= letters.length * 0.8
        return text.replace(Regex("""\s+[-–]\s+"""), " ").split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            if (shouting && word.length >= 2 && word.all { it.isLetter() }) word.lowercase().replaceFirstChar { it.uppercase() } else word
        }
    }
}
