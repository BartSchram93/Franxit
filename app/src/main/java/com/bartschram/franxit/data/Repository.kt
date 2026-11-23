package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import com.bartschram.franxit.scrape.DuckDuckGo
import com.bartschram.franxit.scrape.GoogleShopping
import com.bartschram.franxit.scrape.Matching
import com.bartschram.franxit.scrape.Scraper
import com.bartschram.franxit.scrape.Shoparize
import com.bartschram.franxit.scrape.VergelijkHistory
import com.bartschram.franxit.scrape.VergelijkSearch
import androidx.glance.appwidget.updateAll
import com.bartschram.franxit.widget.PriceListWidget
import com.bartschram.franxit.work.Notifier
import com.bartschram.franxit.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalDate

class Repository private constructor(context: Context) {

    /** How long a looked-up Buy page is used again: a shop's page for a product rarely moves. */
    private val LINK_MS = 30 * 60 * 1000L

    /** At most this many shops' pages are looked up ahead. */
    private val PREPARED_SHOPS = 6

    private val appContext = context.applicationContext
    private val db = Db(appContext)
    private val checkLock = Mutex()
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** While all products are being checked: how many are done, of how many. */
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    suspend fun reload() = withContext(Dispatchers.IO) {
        finishLeftoverDeletes()
        _products.value = db.products()
    }

    /**
     * Swipes whose Undo time was cut short because the app was closed: closing isn't Undo, so
     * delete them now. Ones still counting down in this run are left alone.
     */
    private fun finishLeftoverDeletes() {
        val prefs = appContext.getSharedPreferences(PENDING_DELETES, Context.MODE_PRIVATE)
        val leftover = prefs.all.keys.mapNotNull { it.toLongOrNull() }
            .filter { id -> synchronized(pendingDeletes) { id !in pendingDeletes } }
        for (id in leftover) {
            db.deleteProduct(id)
            Images.file(appContext, id).delete()
            prefs.edit { remove(id.toString()) }
        }
    }

    /** Reloads and redraws home-screen widgets after prices or products changed. */
    private suspend fun changed() {
        reload()
        runCatching { PriceListWidget().updateAll(appContext) }
    }

