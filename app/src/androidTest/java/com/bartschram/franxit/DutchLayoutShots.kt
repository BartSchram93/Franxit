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
 * Walks through the app in Dutch, to see whether the longer Dutch texts still fit: the menu, every
 * dialog, a search, a product with its advice. Then again with a larger font. Screenshots only; run by
 * the "Screenshots" workflow.
 */
class DutchLayoutShots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val out = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun ci(regex: String) = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    private fun await(selector: BySelector, timeoutMs: Long): UiObject2? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runCatching { if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click() }
            runCatching { device.findObject(selector) }.getOrNull()?.let { return it }
            Thread.sleep(700)
        }
        return null
    }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(1200)
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Runs one step; if it fails, keeps what the screen looked like and goes on. */
    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            runCatching { shot("fail-$name") }
            runCatching { device.dumpWindowHierarchy(File(out, "fail-$name.xml")) }
            File(out, "fail-$name.txt").writeText(it.toString())
            repeat(2) { runCatching { device.pressBack() } }
        }
    }

    private fun clickDesc(regex: String) = (await(By.desc(ci(regex)), 30_000) ?: error("no $regex")).click()
    private fun clickText(regex: String) = (await(By.text(ci(regex)), 30_000) ?: error("no $regex")).click()

    private fun scrollDown() =
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)

    private fun scrollUp() =
        device.swipe(device.displayWidth / 2, device.displayHeight / 4, device.displayWidth / 2, device.displayHeight * 3 / 4, 30)

    private fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 30_000)
    }

    @Test
    fun walkThroughInDutch() {
        out.listFiles()?.forEach { it.delete() }
        context.deleteDatabase("franxit.db")
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("choice", "DUTCH").commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putBoolean("share", false).commit()
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE).edit()
            .putString("checked", LocalDate.now().toString()).putInt("latest", 0).commit()
        Db(context).close()

        launch()
        step("tips") { await(By.text("Begrepen"), 30_000) ?: error("no tips"); shot("nl-01-tips"); clickText("Begrepen") }
        step("empty") { shot("nl-02-empty") }
        step("menu") { clickDesc("Meer"); await(By.text("Taal"), 10_000); shot("nl-03-menu"); device.pressBack() }
        step("sort") { clickDesc("Sorteren en filteren"); await(By.text("Sorteer op"), 10_000); shot("nl-04-sort"); device.pressBack() }
        for ((item, name) in listOf(
            "Taal" to "nl-05-language", "Gebruiksstatistieken" to "nl-06-usage",
            "Google Shopping" to "nl-07-google", "Mijn kortingen" to "nl-08-discounts",
        )) {
            step(name) {
                clickDesc("Meer"); clickText(item); Thread.sleep(800); shot(name)
                device.pressBack()
            }
        }
        step("search") {
            clickDesc("Product volgen")
            val field = await(By.clazz("android.widget.EditText"), 30_000) ?: error("no field")
            field.text = "PlayStation 5"
            await(By.textContains("vanaf"), 90_000)
            shot("nl-09-search")
            (await(By.text(ci(".*playstation 5.*")), 30_000) ?: error("no result")).click()
            await(By.textContains("Gebruikelijk"), 120_000)
            scrollUp(); scrollUp()
            shot("nl-10-detail-top")
            scrollDown(); shot("nl-11-detail-middle")
            scrollDown(); shot("nl-12-detail-bottom")
            scrollUp(); scrollUp(); scrollUp()
            step("alert") { clickText("Prijsalert"); Thread.sleep(800); shot("nl-13-alert"); device.pressBack() }
            step("rename") { clickDesc("Hernoemen|Naam"); Thread.sleep(500); shot("nl-14-rename"); device.pressBack() }
            device.pressBack()
        }
        step("second") {
            clickDesc("Product volgen")
            val field = await(By.clazz("android.widget.EditText"), 30_000) ?: error("no field")
            field.text = "AirPods Pro 3"
            await(By.textContains("vanaf"), 90_000)
            (await(By.text(ci(".*airpods pro 3.*")), 30_000) ?: error("no result")).click()
            await(By.textContains("Gebruikelijk"), 120_000)
            device.pressBack()
        }
        step("overview") { Thread.sleep(8000); shot("nl-15-overview") }

        // The same again with a larger font, the usual way text gets squeezed.
        device.executeShellCommand("settings put system font_scale 1.3")
        launch()
        step("big-overview") { Thread.sleep(3000); shot("nl-20-big-overview") }
        step("big-menu") { clickDesc("Meer"); await(By.text("Taal"), 10_000); shot("nl-21-big-menu"); device.pressBack() }
        step("big-detail") {
            clickText(".*playstation.*")
            await(By.textContains("Gebruikelijk"), 60_000)
            shot("nl-22-big-detail-top")
            scrollDown(); shot("nl-23-big-detail-middle")
            scrollDown(); shot("nl-24-big-detail-bottom")
        }
        device.executeShellCommand("settings put system font_scale 1.0")
    }
}
