package com.luca.classmanager.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.luca.classmanager.LucaApplication
import com.luca.classmanager.domain.logic.ReminderPlanner
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LucaApplication).container
        val settings = container.settingsRepository
        if (!settings.remindersEnabled.first()) return Result.success()
        ReminderNotifications.ensureChannel(applicationContext)
        val courses = container.courseRepository.observeCourses().first()
        val now = LocalDateTime.now()
        val lead = settings.reminderLeadMinutes.first()
        val due = ReminderPlanner.due(
            courses = courses,
            now = now,
            leadMinutes = lead,
            uncheckedHour = settings.uncheckedReminderHour.first(),
            defaultRenewalUnits = settings.renewalThresholdCreditUnits.first(),
            enabled = true,
        )
        val seen = settings.firedReminderKeys()
        val fresh = due.filter { it.key !in seen }
        fresh.forEach { notice -> ReminderNotifications.show(applicationContext, notice) }
        settings.rememberReminderKeys(fresh.map { it.key }.toSet())
        ReminderScheduler.armNextClass(applicationContext, ReminderPlanner.nextClass(courses, now, lead))
        return Result.success()
    }
}
