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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.bartschram.franxit.scrape.ProductSearch
import androidx.compose.material3.TextButton

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
    /** Search words while typing (Google's), shown in a list under the field. */
    suggest: suspend (String) -> List<String>,
    /** Products on the web for typed words. */
    discover: suspend (String) -> List<ProductSearch.Product>,
    /** A found product's photo, when its own page shows a price the app reads; null otherwise (then it isn't shown). */
    photoOf: suspend (ProductSearch.Product) -> String?,
    titleFromLink: suspend (String) -> String?,
    /** [title] is the product's name when the result has one (a typed or shared link has none). */
    onPick: (url: String, title: String?) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
    /** The price tag photo button (off in the screenshots, which have no camera). */
    photoSearch: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<VergelijkSearch.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Products found on the web for the typed words.
    var products by remember { mutableStateOf<List<ProductSearch.Product>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var limitReached by remember { mutableStateOf(false) }
    // Words searched when asked (Enter): not while typing. Counted, so each asks once.
    // A name the screen opens with (a price tag's) is searched at once too.
    var searchNow by remember { mutableStateOf(if (initialQuery.isNotBlank()) 1 else 0) }
    val searchedNow = remember { intArrayOf(0) }
    // Whether the products shown are for the words in the field (typing on asks to search again).
    var searched by remember { mutableStateOf(false) }
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
            return@LaunchedEffect
        }
        results = emptyList()
        coroutineScope {
            // Products only when asked (Search): each search counts towards the user's 20 a day.
            if (!now) {
                products = emptyList()
                failed = false
                limitReached = false
                searched = false
                // Search words for what's typed so far, once typing pauses (free; a new letter cancels).
                delay(SUGGEST_DELAY_MS)
                suggestions = suggest(q)
                return@coroutineScope
            }
            suggestions = emptyList()
            searched = true
            searching = true
            val found = runCatching { discover(q) }
            failed = found.isFailure
            limitReached = found.exceptionOrNull() is ProductSearch.LimitReached
            // Only products with a photo that loads, shown with it as each one's is there (the search's, else its own
            // page's: Coolblue's, Amazon's), in the search's order, and each product once.
            val all = found.getOrDefault(emptyList())
            val photos = java.util.concurrent.ConcurrentHashMap<String, String>()
            fun shown() = ProductSearch.oneOfEach(all.mapNotNull { p -> photos[p.url]?.let { p.copy(imageUrl = it) } })
            products = emptyList()
            all.map { p ->
                async { photoOf(p)?.let { photos[p.url] = it; products = shown() } }
            }.awaitAll()
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
                actions = { if (photoSearch) PriceTagSearch(onSearch = { query = it; searchNow++ }) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                // A search button besides the keyboard's: words are only searched when asked (20 a day).
                trailingIcon = {
                    when {
                        searching -> LoadingDots(dotSize = 5.dp)
                        link == null && query.trim().length >= 2 -> IconButton(onClick = { searchNow++ }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, t("Search"))
                        }
                    }
                },
                placeholder = { Text("Search or paste a link") },
                shape = RoundedCornerShape(16.dp),
                // The keyboard's own word suggestions and corrections, said outright: some keyboards leave them off in a
                // search field unless asked.
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                    autoCorrectEnabled = true,
                    imeAction = if (link != null) ImeAction.Done else ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { link?.let { onPick(it, null) } },
                    onSearch = { searchNow++ },
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus),
            )
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
                } else if (query.trim().length >= 2 && !searched && suggestions.isNotEmpty()) {
                    // Search words while typing: picked, searched at once.
                    items(suggestions, key = { "s:$it" }) { words ->
                        Surface(
                            onClick = { query = words; searchNow++ },
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(14.dp))
                                // Whole, over more lines when long: suggestions often differ only at their end.
                                androidx.compose.material3.Text(words, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else if (query.trim().length >= 2 && !searched) {
                    item {
                        Text(
                            "Press the search button to find products.",
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
                                else -> "No products found. Paste the link to the product's page at a shop to track it."
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
                items(results, key = { it.url }) { hit -> ResultRow(hit.title, null, null, hit.image) { onPick(hit.url, hit.title) } }
                // Products found on the web whose own page shows a price the app reads, with their photo. Picked, it is
                // tracked at once; its check finds the other shops.
                if (link == null) items(products, key = { "p:" + it.url }) { product ->
                    ResultRow(product.title, null, null, product.imageUrl) { onPick(product.url, product.title) }
                }
                if (shopLink != null && !searching) {
                    item { ResultRow("Track this link only", shopLink, null) { onPick(shopLink, null) } }
                }
            }
        }
    }
}


/** How long typing must pause before search words are suggested. */
private const val SUGGEST_DELAY_MS = 250L

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
