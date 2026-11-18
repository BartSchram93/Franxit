package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import com.bartschram.franxit.scrape.GoogleShopping
import com.bartschram.franxit.scrape.Matching
import com.bartschram.franxit.scrape.Scraper
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

    /** Starts tracking [url] and checks its price right away. Returns the product id. */
    suspend fun add(url: String): Long = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url).host }.getOrNull() ?: url
        // Until the first check names the product: the shop's address, or a plain label for a comparison page.
        val placeholder = if (host.endsWith("vergelijk.be")) "New product" else host
        val id = db.insertProduct(url, placeholder)
        reload()
        db.products().firstOrNull { it.id == id }?.let { checkLock.withLock { check(it) } }
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

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
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
    private class Extras(val offers: List<ShopOffer>, val error: String? = null)

    private fun extraShops(product: Product, query: String?): Extras {
        if (query != null || !ShoppingSettings.isExtra(appContext, product.id)) return Extras(emptyList())
        val today = LocalDate.now().toString()
        ShoppingSettings.extraOffers(appContext, product.id, today)?.let { return Extras(it) }
        val name = product.displayName
        // The wording that worked last time first, then the product's name, its fuller name, and "kopen".
        val terms = listOfNotNull(ShoppingSettings.extraTerm(appContext, product.id), name, fullNameQuery(product), "$name kopen")
        val found = try {
            searchGoogleShopping(product.id, name, terms) { ShoppingSettings.setExtraTerm(appContext, product.id, it) }
        } catch (e: Exception) {
            return when (e.message) {
                // Nothing for this product: asked once a day, not at every refresh.
                GoogleShopping.NOT_FOUND -> {
                    ShoppingSettings.saveExtraOffers(appContext, product.id, today, emptyList(), days = 3)
                    Extras(emptyList())
                }
                GoogleShopping.BAD_KEY, GoogleShopping.NO_SEARCHES_LEFT -> Extras(emptyList(), e.message)
                else -> Extras(emptyList(), "Couldn't reach Google Shopping")
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
     * The name with the first thing after its comma ("Bose QuietComfort Ultra Earbuds" + "(2e gen) Zwart"),
     * as a second wording to search for; null when that is no short variant, but specs ("36,3 cm", "256 GB").
     */
    private fun fullNameQuery(product: Product): String? {
        val extra = product.name.split(',').getOrNull(1)?.trim().orEmpty()
        val spec = Regex("""\d\s?(cm|inch|"|gb|mb|tb|mm|hz|mah|w|x)\b|^[\d.\s]+$""", RegexOption.IGNORE_CASE)
        if (extra.isEmpty() || extra.split(Regex("\\s+")).size > 4 || spec.containsMatchIn(extra)) return null
        return "${product.displayName} $extra"
    }

    /** Switches a product's photo; the page's own photo comes back with a check (no Google search). */
    suspend fun setPhoto(productId: Long, choice: Photos.Choice) = withContext(Dispatchers.IO) {
        Photos.setChoice(appContext, productId, choice)
        Images.delete(appContext, productId)
        when (choice) {
            Photos.Choice.NONE -> Unit
            Photos.Choice.GOOGLE -> Photos.googlePhoto(appContext, productId)?.let { runCatching { Images.save(appContext, productId, it) } }
            Photos.Choice.SITE -> checkOne(productId)
        }
        changed()
    }

    /** Google Shopping's shops for a product; remembers each shop's page for the Buy button. */
    private fun searchGoogleShopping(
        productId: Long,
        query: String,
        terms: List<String> = listOf(query),
        onTerm: (String) -> Unit = {},
    ): Scraper.Result {
        val key = ShoppingSettings.key(appContext) ?: throw IOException("Turn on Google Shopping in ⋮ → Google Shopping")
        val found = GoogleShopping.fetch(query, key, { TrustedShops.name(appContext, it) }, terms)
        found.term?.let(onTerm)
        // Every search keeps its best result's photo, for "Use Google Shopping photo".
        found.result.image?.let { Photos.saveGooglePhoto(appContext, productId, it) }
        ShoppingSettings.saveLinks(appContext, productId, found.links)
        return found.result
    }


    suspend fun shopLink(product: Product, seller: String? = null): String? = withContext(Dispatchers.IO) {
        // A shop found through Google Shopping: its page from the last search (a new search would cost one).
        val shop = seller ?: Offers.cheapest(product.offers)?.seller
        shop?.let { ShoppingSettings.link(appContext, product.id, it) }?.let { link ->
            if (!link.startsWith(GoogleShopping.TOKEN)) return@withContext link
            return@withContext lookUpStoreLink(product, shop, link.removePrefix(GoogleShopping.TOKEN))
        }
        if (GoogleShopping.queryOf(product.url) != null) return@withContext null
        // A shop's own page you added (fitshop.be), with Google Shopping's shops next to it.
        val host = runCatching { URI(product.url).host.removePrefix("www.") }.getOrDefault("")
        if ("vergelijk.be" !in host) return@withContext product.url.takeIf { shop == null || TrustedShops.key(shop) == TrustedShops.key(host) }
        runCatching {
            val result = Scraper.fetch(product.url)
            if (seller == null) result.cheapest.link
            else result.offers.filter { it.seller.equals(seller, ignoreCase = true) }.minByOrNull { it.price }?.link
        }.getOrNull()
    }

    /**
     * The shop's own page, looked up once and remembered. Without a key, searches left or a match,
     * the Google Shopping search for the product at that shop.
     */
    private fun lookUpStoreLink(product: Product, shop: String, token: String): String {
        val fallback = GoogleShopping.urlFor("${GoogleShopping.queryOf(product.url) ?: product.displayName} $shop")
        val key = ShoppingSettings.key(appContext) ?: return fallback
        val link = runCatching {
            GoogleShopping.storeLink(token, shop, key) { a, b -> TrustedShops.same(a, b) }
        }.getOrNull() ?: return fallback
        ShoppingSettings.saveLink(appContext, product.id, shop, link)
        return link
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
            val result = runCatching { db.products().first { it.id == id }.let { check(it) } }
            changed()
            result.map { }
        }
    }

    /** Scrapes one product and stores today's cheapest price. Returns an [Alert] when worth notifying. */
    private fun check(product: Product): Alert? {
        val now = System.currentTimeMillis()
        val query = GoogleShopping.queryOf(product.url)
        // Google Shopping costs a search of your plan: at most once a day per product.
        if (query != null && product.latest?.day == LocalDate.now() && product.lastError == null) return null
        val result = try {
            if (query == null) Scraper.fetch(product.url) else searchGoogleShopping(product.id, query)
        } catch (e: Exception) {
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
        val own = result.offers.map { ShopOffer(it.seller.ifBlank { host }, it.price, it.inStock) }
        // vergelijk.be's shops (and a shop page you added yourself) are trusted for Google Shopping too.
        if (query == null) TrustedShops.learn(appContext, own.map { it.seller })
        // Optionally Google Shopping's shops too; one shop, one offer (the cheaper), by shop site.
        // Only prices that are plausible next to vergelijk.be's cheapest count (see ExtraShopPrices).
        val ownCheapest = own.minOfOrNull { it.price }
        val extras = extraShops(product, query)
        // (Which shops are trusted was decided when Google's results were read.)
        val extra = extras.offers.filter { ExtraShopPrices.plausible(it.price, ownCheapest) }
        if (query == null && ShoppingSettings.isExtra(appContext, product.id)) {
            // Said on the product page, so "doesn't do much" is never a mystery.
            val added = extra.count { e -> own.none { Shops.name(it.seller).equals(Shops.name(e.seller), ignoreCase = true) } }
            ShoppingSettings.setExtraStatus(
                appContext, product.id,
                extras.error ?: when (added) { 0 -> "No extra shops found"; 1 -> "Found 1 extra shop"; else -> "Found $added extra shops" },
            )
        }
        val offers = (own + extra)
            .groupBy { Shops.name(it.seller).lowercase() }.values
            .map { same -> same.minBy { it.price } }
            .sortedBy { it.price }
        db.replaceOffers(product.id, offers)
        // The page's photo, unless you chose Google Shopping's or none for this product.
        if (query != null || Photos.choice(appContext, product.id) == Photos.Choice.SITE) {
            result.image?.let { runCatching { Images.save(appContext, product.id, it) } }
        }
        val cheapest = Offers.cheapest(offers) ?: return null
        val point = PricePoint(today, cheapest.price, cheapest.seller, offers.size, now, cheapest.inStock)
        db.savePrice(product.id, point)
        db.markChecked(product.id, now, result.name, null)
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
