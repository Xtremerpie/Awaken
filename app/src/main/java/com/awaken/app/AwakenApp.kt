package com.awaken.app

import android.app.Application
import com.awaken.app.di.AppContainer
import com.awaken.app.notifications.Notifications

class AwakenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Notifications.ensureChannels(this)
        container = AppContainer(this)
    }
}
