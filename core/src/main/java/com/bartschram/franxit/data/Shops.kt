package com.bartschram.franxit.data

/**
 * How a shop is named in the app: as its website ("Bol.com", "Coolblue.be") instead of the
 * label vergelijk.be uses ("Bol."). Only for showing; matching (discounts, shop links) uses
 * the original name.
 */
object Shops {

    /** vergelijk.be's labels for shops whose website isn't in their name. */
    private val SITES = mapOf(
        "bol" to "Bol.com",
        "bol." to "Bol.com",
        "amazon" to "Amazon.com.be",
        // There is no amazon.be: Belgium's Amazon is amazon.com.be, whatever a source calls it.
        "amazon.be" to "Amazon.com.be",
        "mediamarkt" to "MediaMarkt.be",
        "fnac" to "Fnac.be",
        "cameranu" to "Cameranu.be",
        "vanden borre" to "VandenBorre.be",
        "krefel" to "Krefel.be",
        "coolblue" to "Coolblue.be",
        "dreamland" to "Dreamland.be",
        "x2o" to "X2O.be",
        "bax music" to "Bax-shop.be",
        "bax-shop" to "Bax-shop.be",
        "gear4music" to "Gear4music.be",
    )

    private val DOMAIN = Regex("""^[\w-]+(\.[\w-]+)*\.(be|com|nl|eu|de|fr|net|shop)$""", RegexOption.IGNORE_CASE)

    /** Sellers on a shop's marketplace, shown under the shop's own site. */
    private val PARTNERS = mapOf(
        "bol plaza" to "Bol.com",
        "amazon marketplace" to "Amazon.com.be",
        "fnac marketplace" to "Fnac.be",
    )

    /**
     * [partners]: say "partner" for marketplace sellers ("Bol.com partner"), where it matters which
     * one it is, as when picking the shop a discount is for. Elsewhere they're just "Bol.com".
     */
    fun name(raw: String, partners: Boolean = false): String {
        val trimmed = raw.trim().removePrefix("www.")
        // Without accents: Google Shopping writes "Krëfel".
        val key = java.text.Normalizer.normalize(trimmed.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        SITES[key]?.let { return it }
        PARTNERS[key]?.let { return if (partners) "$it partner" else it }
        // Already a website ("coolblue.be", "Techinn.com"): just a capital first letter.
        if (DOMAIN.matches(trimmed)) return trimmed.replaceFirstChar { it.uppercase() }
        // A name in small letters only ("nintendo", as Google Shopping writes some shops): with a capital.
        if (trimmed.isNotEmpty() && trimmed.none { it.isUpperCase() }) return trimmed.replaceFirstChar { it.uppercase() }
        return trimmed
    }
}
