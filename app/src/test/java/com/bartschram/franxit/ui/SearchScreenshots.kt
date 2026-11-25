package com.bartschram.franxit.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
    val compose = createComposeRule()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun product(title: String, shop: String, path: String, description: String? = null) =
        ProductSearch.Product(title = title, url = "https://www.$shop$path", description = description, retailer = shop)

    private val ps5 = listOf(
        product("Sony PlayStation 5 Slim Disc Edition", "coolblue.be", "/nl/product/950001/sony-playstation-5-slim-disc.html"),
        product("PlayStation 5 Slim Digital Edition", "bol.com", "/be/nl/p/playstation-5-slim-digital/9300000172342141/"),
        product("Sony PlayStation 5 Pro", "mediamarkt.be", "/nl/product/_sony-playstation-5-pro-2097421.html"),
        product("PlayStation 5 Slim + EA Sports FC 26 bundel", "nedgame.nl", "/playstation-5/playstation-5-slim-fc26-bundel/123456/"),
        product("DualSense draadloze controller – Midnight Black", "fnac.be", "/a17234561/Sony-DualSense"),
        product("PlayStation 5 Pro – 2 TB", "krefel.be", "/nl/p/43001234-playstation-5-pro"),
    )
    // Prices as the app reads them from each page: some pages show none it can read, and then none is shown.
    private val prices = mapOf(ps5[0].url to 449.99, ps5[1].url to 399.0, ps5[2].url to 799.99, ps5[4].url to 64.99)

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
        // Past the waits for typing to pause, and the prices read.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Search(
        query: String,
        found: suspend (String) -> List<ProductSearch.Product> = { ps5 },
        words: List<String> = listOf("playstation 5 pro", "playstation 5 slim", "playstation 5 digital", "playstation 5 controller"),
        titleFromLink: suspend (String) -> String? = { null },
    ) = SearchScreen(
        search = { emptyList() },
        discover = found,
        suggest = { words },
        priceOf = { prices[it.url] },
        titleFromLink = titleFromLink,
        onPick = { _, _ -> },
        onBack = {},
        initialQuery = query,
        onGoogleShopping = {},
    )

    @Test
    fun a_resultsWithSuggestions() = shoot("1-resultaten") { Search("playstation 5") }

    @Test
    fun b_aQuestionInPlainWords() = shoot("2-gewone-taal") {
        Search("goedkope draadloze koptelefoon", found = { headphones }, words = listOf("goedkope draadloze koptelefoon met noise cancelling", "goedkope draadloze koptelefoon sport"))
    }

    @Test
    fun c_searching() = shoot("3-zoeken") { Search("nike air max", found = { awaitCancellation() }, words = listOf("nike air max 90", "nike air max dn", "nike air max plus")) }

    @Test
    fun d_nothingFound() = shoot("4-niets-gevonden") { Search("xqzv kleerhanger 3000", found = { emptyList() }, words = emptyList()) }

    @Test
    fun e_limitReached() = shoot("5-limiet") { Search("iphone 17 pro", found = { throw ProductSearch.LimitReached() }, words = listOf("iphone 17 pro max")) }

    @Test
    fun f_pickedProductComparedAcrossShops() = shoot("6-gekozen-product") {
        Search(ps5[0].url, titleFromLink = { awaitCancellation() })
    }

    @Test
    fun g_empty() = shoot("7-leeg") { Search("") }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-night-xhdpi")
    fun h_tablet() = shoot("8-tablet") { Search("playstation 5") }
}
