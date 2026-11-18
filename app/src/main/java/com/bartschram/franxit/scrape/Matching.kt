package com.bartschram.franxit.scrape

/**
 * Ranks vergelijk.be search hits against what you're looking for: a typed query, or the product
 * name from another shop's page ("Sony WF-1000XM6 Zwart" from Coolblue). Accessories ("Hoes
 * geschikt voor Sony WF-1000XM6") go to the bottom unless you were looking for one.
 */
object Matching {

    private val ACCESSORY = listOf(
        "geschikt voor", "compatible", "suitable for", "hoes", "hoesje", "case", "cover", "sleeve", "screenprotector",
        "screen protector", "beschermfolie", "beschermglas", "tempered glass", "oordopjes voor", "eartips", "ear tips",
        "oplaadkabel", "kabel voor", "adapter voor", "houder", "standaard voor", "skin", "sticker",
    )

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.toSet()

    private fun isAccessory(text: String): Boolean = text.lowercase().let { t -> ACCESSORY.any { it in t } }

    /**
     * Whether a page still shows the product you track: titles change a little over time ("Zwart"
     * added), but share most words. A different product (Pokémon cards for a piano bench) doesn't.
     */
    fun isStillTheSame(tracked: String, page: String): Boolean {
        val a = words(tracked)
        val b = words(page)
        if (a.isEmpty() || b.isEmpty()) return true
        return a.count { it in b }.toDouble() / minOf(a.size, b.size) >= 0.5
    }

    /** How well [title] fits [wanted]: mostly how many of its words [wanted] has, then the reverse. */
    fun score(title: String, wanted: String): Double {
        val have = words(title)
        val want = words(wanted)
        if (have.isEmpty() || want.isEmpty()) return 0.0
        val titleInWanted = have.count { it in want }.toDouble() / have.size
        val wantedInTitle = want.count { it in have }.toDouble() / want.size
        val accessory = if (isAccessory(title) && !isAccessory(wanted)) 1.0 else 0.0
        return titleInWanted + wantedInTitle / 2 - accessory
    }

    /**
     * Whether a search result is the product you're after: most of your words are in its title,
     * and it isn't an accessory (unless you're looking for one).
     */
    fun isSameProduct(title: String, wanted: String, lenient: Boolean = false): Boolean {
        // A screen size in the name ("14,3"", "10.95 inch") is no part of what shops call the product.
        val want = words(wanted.replace(SIZE, " "))
        if (want.isEmpty()) return false
        // A big, reliable shop often sells the product with a case or stylus ("TCL NXTPAPER 14 - 8GB - Grijs + Flip Case
        // & Stylus"): fine. A loose accessory is kept out by the price check (50-200% of the usual price), not by its title.
        if (!lenient) {
            if (isAccessory(title) && !isAccessory(wanted)) return false
            if (BUNDLE.containsMatchIn(title) && !BUNDLE.containsMatchIn(wanted)) return false
        }
        val have = words(title)
        // The brand (first word) must be there.
        val brand = wanted.trim().split(Regex("\\s+")).first().lowercase().filter { it.isLetterOrDigit() }
        if (brand.isNotEmpty() && brand !in have) return false
        // Model codes (letters and digits: kb200b, 1000xm6) must all match, and the title may not
        // name a different one (kb210b): that's a related product, not this one.
        // Compared without spaces and dashes, so "KB 200B" and "KB200B" are the same code.
        val wantCodes = want.filter(::isModelCode)
        val haveCompact = compact(title)
        val wantCompact = compact(wanted)
        if (!wantCodes.all { it in haveCompact }) return false
        // The same product in another colour (a black watch for the silver one) is not this product.
        if (otherColour(title, wanted)) return false
        // Specs ("8GB", "256gb", "6000mah") aren't model codes: shops add them to titles, they don't make another product.
        if (have.filter { isModelCode(it) && !isSpec(it) }.any { it !in wantCompact }) return false
        // Numbers in the name must be in the title too: "TCL Smartphone 60 SE Nxtpaper 5G" isn't the
        // "TCL Nxtpaper 14", though it shares two of its three words.
        if (want.filter { w -> w.all(Char::isDigit) }.any { it !in have }) return false
        // Without a model code, most of the words have to match.
        return wantCodes.isNotEmpty() || want.count { it in have }.toDouble() / want.size >= 0.6
    }

