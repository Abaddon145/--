package com.luca.classmanager

import android.app.Application
import com.luca.classmanager.di.AppContainer
import com.luca.classmanager.reminder.ReminderNotifications
import com.luca.classmanager.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LucaApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderNotifications.ensureChannel(this)
        scope.launch { ReminderScheduler.refresh(this@LucaApplication) }
    }
}
