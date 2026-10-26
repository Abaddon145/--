package com.luca.classmanager.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.luca.classmanager.LucaApplication
import com.luca.classmanager.domain.logic.UpcomingClass
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

object ReminderScheduler {
    private const val PERIODIC = "luca-reminders"
    private const val SOON = "luca-reminders-now"
    private const val ALARM_REQUEST = 41

    fun enqueueSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val settings = (app as LucaApplication).container.settingsRepository
        val work = WorkManager.getInstance(app)
        if (!settings.remindersEnabled.first()) {
            work.cancelUniqueWork(PERIODIC)
            alarmIntent(app)?.let { app.getSystemService(AlarmManager::class.java).cancel(it) }
            return
        }
        val periodic = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        enqueueSoon(app)
    }

    fun armNextClass(context: Context, upcoming: UpcomingClass?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context) ?: return
        if (upcoming == null) {
            alarm.cancel(pending)
            return
        }
        val trigger = upcoming.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val earliest = System.currentTimeMillis() + 5_000
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(trigger, earliest), pending)
    }

    private fun alarmIntent(context: Context): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
