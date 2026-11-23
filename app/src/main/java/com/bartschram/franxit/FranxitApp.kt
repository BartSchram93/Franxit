package com.bartschram.franxit

import android.app.Application
import com.bartschram.franxit.data.Analytics
import com.bartschram.franxit.data.Repository
import com.bartschram.franxit.ui.L10n
import com.bartschram.franxit.work.Notifier

class FranxitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The language first: the notification channels and texts use it.
        L10n.init(this)
        Notifier.createChannel(this)
        // Each product's own nightly check (between 2 and 6), made again when the way it's scheduled changed.
        Repository.get(this).scheduleChecks()
        Analytics.init(this)
        Analytics.track("app_started")
    }
}
