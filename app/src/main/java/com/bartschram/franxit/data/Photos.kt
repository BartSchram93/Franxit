package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit

/**
 * Which photo a product shows: the one from its own page (vergelijk.be or the shop, the default),
 * Google Shopping's, or none, for when the page's photo is wrong (a laptop bag for a keyboard bench).
 * The choice sticks, so the next check doesn't bring the wrong photo back.
 */
object Photos {

    enum class Choice { SITE, GOOGLE, NONE }

    private const val PREFS = "photos"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun choice(context: Context, productId: Long): Choice =
        prefs(context).getString("choice_$productId", null)?.let { runCatching { Choice.valueOf(it) }.getOrNull() } ?: Choice.SITE

    fun setChoice(context: Context, productId: Long, choice: Choice) = prefs(context).edit {
        if (choice == Choice.SITE) remove("choice_$productId") else putString("choice_$productId", choice.name)
    }

    /** The photo of the best Google Shopping result, from the product's last search. */
    fun googlePhoto(context: Context, productId: Long): String? = prefs(context).getString("google_$productId", null)

    fun saveGooglePhoto(context: Context, productId: Long, url: String) = prefs(context).edit { putString("google_$productId", url) }

    /**
     * The photo of the product's own page (the shop's, vergelijk.be's or Google Shopping's for a product found
     * there), from its last check: "Use the shop's photo again" puts it back at once, without a new check (which
     * a Google Shopping product skips the rest of the day, and a shop that keeps apps out never gets through).
     */
    fun sitePhoto(context: Context, productId: Long): String? = prefs(context).getString("site_$productId", null)

    fun saveSitePhoto(context: Context, productId: Long, url: String) = prefs(context).edit { putString("site_$productId", url) }
}
