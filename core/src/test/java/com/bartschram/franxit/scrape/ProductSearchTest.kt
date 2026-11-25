package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductSearchTest {

    private val answer = """{"products":[
        {"title":"PlayStation 5 Slim Disc Edition","description":"Sony PlayStation 5 console","url":"https://www.coolblue.be/nl/product/950000/sony-playstation-5-slim.html","imageUrl":"https://image.coolblue.be/ps5.jpg","price":null,"currency":null,"retailer":"coolblue.be","availability":null,"score":0.81},
        {"title":"PS5 Pro","url":"https://www.bol.com/be/nl/p/playstation-5-pro/9300000190000000/","imageUrl":"http://insecure.example/p.jpg"},
        {"title":"Duplicate","url":"https://www.bol.com/be/nl/p/playstation-5-pro/9300000190000000/"},
        {"title":"","url":"https://www.fnac.be/a1"},
        {"title":"No address"},
        {"title":"Consoles","url":"https://www.mediamarkt.be/nl/category/consoles-123.html"},
        {"title":"Zoeken naar ps5","url":"https://www.krefel.be/nl/search?q=ps5"},
        {"title":"Fnac PS5","url":"https://www.fnac.be/gaming/playstation5/q"},
        {"title":"Coolblue","url":"https://www.coolblue.be/"},
        {"title":"DualSense controller","url":"https://www.nedgame.nl/playstation-5/dualsense-wireless-controller/123/"}
    ]}"""

    @Test
    fun theServicesAnswerIsReadWithOnlyWhatItGives() {
        val products = ProductSearch.parse(answer).products
        val first = products.first()
        assertEquals("PlayStation 5 Slim Disc Edition", first.title)
        assertEquals("coolblue.be", first.retailer)
        assertEquals(0.81, first.score!!, 0.0001)
        // Nothing made up: no price, currency or stock where the answer has none.
        assertNull(first.price)
        assertNull(first.currency)
        assertNull(first.availability)
        // A photo only from a secure address; one page once; no product without a title or an address.
        assertNull(products[1].imageUrl)
        assertEquals(1, products.count { it.url.contains("9300000190000000") })
        assertEquals(listOf("PlayStation 5 Slim Disc Edition", "PS5 Pro", "Consoles", "Zoeken naar ps5", "Fnac PS5", "Coolblue", "DualSense controller"), products.map { it.title })
    }

    @Test
    fun onlyProductPagesAreKept() {
        val pages = ProductSearch.productPages(ProductSearch.parse(answer).products)
        // Not a category, a search, Fnac's theme page or a home page: products only (an accessory is a product too).
        assertEquals(listOf("PlayStation 5 Slim Disc Edition", "PS5 Pro", "DualSense controller"), pages.map { it.title })
    }

    @Test
    fun aQueryIsCheckedBeforeItIsSent() {
        assertNull(ProductSearch.clean(" "))
        assertNull(ProductSearch.clean("a"))
        assertNull(ProductSearch.clean("x".repeat(151)))
        assertEquals("cheap wireless headphones", ProductSearch.clean("  cheap   wireless headphones "))
        assertEquals("""{"query":"PlayStation 5","limit":25}""", ProductSearch.Request("PlayStation 5").toJson())
    }

    @Test
    fun suggestionsAreWordsOtherThanWhatYouTyped() {
        val json = """{"suggestions":["playstation 5","PlayStation 5","play","playstation 5 pro"," "]}"""
        assertEquals(listOf("playstation 5", "playstation 5 pro"), ProductSearch.parseSuggestions(json, "play"))
        assertEquals(emptyList<String>(), ProductSearch.parseSuggestions("{}", "play"))
        // What you typed, written with a Turkish "ı" by Google, is no suggestion either.
        assertEquals(emptyList<String>(), ProductSearch.parseSuggestions("""{"suggestions":["dyson snoerloze stofzuıger"]}""", "dyson snoerloze stofzuiger"))
    }

    @Test
    fun aWebSearchsPagesAreOnlyBelgianProductPages() {
        // What Exa found for real searches: kept (at its Belgian address) or not.
        // Named as the page's address names it, as Exa's titles do.
        fun kept(url: String) = ProductSearch.productPages(listOf(ProductSearch.Product(Matching.titleFromLink(url) ?: "x", url))).singleOrNull()?.url
        assertEquals("https://www.bol.com/be/nl/p/playstation-5-disc-edition-slim/9300000166374057/",
            kept("https://www.bol.com/nl/nl/p/playstation-5-disc-edition-slim/9300000166374057/"))
        assertEquals("https://www.coolblue.be/en/product/865867/playstation-5-digital-edition.html",
            kept("https://www.coolblue.be/en/product/865867/playstation-5-digital-edition.html"))
        for (url in listOf(
            "https://www.decathlon.be/nl/p/mp/trainingschoenen-nike-air-max-alpha-trainer-5-zwart-wit/6256d9ec-04fb-4fae-9d1a",
            "https://www.amazon.com.be/dp/B0CGMDSSJN",
            "https://www.phonemarket.nl/product/sony-playstation-5-slim-digital-edition-825gb/",
            "https://www.paradigit.nl/sony-wf-c710n-blauw/21864811/product",
            "https://www.gooise-gitaren.nl/contents/nl/p961_Dave-Grohl-DG-335-Pelham-Blue.html",
            "https://www.lego.com/nl-be/product/nike-air-max-95-x-lego-set-43025",
        )) assertEquals(url, kept(url))
        for (url in listOf(
            // A shop's Dutch site when it has a Belgian one.
            "https://www.mediamarkt.nl/nl/product/_sony-wh-ch520-zwart-1751309.html",
            "https://www.amazon.nl/dp/B0HNXV9MPJ",
            "https://www.coolblue.nl/en/advice/compare-the-playstation-5-pro-to-the-playstation-5-slim.html",
            "https://www.sony.nl/store/product/whch720nw.ce7/WH-CH720N-draadloze-koptelefoon-met-Noise-Cancelling",
            // Another country's.
            "https://www.thomann.de/intl/epiphone_dave_grohl_dg_335_pelham_blue.htm",
            "https://www.lego.com/en-us/product/nike-air-max-95-x-lego-big-kids-shoes-5010064",
            // Lists, categories, themes, advice, news, offers, a pdf.
            "https://www.bol.com/nl/nl/l/ps5-consoles/74743/",
            "https://www.krefel.be/nl/c/koptelefoons",
            "https://www.coolblue.be/nl/consoles/playstation5",
            "https://www.lego.com/nl-be/themes/nike",
            "https://www.bax-shop.be/nl/b-stock-aanbiedingen/gitaar?p=7",
            "https://www.zalando.be/schoenen/?q=nike+air+max",
            "https://www.bax-shop.be/images/bax_music_magazine_2022.pdf",
            "https://www.dyson.be/nl_be/stofzuigers/draadloze-stofzuigers/v15-v12-bekijk-alle-modellen",
            "https://www.dyson.be/nl_be/stofzuigers/draadloos/v12/shop-alle-modellen",
            "https://www.mediamarkt.be/nl/content/entertainment-mobility/gaming-hardware/verschil-ps5-pro-en-ps5-slim",
            "https://www.galaxus.be/en/page/sony-presents-the-playstation-5-pro-34674",
            "https://es.nbb.com/es/p/consola-sony-playstation-5-pro-2-tb-de-memoria-ssd-trazado-de-rayos-ampliado",
        )) assertNull(url, kept(url))
        // One page in two languages once.
        val twice = ProductSearch.productPages(listOf(
            ProductSearch.Product("PlayStation 5 Pro Digital Edition", "https://www.coolblue.be/nl/product/954519/playstation-5-pro-digital-edition.html"),
            ProductSearch.Product("PlayStation 5 Pro Digital Edition", "https://www.coolblue.be/en/product/954519/playstation-5-pro-digital-edition.html"),
        ))
        assertEquals(listOf("https://www.coolblue.be/nl/product/954519/playstation-5-pro-digital-edition.html"), twice.map { it.url })
        // The language of a shop's site, not another country: kept.
        assertEquals("https://nl.fnac.be/a20901133/Sony-Console-PS5-Pro", kept("https://nl.fnac.be/a20901133/Sony-Console-PS5-Pro"))
    }

    @Test
    fun aPagesTitleIsTheProductsNameWithoutTheShop() {
        assertEquals("SONY PlayStation 5 Pro 2 TB", ProductSearch.cleanTitle("SONY PlayStation 5 Pro 2 TB | MediaMarkt", "www.mediamarkt.be"))
        assertEquals("PlayStation 5 Pro", ProductSearch.cleanTitle("PlayStation 5 Pro | bol", "www.bol.com"))
        assertEquals("PlayStation 5 Pro Digital Edition", ProductSearch.cleanTitle("PlayStation 5 Pro Digital Edition | Coolblue | Consoles", "www.coolblue.be"))
        assertEquals("Playstation 5 Pro (PlayStation 5)", ProductSearch.cleanTitle("Nedgame gameshop: Playstation 5 Pro (PlayStation 5) kopen", "www.nedgame.nl"))
        assertEquals("Dyson Steelstofzuiger V15 Detect Absolute", ProductSearch.cleanTitle("Dyson Steelstofzuiger V15 Detect Absolute - Beste prijs, snelle levering", "www.krefel.be"))
        assertEquals("Dyson Cyclone V10 Origin", ProductSearch.cleanTitle("Dyson Cyclone V10 Origin - Molecule", "www.molecule.be"))
        assertEquals("Sony Playstation 5 Pro 2TB Digital, W128992851 (Digital)",
            ProductSearch.cleanTitle("Sony Playstation 5 Pro 2TB Digital, W128992851 (Digital) : Amazon.com.be: Electronics", "www.amazon.com.be"))
        assertEquals("Jabra Move Draadloze koptelefoon", ProductSearch.cleanTitle("Koop Jabra Move Draadloze koptelefoon | 5 Jaar Garantie", "www.hifiklubben.nl"))
        // A name with a dash of its own stays whole.
        assertEquals("Sony WH-CH520 - Draadloze on-ear koptelefoon - Zwart", ProductSearch.cleanTitle("Sony WH-CH520 - Draadloze on-ear koptelefoon - Zwart | bol", "www.bol.com"))
    }

    @Test
    fun aShopsCategoriesAndLogosAreLeftOut() {
        val logo = "https://www.molecule.be/media/seo/default/Molecule.jpg"
        val found = ProductSearch.productPages(listOf(
            ProductSearch.Product("Dyson Cyclone V10 Origin - Molecule", "https://www.molecule.be/nl/dyson-cyclone-v10-origin-271000937", imageUrl = logo),
            ProductSearch.Product("Molecule – Sledestofzuigers zonder zak - Molecule", "https://www.molecule.be/nl/huishouden/schoonmaken/stofzuigers/sledestofzuigers-zonder-zak", imageUrl = logo),
            ProductSearch.Product("Dyson Big Ball Parquet 2 stofzuiger zonder zak", "https://www.molecule.be/nl/dyson-big-ball-parquet-2-271000555", imageUrl = logo),
            ProductSearch.Product("Sony Playstation 5 Pro 2TB", "https://www.amazon.com.be/Sony-Playstation-Pro-Digital/dp/B0DM8SFRJV"),
        ))
        assertEquals(listOf("Dyson Cyclone V10 Origin", "Dyson Big Ball Parquet 2 stofzuiger zonder zak", "Sony Playstation 5 Pro 2TB"), found.map { it.title })
        assertEquals(listOf<String?>(null, null, null), found.map { it.imageUrl })
    }
}
