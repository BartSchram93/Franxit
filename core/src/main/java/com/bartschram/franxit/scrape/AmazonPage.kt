package com.bartschram.franxit.scrape

/**
 * An Amazon product page, which has no product data the app reads (no schema.org Product): its price and stock from
 * the places Amazon puts them for the product shown (not the other versions on the page, such as the pink Selphy on
 * the black one's page). Null when none of them is there (a check page, another layout).
 */
object AmazonPage {

    private val TWISTER = Regex("""id=["']twister-plus-price-data-price["'][^>]*value=["']([0-9.]+)["']""")
    private val PRICE_AMOUNT = Regex(""""priceAmount"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    // The price block of the product shown, on the desktop and the phone layout (a phone got the phone's:
    // "no price readable" on the TCL NXTPAPER 14 at € 360).
    private const val PRICE_BLOCKS = "corePrice_feature_div|corePriceDisplay_desktop_feature_div|corePrice_desktop|" +
        "corePriceDisplay_mobile_feature_div|corePrice_mobile_feature_div|apex_desktop|apex_mobile|apex_offerDisplay_mobile"
    private val CORE_PRICE = Regex("""id=["'](?:$PRICE_BLOCKS)["'][\s\S]{0,3000}?class=["']a-offscreen["']>\s*([^<]{1,40})<""")
    private val WHOLE_PRICE = Regex("""id=["'](?:$PRICE_BLOCKS)["'][\s\S]{0,3000}?class=["']a-price-whole["']>\s*([0-9][0-9.,]*)[\s\S]{0,200}?class=["']a-price-fraction["']>\s*([0-9]{2})""")
    private val AVAILABILITY = Regex("""id=["']availability["'][^>]*>([\s\S]{0,600}?)</div>""")
    private val UNAVAILABLE = Regex("""currently unavailable|momenteel niet (?:verkrijgbaar|beschikbaar)|actuellement indisponible|niet op voorraad|out of stock""", RegexOption.IGNORE_CASE)

    fun isAmazon(url: String) = url.substringAfter("//").substringBefore('/').lowercase().let { "amazon." in it }

    /** The product on an Amazon page: its name, price and stock, as Scraper's other pages give them; null without a price. */
    fun parse(html: String): Scraper.Result? {
        val price = TWISTER.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: CORE_PRICE.find(html)?.groupValues?.get(1)?.let(::euros)
            ?: WHOLE_PRICE.find(html)?.let { m -> euros(m.groupValues[1].trimEnd('.', ',') + "," + m.groupValues[2]) }
            ?: PRICE_AMOUNT.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val availability = AVAILABILITY.find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ")?.trim().orEmpty()
        val inStock = when {
            availability.isEmpty() -> null
            UNAVAILABLE.containsMatchIn(availability) -> false
            else -> true
        }
        val title = Regex("""id=["']productTitle["'][^>]*>\s*([^<]+)<""").find(html)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&").orEmpty()
        // Sold out without a price: see isSoldOut.
        if (price == null || price <= 0) return null
        return Scraper.Result(title, listOf(Scraper.Offer("Amazon.com.be", price, inStock)))
    }

    /** Whether the page says the product shown can't be bought now (with no price for it). */
    fun isSoldOut(html: String): Boolean {
        val availability = AVAILABILITY.find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ")?.trim().orEmpty()
        return availability.isNotEmpty() && UNAVAILABLE.containsMatchIn(availability)
    }

    /** "€119,00", "119,00 €", "EUR 1.119,00" as a number. */
    fun euros(text: String): Double? {
        val digits = text.replace(Regex("""[^0-9.,]"""), "")
        if (digits.isEmpty()) return null
        val normal = if (digits.contains(',')) digits.replace(".", "").replace(',', '.') else digits
        return normal.toDoubleOrNull()
    }
}
