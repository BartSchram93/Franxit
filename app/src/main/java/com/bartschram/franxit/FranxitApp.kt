package com.bartschram.franxit

import android.app.Application
import com.bartschram.franxit.work.Notifier
import com.bartschram.franxit.work.Scheduler

class FranxitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        Scheduler.ensureScheduled(this)
    }
}
