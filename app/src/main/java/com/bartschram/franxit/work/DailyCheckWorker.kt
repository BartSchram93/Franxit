package com.bartschram.franxit.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bartschram.franxit.data.Repository
import com.bartschram.franxit.data.UpdateCheck

class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Alerts are sent by the repository as each product is checked.
        val repo = Repository.get(applicationContext)
        val summary = repo.checkAll()
        // Not in a store: say once when a newer version is out (see UpdateCheck); never fails the check.
        runCatching {
            UpdateCheck.refresh(applicationContext)
            if (UpdateCheck.shouldNotify(applicationContext) && Notifier.newVersion(applicationContext, UpdateCheck.notes(applicationContext))) {
                UpdateCheck.markTold(applicationContext)
            }
        }
        // Only retry when nothing could be checked (e.g. flaky network); partial results are kept.
        return if (summary.checked == 0 && summary.failed > 0 && runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
