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

    private val MULTIPLIER = Regex("""(?<![\w.,])(\d{1,2})\s?[x×](?!\w)""", RegexOption.IGNORE_CASE)
    private val SET_OF = Regex("""\b(?:set|pack|verpakking|doos)\s+(?:van|of|de)\s+(\d{1,2})\b|\b(\d{1,2})[\s-]?(?:pack|pak)\b""", RegexOption.IGNORE_CASE)
    private val COUNT = Regex(
        """(?<![\w.,])(\d{1,4})\s?-?(stuks|stuk|st|pcs|pieces|pièces|delig|delen|ragers|capsules|capsule|caps|tabletten|tablets|tabs|kauwtabletten|""" +
            """zakjes|sachets|rollen|wasbeurten|doekjes|billendoekjes|luiers|luierbroekjes|broekjes|vellen|pods|sticks)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether [title] is another pack than [wanted]: another multipack ("2x", "set van 6") or another number of
     * pieces ("60 stuks" isn't "100 stuks", "110 wasbeurten" isn't "200"). A count only counts when both name
     * one: a title without a count could be either.
     */
    internal fun otherPack(title: String, wanted: String): Boolean {
        if (multipliers(title) != multipliers(wanted)) return true
        val want = counts(wanted)
        val have = counts(title)
        return want.isNotEmpty() && have.isNotEmpty() && want.none { it in have }
    }

    /** The words of [text] that say which pack it is: "138 Luierbroekjes", "2x", "set van 6". */
    fun packPhrases(text: String): List<String> =
        (COUNT.findAll(text) + SET_OF.findAll(text) +
            MULTIPLIER.findAll(text).filter { (it.groupValues[1].toIntOrNull() ?: 0) in 2..12 })
            .map { it.value.trim() }.distinct().toList()

    /**
     * [short] with the pack its [full] name names and [short] leaves out: "Pampers Baby-Dry Pants Maat 6" with
     * "138 Luierbroekjes" from "Pampers Baby-Dry Pants Maat 6, 138 Luierbroekjes, 14-19kg Maandbox". Without it,
     * the pack of 160 looks like the same product.
     */
    fun withPack(short: String, full: String): String {
        val missing = packPhrases(full).filter { !short.contains(it, ignoreCase = true) }
        return (listOf(short.trim()) + missing).joinToString(" ")
    }

    /** How many pieces a text names ("60 Stuks", "6 ragers", "200 wasbeurten", "12-delig"). */
    fun counts(text: String): Set<Int> = COUNT.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    /**
     * The multipacks a text names: "2x Dash …" and "… 2 X 5kg" are both a 2. Only 2 to 12: a larger number
     * before an "x" is a size ("65 x 45 cm"), and "1x" is just the product.
     */
    fun multipliers(text: String): Set<Int> =
        (MULTIPLIER.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() } +
            SET_OF.findAll(text).mapNotNull { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() })
            .filter { it in 2..12 }.toSet()

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
        // Another pack or another number of pieces ("60 stuks" for "100 stuks") goes down like an accessory.
        val otherPack = if (otherPack(title, wanted)) 1.0 else 0.0
        return titleInWanted + wantedInTitle / 2 - accessory - otherPack
    }

    /**
     * Whether a search result is the product you're after: most of your words are in its title,
     * and it isn't an accessory (unless you're looking for one).
     */
    fun isSameProduct(title: String, wanted: String, lenient: Boolean = false): Boolean {
        // A screen size in the name ("14,3"", "10.95 inch") is no part of what shops call the product.
        val want = words(wanted.replace(SIZE, " "))
        if (want.isEmpty()) return false
        // A multipack is another product with another price than the single pack ("2x Dash … 2 x 5kg" against
        // "Dash … 5,5 kg"), also when the price check would let it pass: both must name the same packs.
        if (otherPack(title, wanted)) return false
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
        // A name with a colour ("… - Zwart"): a title without one can be any colour (the Duux Threesixty 2 "Smart
        // Heater" was the gray one, vergelijk.be's black one is only at bol.com). Every price has to be right.
        if (colours(wanted).isNotEmpty() && colours(title).isEmpty()) return false
        // Another edition of it ("Patchwork Express", "Patchwork Doodle", an expansion) is another product at
        // another price: €20.99 at Fnac for "Patchwork" was one.
        if (otherEdition(title, wanted)) return false
        // Specs ("8GB", "256gb", "6000mah") aren't model codes: shops add them to titles, they don't make another product.
        if (have.filter { isModelCode(it) && !isSpec(it) }.any { it !in wantCompact }) return false
        // Numbers in the name must be in the title too: "TCL Smartphone 60 SE Nxtpaper 5G" isn't the
        // "TCL Nxtpaper 14", though it shares two of its three words.
        if (want.filter { w -> w.all(Char::isDigit) }.any { it !in have }) return false
        // Without a model code, most of the words have to match.
        return wantCodes.isNotEmpty() || want.count { it in have }.toDouble() / want.size >= 0.6
    }

    /** Words that make another edition of a product: smaller, bigger, for children, an expansion. */
    private val EDITION = Regex(
        """\b(express|doodle|junior|kids|kinderversie|deluxe|uitbreiding|uitbreidingsset|expansion|extension|erweiterung|""" +
            """reiseditie|reisversie|reisspel|travel edition|big box|jubileumeditie|anniversary edition|starter ?set)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [title] names an edition [wanted] doesn't ("Patchwork Express" for "Patchwork"). */
    internal fun otherEdition(title: String, wanted: String): Boolean {
        val want = EDITION.findAll(wanted).map { it.value.lowercase() }.toSet()
        return EDITION.findAll(title).any { it.value.lowercase() !in want }
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

    /**
     * Whether a shop's [title] on Google's page for a product is clearly another product: Google groups related
     * products under one ("Nintendo Switch 2 (2026) + Pokémon Legends: Z-A" at Coolblue under the Switch 2 Zelda
     * Edition). Lenient, as a shop may write the product otherwise (bol.com's "HCKB-200B" for the KB200B): never
     * when it carries the name's model code; otherwise another when it shares little of the name (under 40% of its
     * words) and names two or more things the name doesn't.
     */
    fun isOtherProduct(title: String, wanted: String): Boolean {
        if (title.isBlank()) return false
        val want = words(wanted)
        if (want.filter(::isModelCode).any { it in compact(title) }) return false
        // Not by a "+" or an edition word: a shop's title adds a case and stylus to a tablet ("TCL NXTPAPER 14 + Flip
        // Case"), or writes the edition in English ("40th Anniversary Edition" for "40-jarig jubileum").
        val have = words(title)
        val shared = want.count { it in have }.toDouble() / want.size.coerceAtLeast(1)
        return shared < 0.4 && have.count { it !in want }.let { it >= 2 }
    }

    /** The words in a shop page's address ("…/nintendo-switch-2-2026-pokemon-legends-z-a/…"), when they read as a title. */
    fun titleFromLink(link: String): String? {
        val path = runCatching { java.net.URI(link).path }.getOrNull().orEmpty()
        val slug = path.split('/').filter { it.isNotBlank() }.maxByOrNull { part -> part.count { it == '-' } } ?: return null
        val words = slug.removeSuffix(".html").split('-').filter { it.isNotBlank() }
        return words.takeIf { list -> list.count { w -> w.any(Char::isLetter) } >= 3 }?.joinToString(" ")
    }

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
