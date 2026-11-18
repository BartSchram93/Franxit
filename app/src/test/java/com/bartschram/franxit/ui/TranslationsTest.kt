package com.bartschram.franxit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslationsTest {

    private val sources = File("src/main/java/com/bartschram/franxit")

    private val languages = mapOf("Dutch" to DUTCH_TEXTS, "French" to FRENCH_TEXTS)

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\$", "$").replace("\\\\", "\\")

    /** The placeholders of a text, in order: "%1$s", "%d", "%%"... */
    private fun placeholders(s: String) = Regex("%(\\d\\$)?[sd]|%%").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun theLanguageChoiceDecidesWhatTheAppSpeaks() {
        assertEquals(L10n.Lang.NL, L10n.resolve(L10n.Choice.DUTCH, "en"))
        assertEquals(L10n.Lang.FR, L10n.resolve(L10n.Choice.FRENCH, "nl"))
        assertEquals(L10n.Lang.EN, L10n.resolve(L10n.Choice.ENGLISH, "nl"))
        assertEquals(L10n.Lang.NL, L10n.resolve(L10n.Choice.SYSTEM, "nl"))
        assertEquals(L10n.Lang.FR, L10n.resolve(L10n.Choice.SYSTEM, "fr"))
        assertEquals(L10n.Lang.EN, L10n.resolve(L10n.Choice.SYSTEM, "de"))
    }

    @Test
    fun everyTextTheAppSaysHasItsTranslations() {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.exists())
        val missing = sortedSetOf<String>()
        sources.walkTopDown().filter { it.extension == "kt" && !it.name.endsWith("Texts.kt") }.forEach { file ->
            val code = file.readText()
            // t("…") and the app's own Text("…"), but not Glance's (the widget calls t() itself).
            val calls = Regex("""\bt\(\s*"((?:[^"\\\n]|\\.)*)"""").findAll(code).map { it.groupValues[1] } +
                (if ("import com.bartschram.franxit.ui.Text" in code || file.parent.endsWith("ui") && "androidx.glance" !in code)
                    Regex("""(?<![A-Za-z.])Text\(\s*"((?:[^"\\\n]|\\.)*)"(?!\s*\+)""").findAll(code).map { it.groupValues[1] }
                else emptySequence())
            for (raw in calls) {
                val text = unescape(raw)
                // Plain symbols and templates built elsewhere have nothing to translate.
                if (!Regex("[A-Za-z]{3}").containsMatchIn(text) || "$" in raw) continue
                for ((language, texts) in languages) if (text !in texts) missing += "$language, ${file.name}: $text"
            }
        }
        assertTrue("No translation for these texts: $missing", missing.isEmpty())
    }

    @Test
    fun theTranslationsKeepTheirPlaceholders() {
        for ((language, texts) in languages) {
            val broken = texts.filter { (en, translated) -> placeholders(en) != placeholders(translated) }.keys
            assertTrue("$language: placeholders differ for: $broken", broken.isEmpty())
        }
    }

    @Test
    fun bothLanguagesCoverTheSameTexts() {
        assertEquals(DUTCH_TEXTS.keys, FRENCH_TEXTS.keys)
    }

    @Test
    fun theAlertSuggestionsAreTranslated() {
        // Their texts live in the data layer, so the source scan doesn't see them.
        val code = File("src/main/java/com/bartschram/franxit/data/Suggestions.kt").readText()
        val texts = Regex("""add\("([^"]+)",[^"]*"([^"]+)"\)""").findAll(code).flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.toList()
        assertTrue("found no suggestions in $code", texts.size >= 6)
        for ((language, translations) in languages) {
            val missing = texts.filter { it !in translations }
            assertTrue("$language: no translation for: $missing", missing.isEmpty())
        }
    }

    @Test
    fun anUntranslatedTextStaysAsItIs() {
        assertEquals("Welcome", t("Welcome"))
    }
}
