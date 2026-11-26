package com.bartschram.franxit.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.bartschram.franxit.scrape.ProductSearch
import com.bartschram.franxit.ui.theme.FranxitTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the product search in its states, to look at before a release (the screenshots workflow; not run with
 * the other tests). Sample answers stand in for the search service: what the screen does with them, not real results.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class SearchScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun product(title: String, shop: String, path: String, description: String? = null) =
        ProductSearch.Product(title = title, url = "https://www.$shop$path", description = description, retailer = shop, imageUrl = "https://example.invalid/photo.jpg")

    private val ps5 = listOf(
        product("Sony PlayStation 5 Slim Disc Edition", "coolblue.be", "/nl/product/950001/sony-playstation-5-slim-disc.html"),
        product("PlayStation 5 Slim Digital Edition", "bol.com", "/be/nl/p/playstation-5-slim-digital/9300000172342141/"),
        product("Sony PlayStation 5 Pro", "mediamarkt.be", "/nl/product/_sony-playstation-5-pro-2097421.html"),
        product("PlayStation 5 Slim + EA Sports FC 26 bundel", "nedgame.nl", "/playstation-5/playstation-5-slim-fc26-bundel/123456/"),
        product("DualSense draadloze controller – Midnight Black", "fnac.be", "/a17234561/Sony-DualSense"),
        product("PlayStation 5 Pro – 2 TB", "krefel.be", "/nl/p/43001234-playstation-5-pro"),
    )

    private val headphones = listOf(
        product("Sony WH-CH720N draadloze koptelefoon – Zwart", "coolblue.be", "/nl/product/930001/sony-wh-ch720n-zwart.html"),
        product("JBL Tune 670NC – Wit", "bol.com", "/be/nl/p/jbl-tune-670nc-wit/9300000150000000/"),
        product("Soundcore Q20i hybride ANC-koptelefoon", "amazon.com.be", "/dp/B0C3HCD34R"),
    )

    @Before
    fun dutch() {
        L10n.set(ApplicationProvider.getApplicationContext(), L10n.Choice.DUTCH)
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { FranxitTheme { content() } }
        // Past the waits for typing to pause.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // The photos downloaded from the shops (real results only; the samples have none).
        Thread.sleep(25_000)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // Drawn from the window itself: Compose's own capture waits for a frame Robolectric doesn't send.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Search(
        query: String,
        found: suspend (String) -> List<ProductSearch.Product> = { ps5 },
        titleFromLink: suspend (String) -> String? = { null },
    ) = SearchScreen(
        search = { emptyList() },
        discover = found,
        suggest = { listOf("playstation 5 pro", "playstation 5 slim", "playstation 5 digital edition") },
        // As the app: the search's photo, else the page's own (fetched here; a shop that keeps servers out gives none).
        // As the app: a photo that loads, the search's or else the page's (fetched here; a shop that keeps servers out
        // gives none).
        photoOf = { p ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fun loads(url: String?) = url?.takeIf { runCatching { com.bartschram.franxit.data.Images.thumbnail(it) != null }.getOrDefault(false) }
                // The samples: shown as they are (no real photo).
                if (p.imageUrl == "https://example.invalid/photo.jpg") return@withContext p.imageUrl
                // Only a page that shows a price the app reads.
                val html = runCatching { com.bartschram.franxit.scrape.Scraper.download(p.url, "text/html", readTimeoutMs = 8_000) }.getOrNull()
                    ?: return@withContext null
                val price = runCatching { com.bartschram.franxit.data.ShopCheck.seen(com.bartschram.franxit.scrape.Scraper.fromPage(html, p.url), p.title)?.price }.getOrNull()
                if (price == null) null else loads(p.imageUrl) ?: loads(ProductSearch.photoIn(html, p.url))
            }
        },
        titleFromLink = titleFromLink,
        onPick = { _, _ -> },
        onBack = {},
        initialQuery = query,
        photoSearch = false,
    )

    @Test
    fun a_results() = shoot("1-resultaten") { Search("playstation 5") }

    @Test
    fun b_aQuestionInPlainWords() = shoot("2-gewone-taal") {
        Search("goedkope draadloze koptelefoon", found = { headphones })
    }

    @Test
    fun c_searching() = shoot("3-zoeken") { Search("nike air max", found = { awaitCancellation() }) }

    @Test
    fun d_nothingFound() = shoot("4-niets-gevonden") { Search("xqzv kleerhanger 3000", found = { emptyList() }) }

    @Test
    fun e_limitReached() = shoot("5-limiet") { Search("iphone 17 pro", found = { throw ProductSearch.LimitReached() }) }

    @Test
    fun f_pickedProductComparedAcrossShops() = shoot("6-gekozen-product") {
        Search(ps5[0].url, titleFromLink = { awaitCancellation() })
    }

    @Test
    fun g_empty() = shoot("7-leeg") { Search("") }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-night-xhdpi")
    fun h_tablet() = shoot("8-tablet") { Search("playstation 5") }

    /** What the search service answered for a real search (saved from Exa), as the app filters it. */
    private fun real(file: String): List<ProductSearch.Product> =
        ProductSearch.productPages(ProductSearch.parse(javaClass.getResource("/search/$file")!!.readText()).products)

    @Test
    fun i_realPlayStation() = shoot("9-echt-playstation-5-pro") {
        Search("playstation 5 pro console", found = { real("playstation-5-pro.json") })
    }

    @Test
    fun j_realHeadphones() = shoot("10-echt-koptelefoon") {
        Search("betaalbare draadloze koptelefoon", found = { real("draadloze-koptelefoon.json") })
    }

    @Test
    fun k_realNike() = shoot("11-echt-nike-air-max-90") {
        Search("nike air max 90 heren", found = { real("nike-air-max-90.json") })
    }

    @Test
    fun l_realDyson() = shoot("12-echt-dyson") {
        Search("dyson snoerloze stofzuiger", found = { real("dyson-stofzuiger.json") })
    }

    @Test
    fun n_realTcl() = shoot("13-echt-tcl-nxtpaper-14") {
        Search("TCL nxtpaper 14", found = { ProductSearch.oneOfEach(ProductSearch.relevant(real("tcl-nxtpaper-14.json"), "TCL nxtpaper 14")) })
    }

    @Test
    fun m_suggestionsWhileTyping() {
        compose.setContent { FranxitTheme { Search("") } }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("playstation 5")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "0-suggesties.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