    /** Colour words in Dutch, English and French, each to one colour. */
    private val COLOURS = mapOf(
        "zwart" to "black", "black" to "black", "noir" to "black",
        "wit" to "white", "white" to "white", "blanc" to "white",
        "zilver" to "silver", "zilverkleurig" to "silver", "silver" to "silver", "argent" to "silver",
        "grijs" to "grey", "grey" to "grey", "gray" to "grey", "gris" to "grey",
        "blauw" to "blue", "blue" to "blue", "bleu" to "blue",
        "rood" to "red", "red" to "red", "rouge" to "red",
        "groen" to "green", "green" to "green", "vert" to "green",
        "goud" to "gold", "goudkleurig" to "gold", "gold" to "gold",
        "roze" to "pink", "pink" to "pink", "rose" to "pink",
        "paars" to "purple", "purple" to "purple", "violet" to "purple",
        "bruin" to "brown", "brown" to "brown", "brun" to "brown",
        "oranje" to "orange", "orange" to "orange",
        "geel" to "yellow", "yellow" to "yellow", "jaune" to "yellow",
    )

    private fun colours(text: String): Set<String> = words(text).mapNotNull { COLOURS[it] }.toSet()

    /** True when both name a colour and have none in common: the same model, another colour. */
    internal fun otherColour(title: String, wanted: String): Boolean {
        val w = colours(wanted)
        val t = colours(title)
        return w.isNotEmpty() && t.isNotEmpty() && w.none { it in t }
    }

    /**
     * For a shop Google groups under the product (titles aren't matched there): true when its title is
     * clearly another variant: another colour, or (when your name has a model code) a title without that code.
     */
    fun isOtherVariant(title: String, wanted: String): Boolean {
        if (otherColour(title, wanted)) return true
        val wantCodes = words(wanted.replace(SIZE, " ")).filter(::isModelCode)
        // With a model code in the name, a shop's title has to carry it too: a watch is told from its sibling by that code.
        return wantCodes.isNotEmpty() && !wantCodes.all { it in compact(title) }
    }

    private val SIZE = Regex("""\d+([.,]\d+)?\s?("|″|”|''|-?inch\b|-?cm\b)|\d+[.,]\d+""", RegexOption.IGNORE_CASE)

    private val BUNDLE = Regex("""\s\+\s|\bbundel\b|\bbundle\b|\bset met\b|\bincl\.?\b|\binclusief\b""", RegexOption.IGNORE_CASE)

    private fun compact(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private val SPEC = Regex("""\d+(gb|tb|mb|mah|hz|ghz|mm|cm|inch|mp|kg|ml|w|v|wh)""")

    private fun isSpec(word: String) = SPEC.matches(word)

    private fun isModelCode(word: String) = word.length >= 3 && word.any(Char::isDigit) && word.any(Char::isLetter)

    /** [hits] best match first; ties keep vergelijk.be's order. */
    fun rank(hits: List<VergelijkSearch.Hit>, wanted: String): List<VergelijkSearch.Hit> =
        hits.sortedByDescending { score(it.title, wanted) }

    /**
     * The one hit that clearly is [wanted]: every word of its title appears in [wanted]
     * (vergelijk.be titles are short: "WF-1000XM6 Zwart"), it isn't an accessory, and no other
     * hit fits as well. Null when that's not clear-cut.
     */
    fun clearMatch(hits: List<VergelijkSearch.Hit>, wanted: String): VergelijkSearch.Hit? {
        val want = words(wanted)
        val full = hits.filter { hit ->
            val have = words(hit.title)
            have.isNotEmpty() && have.all { it in want } && !(isAccessory(hit.title) && !isAccessory(wanted))
        }
        // Several hits fully inside the name (e.g. "WF-1000XM6" and "WF-1000XM6 Zwart"): the one
        // with the most words is the most specific, if only one has that many.
        val most = full.maxOfOrNull { words(it.title).size } ?: return null
        return full.filter { words(it.title).size == most }.singleOrNull()
    }
}
