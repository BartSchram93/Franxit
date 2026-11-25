package com.bartschram.franxit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.bartschram.franxit.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import com.bartschram.franxit.data.Discounts
import com.bartschram.franxit.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import com.bartschram.franxit.ui.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.Verdict
import com.bartschram.franxit.data.BuyAdvisor
import com.bartschram.franxit.data.Shops
import com.bartschram.franxit.ui.theme.ChartLine
import com.bartschram.franxit.ui.theme.PriceDown
import com.bartschram.franxit.ui.theme.PriceUp
import com.bartschram.franxit.ui.theme.Warning

enum class SortOrder(val label: String) {
    ADDED("Date added"),
    PRICE_UP("Lowest price"),
    PRICE_DOWN("Highest price"),
    BEST_DEALS("Best deals"),
    NAME("Name");

    // Products without a price yet go last either way.
    fun apply(products: List<Product>): List<Product> = when (this) {
        ADDED -> products
        PRICE_UP -> products.sortedBy { it.latest?.price ?: Double.MAX_VALUE }
        PRICE_DOWN -> products.sortedByDescending { it.latest?.price ?: -1.0 }
        // By the Buy or wait score; products without enough history go last.
        BEST_DEALS -> products.sortedByDescending { BuyAdvisor.advise(it).score ?: -1 }
        NAME -> products.sortedBy { it.displayName.lowercase() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    products: List<Product>,
    refreshing: Boolean,
    progress: Pair<Int, Int>?,
    onRefresh: () -> Unit,
    onOpen: (Product) -> Unit,
    onAdd: () -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    alertsOnly: Boolean,
    onAlertsOnly: (Boolean) -> Unit,
    onSetAlert: (Product, Double?) -> Unit,
    discounts: Map<String, Double>,
    onDiscounts: () -> Unit,
    notificationsOn: Boolean,
    onTurnOnNotifications: () -> Unit,
    showTips: Boolean,
    /** Goes up when a product's photo changed, so the cards load it again. */
    photoVersion: Int,
    onTipsSeen: () -> Unit,
    onShowTips: () -> Unit,
    onLanguage: () -> Unit = {},
    onDelete: (Product) -> Unit,
    onUndoDelete: (Product) -> Unit,
    /** A newer version is out (the app isn't in a store): say so, with the way to the download. */
    /** What's new in that version, in a short sentence. */
) {
    var menuOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // One swipe can report "dismissed" more than once; only the first one counts.
    val removing = remember { mutableSetOf<Long>() }
    // The list remembers each card's swipe by its key, so a product brought back by Undo would
    // return still "swiped away" and be removed again at once. A new key after each Undo gives
    // it a fresh card.
    val undone = remember { mutableStateMapOf<Long, Int>() }
    var alertFor by remember { mutableStateOf<Product?>(null) }
    val sorted = remember(products, sort, alertsOnly) {
        sort.apply(if (alertsOnly) products.filter { it.targetPrice != null } else products)
    }
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            ) { Icon(Icons.Default.Add, contentDescription = t("Track a product")) }
        },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            refreshing, onRefresh, Modifier.padding(padding).fillMaxSize(), state = pullState,
            // The same dots as everywhere else, in a small pill that follows the finger down.
            indicator = {
                val pulled = pullState.distanceFraction
                if (refreshing || pulled > 0f) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 3.dp,
                        modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                            val f = if (refreshing) 1f else pulled.coerceIn(0f, 1.3f)
                            translationY = f * 56.dp.toPx() - 40.dp.toPx()
                            alpha = f.coerceAtMost(1f)
                        },
                    ) { LoadingDots(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) }
                }
            },
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Overview",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        // Sorting and filtering under their own icon; blue while a filter is on.
                        Box {
                            IconButton(onClick = { filterOpen = true }) {
                                Icon(FilterListIcon, t("Sort and filter"), tint = if (alertsOnly) ChartLine else LocalContentColor.current)
                            }
                            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                                Text(
                                    "Sort by",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        leadingIcon = { if (order == sort) Icon(Icons.Default.Check, null) },
                                        onClick = { filterOpen = false; onSort(order) },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("With price alert") },
                                    leadingIcon = { if (alertsOnly) Icon(Icons.Default.Check, null) },
                                    onClick = { filterOpen = false; onAlertsOnly(!alertsOnly) },
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, t("More")) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!notificationsOn) {
                                    DropdownMenuItem(
                                        text = { Text("Turn on notifications") },
                                        onClick = { menuOpen = false; onTurnOnNotifications() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("My discounts") },
                                    onClick = { menuOpen = false; onDiscounts() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Language") },
                                    onClick = { menuOpen = false; onLanguage() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tips") },
                                    onClick = { menuOpen = false; onShowTips() },
                                )
                            }
                        }
                    }
                    // How far a refresh is, so a long one doesn't look stuck.
                    if (refreshing && progress != null && progress.second > 1) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t("Checking %1\$d of %2\$d…", minOf(progress.first + 1, progress.second), progress.second),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Alerts are useless when Android blocks the app's notifications: say so, with the way out.
                    if (!notificationsOn && products.any { it.targetPrice != null }) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Notifications are off",
                                style = MaterialTheme.typography.bodySmall,
                                color = Warning,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onTurnOnNotifications) { Text("Turn on") }
                        }
                    }
                    // One warning for the whole list when the daily check stopped, not one per card.
                    val stale = products.filter { it.isStale() }.mapNotNull { it.latest?.checkedAt }.maxOrNull()
                    if (stale != null) {
                        Spacer(Modifier.height(4.dp))
                        StaleLine(stale)
                    }
                    // While filtering, say so, so products don't seem to have disappeared.
                    if (alertsOnly) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, null, tint = ChartLine, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "With price alert",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onAlertsOnly(false) }) { Text("Show all") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (products.isEmpty()) {
                    item {
                        // The logo, in its blue (readable on light and dark), above the first tips.
                        Image(
                            painterResource(R.drawable.logo_blue),
                            contentDescription = null,
                            modifier = Modifier.padding(top = 24.dp).height(56.dp),
                        )
                        Text(
                            "Nothing tracked yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                        )
                        Tips(onDone = null)
                    }
                } else if (sorted.isEmpty()) {
                    item {
                        Text(
                            "No products with a price alert yet. Open a product and tap Price alert to set one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 40.dp),
                        )
                    }
                } else if (showTips) {
                    // For new users, until they tap Got it.
                    item { Tips(onDone = onTipsSeen) }
                }
                items(sorted, key = { "${it.id}:${undone[it.id] ?: 0}" }) { product ->
                    // Swipe left to stop tracking; the Undo bar brings it back.
                    val swipe = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = swipe,
                        enableDismissFromStartToEnd = false,
                        onDismiss = {
                            if (removing.add(product.id)) {
                                // Hidden right away; deleted once the Undo time is over, even if you leave.
                                onDelete(product)
                                scope.launch {
                                    // A new removal replaces the previous bar instead of queueing behind it.
                                    snackbar.currentSnackbarData?.dismiss()
                                    val result = snackbar.showSnackbar(
                                        t("%s removed", product.displayName), actionLabel = t("Undo"), duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        undone[product.id] = (undone[product.id] ?: 0) + 1
                                        onUndoDelete(product)
                                    }
                                    removing.remove(product.id)
                                }
                            }
                        },
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(PriceUp.copy(alpha = 0.15f), RoundedCornerShape(20.dp)).padding(end = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Icon(Icons.Default.Delete, "Stop tracking", tint = PriceUp) }
                        },
                    ) { ProductCard(
                            product, discounts, photoVersion,
                            // A product just added, still being looked up: say so, instead of "not checked yet".
                            loading = refreshing && product.latest == null && product.lastError == null,
                            onClick = { onOpen(product) },
                            // A price alert needs a price; without one, long-press does nothing.
                            onLongClick = if (product.latest != null) ({ alertFor = product }) else null,
                        ) }
                }
            }
        }
    }

    // Long-pressing a card sets its price alert without opening the product.
    alertFor?.let { product ->
        PriceAlertDialog(product, onDismiss = { alertFor = null }, showName = true) { price -> onSetAlert(product, price) }
    }
}

