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
    fun name(lines: List<Line>): String? = read(lines).name

    /**
     * What the photo says: the [name] (null when none), and whether it [clear]ly is a price tag's name: a brand and
     * model ("ROWENTA - RO3134EA") or a model code. Otherwise the photo is likely of the product itself, and the app
     * offers to recognise it online.
     */
    data class Reading(val name: String?, val clear: Boolean)

    fun read(lines: List<Line>): Reading {
        val candidates = lines.sortedBy { it.top }
            .map { it.copy(text = separateBrand(it.text.replace(ARTICLE, " ").replace(Regex("\\s+"), " ").trim())) }
            .filter { isName(it.text) }
        // The brand and model line first ("SINGER - M3405"), else a line with a model code, else the tallest text.
        val clear = candidates.firstOrNull { BRAND_MODEL.containsMatchIn(it.text) }
            ?: candidates.firstOrNull { line -> line.text.split(' ').any { MODEL.matches(it) } }
        val best = clear ?: candidates.maxByOrNull { it.height } ?: return Reading(null, false)
        val name = tidy(joinBrand(best.text)).takeIf { it.count(Char::isLetter) >= 3 }
        return Reading(name, clear != null && name != null)
    }

    /**
     * "DREAME-AQUA 10 ULTRA ROL BLANC": the gap around the dash after the brand not read. Put back for a line in
     * capitals (as tags write the brand and model), so the brand stays a word of its own ("Coca-Cola" is kept).
     */
    internal fun separateBrand(text: String): String {
        if (!isShouting(text)) return text
        return text.replaceFirst(Regex("""^(\p{L}{2,})[-–](?=\p{L}{2})"""), "$1 - ")
    }

    private fun isShouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.count { it.isUpperCase() } >= letters.length * 0.8
    }

    /**
     * A brand the photo split in two ("ROWEN TA - RO3134EA", the letters of a tag's narrow font read with a gap):
     * a piece of one or two letters is joined to the other word. Only before the dash, where the brand is one name.
     */
    internal fun joinBrand(text: String): String {
        val dash = Regex("""\s+[-–]\s+""").find(text) ?: return text
        val brand = text.substring(0, dash.range.first).split(' ').filter { it.isNotEmpty() }
        if (brand.size != 2 || brand.any { word -> !word.all { it.isLetter() } }) return text
        val (a, b) = brand
        if (minOf(a.length, b.length) > 2 || a.length + b.length < 5) return text
        return a + b + text.substring(dash.range.first)
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
        val shouting = isShouting(text)
        return text.replace(Regex("""\s+[-–]\s+"""), " ").split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            if (shouting && word.length >= 2 && word.all { it.isLetter() }) word.lowercase().replaceFirstChar { it.uppercase() } else word
        }
    }
}