    /**
     * Starts tracking [url] and checks its price right away. Returns the product id.
     * [name] is what the search called it, shown until the first check names it properly.
     * [onCreated] is called at once, before anything is fetched, so the product can open with
     * its searching words. A check that fails is shown on the product (why, in words), not thrown.
     */
    suspend fun add(url: String, name: String? = null, onCreated: (Long) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url).host }.getOrNull() ?: url
        // Until the first check names the product: the shop's address, or a plain label for a comparison page.
        val placeholder = if (host.endsWith("vergelijk.be")) "New product" else host
        val id = db.insertProduct(url, name?.trim()?.takeIf { it.isNotEmpty() } ?: placeholder)
        // Its own nightly check, at a moment of its own between 2 and 6.
        Scheduler.schedule(appContext, id)
        reload()
        onCreated(id)
        db.products().firstOrNull { it.id == id }?.let {
            checkLock.withLock {
                // vergelijk.be's shops are stored and shown first, then Google Shopping's are added.
                runCatching { check(it, LOOKUPS_ONE) { _products.value = db.products() } }
            }
        }
        changed()
        id
    }

    private val _removing = MutableStateFlow<Set<Long>>(emptySet())
    /** Products swiped away whose Undo time is still running: hidden, not yet deleted. */
    val removing: StateFlow<Set<Long>> = _removing
    private val pendingDeletes = mutableMapOf<Long, Job>()
    // Outlives the screens, so a delete still happens when you open a product or leave the app.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Hides [id] now and deletes it after the Undo time, unless [undoDelete] comes first. */
    fun deleteAfterUndoTime(id: Long) {
        _removing.update { it + id }
        // Written down right away, so closing the app during the Undo time still deletes it.
        appContext.getSharedPreferences(PENDING_DELETES, Context.MODE_PRIVATE).edit { putBoolean(id.toString(), true) }
        synchronized(pendingDeletes) {
            pendingDeletes.remove(id)?.cancel()
            pendingDeletes[id] = background.launch {
                delay(UNDO_MS)
                delete(id)
                appContext.getSharedPreferences(PENDING_DELETES, Context.MODE_PRIVATE).edit { remove(id.toString()) }
                synchronized(pendingDeletes) { pendingDeletes.remove(id) }
                _removing.update { it - id }
            }
        }
    }

    fun undoDelete(id: Long) {
        synchronized(pendingDeletes) { pendingDeletes.remove(id)?.cancel() }
        appContext.getSharedPreferences(PENDING_DELETES, Context.MODE_PRIVATE).edit { remove(id.toString()) }
        _removing.update { it - id }
    }

    /** Whether a product is still tracked (a nightly check of a deleted one stops). */
    suspend fun exists(id: Long): Boolean = withContext(Dispatchers.IO) { db.products().any { it.id == id } }

    /** Every product's own nightly check, on app start (see Scheduler). */
    fun scheduleChecks() {
        Thread { runCatching { Scheduler.ensureScheduled(appContext, db.products().map { it.id }) } }.start()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        Scheduler.cancel(appContext, id)
        db.deleteProduct(id)
        Images.file(appContext, id).delete()
        changed()
    }

    suspend fun rename(id: Long, name: String?) = withContext(Dispatchers.IO) {
        db.setCustomName(id, name)
        changed()
    }

    suspend fun setNotify(id: Long, notify: Boolean) = withContext(Dispatchers.IO) {
        db.setNotify(id, notify)
        changed()
    }

    suspend fun setTarget(id: Long, price: Double?) = withContext(Dispatchers.IO) {
        db.setTargetPrice(id, price)
        changed()
    }

    /**
     * A fresh link to the product page at [seller], or at the cheapest shop when [seller] is null.
     * Null when the site has no shop links (then the product page itself is the shop).
     * vergelijk.be's shop links expire, so this reads the page again instead of storing them.
     */
    /**
     * Google Shopping's shops for a vergelijk.be product with "Also search Google Shopping" on:
     * searched once a day, then reused. Never fails the check; without results it's just vergelijk.be.
     */
    private fun searchesExtra(query: String?, ownShops: Int) =
        query == null && ownShops <= FEW_SHOPS && ShoppingSettings.enabled()

    private class Extras(val offers: List<ShopOffer>, val error: String? = null)

    private fun extraShops(product: Product, query: String?, reference: Double?, ownShops: Int): Extras {
        if (!searchesExtra(query, ownShops)) return Extras(emptyList())
        val today = LocalDate.now().toString()
        ShoppingSettings.extraOffers(appContext, product.id, today)?.let { return Extras(it) }
        // What results are held against: the name with its model code ("… Zilverkleurig" + "GM-2100YM-8AER"), so another colour's code is out.
        val matchName = matchName(product)
        // The wording that worked last time first, then the product's name, its fuller name, and "kopen".
        val terms = SearchNames.googleTerms(product, ShoppingSettings.extraTerm(appContext, product.id), ShoppingSettings.brand(appContext, product.id))
        val found = try {
            searchGoogleShopping(product.id, matchName, terms, reference) { ShoppingSettings.setExtraTerm(appContext, product.id, it) }
        } catch (e: Exception) {
            return when (e.message) {
                // Nothing for this product: asked once a day, not at every refresh.
                GoogleShopping.NOT_FOUND -> {
                    ShoppingSettings.saveExtraOffers(appContext, product.id, today, emptyList(), days = OfferCache.GOOGLE_NOTHING_DAYS)
                    Extras(emptyList())
                }
                // Out of searches, a key that doesn't work, no connection: the customer isn't told, it just
                // doesn't add shops until it works again (asked again at the next check).
                else -> Extras(emptyList(), SILENT)
            }
        }
        found.image?.let { photo ->
            if (Photos.choice(appContext, product.id) == Photos.Choice.GOOGLE) runCatching { Images.save(appContext, product.id, photo) }
        }
        val offers = found.offers.map { ShopOffer(it.seller, it.price, it.inStock) }
        ShoppingSettings.saveExtraOffers(appContext, product.id, today, offers)
        return Extras(offers)
    }

    /**
     * Shoparize's shops for a product from vergelijk.be (or a shop page), as extra prices: searched once a day,
     * then reused. Never fails the check; without results it's just the other sources. Every shop Shoparize
     * lists is trusted (and trusted for Google Shopping's results too), except marketplaces; refurbished offers
     * are left out when the page is read. Always, after vergelijk.be or Google Shopping has searched, whatever
     * the number of shops: for a Google Shopping product ([query]) it searches the words you typed.
     */
    private fun shoparizeShops(product: Product, query: String?): List<ShopOffer> {
        val today = LocalDate.now().toString()
        ShoppingSettings.shoparizeOffers(appContext, product.id, today)?.let { return it }
        val found = try {
            // Searched with the brand ("999 Games Patchwork"), held against the name too: titles often leave it out.
            Shoparize.productFor(
                SearchNames.shoparize(product, query, ShoppingSettings.brand(appContext, product.id)),
                also = query ?: matchName(product),
            )
        } catch (e: Exception) {
            // No connection, or the site changed: nothing is saved, so it is asked again at the next check.
            return emptyList()
        }
        found.title?.let { ShoppingSettings.saveShoparizeTitle(appContext, product.id, it) }
        val listed = found.offers.filterNot { GoogleShopping.isMarketplace(it.seller) }
        val offers = ShopPrices.fromShoparize(found.offers)
        TrustedShops.learn(appContext, offers.map { it.seller })
        // The shop's own product page where Shoparize's link names it (cleaned of tracking); else the Buy button searches.
        ShoppingSettings.saveShoparizeLinks(appContext, product.id, listed.mapNotNull { offer -> offer.link?.let { Shops.name(offer.seller) to it } }.toMap())
        // Kept for the day, also when nothing was found: Shoparize sometimes answers with an empty page for a
        // while, and that must not hide its shops for days.
        ShoppingSettings.saveShoparizeOffers(appContext, product.id, today, offers, days = OfferCache.SHOPARIZE_DAYS)
        return offers
    }

    /** What results are held against (see SearchNames, where the cases are tested). */
    private fun matchName(product: Product): String = SearchNames.match(product)

    /** Switches a product's photo; the page's own photo comes back with a check (no Google search). */
    suspend fun setPhoto(productId: Long, choice: Photos.Choice) = withContext(Dispatchers.IO) {
        Photos.setChoice(appContext, productId, choice)
        Images.delete(appContext, productId)
        when (choice) {
            Photos.Choice.NONE -> Unit
            Photos.Choice.GOOGLE -> Photos.googlePhoto(appContext, productId)?.let { runCatching { Images.save(appContext, productId, it) } }
            // The page's photo from its last check; only without one, a new check fetches it.
            Photos.Choice.SITE -> Photos.sitePhoto(appContext, productId)
                ?.let { photo -> runCatching { Images.save(appContext, productId, photo) }.getOrNull() }
                ?: checkOne(productId)
        }
        changed()
    }

    /** Google Shopping's shops for a product; remembers each shop's page for the Buy button. */
    private fun searchGoogleShopping(
        productId: Long,
        query: String,
        terms: List<String> = listOf(query),
        reference: Double? = null,
        onTerm: (String) -> Unit = {},
    ): Scraper.Result {
        // With the app in French, French pages are welcome too.
        GoogleShopping.allowFrench = com.bartschram.franxit.ui.L10n.lang == com.bartschram.franxit.ui.L10n.Lang.FR
        val found = GoogleShopping.fetch(query, { TrustedShops.name(appContext, it) }, terms, reference)
        found.term?.let(onTerm)
        // Every search keeps its best result's photo, for "Use Google Shopping photo".
        found.result.image?.let { Photos.saveGooglePhoto(appContext, productId, it) }
        ShoppingSettings.saveLinks(appContext, productId, found.links)
        return found.result
    }


    /** Today's shops of [product] as their own pages show them now, stored again when that changed anything. */
    private fun recheckShopPages(product: Product, lookups: Int) {
        val checked = checkShopPages(product, product.offers, Scraper.Result(product.name, emptyList()), lookups)
        if (checked == product.offers) return
        db.replaceOffers(product.id, checked)
        val cheapest = Offers.cheapest(checked) ?: return
        val now = System.currentTimeMillis()
        db.savePrice(product.id, PricePoint(LocalDate.now(), cheapest.price, cheapest.seller, checked.size, now, cheapest.inStock))
    }

    /** Shop pages read at the same time; a check waits at most [SHOP_CHECK_SECONDS] for all of them. */
    private val shopReaders = java.util.concurrent.Executors.newFixedThreadPool(8)
    private val SHOP_CHECK_SECONDS = 25L
    /** What a shop's page said, by its link, with when: read again after [SHOP_CHECK_MS]. */
    private val shopPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<ShopCheck.Seen?, String>>>()
    private val SHOP_CHECK_MS = 3 * 60 * 60 * 1000L

    /**
     * [offers] as each shop's own product page shows them, where the app knows that page (from Google Shopping or
     * Shoparize; not a search): Google lists prices without saying whether a shop has the product (Coolblue's
     * Zelda Edition, sold out). The page the product was added from is already read ([own]). A page that can't be
     * read, or is of another product, changes nothing.
     */
    private fun checkShopPages(product: Product, offers: List<ShopOffer>, own: Scraper.Result, lookups: Int): List<ShopOffer> {
        val ownHost = runCatching { URI(product.url).host.removePrefix("www.") }.getOrDefault("")
        val name = own.name.ifBlank { product.displayName }
        // Links the version before found itself and kept as if Google gave them: dropped once (a list of ink at 123inkt.be).
        val fromGoogle = if (GoogleShopping.queryOf(product.url) != null) offers.map { it.seller }
            else ShoppingSettings.lastExtraOffers(appContext, product.id).orEmpty().map { it.seller }
        ShoppingSettings.cleanFoundLinksOnce(appContext, product.id, fromGoogle.toSet())
        // How each shop was checked, shown under it (ShopCheck.Status); shops not checked this time keep their last word.
        val statuses = java.util.concurrent.ConcurrentHashMap<String, String>()
        // vergelijk.be's own link for each of its shops, followed once per shop and product (it counts as a click there):
        // the page you land on when you tap the shop, read from then on. Exactly the page of the price you'd pay.
        val clickLinks = own.offers.mapNotNull { o -> o.link?.takeIf { "vergelijk.be/clickout" in it }?.let { o.seller to it } }
        val landings = java.util.concurrent.ConcurrentHashMap<String, String>()
        // Shops whose site is another country's (fotodevakman.nl): not shown, the app is for shopping in Belgium.
        val foreign = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val toFollow = mutableListOf<java.util.concurrent.Callable<Unit>>()
        for (offer in offers) {
            if (DuckDuckGo.isForeignSite("https://" + Shops.name(offer.seller).lowercase())) {
                foreign += offer.seller
                continue
            }
            val known = ShoppingSettings.landing(appContext, product.id, offer.seller)
            if (known != null) {
                // (One remembered before this rule: a landing on another country's site.)
                if (known == FOREIGN || (known != "-" && DuckDuckGo.isForeignSite(known))) foreign += offer.seller
                else if (known != "-") landings[offer.seller] = known
                continue
            }
            val click = clickLinks.firstOrNull { TrustedShops.same(it.first, offer.seller) }?.second ?: continue
            toFollow += java.util.concurrent.Callable {
                val end = runCatching { DuckDuckGo.belgian(Scraper.finalUrl(click)) }.getOrNull()
                if (end != null && DuckDuckGo.isForeignSite(end) && ShopCheck.isProductPage(end)) {
                    ShoppingSettings.saveLanding(appContext, product.id, offer.seller, FOREIGN)
                    foreign += offer.seller
                    return@Callable
                }
                val landing = end?.takeIf { ShopCheck.isLandingOf(it, offer.seller) }
                ShoppingSettings.saveLanding(appContext, product.id, offer.seller, landing ?: "-")
                if (landing != null) landings[offer.seller] = landing
            }
        }
        if (toFollow.isNotEmpty()) runCatching { shopReaders.invokeAll(toFollow, SHOP_CHECK_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
        // Shops whose only link (from Google or Shoparize) is clearly another product (a bundle at Coolblue): looked
        // up instead; left out when their right page isn't found, as their price is that other product's.
        val wrongLinks = mutableSetOf<String>()
        val links = offers.associate { offer ->
            // A link through Google (".../url?q=https://www.coolblue.be/...") is read at the shop's own address.
            // The page vergelijk.be's link lands on first: it is the shop's page for the price listed.
            landings[offer.seller]?.let { return@associate offer.seller to it }
            val link = listOf(ShoppingSettings.link(appContext, product.id, offer.seller), ShoppingSettings.shoparizeLink(appContext, product.id, offer.seller))
                .map(ShopCheck::direct)
                .map { it?.let(DuckDuckGo::belgian) }
                // A page for another country (Nintendo's UK store, in pounds) is no link: the shop is looked up anew.
                .firstOrNull { it != null && ShopCheck.isProductPage(it) && !DuckDuckGo.isOtherCountry(it) && !DuckDuckGo.isForeignSite(it) && ShopCheck.isShopsLink(it, offer.seller) }
                ?.takeIf { !TrustedShops.same(offer.seller, ownHost) }
            if (link != null && ShopCheck.isElsewhere(link, own.name.ifBlank { product.displayName })) {
                wrongLinks += offer.seller
                offer.seller to null
            } else offer.seller to link
        }
        // The page the product was added from is read already.
        offers.filter { TrustedShops.same(it.seller, ownHost) }.forEach { statuses[it.seller] = ShopCheck.Status.CHECKED }
        // A shop listed without a page of its own (Nintendo's store from Google, Kamera Express from vergelijk.be,
        // whose links are clicks it counts): looked up on DuckDuckGo, on its site when known, else by its name,
        // the cheapest first, at most [lookups] per check and once a day per shop. A page that is this product is
        // remembered: read from then on, and opened by the Buy button.
        val today = LocalDate.now().toString()
        // Pages found earlier (and read as this product then): read again, as long as they stay this product.
        val remembered = offers.filter { links[it.seller] == null && !TrustedShops.same(it.seller, ownHost) }
            .mapNotNull { offer ->
                val link = ShoppingSettings.foundLink(appContext, product.id, offer.seller) ?: return@mapNotNull null
                if (DuckDuckGo.isOtherCountry(link) || DuckDuckGo.isForeignSite(link) || !ShopCheck.isShopsLink(link, offer.seller)) {
                    ShoppingSettings.forgetFoundLink(appContext, product.id, offer.seller)
                    null
                } else offer.seller to link
            }.toMap()
        val toFind = offers.filter { links[it.seller] == null && it.seller !in remembered && !TrustedShops.same(it.seller, ownHost) }
            .sortedBy { it.price }
            .map { offer -> offer.seller to GoogleShopping.siteOf(offer.seller) }
            // Once a day per shop, counted only when the search got an answer: one DuckDuckGo blocked is tried again.
            .filter { (seller, _) -> lookedUp["${product.id}|$seller"] != today }
            .take(lookups)
        // With the colour the full name has ("Zwart"): the search finds that one first.
        val colour = Matching.colourWords(name).filter { it !in product.displayName.lowercase() }.joinToString(" ")
        val searchName = (SearchNames.withBrand(product.displayName, ShoppingSettings.brand(appContext, product.id)) + " " + colour).trim()
        // A result whose address names another colour or product ("…-cp1500-blanc") is passed over for the next one.
        val fits = fits@{ url: String ->
            // A search or list page ("…/s?k=…") is no product's page.
            if (ShopCheck.isElsewhere(url, name)) return@fits false
            val words = Matching.titleFromLink(url) ?: return@fits true
            !Matching.otherColour(words, name) && !Matching.isOtherProduct(words, name) &&
                !Matching.otherEdition(words, name) && !Matching.addsBundleOrAccessory(words, name)
        }
        // When DuckDuckGo blocks the app's searches: a few in a browser instead.
        // One product refreshed: more of its shops in the browser (and more time); all products at once: a few.
        val single = lookups >= LOOKUPS_ONE
        val browserBudget = if (single) BROWSER_ONE else BROWSER_READS
        val waitSeconds = if (single) BROWSER_WAIT_SECONDS else SHOP_CHECK_SECONDS
        val browserSearches = java.util.concurrent.atomic.AtomicInteger(browserBudget)
        val browser = { url: String, ready: (String) -> Boolean -> if (browserSearches.getAndDecrement() > 0) BrowserPages.html(appContext, url, ready) else null }
        // What happened to each shop, shown when you hold it down: to find out why a price wasn't checked.
        val notes = java.util.concurrent.ConcurrentHashMap<String, String>()
        val found = toFind.map { (seller, site) ->
            java.util.concurrent.Callable {
                val lookup = runCatching { if (site != null) DuckDuckGo.productPage(searchName, site, browser, fits) else DuckDuckGo.shopPage(searchName, seller, browser, fits) }
                    .getOrElse { DuckDuckGo.Lookup(null, blocked = true) }
                // Not a page that its address says is another product.
                val page = lookup.page?.takeIf { url -> !Matching.isOtherProduct(Matching.titleFromLink(url).orEmpty(), name) }
                // Found only on another country's site (fotodevakman.nl): a shop of that country, not shown.
                if (page == null && lookup.foreign != null) {
                    ShoppingSettings.saveLanding(appContext, product.id, seller, FOREIGN)
                    foreign += seller
                }
                notes[seller] = when {
                    page != null -> "Found: $page"
                    lookup.foreign != null -> "Only on another country's site: ${lookup.foreign}"
                    lookup.page != null -> "Found another product: ${lookup.page}"
                    lookup.blocked -> "Search blocked (DuckDuckGo, browser, Bing)"
                    else -> "No page found on " + (site ?: "the shop's site")
                }
                if (page == null) statuses[seller] = if (lookup.blocked) ShopCheck.Status.SEARCH_BLOCKED else ShopCheck.Status.NOT_FOUND
                if (!lookup.blocked) lookedUp["${product.id}|$seller"] = today
                seller to page
            }
        }.let { lookupTasks ->
            if (lookupTasks.isEmpty()) emptyList<java.util.concurrent.Future<Pair<String, String?>>>()
            else runCatching { shopReaders.invokeAll(lookupTasks, waitSeconds, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        }
            .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
            .mapNotNull { (seller, page) -> page?.let { seller to it } }.toMap()
        // Found pages are only kept once read as this product (below): 123inkt's first find was a list of ink.
        val foundLinks = remembered + found
        val allLinks = links + foundLinks
        // What each page said, by link, with its status.
        // Sites only a browser could read before: straight to the browser, without a request that is kept out anyway.
        val browserSites = ShoppingSettings.browserSites(appContext)
        fun siteOfLink(link: String) = runCatching { URI(link).host.removePrefix("www.") }.getOrDefault("")
        val tasks = allLinks.values.filterNotNull().distinct().map { link ->
            java.util.concurrent.Callable {
                // A search or list page, or another product's page by its address: not read, the shop is left out.
                if (ShopCheck.isElsewhere(link, name)) return@Callable Triple(link, ShopCheck.ELSEWHERE, ShopCheck.Status.CHECKED)
                shopPages[link]?.takeIf { System.currentTimeMillis() - it.first < SHOP_CHECK_MS }?.let { return@Callable Triple(link, it.second.first, it.second.second) }
                if (siteOfLink(link) in browserSites) return@Callable Triple(link, null, ShopCheck.Status.BLOCKED)
                var error: String? = null
                val seen = try {
                    // A shop that takes long is given up on sooner: many pages are read for one check.
                    ShopCheck.seen(Scraper.fetch(link, readTimeoutMs = 12_000), name)
                } catch (e: IOException) {
                    error = e.message
                    // "Not available at … right now": the page says sold out without a price.
                    if (e.message?.startsWith("Not available at") == true) ShopCheck.SOLD_OUT else null
                } catch (e: Exception) {
                    error = e.message
                    null
                }
                val status = ShopCheck.status(seen, error)
                shopPages[link] = System.currentTimeMillis() to (seen to status)
                Triple(link, seen, status)
            }
        }
        val seen = (if (tasks.isEmpty()) emptyMap() else
            runCatching { shopReaders.invokeAll(tasks, SHOP_CHECK_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .associate { (link, page, status) -> link to (page to status) }).toMutableMap()
        // Shops that keep the app out (Fnac, Nintendo's store): their pages in a real browser, the cheapest shops
        // first, two at a time (a browser is heavy). What it reads counts like any page.
        val priceOfLink = offers.mapNotNull { offer -> allLinks[offer.seller]?.let { it to offer.price } }.toMap()
        // Also pages whose price the app couldn't read: shops that only show it with their scripts, which a browser runs.
        val browserReads = seen.filter { (_, value) -> value.second == ShopCheck.Status.BLOCKED || value.second == ShopCheck.Status.UNREADABLE }.keys
            .sortedBy { priceOfLink[it] ?: Double.MAX_VALUE }.take(browserBudget)
            .map { link ->
                java.util.concurrent.Callable {
                    val html = runCatching { BrowserPages.html(appContext, link) }.getOrNull() ?: return@Callable null
                    val page = runCatching { ShopCheck.seen(Scraper.fromPage(html, link), name) }.getOrElse { e ->
                        if (e.message?.startsWith("Not available at") == true) ShopCheck.SOLD_OUT else null
                    } ?: return@Callable null
                    link to page
                }
            }
        if (browserReads.isNotEmpty()) {
            runCatching { browserReaders.invokeAll(browserReads, waitSeconds, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .forEach { (link, page) ->
                    seen[link] = page to ShopCheck.Status.CHECKED
                    shopPages[link] = System.currentTimeMillis() to (page to ShopCheck.Status.CHECKED)
                    ShoppingSettings.addBrowserSite(appContext, siteOfLink(link))
                }
        }
        offers.forEach { offer -> allLinks[offer.seller]?.let { seen[it] }?.let { statuses[offer.seller] = it.second } }
        offers.forEach { offer ->
            val link = allLinks[offer.seller] ?: return@forEach
            val read = seen[link]
            val what = when {
                read == null -> "not read in time"
                read.first?.otherColour == true -> "another colour"
                read.first?.otherProduct == true -> "another product or a list"
                read.first != null -> "price ${read.first?.price ?: "-"}, in stock ${read.first?.inStock ?: "?"}"
                read.second == ShopCheck.Status.BLOCKED -> "the shop keeps the app out"
                else -> "no price readable"
            }
            notes[offer.seller] = (notes[offer.seller]?.let { "$it\n" } ?: "") + "Read $link: $what"
        }
        ShoppingSettings.saveShopNotes(appContext, product.id, notes)
        // A page the app found itself: kept when it is this product, else forgotten, and then it says nothing about
        // the shop (which stays, with the price its source gave): it may just be the wrong page of that shop.
        val wrongFinds = mutableSetOf<String>()
        foundLinks.forEach { (seller, link) ->
            val page = seen[link]?.first
            if (page != null && !page.otherColour && !page.otherProduct) {
                ShoppingSettings.saveFoundLink(appContext, product.id, seller, link)
            } else {
                ShoppingSettings.forgetFoundLink(appContext, product.id, seller)
                if (page != null || ShopCheck.isElsewhere(link, name)) statuses[seller] = ShopCheck.Status.NOT_FOUND
                wrongFinds += seller
            }
        }
        ShoppingSettings.saveShopStatuses(appContext, product.id, statuses)
        // Shops the product's own page lists (vergelijk.be's bol.com): their price is that page's, so a link from another
        // source that turns out to be another version (another colour, another product) says nothing about it.
        // (For a Google Shopping product its own offers are Google's: a page of another version leaves that shop out.)
        val ownSellers = if (GoogleShopping.queryOf(product.url) != null) emptyList() else own.offers.map { it.seller }
        return offers.mapNotNull { offer ->
            if (offer.seller in foreign) return@mapNotNull null
            var page = if (offer.seller in wrongFinds) null else allLinks[offer.seller]?.let { seen[it]?.first }
            // Its source's link was another product, and its right page wasn't found: the price is that product's.
            if (page == null && offer.seller in wrongLinks && (allLinks[offer.seller] == null || offer.seller in wrongFinds)) page = ShopCheck.ELSEWHERE
            if (page != null && (page.otherColour || page.otherProduct) && ownSellers.any { TrustedShops.same(it, offer.seller) }) page = null
            ShopCheck.apply(offer, page)
        }
    }

    /** Shops looked up on DuckDuckGo, by product and shop, with the day: once a day each. */
    private val lookedUp = java.util.concurrent.ConcurrentHashMap<String, String>()
    /**
     * Shops looked up per product: every one without a known page when you add or refresh that one product (every
     * price has to be right), a few when all products are checked at once (the daily check, which has to stay within
     * Android's time for background work); pages found are remembered, so each daily check adds a few more.
     */
    private val LOOKUPS_ONE = Int.MAX_VALUE
    private val LOOKUPS_ALL = 5

    /** What's remembered for a shop whose vergelijk.be link lands on another country's site. */
    private val FOREIGN = "foreign"

    /** Pages read in a browser per check, for shops that keep the app's own requests out. */
    private val BROWSER_READS = 3

    /** Searches and pages in the browser when one product is refreshed (all of them), and how long they may take. */
    private val BROWSER_ONE = Int.MAX_VALUE
    private val BROWSER_WAIT_SECONDS = 180L

    /** How long a tap on Buy waits for Google's page of a shop before it opens a search on the shop's site. */
    private val TAP_WAIT_MS = 6_000L

    /** Browsers at the same time: two (each is a whole web page with its scripts). */
    private val browserReaders = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Only web addresses are ever opened: a link from a shop or from Google is not trusted with any other kind. */
    suspend fun shopLink(product: Product, seller: String? = null): String? {
        // By the shop the button names: after a check the cheapest can be another shop.
        val key = "${product.id}|${BuyLink.shop(seller, product.offers).orEmpty()}"
        links[key]?.takeIf { System.currentTimeMillis() - it.first < LINK_MS }?.let { return it.second }
        val link = shopLinkUnchecked(product, seller)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            // A shop's search or list page, or another product's page ("fr.fnac.be/se77634/La-Legende-des-Quatres"
            // for a keyboard bench): a search for the product on the shop's site instead.
            ?.let { found ->
                val shop = BuyLink.shop(seller, product.offers)
                if (shop != null && ShopCheck.isProductPage(found) && ShopCheck.isElsewhere(found, product.name)) {
                    BuyLink.orSearch(null, shop, buyName(product))
                } else found
            }
        if (link != null) links[key] = System.currentTimeMillis() to link
        return link
    }

    /**
     * Looks up the Buy button's pages for [product] ahead of a tap: the cheapest shop's and each shop's in the list,
     * with vergelijk.be's page read once for all. A tap then opens the shop at once instead of after a download.
     */
    suspend fun prepareShopLinks(product: Product) {
        // Not a Google Shopping shop whose page isn't known yet: looking that up is a search, only spent on a tap.
        fun searchNeeded(shop: String?) = shop != null &&
            ShoppingSettings.link(appContext, product.id, shop)?.startsWith(GoogleShopping.TOKEN) == true
        withContext(Dispatchers.IO) {
            if (!searchNeeded(BuyLink.shop(null, product.offers))) runCatching { shopLink(product, null) }
            val sellers = product.offers.map { it.seller }.distinct().filterNot(::searchNeeded)
            for (seller in sellers.take(PREPARED_SHOPS)) runCatching { shopLink(product, seller) }
        }
    }

    /** The Buy button's pages already looked up, by product and shop, with when: kept [LINK_MS]. */
    private val links = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** vergelijk.be's product pages read for the Buy button, kept [LINK_MS]: one read serves every shop's button. */
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Scraper.Result>>()

    private fun buyPage(url: String): Scraper.Result {
        pages[url]?.takeIf { System.currentTimeMillis() - it.first < LINK_MS }?.let { return it.second }
        return Scraper.fetch(url).also { pages[url] = System.currentTimeMillis() to it }
    }

    private suspend fun shopLinkUnchecked(product: Product, seller: String? = null): String? = withContext(Dispatchers.IO) {
        // A shop found through Google Shopping: its page from the last search (a new search would cost one).
        val shop = BuyLink.shop(seller, product.offers)
        // A page the app found itself and read as this product: when no source gives the shop's product page.
        val foundPage = shop?.let { ShoppingSettings.foundLink(appContext, product.id, it) }?.takeIf { !DuckDuckGo.isOtherCountry(it) }
        shop?.let { ShoppingSettings.link(appContext, product.id, it) }?.let { link ->
            val page = if (link.startsWith(GoogleShopping.TOKEN)) {
                // A page the app found and read as this product: opened at once, no search spent or waited for.
                if (foundPage != null) return@withContext foundPage
                // Google's page for the shop is a search that can take a minute or two ("Buy at Bol.com" kept loading):
                // the tap waits a few seconds, then opens a search on the shop's site; the lookup goes on and is
                // remembered, so the next tap opens the shop's page.
                val lookup = background.async { lookUpStoreLink(product, shop, link.removePrefix(GoogleShopping.TOKEN)) }
                withTimeoutOrNull(TAP_WAIT_MS) { lookup.await() } ?: run {
                    lookup.invokeOnCompletion { links.keys.removeIf { it.startsWith("${product.id}|") } }
                    return@withContext GoogleShopping.fallbackUrl(GoogleShopping.queryOf(product.url) ?: product.displayName, shop)
                }
            } else link
            // Google's link when it is the shop's page; a search only when nothing better is known.
            if (ShopCheck.isProductPage(ShopCheck.direct(page)) || foundPage == null) return@withContext page
        }
        if (GoogleShopping.queryOf(product.url) != null) return@withContext shop?.let { shoparizeShopLink(product, it) } ?: foundPage
        // A shop's own page you added (fitshop.be), with Google Shopping's shops next to it.
        val host = runCatching { URI(product.url).host.removePrefix("www.") }.getOrDefault("")
        // Another shop than that page's ("Buy at Bol.com" on a Multibazar page): through Shoparize, never that page.
        val found = if ("vergelijk.be" !in host) {
            BuyLink.ownPage(product.url, shop) ?: shop?.let { shoparizeShopLink(product, it) }
        } else {
            // The button's shop on vergelijk.be's page, else through Shoparize (see BuyLink, where the cases are tested).
            runCatching { BuyLink.fromPage(buyPage(product.url), shop) { shoparizeShopLink(product, it) } }.getOrNull()
                ?: shop?.let { shoparizeShopLink(product, it) }
        }
        BuyLink.orSearch(found ?: foundPage, shop, buyName(product))
    }

    /**
     * A shop that Shoparize listed for this product: where it can be bought is the shop's own product page when
     * Shoparize's link names it (cleaned of tracking), else the shop's own search for the product. Shoparize's
     * links themselves are never opened. Null for any other shop.
     */
    private fun shoparizeShopLink(product: Product, shop: String): String? {
        val listed = ShoppingSettings.lastShoparizeOffers(appContext, product.id) ?: return null
        return BuyLink.fromShoparize(listed, shop, ShoppingSettings.shoparizeLink(appContext, product.id, shop), buyName(product))
    }

    /** The name a shop's site is searched with for the Buy button (see BuyLink.searchName). */
    private fun buyName(product: Product): String =
        BuyLink.searchName(
            SearchNames.withBrand(product.displayName, ShoppingSettings.brand(appContext, product.id)),
            ShoppingSettings.shoparizeTitle(appContext, product.id),
        )

    /**
     * The shop's own page, looked up once and remembered. Without a key, searches left or a match,
     * the Google Shopping search for the product at that shop.
     */
    private fun lookUpStoreLink(product: Product, shop: String, token: String): String {
        val fallback = GoogleShopping.fallbackUrl(GoogleShopping.queryOf(product.url) ?: product.displayName, shop)
        val link = runCatching {
            GoogleShopping.storeLink(token, shop) { a, b -> TrustedShops.same(a, b) }
        }.getOrNull() ?: return fallback
        ShoppingSettings.saveLink(appContext, product.id, shop, link)
        return link
    }

    /** What a shared product is, shown before you decide to track it: its name, photo and lowest price. */
    data class Preview(val name: String, val image: String?, val price: Double?)

    /**
     * The [Preview] of a shared product's page, read once (nothing is saved); null when the page can't be read.
     * A Google Shopping product is its search words: no search is spent on a preview.
     */
    suspend fun preview(url: String): Preview? = withContext(Dispatchers.IO) {
        GoogleShopping.queryOf(url)?.let { return@withContext Preview(it, null, null) }
        val page = runCatching { Scraper.fetch(url) }.getOrNull() ?: return@withContext null
        val name = Names.short(page.name).ifBlank { page.name }.takeIf { it.isNotBlank() } ?: return@withContext null
        Preview(name, page.image, page.offers.takeIf { it.isNotEmpty() }?.let { page.cheapest.price })
    }

    suspend fun search(query: String): List<VergelijkSearch.Hit> = withContext(Dispatchers.IO) {
        VergelijkSearch.search(query)
    }

    /**
     * What a shop link is for, to find it on vergelijk.be: the page's full product title (colour
     * and all, to match the right version), or the link's words.
     */
    suspend fun productTitleFor(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { Scraper.fetch(url).name }.getOrNull()?.takeIf { it.isNotBlank() } ?: Names.fromUrl(url)
    }

    suspend fun checkAll(): CheckSummary = withContext(Dispatchers.IO) {
        checkLock.withLock {
            val products = db.products()
            val done = AtomicInteger()
            _progress.value = 0 to products.size
            // A few products at once: a check mostly waits on the network, so one after the other
            // made a refresh take as long as all the page loads added up.
            val gate = Semaphore(PARALLEL_CHECKS)
            val results = coroutineScope {
                products.map { product ->
                    async {
                        gate.withPermit { runCatching { check(product) } }
                            .also { _progress.value = done.incrementAndGet() to products.size }
                    }
                }.awaitAll()
            }
            _progress.value = null
            changed()
            val failed = results.count { it.isFailure }
            CheckSummary(products.size - failed, failed, results.mapNotNull { it.getOrNull() })
        }
    }

    suspend fun checkOne(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        checkLock.withLock {
            val result = runCatching { db.products().first { it.id == id }.let { check(it, LOOKUPS_ONE) { _products.value = db.products() } } }
            changed()
            result.map { }
        }
    }

    /** Scrapes one product and stores today's cheapest price. Returns an [Alert] when worth notifying. */
    private fun check(product: Product, lookups: Int = LOOKUPS_ALL, onFirstResults: (() -> Unit)? = null): Alert? {
        val now = System.currentTimeMillis()
        val query = GoogleShopping.queryOf(product.url)
        // Google Shopping costs a search of your plan: at most once a day per product. The shops' own pages cost
        // none: a refresh reads them again (sold out since this morning at Coolblue).
        if (query != null && product.latest?.day == LocalDate.now() && product.lastError == null) {
            recheckShopPages(product, lookups)
            return null
        }
        val result = try {
            if (query == null) Scraper.fetch(product.url) else searchGoogleShopping(product.id, query)
        } catch (e: Exception) {
            // Google Shopping used up (or its key refused): not the customer's problem, tried again at the next check.
            if (query != null && e.message in setOf(GoogleShopping.NO_SEARCHES_LEFT, GoogleShopping.BAD_KEY)) return null
            db.markChecked(product.id, now, null, e.message ?: e.javaClass.simpleName)
            throw e
        }
        // The page shows another product now: keep the history clean instead of saving its prices.
        // Only once it has been read before: a new product's name is just a placeholder.
        if (query == null && product.history.isNotEmpty() && !Matching.isStillTheSame(product.name, result.name)) {
            val message = Scraper.goneMessage(product.url)
            db.markChecked(product.id, now, null, message)
            throw IOException(message)
        }
        val today = LocalDate.now()
        // Shops selling their own products (e.g. Coolblue) don't name a seller; use the shop's domain.
        val host = runCatching { URI(product.url).host.removePrefix("www.") }.getOrDefault("")
        // The shop's own page (or vergelijk.be) lists what you can buy: in stock unless it says otherwise.
        // Google Shopping's shops stay "unknown" (null) and don't set the headline price while a known one exists.
        val own = result.offers.map { ShopOffer(it.seller.ifBlank { host }, it.price, it.inStock ?: true) }
        // vergelijk.be's shops (and a shop page you added yourself) are trusted for Google Shopping too.
        if (query == null) TrustedShops.learn(appContext, own.map { it.seller })
        // Optionally Google Shopping's shops too; one shop, one offer (the cheaper), by shop site.
        // Only prices that are plausible next to vergelijk.be's cheapest count (see ExtraShopPrices).
        val ownCheapest = own.minOfOrNull { it.price }
        val ownShops = own.map { Shops.name(it.seller).lowercase() }.distinct().size
        // A product just added is still called "New product": Google is asked for the name the shop gave it.
        val named = product.copy(name = result.name.ifBlank { product.name })
        // The page's photo, unless you chose Google Shopping's or none for this product.
        result.image?.let { Photos.saveSitePhoto(appContext, product.id, it) }
        if (query == null) result.brand?.let { ShoppingSettings.saveBrand(appContext, product.id, it) }
        if (query != null || Photos.choice(appContext, product.id) == Photos.Choice.SITE) {
            result.image?.let { runCatching { Images.save(appContext, product.id, it) } }
        }
        // Stores today's shops and cheapest price; returns the cheapest offer (null when there is none).
        fun store(offers: List<ShopOffer>): Pair<List<ShopOffer>, ShopOffer?> {
            db.replaceOffers(product.id, offers)
            val cheapest = Offers.cheapest(offers)
            if (cheapest != null) {
                db.savePrice(product.id, PricePoint(today, cheapest.price, cheapest.seller, offers.size, now, cheapest.inStock))
                db.markChecked(product.id, now, result.name, null)
            }
            return offers to cheapest
        }
        // (Which shops are trusted was decided when Google's and Shoparize's results were read.)
        // Only prices that are plausible next to vergelijk.be's cheapest count (see ExtraShopPrices).
        // The shops to show, one offer each (see ShopPrices.combine, where the choices are tested).
        fun combine(google: List<ShopOffer>, shoparize: List<ShopOffer>): List<ShopOffer> =
            ShopPrices.combine(own, google, shoparize, googleProduct = query != null)
        // Google Shopping and Shoparize are the slow part: store and show vergelijk.be's shops first, with what they
        // found last time (so the prices don't jump back and forth), then the fresh results.
        val searchesGoogle = searchesExtra(query, ownShops) &&
            ShoppingSettings.extraOffers(appContext, product.id, today.toString()) == null
        val searchesShoparize = ShoppingSettings.shoparizeOffers(appContext, product.id, today.toString()) == null
        if ((searchesGoogle || searchesShoparize) && onFirstResults != null) {
            store(combine(ShoppingSettings.lastExtraOffers(appContext, product.id).orEmpty(), ShoppingSettings.lastShoparizeOffers(appContext, product.id).orEmpty()))
            onFirstResults()
        }
        val extras = extraShops(named, query, ownCheapest, ownShops)
        val shoparize = shoparizeShops(named, query)
        // Each shop's own page, where its link is known: sold out there, or another price, as the shop says it.
        val (offers, cheapest) = store(checkShopPages(named, combine(extras.offers, shoparize), result, lookups))
        // The shops are in: show them now, before the site's price history is fetched.
        onFirstResults?.invoke()
        if (cheapest == null) return null
        val point = PricePoint(today, cheapest.price, cheapest.seller, offers.size, now, cheapest.inStock)
        // Optional extra: the site's own long-term history. Never fails the check. It only gains a
        // day at a time, so once it reaches today a refresh skips it.
        if (product.siteHistory.lastOrNull()?.day != today) {
            runCatching { VergelijkHistory.fetch(product.url) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { db.replaceSiteHistory(product.id, it) }
        }

        // Notify from here, so a manual refresh alerts just like the daily check. Otherwise a refresh
        // that crosses the target would make the next daily check think it was already crossed.
        // Only the user's own price alert notifies; drops and new lows are just shown in the app.
        discountAlert(product, offers, cheapest.price)
        return Alerts.decide(product.copy(name = result.name.ifBlank { product.name }), point, today)
            ?.also { if (product.notify && it.kind == AlertKind.TARGET) Notifier.priceAlert(appContext, it) }
    }

    /**
     * Price alerts follow the shops' own prices. When only your shop discount brings a product to
     * or below your alert, say so once (again only after it went back above, or the alert changed).
     */
    private fun discountAlert(product: Product, offers: List<ShopOffer>, shopsCheapest: Double) {
        val prefs = appContext.getSharedPreferences(DISCOUNT_ALERTS, Context.MODE_PRIVATE)
        val key = "p${product.id}"
        val target = product.targetPrice
        val discounts = Discounts.load(appContext)
        val best = Discounts.bestForYou(offers, discounts)
        val percent = best?.let { Discounts.percentFor(it.seller, discounts) }
        val yours = best?.let { Discounts.yourPrice(it, discounts) }
        if (target == null || best == null || percent == null || yours == null ||
            yours > target + 0.005 || shopsCheapest <= target + 0.005
        ) {
            prefs.edit { remove(key) }
            return
        }
        if (prefs.getFloat(key, -1f) == target.toFloat()) return
        prefs.edit { putFloat(key, target.toFloat()) }
        if (product.notify) Notifier.discountAlert(appContext, product, yours, best.seller, percent, target)
    }

    companion object {
        private const val PARALLEL_CHECKS = 4
        /** Google Shopping is searched for products with this many shops or fewer without it. */
        private const val FEW_SHOPS = 3
        private const val SILENT = "silent"
        private const val DISCOUNT_ALERTS = "discount_alerts"
        private const val PENDING_DELETES = "pending_deletes"
        /** A little longer than the Undo bar shows (Snackbar "short" is 4 s). */
        private const val UNDO_MS = 5_000L

        @Volatile private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context).also { instance = it }
            }
    }
}
