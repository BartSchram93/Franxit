package com.bartschram.franxit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.bartschram.franxit.data.Db
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.regex.Pattern

/**
 * Takes the screenshots for the download page, with the real app doing real work: it starts empty,
 * searches vergelijk.be for popular products like a user would, and tracks them. Every price,
 * shop, photo and price history on the screenshots comes from the live site. Nothing is invented
 * and nothing is seeded: if a search fails, the test fails. Run by the "Screenshots" workflow.
 */
class DemoScreenshots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val out = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

    /** (what the user types, a pattern the right result's whole title must match) */
    private val products = listOf(
        "PlayStation 5" to ".*playstation 5.*",
        "AirPods Pro 3" to ".*airpods pro 3.*",
        "Dyson V15 Detect" to ".*v15 detect.*",
        "Sony WH-1000XM5" to ".*wh-?1000xm5.*",
        "Samsung Galaxy S25" to ".*galaxy s25.*",
        "Philips Airfryer XXL" to ".*airfryer xxl.*",
    )

    // One language per run ("ENGLISH", "DUTCH" or "FRENCH"): the app is set to it, and these are the words it shows.
    private val language = InstrumentationRegistry.getArguments().getString("lang") ?: "DUTCH"
    private val TRACK = mapOf("ENGLISH" to "Track a product", "DUTCH" to "Product volgen", "FRENCH" to "Suivre un produit").getValue(language)
    private val FROM = mapOf("ENGLISH" to "from", "DUTCH" to "vanaf", "FRENCH" to "dès").getValue(language)
    private val TYPICAL = mapOf("ENGLISH" to "Typical", "DUTCH" to "Gebruikelijk", "FRENCH" to "Habituel").getValue(language)
    private val GOT_IT = mapOf("ENGLISH" to "Got it", "DUTCH" to "Begrepen", "FRENCH" to "Compris").getValue(language)

    private fun startEmpty() {
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("choice", language).commit()
        context.deleteDatabase("franxit.db")
        // No update banner while taking screenshots.
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE).edit()
            .putString("checked", LocalDate.now().toString()).putInt("latest", 0).commit()
        Db(context).close()
    }

    /** A slow emulator can show "isn't responding" boxes and the first-start tips: click them away. */
    private fun clearDialogs() {
        repeat(3) {
            // A box can vanish between finding it and clicking it: that is fine.
            runCatching {
                // "Wait" is also a verdict on a product card: only click it inside the system box.
                if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click()
            }
            runCatching { device.findObject(By.text(GOT_IT))?.click() }
            Thread.sleep(500)
        }
    }

    private fun shot(name: String) {
        clearDialogs()
        device.waitForIdle()
        Thread.sleep(1500)
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Waits for [selector], clicking away "isn't responding" boxes meanwhile (the emulator is slow). */
    private fun await(selector: BySelector, timeoutMs: Long): UiObject2? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runCatching { if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click() }
            runCatching { device.findObject(selector) }.getOrNull()?.let { return it }
            Thread.sleep(700)
        }
        return null
    }

    /** For when a step fails: what the screen looked like. */
    private fun debug(name: String) {
        runCatching { shot("debug-$name") }
        runCatching { device.dumpWindowHierarchy(File(out, "debug-$name.xml")) }
    }

    private fun scrollDown() =
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)

    @Test
    fun takeScreenshots() {
        out.listFiles()?.forEach { it.delete() }
        startEmpty()

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 30_000)
        clearDialogs()

        var tracked = 0
        products.forEachIndexed { index, (query, word) ->
            // Search like a user: tap +, type, wait for the results.
            (await(By.desc(TRACK), 60_000) ?: run { debug("plus-$word"); error("No + button") }).click()
            val field = await(By.clazz("android.widget.EditText"), 60_000) ?: run { debug("field-$word"); error("No search field") }
            field.text = query
            // The result's title (not the text field, which also holds the word); any letter case.
            val inTitle = By.text(Pattern.compile(word, Pattern.CASE_INSENSITIVE or Pattern.DOTALL))
            await(By.textContains(FROM), 90_000)
            val result = device.findObjects(inTitle).firstOrNull { it.className != "android.widget.EditText" }
            if (result == null) {
                // Not found: leave it out (never invent it) and go on with the next product.
                debug("search-$word")
                repeat(3) { if (device.findObject(By.desc(TRACK)) == null) device.pressBack() }
                return@forEachIndexed
            }
            tracked++
            if (tracked == 1) shot("1-search")

            // Tap the result: the app tracks it and opens it.
            result.click()
            await(By.textContains(TYPICAL), 120_000)
            if (tracked == 1) {
                shot("3-advice")
                scrollDown()
                scrollDown()
                shot("4-details")
            }
            device.pressBack()
            await(By.desc(TRACK), 60_000)
        }
        Thread.sleep(10_000) // the last photos are still coming in
        shot("2-overview")
        check(tracked >= 4) { "Only $tracked products could be tracked" }
    }
}
