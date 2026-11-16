package com.bartschram.franxit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DutchTextsTest {

    private val sources = File("src/main/java/com/bartschram/franxit")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\$", "$").replace("\\\\", "\\")

    /** The placeholders of a text, in order: "%1$s", "%d", "%%"... */
    private fun placeholders(s: String) = Regex("%(\\d\\$)?[sd]|%%").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun theLanguageChoiceDecidesWhetherTheAppIsDutch() {
        assertTrue(L10n.isDutch(L10n.Choice.DUTCH, "en"))
        assertFalse(L10n.isDutch(L10n.Choice.ENGLISH, "nl"))
        assertTrue(L10n.isDutch(L10n.Choice.SYSTEM, "nl"))
        assertFalse(L10n.isDutch(L10n.Choice.SYSTEM, "fr"))
    }

    @Test
    fun everyTextTheAppSaysHasItsDutch() {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.exists())
        val missing = sortedSetOf<String>()
        sources.walkTopDown().filter { it.extension == "kt" && it.name != "DutchTexts.kt" }.forEach { file ->
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
                if (text !in DUTCH_TEXTS) missing += "${file.name}: $text"
            }
        }
        assertTrue("No Dutch for these texts: $missing", missing.isEmpty())
    }

    @Test
    fun theLongUsageTextIsTranslatedAsAWhole() {
        // Written as joined pieces in the code, so the scan above skips it: check it by hand.
        assertTrue(DUTCH_TEXTS.keys.any { it.startsWith("Franxit counts anonymously") && it.endsWith("never your products or prices.") })
    }

    @Test
    fun dutchTextsKeepTheirPlaceholders() {
        val broken = DUTCH_TEXTS.filter { (en, nl) -> placeholders(en) != placeholders(nl) }.keys
        assertEquals("Placeholders differ for: $broken", emptySet<String>(), broken)
    }

    @Test
    fun dutchOnlyWhenAskedFor() {
        // Without a stored choice and on an English test machine, texts stay English.
        assertEquals("Welcome", t("Welcome"))
    }
}
