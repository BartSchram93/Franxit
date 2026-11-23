package com.bartschram.franxit.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bartschram.franxit.scrape.Scraper
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A shop's page read in a real browser (Android's WebView, never shown), for shops that keep the app's own requests
 * out (Fnac, Nintendo's store): a browser runs their checks' scripts and often gets through. Not always: a check that
 * wants a person (a puzzle) still stops it. Called from a background thread; the browser itself lives on the main one.
 */
internal object BrowserPages {

    /** How long a page may take in all, its checks included. */
    private const val WAIT_MS = 20_000L

    /** After the page has loaded: a moment for its scripts (a check page passes, then loads the product). */
    private const val SETTLE_MS = 2_500L

    /** The page's HTML once loaded and past any check page; null when it didn't get there in time. */
    @SuppressLint("SetJavaScriptEnabled")
    fun html(context: Context, url: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        var view: WebView? = null
        val main = Handler(Looper.getMainLooper())
        var tries = 0
        lateinit var grab: Runnable
        grab = Runnable {
            val web = view ?: return@Runnable
            web.evaluateJavascript("document.documentElement.outerHTML") { value ->
                val html = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull().orEmpty()
                // Still a check page: wait for it to pass, a few times.
                if ((html.isEmpty() || Scraper.isBrowserCheck(html)) && ++tries < 4) {
                    main.postDelayed(grab, SETTLE_MS)
                } else {
                    result = html.takeIf { it.isNotEmpty() && !Scraper.isBrowserCheck(it) }
                    done.countDown()
                }
            }
        }
        main.post {
            try {
                val web = WebView(context.applicationContext)
                view = web
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.blockNetworkImage = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(page: WebView, address: String) {
                        // Each load (a check page, then the product) starts the wait again.
                        main.removeCallbacks(grab)
                        main.postDelayed(grab, SETTLE_MS)
                    }
                }
                web.loadUrl(url)
            } catch (e: Throwable) {
                // No WebView on this phone (or it couldn't start): nothing read.
                done.countDown()
            }
        }
        done.await(WAIT_MS, TimeUnit.MILLISECONDS)
        main.post {
            main.removeCallbacks(grab)
            view?.stopLoading()
            view?.destroy()
            view = null
        }
        return result
    }
}
