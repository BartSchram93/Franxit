package com.bartschram.franxit.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.bartschram.franxit.MainActivity
import com.bartschram.franxit.R
import com.bartschram.franxit.data.Discounts
import com.bartschram.franxit.data.Shops
import com.bartschram.franxit.data.Alert
import com.bartschram.franxit.data.AlertKind
import com.bartschram.franxit.data.Product
import com.bartschram.franxit.data.UpdateCheck
import com.bartschram.franxit.ui.euro
import com.bartschram.franxit.ui.t

object Notifier {

    private const val CHANNEL = "price_drops"
    private const val SENT = "sent_alerts"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, t("Price drops"), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Shows [alert], unless an alert at least as important was already shown for this product
     * today. Both the daily check and a manual refresh land here, so whichever finds the price
     * first notifies, and the other stays quiet.
     */
    fun priceAlert(context: Context, alert: Alert) {
        if (!allowed(context)) return

        val sent = context.getSharedPreferences(SENT, Context.MODE_PRIVATE)
        val key = "p${alert.product.id}"
        val previous = sent.getString(key, null)?.split(':')
        val today = alert.to.day.toString()
        if (previous?.getOrNull(0) == today &&
            (previous.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE) <= alert.kind.ordinal
        ) return
        sent.edit { putString(key, "$today:${alert.kind.ordinal}") }

        val price = alert.to.price.euro()
        val title = when (alert.kind) {
            AlertKind.TARGET -> t("%1\$s — below your %2\$s target", price, alert.product.targetPrice?.euro().orEmpty())
            AlertKind.LOWEST -> t("%s — lowest price seen", price)
            AlertKind.DROP -> t("%1\$s — was %2\$s", price, alert.from?.euro().orEmpty())
        }
        show(context, alert.product, title, alert.to.seller, id = alert.product.id.toInt())
    }

    /**
     * Your shop discount brings [product] to or below your alert, while the shops' own prices
     * aren't there yet. Its own notification, so it doesn't replace a real price alert.
     */
    fun discountAlert(context: Context, product: Product, price: Double, seller: String, percent: Double, target: Double) {
        if (!allowed(context)) return
        val title = t("%1\$s with your %2\$s%% — below your %3\$s target", price.euro(), Discounts.label(percent), target.euro())
        show(context, product, title, seller, id = DISCOUNT_ID_OFFSET + product.id.toInt())
    }

    private const val DISCOUNT_ID_OFFSET = 1_000_000

    private const val UPDATES = "app_updates"
    private const val UPDATE_ID = 2_000_000

    /**
     * Says a newer Franxit is out (it isn't in a store), once per version: quietly, with what's new,
     * and a tap opens the download page. False when notifications aren't allowed.
     */
    fun newVersion(context: Context, notes: String?): Boolean {
        if (!allowed(context)) return false
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(UPDATES, t("App updates"), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.DOWNLOAD_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, UPDATE_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = notes ?: t("A new version is ready to download.")
        val notification = NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.ic_stat_price)
            .setContentTitle(t("New version of Franxit"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_ID, notification)
        return true
    }

    private fun show(context: Context, product: Product, title: String, seller: String, id: Int) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PRODUCT_ID, product.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, id, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = t("%1\$s at %2\$s", product.displayName, seller.ifBlank { t("a shop") }.let(Shops::name))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_price)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
