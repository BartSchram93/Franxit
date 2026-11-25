package com.bartschram.franxit.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bartschram.franxit.data.Repository

/** One product's nightly check (see Scheduler); alerts are sent by the repository as it checks. */
class ProductCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(PRODUCT, -1)
        val repo = Repository.get(applicationContext)
        // A product deleted in the meantime: its check stops.
        if (id < 0 || !repo.exists(id)) {
            if (id >= 0) Scheduler.cancel(applicationContext, id)
            return Result.success()
        }
        // A failed check (no connection) is tried again a few times; a page that changed shows on the product.
        // (Six times, 15 minutes apart and more: the phone's network can sleep for a while at night.)
        return if (repo.checkOne(id, background = true).isFailure && runAttemptCount < 6) Result.retry() else Result.success()
    }

    companion object {
        const val PRODUCT = "product"
    }
}
