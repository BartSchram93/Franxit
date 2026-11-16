package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import java.text.Normalizer

/**
 * Which shops Google Shopping results may come from. Google lists any webshop, scam sites too
 * (Garantie2ans.com), so only shops with a reason to trust them count:
 * - every shop vergelijk.be lists (it screens its shops); the app learns these with each check,
 * - a fixed list of well-known shops that sell in Belgium.
 * Any other shop is left out, however cheap: an unknown shop with the lowest price is exactly
 * what a fake shop looks like.
 */
object TrustedShops {

    private const val PREFS = "trusted_shops"

    /** Well-known shops selling in Belgium, as [key]s. */
    private val KNOWN = setOf(
        // Electronics and general
        "bol", "amazon", "coolblue", "mediamarkt", "fnac", "vandenborre", "krefel", "cameranu", "dreamland", "x2o",
        "alternate", "megekko", "azerty", "centralpoint", "informatique", "exellent", "artencraft",
        "gamemania", "fun", "colruyt", "collectandgo", "carrefour", "makro", "action", "hema", "blokker", "kruidvat",
        "ikea", "decathlon", "brico", "gamma", "hubo", "intratuin", "aveve", "torfs", "zalando", "standaardboekhandel",
        "lego", "apple", "samsung", "sony", "lenovo", "dell", "hp", "lg", "philips", "bose", "jbl", "dyson",
        "proximus", "orange", "telenet", "base", "galaxus",
        // Photo and music
        "kamera-express", "kameraexpress", "fotokonijnenberg", "bax-shop", "baxshop", "baxmusic", "thomann", "gear4music",
        "woodbrass", "keymusic", "michenaud",
    ).map(::key).toSet()

    /**
     * A shop's name in a form both sources share: "MediaMarkt.be", "Mediamarkt" and "www.mediamarkt.be"
     * are all "mediamarkt"; "Bol." and "Bol.com" are "bol"; "Krëfel" is "krefel".
     */
    fun key(name: String): String {
        val plain = Normalizer.normalize(Shops.name(name).lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .removePrefix("www.")
            .replace(Regex("""\.(com\.be|co\.uk|be|com|nl|eu|fr|de|shop|net|store)$"""), "")
        return plain.replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * Words Google adds after a shop's name that don't make it another shop: "Bax Music BEFR",
     * "Amazon.com.be - Seller", "Zalando BE".
     */
    private val EXTRA_WORDS = setOf(
        "be", "befr", "benl", "nl", "fr", "de", "com", "eu", "seller", "marketplace", "partner", "official",
        "store", "shop", "webshop", "online", "belgie", "belgium", "belgique", "benelux",
    )

    /**
     * How a trusted shop is shown: as is when its whole name is known ("Coolblue.be"), else by the
     * known part ("Bax Music BEFR" is Bax-shop.be). Null for a shop that isn't trusted.
     * Pure, for tests: known, or among the [learned] keys.
     */
    fun name(shop: String, learned: Set<String>): String? {
        if (key(shop).let { it.isNotEmpty() && (it in KNOWN || it in learned) }) return shop
        val words = Normalizer.normalize(shop.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .removePrefix("www.").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for (n in 1 until words.size) {
            if (!words.drop(n).all { it in EXTRA_WORDS }) continue
            val known = words.take(n).joinToString(" ")
            if (key(known).let { it.isNotEmpty() && (it in KNOWN || it in learned) }) return Shops.name(known)
        }
        return null
    }

    fun isTrusted(shop: String, learned: Set<String>): Boolean = name(shop, learned) != null

    fun name(context: Context, shop: String): String? = name(shop, learned(context))

    fun isTrusted(context: Context, shop: String): Boolean = isTrusted(shop, learned(context))

    /** Whether two names are the same shop ("Bax Music BEFR" and "Bax-shop.be"). */
    fun same(a: String, b: String, learned: Set<String> = emptySet()): Boolean {
        fun canonical(s: String) = key(name(s, learned) ?: s)
        return canonical(a).let { it.isNotEmpty() && it == canonical(b) }
    }

    /** Remembers the shops vergelijk.be lists (and a page's own shop), so Google may show them too. */
    fun learn(context: Context, shops: Collection<String>) {
        val learned = learned(context)
        val new = shops.map(::key).filter { it.isNotEmpty() && it !in learned && it !in KNOWN }
        if (new.isEmpty()) return
        prefs(context).edit { putStringSet("keys", learned + new) }
    }

    private fun learned(context: Context): Set<String> = prefs(context).getStringSet("keys", null).orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
