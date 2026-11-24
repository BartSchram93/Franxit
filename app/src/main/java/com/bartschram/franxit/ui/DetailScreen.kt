package com.bartschram.franxit.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.bartschram.franxit.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.Discounts
import com.bartschram.franxit.data.Offers
import com.bartschram.franxit.data.PricePoint
import com.bartschram.franxit.data.BuyAdvisor
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.filled.Share
import com.bartschram.franxit.data.BuyAdvice
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.ShareLink
import com.bartschram.franxit.data.Verdict
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.bartschram.franxit.data.Images
import com.bartschram.franxit.data.Photos
import com.bartschram.franxit.data.Shops
import com.bartschram.franxit.scrape.GoogleShopping
import com.bartschram.franxit.data.ShopOffer
import com.bartschram.franxit.data.SitePoint
import com.bartschram.franxit.ui.theme.PriceDown
import com.bartschram.franxit.ui.theme.PriceUp
import com.bartschram.franxit.ui.theme.Warning
import kotlinx.coroutines.launch
import java.net.URI
import kotlin.math.roundToInt
import java.time.LocalDate

private enum class Span(val label: String, val from: ((LocalDate) -> LocalDate)?) {
    W1("1W", { it.minusWeeks(1) }),
    M1("1M", { it.minusMonths(1) }),
    M3("3M", { it.minusMonths(3) }),
    ALL("All", null),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    product: Product,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String?) -> Unit,
    onSetTarget: (Double?) -> Unit,
    onSetNotify: (Boolean) -> Unit,
    discounts: Map<String, Double>,
    shopLink: suspend (seller: String?) -> String?,
    /** How a shop's price was checked (ShopCheck.Status), shown under it; null when not known. */
    shopStatus: (seller: String) -> String? = { null },
    /** What happened to a shop at the last check, for finding out why its price wasn't checked (held down). */
    shopNote: (seller: String) -> String? = { null },
    /** "Also search Google Shopping": null when it doesn't apply (no key, or a Google Shopping product). */
    googleAuto: Boolean = false,
    photoChoice: Photos.Choice = Photos.Choice.SITE,
    /** Whether a Google Shopping photo is there to switch to. */
    googlePhoto: Boolean = false,
    photoVersion: Int = 0,
    onPhoto: (Photos.Choice) -> Unit = {},
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var settingTarget by rememberSaveable { mutableStateOf(false) }
    var openingShop by remember { mutableStateOf(false) }
    var span by rememberSaveable { mutableStateOf(Span.M1) }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val host = runCatching { URI(product.url).host.removePrefix("www.") }.getOrDefault("")
    // Several shops to choose from: vergelijk.be, a Google Shopping search, or a shop's own page with
    // Google Shopping's shops added (then the Buy button goes where it's cheapest for you, too).
    val comparisonSite = host.endsWith("vergelijk.be") || GoogleShopping.queryOf(product.url) != null ||
        product.offers.map { Shops.name(it.seller).lowercase() }.distinct().size > 1

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    val shareContext = LocalContext.current
                    IconButton(onClick = { shareProduct(shareContext, product) }) { Icon(Icons.Default.Share, t("Share")) }
                    if (refreshing) {
                        SpinningRefresh()
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, t("Check now")) }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, t("Stop tracking")) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        // While a check runs: a thin moving bar under the top bar, seen wherever you have scrolled to.
        if (refreshing) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        } else {
            Spacer(Modifier.height(3.dp))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            val context = LocalContext.current
            val photo by produceState<ImageBitmap?>(null, product.id, product.lastChecked, photoVersion) {
                value = withContext(Dispatchers.IO) { Images.load(context, product.id)?.asImageBitmap() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(product.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { renaming = true }) {
                    Icon(Icons.Default.Edit, "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            extraName(product.displayName, product.name)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            val advice = remember(product) { BuyAdvisor.advise(product) }
            val adviceRequester = remember { BringIntoViewRequester() }
            val latest = product.latest
            // The photo sits right of the price, in space that's otherwise empty: you see it's the right
            // product (and spot a wrong photo) without the name losing width.
            if (latest != null) Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) {
                Text(latest.price.euro(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                // The shop is on the Buy button and in Shops today; only say what those don't.
                if (latest.inStock == false) {
                    Spacer(Modifier.height(4.dp))
                    Text("Out of stock", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                product.targetPrice?.let {
                    Spacer(Modifier.height(4.dp))
                    TargetLine(it, latest.price, muted = !product.notify)
                }
                if (product.isStale()) {
                    Spacer(Modifier.height(6.dp))
                    StaleLine(latest.checkedAt)
                }
                // The advice from the bottom, in short; a tap scrolls down to it.
                if (advice.score != null) {
                    Spacer(Modifier.height(6.dp))
                    VerdictHint(advice, onClick = { scope.launch { adviceRequester.bringIntoView() } })
                }
            }
                Spacer(Modifier.width(12.dp))
                ProductPhoto(photo, 72.dp, 16.dp)
            }
            product.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(tMessage(it), style = MaterialTheme.typography.bodySmall, color = PriceUp)
            }

            Spacer(Modifier.height(20.dp))
            // Where it's cheapest for you: your shop discounts can make another shop the best buy.
            val best = remember(product.offers, discounts) { Discounts.bestForYou(product.offers, discounts) }
            val bestPercent = best?.let { Discounts.percentFor(it.seller, discounts) }
            Button(
                onClick = {
                    if (!comparisonSite) {
                        uri.openUri(product.url)
                    } else if (!openingShop) {
                        openingShop = true
                        scope.launch {
                            try {
                                // Only a product page: the shop's, else vergelijk.be's own page of the product; never a search.
                                (shopLink(best?.seller?.takeIf { bestPercent != null }) ?: product.url.takeIf { com.bartschram.franxit.scrape.GoogleShopping.queryOf(it) == null })?.let(uri::openUri)
                            } finally {
                                openingShop = false
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                val latest = product.latest
                // Two lines: the shop, and under it the price, so long shop names never get cut off.
                @Composable
                fun Label(shop: String, price: String?) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("Buy at %s", shop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        price?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
                        }
                    }
                }
                when {
                    openingShop -> LoadingDots(color = MaterialTheme.colorScheme.onPrimary)
                    !comparisonSite -> Text(t("Open %s", host))
                    best != null && bestPercent != null -> Label(
                        Shops.name(best.seller),
                        t("%1\$s with your %2\$s%%", Discounts.yourPrice(best, discounts).euro(), Discounts.label(bestPercent)),
                    )
                    latest != null && latest.seller.isNotBlank() -> Label(Shops.name(latest.seller), latest.price.euro())
                    else -> Text("Go to cheapest shop")
                }
            }
            // Your discount gets you to or below your alert, while the shop's own price doesn't.
            val target = product.targetPrice
            if (best != null && bestPercent != null && target != null) {
                val yours = Discounts.yourPrice(best, discounts)
                if (yours <= target + 0.005) {
                    Text(
                        t("Below your %1\$s alert with your %2\$s%%", target.euro(), Discounts.label(bestPercent)),
                        style = MaterialTheme.typography.bodySmall,
                        color = PriceDown,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // A price alert needs a price to compare with: no button until one could be read.
            if (product.latest != null) Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { settingTarget = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Default.Notifications, null, Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    val target = product.targetPrice
                    Text(
                        when {
                            target == null -> "Price alert"
                            product.notify -> t("Alert %s", target.euroRounded())
                            else -> t("Alert %s · muted", target.euroRounded())
                        }
                    )
                }
            }

            ShopsToday(product, discounts, shopStatus, shopNote, refreshing) { seller -> shopLink(seller) }
            // Few shops: more are searched for by themselves; the customer only sees the usual searching words.
            // Or a product still being looked up for the first time: nothing to show yet but the words.
            if (refreshing && (googleAuto || product.latest == null)) {
                Spacer(Modifier.height(12.dp))
                SearchingWords(if (product.latest == null) "Looking up the product…" else "Searching…")
            }

            if (product.history.isNotEmpty() || product.siteHistory.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                // Prices the advice treats as data errors (another pack in the site's history) leave a gap in the
                // chart, so the chart and "Lowest" agree.
                val plausible = remember(product) { BuyAdvisor.plausibleRange(product) }
                val shown = filter(product.history, span)
                val shownSite = filterSite(product.siteHistory, span)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        // The ranges share the full width, so they line up with the chart below.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Span.entries.forEach { s ->
                                FilterChip(
                                    selected = s == span,
                                    onClick = { span = s },
                                    label = { Text(s.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        PriceChart(shown, shownSite, plausible = plausible)
                    }
                }

                // Lowest and Typical come from the same advice as Buy or wait below, so they always agree.
                val low = advice.historicalLow
                val typical = advice.medianPrice
                if (low != null && typical != null) {
                    val below = latest?.price?.let { (it - low) / it }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat(
                            "Lowest", low, PriceDown, Modifier.weight(1f),
                            advice.historicalLowDate?.short() ?: "",
                            if (below != null && below >= 0.005) t("%d%% below current", (below * 100).roundToInt()) else "Today's price",
                        )
                        Stat("Typical", typical, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f), "Median price", "of the past year")
                    }
                }
            }

            // Buy or wait: what the price history says about today's price, at the very bottom.
            Spacer(Modifier.height(28.dp))
            BuyWaitRecommendation(
                advice,
                currentAlert = product.targetPrice,
                onSetAlert = { price: Double ->
                    onSetTarget(price)
                    // Alerts muted with the old "Notify me" switch notify again once set.
                    if (!product.notify) onSetNotify(true)
                }.takeIf { product.latest != null },
                modifier = Modifier.bringIntoViewRequester(adviceRequester),
            )
        }
        }
    }

    if (renaming) {
        var text by rememberSaveable { mutableStateOf(product.displayName) }
        val context = LocalContext.current
        val photo by produceState<ImageBitmap?>(null, product.id, product.lastChecked, photoVersion) {
            value = withContext(Dispatchers.IO) { Images.load(context, product.id)?.asImageBitmap() }
        }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name and photo") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
                    // Back to the shop's own name: only when you changed it.
                    if (!product.customName.isNullOrBlank()) {
                        TextButton(onClick = { renaming = false; onRename(null) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("Use site name", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    // The photo you see now, with the one option that makes sense: when the page's one is wrong.
                    val (label, choice) = when {
                        photoChoice != Photos.Choice.SITE ->
                            (if (host.endsWith("vergelijk.be")) "Use the original photo again" else "Use the shop's photo again") to Photos.Choice.SITE
                        googlePhoto -> "Try another photo" to Photos.Choice.GOOGLE
                        else -> "Remove photo" to Photos.Choice.NONE
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductPhoto(photo, 56.dp, 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Photo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // Padding inside the button, so its rounded corners don't cut off the first letters of a
                            // label on two lines; moved left by as much, so the text lines up with "Photo" above it.
                            TextButton(
                                onClick = { onPhoto(choice) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.offset(x = (-10).dp),
                            ) { Text(label) }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { renaming = false; onRename(text.takeIf { it.isNotBlank() }) }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            },
        )
    }

    if (settingTarget) {
        PriceAlertDialog(product, onDismiss = { settingTarget = false }) { price ->
            onSetTarget(price)
            // Alerts muted with the old "Notify me" switch notify again once saved.
            if (price != null && !product.notify) onSetNotify(true)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Stop tracking?") },
            text = { Text("This removes the product and its price history.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun filter(history: List<PricePoint>, span: Span): List<PricePoint> {
    val from = span.from?.invoke(LocalDate.now()) ?: return history
    return history.filter { !it.day.isBefore(from) }.ifEmpty { history.takeLast(1) }
}

/**
 * Every shop's price from the latest check, cheapest first. Tapping a shop opens the product
 * at that shop (through a fresh vergelijk.be link), so you can pick where to buy yourself.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ShopsToday(
    product: Product,
    discounts: Map<String, Double>,
    shopStatus: (String) -> String?,
    shopNote: (String) -> String?,
    refreshing: Boolean,
    shopLink: suspend (String) -> String?,
) {
    // Held down: what happened to that shop at the last check.
    var note by remember { mutableStateOf<Pair<String, String>?>(null) }
    note?.let { (shop, text) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { note = null },
            title = { androidx.compose.material3.Text(shop) },
            text = { androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { note = null }) { androidx.compose.material3.Text("OK") } },
        )
    }
    // Cheapest for you first: with a shop discount that can differ from the shops' own order.
    val offers = remember(product.offers, discounts) {
        // The same order as the Buy button's choice (Offers.best): at the same price, by name.
        mergeSameShop(product.offers).sortedWith(
            compareBy<ShopOffer> { it.inStock == false }.then(Offers.order { Discounts.yourPrice(it, discounts) }),
        )
    }
    if (offers.size < 2) return
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(24.dp))
    Text("Shops today", style = MaterialTheme.typography.titleSmall)
    // The list isn't final yet: more shops can come, prices and the cheapest can still change.
    if (refreshing) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Still checking shops…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(4.dp))
    // "Bol." and "Bol Plaza" both read "Bol.com"; only when both are listed, call the second "Bol.com partner".
    val names = remember(offers) {
        val plain = offers.map { Shops.name(it.seller) }
        offers.associate { o ->
            val name = Shops.name(o.seller)
            o.seller to if (plain.count { it == name } > 1) Shops.name(o.seller, partners = true) else name
        }
    }
    // The cheapest few are what matter; the rest on request.
    val shown = if (showAll) offers else offers.take(SHOPS_SHOWN)
    shown.forEachIndexed { i, offer ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = opening == null,
                    onLongClick = { note = Shops.name(offer.seller) to (shopNote(offer.seller) ?: "Not checked at the shop yet.") },
                ) {
                    opening = offer.seller
                    scope.launch {
                        try {
                            (shopLink(offer.seller) ?: product.url.takeIf { com.bartschram.franxit.scrape.GoogleShopping.queryOf(it) == null })?.let(uri::openUri)
                        } finally {
                            opening = null
                        }
                    }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(names[offer.seller] ?: Shops.name(offer.seller), style = MaterialTheme.typography.bodyMedium)
                if (offer.inStock == false) {
                    Text("Out of stock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (opening == offer.seller) {
                LoadingDots(dotSize = 5.dp)
                Spacer(Modifier.width(12.dp))
            }
            val percent = Discounts.percentFor(offer.seller, discounts)
            if (percent == null) {
                Text(offer.price.euro(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(Discounts.yourPrice(offer, discounts).euro(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${offer.price.euro()} −${Discounts.label(percent)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(" \u203A", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (offers.size > SHOPS_SHOWN) {
        TextButton(onClick = { showAll = !showAll }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showAll) "Show fewer" else t("Show all %d shops", offers.size))
        }
    }
}

private const val SHOPS_SHOWN = 3

/**
 * The part of the site's name that the short name doesn't already say ("128GB Wifi" under
 * "Apple iPad Air 13\""), or the whole site name for a custom name. Null when it adds nothing,
 * also when it only repeats the name's words ("Chiaogoo Chiaogoo Twist" under "Chiaogoo Twist").
 */
internal fun extraName(displayName: String, siteName: String): String? {
    if (displayName == siteName) return null
    fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    if (words(siteName).all { it in words(displayName) }) return null
    if (!siteName.startsWith(displayName, ignoreCase = true)) return siteName
    return siteName.substring(displayName.length).trim(' ', ',', '-', '–', '·').ifBlank { null }
}

/**
 * One row per shop: the same price under two names of one shop ("Amazon" and "Amazon Marketplace")
 * shows once, under the shorter name.
 */
internal fun mergeSameShop(offers: List<ShopOffer>): List<ShopOffer> {
    val out = mutableListOf<ShopOffer>()
    for (offer in offers.sortedBy { it.seller.length }) {
        val same = out.any {
            kotlin.math.abs(it.price - offer.price) < 0.005 && offer.seller.startsWith(it.seller, ignoreCase = true)
        }
        if (!same) out += offer
    }
    return out.sortedBy { it.price }
}

private fun filterSite(history: List<SitePoint>, span: Span): List<SitePoint> {
    val from = span.from?.invoke(LocalDate.now()) ?: return history
    return history.filter { !it.day.isBefore(from) }
}

/** A price card under the chart: a label, the price, and two short lines of context (same height side by side). */
@Composable
private fun Stat(label: String, price: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier, line1: String, line2: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(price.euro(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
            Text(line1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** What is shared: the product's name and price, and under it the link that opens it in a friend's Franxit. */
internal fun shareText(product: Product): String {
    val price = product.latest?.price?.euro()
    val first = if (price != null) t("%1\$s: %2\$s", product.displayName, price) else product.displayName
    return first + "\n" + ShareLink.forProduct(product.url)
}

private fun shareProduct(context: Context, product: Product) {
    com.bartschram.franxit.data.Analytics.track("product_shared")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(product))
    context.startActivity(Intent.createChooser(send, t("Share")))
}


/** While Google Shopping searches, a changing word says what it is up to, so the line doesn't sit still. */
@Composable
internal fun SearchingWords(first: String = "Searching…") {
    // A few calm words, one after the other; the last one stays when it takes longer.
    val words = remember(first) {
        listOf(
            first, "Reading the details…", "Checking the shops…", "Comparing prices…", "Checking stock…",
            "Looking for the best price…", "Double-checking the prices…", "Still searching…",
        )
    }
    var index by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (index < words.lastIndex) {
            kotlinx.coroutines.delay(2500)
            index++
        }
    }
    // The dots at the end walk from one to three and start again, so you can see it is still working.
    // The ones not shown yet are drawn invisible, so the line keeps its width and doesn't jump.
    var dots by remember { mutableStateOf(3) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            dots = dots % 3 + 1
        }
    }
    val shown = t(words[index])
    val text = buildAnnotatedString {
        if (shown.endsWith("…")) {
            append(shown.removeSuffix("…"))
            append(".".repeat(dots))
            withStyle(SpanStyle(color = Color.Transparent)) { append(".".repeat(3 - dots)) }
        } else {
            append(shown)
        }
    }
    androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

