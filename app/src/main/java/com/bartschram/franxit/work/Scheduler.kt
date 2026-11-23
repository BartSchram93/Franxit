package com.bartschram.franxit.work

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Each product is checked once a day on its own, at a moment of its own between 2 and 6 at night: every check gets
 * Android's full time for background work (a product's shops can take minutes), and the searches for many products
 * don't all come at once (DuckDuckGo blocks a burst).
 */
object Scheduler {

    private const val PREFS = "schedule"

    /** The one check for all products the app used to run at a time you chose. */
    private const val OLD_WORK = "daily-price-check"

    /** Bumped when the way products are scheduled changes: then each is scheduled again once. */
    private const val VERSION = 1

    private fun workName(productId: Long) = "check-product-$productId"

    /** A product's own moment between 2:00 and 6:00, the same every day (from its id). */
    internal fun timeOf(productId: Long): LocalTime {
        val minutes = java.util.Random(productId * 7919 + 104729).nextInt(4 * 60)
        return LocalTime.of(2, 0).plusMinutes(minutes.toLong())
    }

    /** Every product scheduled ([productIds]), the old daily check removed. Kept as they are when already scheduled. */
    fun ensureScheduled(context: Context, productIds: List<Long>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val again = prefs.getInt("version", 0) != VERSION
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(OLD_WORK)
        val policy = if (again) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        productIds.forEach { schedule(context, it, policy) }
        if (again) prefs.edit { putInt("version", VERSION) }
    }

    /** A new product: its own nightly check. */
    fun schedule(context: Context, productId: Long, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(timeOf(productId))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ProductCheckWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf(ProductCheckWorker.PRODUCT to productId))
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(workName(productId), policy, request)
    }

    /** A deleted product: no more nightly check. */
    fun cancel(context: Context, productId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(productId))
    }
}
