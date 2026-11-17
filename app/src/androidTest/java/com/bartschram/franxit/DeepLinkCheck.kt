package com.bartschram.franxit

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.bartschram.franxit.data.Db
import com.bartschram.franxit.data.ShareLink
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Does a shared product link open Franxit? Opens one the way a message app would (no package
 * named), and records what Android says about the app link and what is on screen afterwards.
 */
class DeepLinkCheck {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val out = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle(); Thread.sleep(1500)
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun aSharedLinkOpensTheApp() {
        out.listFiles()?.forEach { it.delete() }
        context.deleteDatabase("franxit.db")
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().putBoolean("tips_seen", true).commit()
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("choice", "DUTCH").commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putBoolean("share", false).commit()
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE).edit()
            .putString("checked", LocalDate.now().toString()).putInt("latest", 0).commit()
        Db(context).close()

        val link = ShareLink.forProduct("https://www.coolblue.be/nl/product/941836/playstation-5-slim-digital-edition.html")
        File(out, "link.txt").writeText(link)
        File(out, "links-before.txt").writeText(device.executeShellCommand("pm get-app-links ${context.packageName}"))
        // Ask Android to check the domain again (it does so at install, and may have done it before the file was online).
        device.executeShellCommand("pm verify-app-links --re-verify ${context.packageName}")
        Thread.sleep(25_000)
        File(out, "links-after.txt").writeText(device.executeShellCommand("pm get-app-links ${context.packageName}"))

        device.pressHome()
        Thread.sleep(1500)
        device.executeShellCommand("am start -a android.intent.action.VIEW -d '$link'")
        Thread.sleep(8000)
        File(out, "foreground.txt").writeText(device.currentPackageName ?: "unknown")
        shot("dl-1-after-open")
    }
}
