package com.bartschram.franxit

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.DisposableEffect
import android.provider.Settings
import android.app.Activity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.bartschram.franxit.data.Discounts
import com.bartschram.franxit.data.Photos
import com.bartschram.franxit.data.Repository
import com.bartschram.franxit.data.ShareLink
import com.bartschram.franxit.ui.L10n
import com.bartschram.franxit.ui.LanguageDialog
import com.bartschram.franxit.ui.t
import com.bartschram.franxit.ui.tMessage
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.bartschram.franxit.data.Analytics
import com.bartschram.franxit.ui.mergeSameShop
import com.bartschram.franxit.scrape.GoogleShopping
import com.bartschram.franxit.data.ShoppingSettings
import com.bartschram.franxit.data.Shops
import com.bartschram.franxit.ui.DetailScreen
import com.bartschram.franxit.ui.ListScreen
import com.bartschram.franxit.ui.SearchScreen
import com.bartschram.franxit.ui.DiscountsDialog
import com.bartschram.franxit.ui.SortOrder
import com.bartschram.franxit.ui.checkedLabel
import com.bartschram.franxit.ui.theme.FranxitTheme
import androidx.glance.appwidget.updateAll
import com.bartschram.franxit.widget.PriceListWidget
import com.bartschram.franxit.work.Scheduler
import kotlinx.coroutines.launch
import java.time.LocalTime

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)
    // A product a friend shared (from its link): the app asks whether to track it.
    private val sharedProduct = mutableStateOf<ShareLink.Shared?>(null)
    private val openProductId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        L10n.init(applicationContext)
        // key(): when the language changes, everything is drawn again in the new one.
        setContent { FranxitTheme { androidx.compose.runtime.key(L10n.choice) { App(sharedUrl, openProductId, sharedProduct) } } }
    }

    override fun onStart() {
        super.onStart()
        // Earlier versions kept a small "usage" file for an anonymous daily count; it is gone for good.
        applicationContext.deleteSharedPreferences("usage")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            sharedUrl.value = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { URL_REGEX.find(it)?.value }
        }
        // A link from a friend's shared advice: ask whether to track that product.
        if (intent.action == Intent.ACTION_VIEW) ShareLink.parse(intent.dataString)?.let { sharedProduct.value = it }
        intent.getLongExtra(EXTRA_PRODUCT_ID, -1).takeIf { it >= 0 }?.let { openProductId.value = it }
    }

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}

