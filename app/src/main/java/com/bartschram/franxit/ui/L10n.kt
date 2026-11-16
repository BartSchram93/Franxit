package com.bartschram.franxit.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Locale

/**
 * The app's language: English, Dutch, or whatever the phone uses (Dutch when the phone is Dutch,
 * English otherwise). Every text the user sees goes through [t]: English is the source text, and
 * [dutch] holds its Dutch. Reading [choice] in a composable makes it redraw when the language changes.
 */
object L10n {

    enum class Choice { SYSTEM, ENGLISH, DUTCH }

    private const val PREFS = "language"

    var choice by mutableStateOf(Choice.SYSTEM)
        private set

    fun init(context: Context) {
        choice = runCatching {
            Choice.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("choice", null)!!)
        }.getOrDefault(Choice.SYSTEM)
    }

    fun set(context: Context, new: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("choice", new.name) }
        choice = new
    }

    /** Pure, for tests: whether this [choice] on a phone set to [systemLanguage] shows Dutch. */
    fun isDutch(choice: Choice, systemLanguage: String): Boolean = when (choice) {
        Choice.DUTCH -> true
        Choice.ENGLISH -> false
        Choice.SYSTEM -> systemLanguage == "nl"
    }

    val dutch: Boolean get() = isDutch(choice, Locale.getDefault().language)

    /** For dates: month names in the app's language. */
    val locale: Locale get() = if (dutch) Locale.forLanguageTag("nl-BE") else Locale.ENGLISH

    /** The Dutch text for [en] when the app is Dutch and there is one; else [en] itself. */
    fun t(en: String): String = if (dutch) DUTCH_TEXTS[en] ?: en else en
}

/** [en] in the app's language. */
fun t(en: String): String = L10n.t(en)

/** [en] in the app's language, with its %s / %d / %1$s filled in. */
fun t(en: String, vararg args: Any?): String = String.format(L10n.locale, L10n.t(en), *args)

/**
 * A stored or thrown message, in the app's language: the ones that carry a shop or a number are
 * matched by their shape ("No longer on Coolblue.be"), the rest by their text.
 */
fun tMessage(message: String): String {
    if (!L10n.dutch) return message
    Regex("""No longer on (.+)""").matchEntire(message)?.let { return t("No longer on %s", it.groupValues[1]) }
    Regex("""Found (\d+) extra shops""").matchEntire(message)?.let { return t("Found %d extra shops", it.groupValues[1].toInt()) }
    if (message.startsWith("Google Shopping: ")) return "Google Shopping: " + t(message.removePrefix("Google Shopping: "))
    return t(message)
}
