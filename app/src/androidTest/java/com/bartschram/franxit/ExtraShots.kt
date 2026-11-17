package com.bartschram.franxit

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
 * Two extra screenshots for the site, with real data: the home-screen widget with products tracked
 * through the app's own search, and a shop's product page in the browser shared with Franxit.
 * Run by the "Screenshots" workflow.
 */
class ExtraShots {

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
        Thread.sleep(1500)
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            runCatching { shot("fail-$name") }
            runCatching { device.dumpWindowHierarchy(File(out, "fail-$name.xml")) }
            File(out, "fail-$name.txt").writeText(it.toString())
        }
    }

    private fun launchApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 30_000)
    }

    /** Clicks the first of these buttons that is on screen, up to [rounds] times: first-run screens and consent boxes. */
    private fun clickAway(rounds: Int, vararg texts: String) {
        repeat(rounds) {
            val hit = texts.firstNotNullOfOrNull { t -> runCatching { device.findObject(By.text(ci(t))) }.getOrNull() }
            if (hit != null) { runCatching { hit.click() }; Thread.sleep(1500) } else Thread.sleep(1000)
        }
    }

    @Test
    fun widgetAndShare() {
        out.listFiles()?.forEach { it.delete() }
        context.deleteDatabase("franxit.db")
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("tips_seen", true).commit()
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("choice", "DUTCH").commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putBoolean("share", false).commit()
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE).edit()
            .putString("checked", LocalDate.now().toString()).putInt("latest", 0).commit()
        Db(context).close()

        // 1. Track a few real products through the app's own search.
        launchApp()
        var tracked = 0
        for ((query, pattern) in listOf(
            "PlayStation 5" to ".*playstation 5 slim.*", "AirPods Pro 3" to ".*airpods pro 3.*", "Samsung Galaxy S25" to ".*galaxy s25.*",
        )) {
            step("track-$query") {
                (await(By.desc("Product volgen"), 60_000) ?: error("no +")).click()
                val field = await(By.clazz("android.widget.EditText"), 30_000) ?: error("no field")
                field.text = query
                await(By.textContains("vanaf"), 90_000)
                await(By.text(ci(pattern)), 60_000)
                val hit = device.findObjects(By.text(ci(pattern))).firstOrNull { it.className != "android.widget.EditText" } ?: error("no result")
                hit.click()
                await(By.textContains("Gebruikelijk"), 120_000)
                device.pressBack()
                tracked++
            }
        }
        Thread.sleep(8000)

        // 2. The widget: ask Android to pin it, confirm, and look at the home screen.
        step("widget") {
            val provider = ComponentName(context, "com.bartschram.franxit.widget.PriceListWidgetReceiver")
            val ok = AppWidgetManager.getInstance(context).requestPinAppWidget(provider, null, null)
            File(out, "widget-pin-supported.txt").writeText(ok.toString())
            clickAway(8, "Add to home screen", "Toevoegen aan startscherm", "^Add$", "Add automatically")
            Thread.sleep(2000)
            device.pressHome()
            Thread.sleep(8000)
            shot("5-widget")
        }

        // 3. A shop's product page in the browser, shared with Franxit.
        step("share") {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bol.com/be/nl/p/playstation-5-disc-edition-slim/9300000166374057/"))
                .setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            Thread.sleep(6000)
            clickAway(6, "Accept & continue", "Use without an account", "No thanks", "No, thanks", "Not now", "Got it", "Next")
            Thread.sleep(8000)
            clickAway(3, "Alles accepteren", "Accepteer alles", "Accepteren", "Akkoord", "Alle cookies accepteren")
            Thread.sleep(5000)
            shot("6-shop-page")
            (await(By.desc(ci("more options")), 30_000) ?: error("no chrome menu")).click()
            (await(By.text(ci("share…|share\\.\\.\\.|share")), 30_000) ?: error("no share")).click()
            await(By.text("Franxit"), 30_000) ?: error("Franxit not in the share sheet")
            Thread.sleep(1500)
            shot("7-share-sheet")
            device.findObject(By.text("Franxit")).click()
            await(By.pkg(context.packageName).depth(0), 30_000)
            await(By.textContains("vanaf"), 90_000)
            Thread.sleep(3000)
            shot("8-after-share")
        }
        File(out, "tracked.txt").writeText(tracked.toString())
    }
}
