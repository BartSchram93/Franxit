package com.bartschram.franxit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bartschram.franxit.data.Db
import com.bartschram.franxit.data.PricePoint
import com.bartschram.franxit.data.ShopOffer
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.sin

/**
 * Takes the screenshots for the download page: the real app on an emulator with made-up
 * products and prices (no network, no real shops). Run by the "Screenshots" workflow.
 */
class DemoScreenshots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val out = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun seed() {
        context.deleteDatabase("franxit.db")
        // No anonymous count and no update banner while taking screenshots.
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putBoolean("share", false).commit()
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE).edit()
            .putString("checked", LocalDate.now().toString()).putInt("latest", 0).commit()

        val db = Db(context)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        fun product(url: String, name: String, base: Double, dip: Double, current: Double, wave: Double,
                    offers: List<ShopOffer>, target: Double? = null) {
            val id = db.insertProduct(url, name)
            for (daysAgo in 365 downTo 1 step 3) {
                var price = base + wave * sin(daysAgo / 17.0) + wave * 0.5 * sin(daysAgo / 5.0)
                if (daysAgo in 58..64) price = dip
                db.savePrice(id, PricePoint(today.minusDays(daysAgo.toLong()), Math.round(price).toDouble(), offers.first().seller, offers.size, now))
            }
            db.savePrice(id, PricePoint(today, current, offers.first().seller, offers.size, now))
            db.replaceOffers(id, offers)
            db.markChecked(id, now, null, null)
            target?.let { db.setTargetPrice(id, it) }
        }

        product(
            "https://example.test/koptelefoon", "Wireless noise-cancelling headphones", 231.0, 189.0, 199.0, 9.0,
            listOf(ShopOffer("Techhoek.be", 199.0, true), ShopOffer("Gadgetgigant.be", 204.0, true), ShopOffer("Huisplein.be", 215.0, true)),
            target = 190.0,
        )
        product(
            "https://example.test/stofzuiger", "Robot vacuum cleaner", 349.0, 299.0, 389.0, 12.0,
            listOf(ShopOffer("Huisplein.be", 389.0, true), ShopOffer("Techhoek.be", 399.0, true)),
        )
        product(
            "https://example.test/espresso", "Espresso machine", 279.0, 229.0, 259.0, 11.0,
            listOf(ShopOffer("Gadgetgigant.be", 259.0, true), ShopOffer("Techhoek.be", 262.0, true), ShopOffer("Huisplein.be", 269.0, false)),
        )
        db.close()
    }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(1500)
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun takeScreenshots() {
        out.listFiles()?.forEach { it.delete() }
        seed()
        device.executeShellCommand("cmd uimode night no")
        device.executeShellCommand("settings put global sysui_demo_allowed 1")
        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command enter")
        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941")
        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false")
        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4")
        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10_000)
        // Android 13+ asks for the notification permission on first start.
        device.findObject(By.text("Allow"))?.click()
        device.wait(Until.hasObject(By.textContains("Wireless")), 10_000)
        shot("1-overview")

        device.findObject(By.textContains("Wireless")).click()
        device.wait(Until.hasObject(By.textContains("Buy now")), 10_000)
        shot("2-advice")

        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
        shot("3-details")

        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command exit")
    }
}
