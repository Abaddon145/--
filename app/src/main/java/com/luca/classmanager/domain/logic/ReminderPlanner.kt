package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.ScheduleMode
import java.time.LocalDateTime

data class ReminderNotice(
    val key: String,
    val title: String,
    val body: String,
)

data class UpcomingClass(
    val courseId: String,
    val courseName: String,
    val triggerAt: LocalDateTime,
)

/**
 * Decides which local reminders are due. Scheduling and permissions stay outside this object.
 */
object ReminderPlanner {
    fun due(
        courses: List<CourseDetails>,
        now: LocalDateTime,
        leadMinutes: Int,
        uncheckedHour: Int,
        defaultRenewalUnits: Int,
        enabled: Boolean,
    ): List<ReminderNotice> {
        if (!enabled) return emptyList()
        val lead = leadMinutes.coerceIn(5, 240)
        val evening = uncheckedHour.coerceIn(0, 23)
        val today = now.toLocalDate()
        val minuteOfDay = now.hour * 60 + now.minute
        val notices = mutableListOf<ReminderNotice>()
        courses.filter { it.status == CourseStatus.ACTIVE && it.scheduleMode == ScheduleMode.WEEKLY }.forEach { course ->
            val todayRules = course.rules.filter { it.dayOfWeek == today.dayOfWeek.value }
            todayRules.forEach { rule ->
                val windowStart = rule.startMinutes - lead
                if (minuteOfDay in windowStart until rule.startMinutes) {
                    notices += ReminderNotice(
                        key = "class:${course.id}:$today:${rule.startMinutes}",
                        title = "即将上课",
                        body = "${course.name} ${formatMinuteRange(rule.startMinutes, rule.endMinutes)} 即将开始",
                    )
                }
            }
            val ended = todayRules.any { minuteOfDay >= it.endMinutes && minuteOfDay >= evening * 60 }
            if (ended && today.toString() !in course.recordedAttendanceDates) {
                notices += ReminderNotice(
                    key = "unchecked:${course.id}:$today",
                    title = "今天还没打卡",
                    body = "${course.name} 今天的课已经结束，还没有打卡",
                )
            }
            val metrics = course.metrics(defaultRenewalUnits)
            if (metrics.needsRenewal) {
                val remaining = metrics.remainingCreditUnits
                notices += ReminderNotice(
                    key = "renewal:${course.id}:${remaining ?: "unknown"}",
                    title = "建议续费",
                    body = if (remaining == null) {
                        "${course.name} 的剩余课时还不能确定，已到提醒线"
                    } else {
                        "${course.name} 还剩 ${CreditUnits.format(remaining)} 课时，低于提醒线"
                    },
                )
            }
        }
        val paused = courses.filter { it.status == CourseStatus.PAUSED }
        if (paused.isNotEmpty()) {
            notices += ReminderNotice(
                key = "paused:$today:${paused.joinToString(",") { it.id }}",
                title = "有课程已暂停",
                body = paused.joinToString("、") { it.name } + " 已暂停，不会再生成今天之后的课",
            )
        }
        return notices.distinctBy { it.key }
    }

    fun nextClass(courses: List<CourseDetails>, now: LocalDateTime, leadMinutes: Int): UpcomingClass? {
        val lead = leadMinutes.coerceIn(5, 240)
        var best: UpcomingClass? = null
        courses.filter { it.status == CourseStatus.ACTIVE && it.scheduleMode == ScheduleMode.WEEKLY }.forEach { course ->
            for (offset in 0..7) {
                val date = now.toLocalDate().plusDays(offset.toLong())
                course.rules.filter { it.dayOfWeek == date.dayOfWeek.value }.forEach { rule ->
                    val triggerMinute = rule.startMinutes - lead
                    val dayOffset = if (triggerMinute < 0) -1 else 0
                    val minute = if (triggerMinute < 0) triggerMinute + 24 * 60 else triggerMinute
                    val trigger = date.plusDays(dayOffset.toLong()).atTime(minute / 60, minute % 60)
                    if (trigger.isAfter(now) && (best == null || trigger.isBefore(best!!.triggerAt))) {
                        best = UpcomingClass(course.id, course.name, trigger)
                    }
                }
            }
        }
        return best
    }
}
