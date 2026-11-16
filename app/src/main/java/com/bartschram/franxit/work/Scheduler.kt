package com.bartschram.franxit.work

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Runs [DailyCheckWorker] once a day, starting at the chosen time of day. */
object Scheduler {

    private const val WORK_NAME = "daily-price-check"
    private const val PREFS = "schedule"

    fun time(context: Context): LocalTime {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LocalTime.of(prefs.getInt("hour", 8), prefs.getInt("minute", 0))
    }

    fun setTime(context: Context, time: LocalTime) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("hour", time.hour)
            putInt("minute", time.minute)
        }
        schedule(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    /**
     * Keeps the existing schedule, unless it was made for a worker class that moved (WorkManager
     * stores the class name); then it's made again.
     */
    fun ensureScheduled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val worker = DailyCheckWorker::class.java.name
        if (prefs.getString("worker", null) == worker) {
            schedule(context, ExistingPeriodicWorkPolicy.KEEP)
        } else {
            schedule(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
            prefs.edit { putString("worker", worker) }
        }
    }

    private fun schedule(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time(context))
        if (!next.isAfter(now)) next = next.plusDays(1)

        val request = PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }
}
