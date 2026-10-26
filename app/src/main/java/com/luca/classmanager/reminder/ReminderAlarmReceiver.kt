package com.luca.classmanager.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.luca.classmanager.LucaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = (app as? LucaApplication)?.container?.settingsRepository?.remindersEnabled?.first() == true
                if (enabled) ReminderScheduler.enqueueSoon(app)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app is LucaApplication) ReminderScheduler.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}