internal val ThumbnailSize = 44.dp
internal val ThumbnailGap = 14.dp

/** The product photo on the Overview, with the gap to the name. */
@Composable
internal fun Thumbnail(bitmap: ImageBitmap?) {
    ProductPhoto(bitmap, ThumbnailSize, 12.dp)
    Spacer(Modifier.width(ThumbnailGap))
}

/**
 * A product photo in its tile. Photos mostly come on white: the photo fills the tile, and in dark
 * mode its white is tinted to the tile's softer colour, so there's no frame inside the frame and no
 * glare. Without a photo (none yet, or removed) the tile stays empty, so nothing around it moves.
 */
@Composable
internal fun ProductPhoto(bitmap: ImageBitmap?, size: Dp, corner: Dp) {
    val dark = isSystemInDarkTheme()
    val tile = if (dark) Color(0xFFE4E4E0) else Color.White
    val shape = RoundedCornerShape(corner)
    if (bitmap == null) {
        // No photo (none yet, still loading, or removed): a quiet cart, so the tile doesn't look broken.
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.ShoppingCart, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.size(size * 0.42f),
            )
        }
        return
    }
    // A margin inside the tile: photos that fill their whole frame ran into the rounded edge.
    Box(Modifier.size(size).clip(shape).background(tile), contentAlignment = Alignment.Center) {
        Image(
            bitmap, contentDescription = null, contentScale = ContentScale.Fit,
            colorFilter = if (dark) ColorFilter.tint(tile, BlendMode.Multiply) else null,
            modifier = Modifier.fillMaxSize().padding(size * 0.09f),
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ProductCard(
    product: Product,
    discounts: Map<String, Double>,
    photoVersion: Int,
    loading: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val latest = product.latest
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Where it's cheapest for you (after your shop discounts), as the detail screen's Buy button.
    val best = remember(product.offers, discounts) { Discounts.bestForYou(product.offers, discounts) }
    // The shop named under the name: of several at the same lowest price (€ 99 at bol.com, Coolblue and Vandenborre),
    // the one the Buy button opens, so the list and the product's page name the same shop.
    val shop = latest?.let { l -> best?.takeIf { kotlin.math.abs(it.price - l.price) < 0.005 }?.seller ?: l.seller }
    val photo by produceState<ImageBitmap?>(null, product.id, product.lastChecked, photoVersion) {
        value = withContext(Dispatchers.IO) { Images.load(context, product.id)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Next to the photo two columns: name and shop on the left, price with the advice right under it.
            Row(verticalAlignment = Alignment.Top) {
                Thumbnail(photo)
                Column(Modifier.weight(1f)) {
                    Text(
                        product.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (latest != null) {
                        Text(
                            shop.orEmpty().ifBlank { "Cheapest offer" }.let(Shops::name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (latest.inStock == false) {
                            Text("Out of stock", style = MaterialTheme.typography.bodySmall, color = Warning)
                        }
                    }
                }
                if (latest != null) {
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(latest.price.euro(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        VerdictLabel(product)
                    }
                }
            }
            // The lines below start under the name, not under the photo.
            val indent = ThumbnailSize + ThumbnailGap
            if (latest == null) {
                Spacer(Modifier.height(10.dp))
                if (loading) {
                    Box(Modifier.padding(start = indent)) { SearchingWords("Looking up the product…") }
                    return@Column
                }
                val error = product.lastError
                Text(
                    if (error != null) t("Couldn't read price: %s", tMessage(error)) else "Not checked yet · pull down to check",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) PriceUp else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = indent),
                )
                return@Column
            }
            // Extra lines, each only when it has something to say, evenly spaced.
            val percent = best?.let { Discounts.percentFor(it.seller, discounts) }
            // The price change since tracking started is on the detail screen; the card stays short.
            val lines = product.targetPrice != null || percent != null || product.lastError != null
            if (lines) Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(start = indent), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                product.targetPrice?.let { target -> TargetLine(target, latest.price, muted = !product.notify) }
                // Only when a shop discount applies to where it's cheapest for you.
                if (best != null && percent != null) {
                    val yours = Discounts.yourPrice(best, discounts)
                    DiscountLine(
                        yours,
                        percent,
                        // Green when your discount gets you to or below your alert.
                        belowAlert = product.targetPrice?.let { yours <= it + 0.005 } == true,
                        // The shop is already named above when it's the same one.
                        shop = best.seller.takeIf { it != shop }?.let(Shops::name),
                    )
                }
                if (product.lastError != null) {
                    Text(
                        tMessage(product.lastError.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = PriceUp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The few things to know on first use, mostly hidden gestures; [onDone] adds a Got it button. */
@Composable
private fun Tips(onDone: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (onDone == null) 16.dp else 4.dp)) {
            @Composable
            fun Tip(icon: ImageVector, text: String) {
                // Top-aligned, so the icon stays next to the first line when the text wraps.
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Tip(Icons.Default.Add, "Add a product with the + at the bottom right, or share a link from your browser")
            Tip(Icons.Default.Notifications, "Long-press a product to set a price alert")
            Tip(Icons.Default.Delete, "Swipe a product left to remove it")
            Tip(FilterListIcon, "Sort or show only alerts")
            onDone?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) { Text("Got it") } }
        }
    }
}

/**
 * The Buy or wait advice in a word, under the price: only "Buy now" in green, the rest grey with a
 * coloured dot, so the list stays calm. Without enough history it says "No advice yet", in grey.
 */
@Composable
private fun VerdictLabel(product: Product) {
    val advice = remember(product) { BuyAdvisor.advise(product) }
    val buy = advice.verdict == Verdict.BUY_NOW
    Spacer(Modifier.width(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(verdictColor(advice.verdict), CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            shortTitle(advice.verdict),
            style = MaterialTheme.typography.bodySmall,
            color = if (buy) PriceDown else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A bell and the alert amount, in blue; green once the price reached it. */
@Composable
fun TargetLine(target: Double, price: Double, muted: Boolean = false) {
    val reached = price <= target + 0.005
    // Blue like your own checks in the chart: it's your setting. Green once reached.
    val color = if (reached) PriceDown else ChartLine
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Notifications, contentDescription = t("Price alert"), tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (muted) t("%s · muted", target.euro()) else target.euro(), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Shown when the daily check hasn't run, e.g. because the phone's battery saver blocked it. */
@Composable
fun StaleLine(lastSuccess: Long) {
    Text(
        t("Last checked %s · daily check may be blocked by battery saving", lastSuccess.ageLabel()),
        style = MaterialTheme.typography.bodySmall,
        color = Warning,
    )
}


/**
 * A cart and what you'd pay with your discount: "€ 202,35 with your 5%" (plus the shop when it
 * differs). Green when that's at or below your price alert.
 */
@Composable
private fun DiscountLine(price: Double, percent: Double, shop: String?, belowAlert: Boolean = false) {
    val muted = if (belowAlert) PriceDown else MaterialTheme.colorScheme.onSurfaceVariant
    // Top-aligned, so the cart stays next to the first line when the text wraps.
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = muted, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (shop != null) t("%1\$s at %2\$s with your %3\$s%%", price.euro(), shop, Discounts.label(percent))
            else t("%1\$s with your %2\$s%%", price.euro(), Discounts.label(percent)),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

/** Material's "filter list" icon (three lines, getting shorter); not in the core icon set. */
private val FilterListIcon: ImageVector = ImageVector.Builder("FilterList", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M10,18h4v-2h-4v2zM3,6v2h18V6H3zm3,7h12v-2H6v2z"), fill = SolidColor(Color.Black))
    .build()
