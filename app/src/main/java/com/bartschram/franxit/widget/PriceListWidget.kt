package com.bartschram.franxit.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bartschram.franxit.MainActivity
import com.bartschram.franxit.data.Discounts
import com.bartschram.franxit.data.Images
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.Verdict
import com.bartschram.franxit.data.BuyAdvisor
import com.bartschram.franxit.data.Repository
import com.bartschram.franxit.data.Shops
import com.bartschram.franxit.ui.SortOrder
import com.bartschram.franxit.ui.euro
import com.bartschram.franxit.ui.shortTitle
import com.bartschram.franxit.ui.t

/**
 * Home-screen widget listing the tracked products: photo, name, price and today's change.
 * It follows the app's sorting and its "Only with price alert" filter.
 */
class PriceListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository.get(context)
        repo.reload()
        provideContent {
            // Collected rather than read once: a running widget session is only recomposed on update,
            // so a value captured above would keep showing deleted or outdated products.
            val all by repo.products.collectAsState()
            // Read on every update, so changing the sorting or filter in the app shows here right away.
            val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            val alertsOnly = prefs.getBoolean("alerts_only", false)
            val sort = runCatching { SortOrder.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(SortOrder.ADDED)
            val products = sort.apply(if (alertsOnly) all.filter { it.targetPrice != null } else all)
            val discounts = Discounts.load(context)
            // Small copies of the photos; widgets have a tight memory budget for images.
            val photos = remember(products) {
                products.associate { p ->
                    p.id to Images.load(context, p.id)?.let { Bitmap.createScaledBitmap(it, 96, 96 * it.height / it.width.coerceAtLeast(1), true) }
                }
            }
            Content(context, products, photos, alertsOnly, discounts)
        }
    }

    @Composable
    private fun Content(
        context: Context,
        products: List<Product>,
        photos: Map<Long, Bitmap?>,
        alertsOnly: Boolean,
        discounts: Map<String, Double>,
    ) {
        Column(
            GlanceModifier.fillMaxSize().background(Background).cornerRadius(22.dp).padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                if (alertsOnly) t("Price alerts") else t("Overview"),
                style = TextStyle(color = ColorProvider(TextMuted), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            )
            Spacer(GlanceModifier.height(6.dp))
            if (products.isEmpty()) {
                Text(if (alertsOnly) t("No products with a price alert") else t("Add a product in the app"), style = TextStyle(color = ColorProvider(TextMain), fontSize = 13.sp))
                return@Column
            }
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(products, itemId = { it.id }) { product -> ProductRow(context, product, photos[product.id], discounts) }
            }
        }
    }

    @Composable
    private fun ProductRow(context: Context, product: Product, photo: Bitmap?, discounts: Map<String, Double>) {
        val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_PRODUCT_ID, product.id)
        Row(
            GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (photo != null) {
                Image(
                    ImageProvider(photo), contentDescription = null,
                    modifier = GlanceModifier.size(36.dp).background(Color.White).cornerRadius(8.dp).padding(2.dp),
                )
                Spacer(GlanceModifier.width(10.dp))
            }
            Column(GlanceModifier.defaultWeight()) {
                Text(product.displayName, style = TextStyle(color = ColorProvider(TextMain), fontSize = 13.sp), maxLines = 1)
                product.latest?.seller?.takeIf { it.isNotBlank() }?.let {
                    Text(Shops.name(it), style = TextStyle(color = ColorProvider(TextMuted), fontSize = 11.sp), maxLines = 1)
                }
                // Like the app: what you'd pay where a shop discount makes it cheapest for you.
                val best = Discounts.bestForYou(product.offers, discounts)
                val percent = best?.let { Discounts.percentFor(it.seller, discounts) }
                if (best != null && percent != null) {
                    val yours = Discounts.yourPrice(best, discounts).euro()
                    Text(
                        if (best.seller != product.latest?.seller) t("%1\$s at %2\$s with your %3\$s%%", yours, Shops.name(best.seller), Discounts.label(percent))
                        else t("%1\$s with your %2\$s%%", yours, Discounts.label(percent)),
                        style = TextStyle(color = ColorProvider(TextMuted), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    product.latest?.price?.euro() ?: "–",
                    style = TextStyle(color = ColorProvider(TextMain), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                // As in the app: the Buy or wait advice in a word, or a warning when the check stopped.
                if (product.isStale()) {
                    Text(t("Check overdue"), style = TextStyle(color = ColorProvider(Warn), fontSize = 11.sp), maxLines = 1)
                } else {
                    val advice = BuyAdvisor.advise(product)
                    if (advice.verdict != Verdict.INSUFFICIENT_DATA) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("● ", style = TextStyle(color = ColorProvider(verdictDot(advice.verdict)), fontSize = 9.sp))
                            Text(
                                t(shortTitle(advice.verdict)),
                                style = TextStyle(color = ColorProvider(if (advice.verdict == Verdict.BUY_NOW) Down else TextMuted), fontSize = 11.sp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun verdictDot(verdict: Verdict): Color = when (verdict) {
        Verdict.BUY_NOW -> Down
        Verdict.CONSIDER_BUYING -> Blue
        Verdict.WAIT -> Warn
        Verdict.WAIT_EXPENSIVE -> Up
        Verdict.INSUFFICIENT_DATA -> TextMuted
    }
}

class PriceListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PriceListWidget()
}
