package com.bartschram.franxit

import android.app.Application
import com.bartschram.franxit.ui.L10n
import com.bartschram.franxit.work.Notifier
import com.bartschram.franxit.work.Scheduler

class FranxitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The language first: the notification channels and texts use it.
        L10n.init(this)
        Notifier.createChannel(this)
        Scheduler.ensureScheduled(this)
    }
}
