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
import com.bartschram.franxit.ui.UsageDialog
import com.bartschram.franxit.data.Usage
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.bartschram.franxit.data.UpdateCheck
import com.bartschram.franxit.ui.ShoppingDialog
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
    private val openProductId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { FranxitTheme { App(sharedUrl, openProductId) } }
    }

    override fun onStart() {
        super.onStart()
        // This phone counted anonymously once a day (see Usage); off the main thread.
        val app = applicationContext
        Thread { Usage.reportToday(app) }.start()
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
        intent.getLongExtra(EXTRA_PRODUCT_ID, -1).takeIf { it >= 0 }?.let { openProductId.value = it }
    }

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}

@Composable
private fun App(sharedUrl: MutableState<String?>, openProductId: MutableState<Long?>) {
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
    var showShopping by rememberSaveable { mutableStateOf(false) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var shoppingKey by remember { mutableStateOf(ShoppingSettings.key(context) != null) }
    var extraOn by remember { mutableStateOf(ShoppingSettings.extraIds(context)) }
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
    fun add(url: String) {
        scope.launch {
            refreshing = true
            selectedId = repo.add(url.trim())
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        repo.reload()
        if (Build.VERSION.SDK_INT >= 33 && !notificationsOn) requestNotifications()
    }
    // Not in a store: ask GitHub once a day whether a newer version is out.
    var updateAvailable by remember { mutableStateOf(UpdateCheck.available(context)) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UpdateCheck.refresh(context) }
        updateAvailable = UpdateCheck.available(context)
        // The banner tells them now: no notification about this version later.
        if (updateAvailable) UpdateCheck.markTold(context)
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
            onPick = { url -> searching = false; searchStart = ""; add(url) },
            onBack = { searching = false; searchStart = "" },
            initialQuery = searchStart,
            onGoogleShopping = if (shoppingKey) { query -> searching = false; searchStart = ""; add(GoogleShopping.urlFor(query)) } else null,
        )
    } else if (selected != null) {
        BackHandler { selectedId = null }
        DetailScreen(
            product = selected,
            refreshing = refreshing,
            onBack = { selectedId = null },
            onRefresh = {
                scope.launch {
                    refreshing = true
                    repo.checkOne(selected.id).onFailure { toast("Check failed: ${it.message}") }
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
            onSetTarget = { price -> scope.launch { repo.setTarget(selected.id, price) } },
            onSetNotify = { on ->
                scope.launch { repo.setNotify(selected.id, on) }
                if (on) askForNotifications()
            },
            discounts = discounts,
            shopLink = { seller -> repo.shopLink(selected, seller) },
            googleExtra = if (shoppingKey && GoogleShopping.queryOf(selected.url) == null) {
                extraOn.contains(selected.id)
            } else null,
            googleStatus = remember(selected.id, selected.lastChecked, extraOn) { ShoppingSettings.extraStatus(context, selected.id) },
            onGoogleExtra = { on ->
                ShoppingSettings.setExtra(context, selected.id, on)
                extraOn = if (on) extraOn + selected.id else extraOn - selected.id
                scope.launch {
                    refreshing = true
                    repo.checkOne(selected.id)
                    refreshing = false
                }
            },
        )
    } else {
        ListScreen(
            updateAvailable = updateAvailable,
            updateNotes = remember(updateAvailable) { UpdateCheck.notes(context) },
            onUpdate = { uriHandler.openUri(UpdateCheck.DOWNLOAD_PAGE) },
            products = products,
            refreshing = refreshing,
            progress = progress,
            checkTime = checkTime,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val summary = repo.checkAll()
                    refreshing = false
                    if (summary.failed > 0) toast("${summary.failed} product(s) couldn't be checked")
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
                scope.launch {
                    repo.setTarget(product.id, price)
                    // Alerts muted with the old "Notify me" switch notify again once saved.
                    if (price != null && !product.notify) repo.setNotify(product.id, true)
                }
                if (price != null) askForNotifications()
            },
            discounts = discounts,
            onDiscounts = { showDiscounts = true },
            onGoogleShopping = { showShopping = true },
            onUsage = if (Usage.configured) ({ showUsage = true }) else null,
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

    if (showUsage) {
        UsageDialog(
            enabled = Usage.enabled(context),
            onChange = { on -> Usage.setEnabled(context, on) },
            onDismiss = { showUsage = false },
        )
    }

    if (showShopping) {
        ShoppingDialog(
            hasKey = shoppingKey,
            onSave = { key ->
                ShoppingSettings.setKey(context, key)
                shoppingKey = key != null
                if (key == null) showShopping = false
            },
            onDismiss = { showShopping = false },
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