@Composable
private fun App(sharedUrl: MutableState<String?>, openProductId: MutableState<Long?>, sharedProduct: MutableState<ShareLink.Shared?>) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val allProducts by repo.products.collectAsState()
    val removing by repo.removing.collectAsState()
    // Products swiped away are hidden while their Undo time runs.
    val products = remember(allProducts, removing) { allProducts.filter { it.id !in removing } }
    val progress by repo.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // A link shared from the browser opens the search with it, to find it on vergelijk.be.
    var searchStart by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var checkTime by remember { mutableStateOf(Scheduler.time(context)) }
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var sort by remember {
        mutableStateOf(runCatching { SortOrder.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(SortOrder.ADDED))
    }
    var photoVersion by remember { mutableStateOf(0) }
    var showTips by remember { mutableStateOf(!prefs.getBoolean("tips_seen", false)) }
    var alertsOnly by remember { mutableStateOf(prefs.getBoolean("alerts_only", false)) }
    var discounts by remember { mutableStateOf(Discounts.load(context)) }
    var showDiscounts by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    val shoppingKey = remember { ShoppingSettings.enabled() }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    var notificationsOn by remember { mutableStateOf(notificationsAllowed()) }
    // Checked again whenever the app comes back, e.g. from Android's notification settings.
    // The activity itself; the old LocalLifecycleOwner from compose-ui is deprecated.
    val lifecycle = (context as LifecycleOwner).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsOn = notificationsAllowed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOn = notificationsAllowed()
    }
    val appPrefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    fun requestNotifications() {
        appPrefs.edit { putBoolean("asked_notifications", true) }
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Android shows its permission question only so often; after that, the app's notification
     * settings are the way to turn them on.
     */
    fun turnOnNotifications() {
        val activity = context as? Activity
        val canAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!appPrefs.getBoolean("asked_notifications", false) ||
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)
        if (canAsk) {
            requestNotifications()
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    // An alert is pointless if Android blocks the app's notifications.
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestNotifications()
    }
    fun add(url: String, via: String, title: String? = null) {
        Analytics.track("product_added", mapOf("via" to via))
        scope.launch {
            refreshing = true
            // The product opens at once, with its searching words; vergelijk.be's shops come in first,
            // Google Shopping's after.
            try {
                // Only opened from the callback: if you went back to the list meanwhile, it stays there.
                repo.add(url.trim(), title) { id -> scope.launch { selectedId = id } }
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        repo.reload()
        if (Build.VERSION.SDK_INT >= 33 && !notificationsOn) requestNotifications()
    }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(sharedUrl.value) {
        sharedUrl.value?.let { searchStart = it; searching = true; sharedUrl.value = null }
    }
    LaunchedEffect(openProductId.value) {
        openProductId.value?.let { selectedId = it; openProductId.value = null }
    }

    val selected = products.firstOrNull { it.id == selectedId }
    if (searching) {
        BackHandler { searching = false }
        SearchScreen(
            search = { repo.search(it) },
            titleFromLink = { repo.productTitleFor(it) },
            onPick = { url, title -> searching = false; searchStart = ""; add(url, "search", title) },
            onBack = { searching = false; searchStart = "" },
            initialQuery = searchStart,
            onGoogleShopping = if (shoppingKey) { query -> searching = false; searchStart = ""; add(GoogleShopping.urlFor(query), "google_shopping", query) } else null,
        )
    } else if (selected != null) {
        BackHandler { selectedId = null }
        // The Buy button's page looked up while you look at the product, so a tap opens it at once.
        LaunchedEffect(selected.id) { repo.prepareShopLinks(selected) }
        DetailScreen(
            product = selected,
            refreshing = refreshing,
            onBack = { selectedId = null },
            onRefresh = {
                scope.launch {
                    refreshing = true
                    repo.checkOne(selected.id).onFailure { toast(tMessage(it.message.orEmpty())) }
                    refreshing = false
                }
            },
            onDelete = {
                selectedId = null
                scope.launch { repo.delete(selected.id) }
            },
            onRename = { name -> scope.launch { repo.rename(selected.id, name) } },
            photoChoice = remember(selected.id, photoVersion) { Photos.choice(context, selected.id) },
            // A Google Shopping product already shows Google's photo; for others, once a search found one.
            googlePhoto = remember(selected.id, photoVersion, selected.lastChecked) {
                GoogleShopping.queryOf(selected.url) == null && Photos.googlePhoto(context, selected.id) != null
            },
            photoVersion = photoVersion,
            onPhoto = { choice ->
                scope.launch {
                    repo.setPhoto(selected.id, choice)
                    photoVersion++
                }
            },
            onSetTarget = { price -> if (price != null) Analytics.track("price_alert_set"); scope.launch { repo.setTarget(selected.id, price) } },
            onSetNotify = { on ->
                scope.launch { repo.setNotify(selected.id, on) }
                if (on) askForNotifications()
            },
            discounts = discounts,
            shopLink = { seller -> repo.shopLink(selected, seller) },
            googleAuto = shoppingKey && GoogleShopping.queryOf(selected.url) == null &&
                mergeSameShop(selected.offers).size <= 3,
        )
    } else {
        ListScreen(
            products = products,
            refreshing = refreshing,
            progress = progress,
            checkTime = checkTime,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val summary = repo.checkAll()
                    refreshing = false
                    if (summary.failed == 1) toast(t("1 product couldn't be checked"))
                    else if (summary.failed > 1) toast(t("%d products couldn't be checked", summary.failed))
                }
            },
            onOpen = { selectedId = it.id },
            onAdd = { searching = true },
            sort = sort,
            onSort = {
                sort = it
                prefs.edit { putString("sort", it.name) }
                // The home-screen widget uses the same sorting.
                scope.launch { runCatching { PriceListWidget().updateAll(context) } }
            },
            alertsOnly = alertsOnly,
            onAlertsOnly = {
                alertsOnly = it
                prefs.edit { putBoolean("alerts_only", it) }
                // The home-screen widget follows the same filter.
                scope.launch { runCatching { PriceListWidget().updateAll(context) } }
            },
            onSetAlert = { product, price ->
                if (price != null) Analytics.track("price_alert_set")
                scope.launch {
                    repo.setTarget(product.id, price)
                    // Alerts muted with the old "Notify me" switch notify again once saved.
                    if (price != null && !product.notify) repo.setNotify(product.id, true)
                }
                if (price != null) askForNotifications()
            },
            discounts = discounts,
            onDiscounts = { showDiscounts = true },
            onLanguage = { showLanguage = true },
            notificationsOn = notificationsOn,
            onTurnOnNotifications = { turnOnNotifications() },
            showTips = showTips,
            photoVersion = photoVersion,
            onTipsSeen = { showTips = false; prefs.edit { putBoolean("tips_seen", true) } },
            onShowTips = { showTips = true },
            onDelete = { product -> repo.deleteAfterUndoTime(product.id) },
            onUndoDelete = { product -> repo.undoDelete(product.id) },
            onChangeTime = {
                TimePickerDialog(context, { _, h, m ->
                    val time = LocalTime.of(h, m)
                    Scheduler.setTime(context, time)
                    checkTime = time
                }, checkTime.hour, checkTime.minute, true).show()
            },
        )
    }

    sharedProduct.value?.let { shared ->
        // A preview of the shared product (photo, name, price) before you track it.
        com.bartschram.franxit.ui.SharedProductDialog(
            shared = shared,
            preview = { repo.preview(it) },
            onTrack = { name -> sharedProduct.value = null; add(shared.url, "shared_link", name) },
            onDismiss = { sharedProduct.value = null },
        )
    }

    if (showLanguage) {
        LanguageDialog(
            choice = L10n.choice,
            onChoose = { choice ->
                L10n.set(context, choice)
                // The home-screen widget speaks the same language.
                scope.launch { runCatching { PriceListWidget().updateAll(context) } }
            },
            onDismiss = { showLanguage = false },
        )
    }

    if (showDiscounts) {
        DiscountsDialog(
            discounts = discounts,
            shops = products.flatMap { p -> p.offers.map { it.seller } }.filter { it.isNotBlank() }
                // One entry per shop: a discount for bol.com also counts for its partners.
                .distinctBy { Shops.name(it) }.sortedBy { Shops.name(it).lowercase() },
            onSet = { shop, percent ->
                Discounts.set(context, shop, percent)
                discounts = Discounts.load(context)
                // The widget shows your discounted price too.
                scope.launch { runCatching { PriceListWidget().updateAll(context) } }
            },
            onDismiss = { showDiscounts = false },
        )
    }
}
