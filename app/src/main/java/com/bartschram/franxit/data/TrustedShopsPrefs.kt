package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit

// The shops the app learned (vergelijk.be's), kept on the phone. TrustedShops itself is in the shared module, which
// knows nothing of Android: these are its phone-side parts, called as before (TrustedShops.learn(context, …)).

private const val PREFS = "trusted_shops"

fun TrustedShops.name(context: Context, shop: String): String? = name(shop, learned(context))

fun TrustedShops.isTrusted(context: Context, shop: String): Boolean = isTrusted(shop, learned(context))

/** Remembers the shops vergelijk.be lists (and a page's own shop), so Google may show them too. */
fun TrustedShops.learn(context: Context, shops: Collection<String>) {
    val learned = learned(context)
    val new = shops.map(::key).filter { it.isNotEmpty() && it !in learned && it !in KNOWN }
    if (new.isEmpty()) return
    prefs(context).edit { putStringSet("keys", learned + new) }
}

private fun learned(context: Context): Set<String> = prefs(context).getStringSet("keys", null).orEmpty()

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
