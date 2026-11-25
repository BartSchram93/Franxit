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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.bartschram.franxit.ui.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bartschram.franxit.data.Images
import com.bartschram.franxit.data.Names
import com.bartschram.franxit.scrape.Matching
import com.bartschram.franxit.scrape.VergelijkSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.bartschram.franxit.scrape.ProductSearch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.lazy.LazyRow

/**
 * Search for a product, or paste any product link, and pick what to track. Typed words (or a question: "cheap
 * wireless headphones") find product pages on the web at the shops the app trusts; picking one finds that product
 * on vergelijk.be to compare all its shops, as a pasted shop link does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    /** vergelijk.be's search: for a picked or pasted shop link, the same product there with all its shops. */
    search: suspend (String) -> List<VergelijkSearch.Hit>,
    /** Products on the web for typed words. */
    discover: suspend (String) -> List<ProductSearch.Product>,
    /** Search words while typing. */
    suggest: suspend (String) -> List<String>,
    titleFromLink: suspend (String) -> String?,
    /** [title] is the product's name when the result has one (a typed or shared link has none). */
    onPick: (url: String, title: String?) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
    /** Track via Google Shopping instead; null without a SerpApi key. */
    onGoogleShopping: ((query: String) -> Unit)? = null,
    /** The price tag photo button (off in the screenshots, which have no camera). */
    photoSearch: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<VergelijkSearch.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // What to search Google Shopping for: the typed words, or the product name behind a shop link.
    var shoppingQuery by remember { mutableStateOf<String?>(null) }
    // Products found on the web for the typed words, and search words to pick while typing.
    var products by remember { mutableStateOf<List<ProductSearch.Product>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var limitReached by remember { mutableStateOf(false) }
    // Words searched at once (Enter, or a suggestion picked): no wait for typing to pause. Counted, so each asks once.
    var searchNow by remember { mutableStateOf(0) }
    val searchedNow = remember { intArrayOf(0) }
    val focus = remember { FocusRequester() }
    val link = query.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    // A link from another shop (e.g. Coolblue): find the same product on vergelijk.be, which
    // compares all shops. The link itself can still be tracked on its own.
    val shopLink = link?.takeIf { "vergelijk.be" !in it }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, searchNow) {
        val now = searchNow != searchedNow[0]
        searchedNow[0] = searchNow
        val q = query.trim()
        if (shopLink != null) {
            searching = true
            val found = runCatching {
                val title = titleFromLink(shopLink) ?: return@runCatching emptyList<VergelijkSearch.Hit>() to null
                shoppingQuery = Names.short(title)
                val hits = search(Names.short(title))
                Matching.rank(hits, title) to Matching.clearMatch(hits, title)
            }
            failed = found.isFailure
            val (hits, match) = found.getOrDefault(emptyList<VergelijkSearch.Hit>() to null)
            results = hits
            searching = false
            // Clearly the same product (and version) on vergelijk.be: track that one right away.
            match?.let { onPick(it.url, it.title) }
            return@LaunchedEffect
        }
        if (link != null || q.length < 2) {
            results = emptyList()
            products = emptyList()
            suggestions = emptyList()
            if (shopLink == null) shoppingQuery = null
            return@LaunchedEffect
        }
        results = emptyList()
        shoppingQuery = q
        coroutineScope {
            // Search words first (free, quick); the products once typing pauses. A new letter cancels both.
            launch {
                delay(SUGGEST_DELAY_MS)
                suggestions = suggest(q)
            }
            if (!now) delay(DISCOVER_DELAY_MS)
            searching = true
            val found = runCatching { discover(q) }
            failed = found.isFailure
            limitReached = found.exceptionOrNull() is ProductSearch.LimitReached
            products = found.getOrDefault(emptyList())
            searching = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Track a product") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                // Search by a photo of a price tag in a shop: its name goes into the search field.
                actions = { if (photoSearch) PriceTagSearch(onSearch = { query = it }) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) LoadingDots(dotSize = 5.dp) },
                placeholder = { Text("Search or paste a link") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = if (link != null) ImeAction.Done else ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onDone = { link?.let { onPick(it, null) } },
                    onSearch = { searchNow++ },
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus),
            )
            // Search words to pick while typing: words, not products (those are below).
            if (link == null && query.trim().length >= 2 && suggestions.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    items(suggestions, key = { it }) { words ->
                        SuggestionChip(
                            onClick = { query = words; searchNow++ },
                            label = { androidx.compose.material3.Text(words, maxLines = 1) },
                        )
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shopLink != null) {
                    if (searching) item {
                        Text(
                            "Looking for this product across shops…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!searching) item {
                        Text(
                            if (results.isEmpty()) "No other shops found, so only this shop's price can be followed."
                            else "Pick it to compare prices across all shops:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (link != null) {
                    item { ResultRow("Track this link", link, null) { onPick(link, null) } }
                } else if (query.trim().length >= 2 && searching && products.isEmpty()) {
                    item {
                        Text(
                            "Looking for products at the shops…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (query.trim().length >= 2 && !searching && products.isEmpty()) {
                    item {
                        Text(
                            when {
                                limitReached -> ProductSearch.LIMIT
                                failed -> ProductSearch.FAILED
                                else -> "No products found."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (query.isBlank()) {
                    item {
                        Text(
                            "Type a product name, or paste a link to a product page.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // For products vergelijk.be doesn't have: Google Shopping, one search of your plan a day.
                val google = shoppingQuery?.takeIf { onGoogleShopping != null && !searching && it.length >= 2 }
                    ?.takeIf { link == null || shopLink != null }
                // First when nothing found looks like what you searched for (Maltesers on vergelijk.be: Maxi-Cosi
                // strollers), so the way to follow it isn't below a list of other products.
                val googleFirst = google != null && shopLink == null && results.isNotEmpty() && !VergelijkSearch.fits(results, google)
                if (google != null && googleFirst) {
                    item(key = "google") {
                        ResultRow(t("Track via Google Shopping: \"%s\"", google), "Not found on comparison sites · checked once a day", null) {
                            onGoogleShopping?.invoke(google)
                        }
                    }
                }
                items(results, key = { it.url }) { hit -> ResultRow(hit.title, null, null, hit.image) { onPick(hit.url, hit.title) } }
                // Products found on the web, by their name only: picked, the product is tracked, found across
                // all shops first (as a pasted shop link), so its price is compared.
                if (link == null) items(products, key = { "p:" + it.url }) { product ->
                    ResultRow(product.title, null, null) { query = product.url }
                }
                if (google != null && !googleFirst) {
                    item(key = "google") {
                        ResultRow(t("Track via Google Shopping: \"%s\"", google), "Checked once a day", null) {
                            onGoogleShopping?.invoke(google)
                        }
                    }
                }
                if (shopLink != null && !searching) {
                    item { ResultRow("Track this link only", shopLink, null) { onPick(shopLink, null) } }
                }
            }
        }
    }
}

/** How long typing must pause before search words are suggested, and before products are searched. */
private const val SUGGEST_DELAY_MS = 250L
private const val DISCOVER_DELAY_MS = 600L

@Composable
private fun ResultRow(title: String, subtitle: String?, price: Double?, image: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // A search result's photo, so you see which product (and which pack) it is before you pick it.
            if (image != null) {
                val photo by produceState<ImageBitmap?>(null, image) {
                    value = withContext(Dispatchers.IO) { runCatching { Images.thumbnail(image)?.asImageBitmap() }.getOrNull() }
                }
                ProductPhoto(photo, ThumbnailSize, 12.dp)
                Spacer(Modifier.width(ThumbnailGap))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            price?.let {
                Spacer(Modifier.width(12.dp))
                Text(t("from %s", it.euro()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}
