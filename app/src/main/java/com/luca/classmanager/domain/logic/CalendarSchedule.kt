package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseEventInfo
import com.luca.classmanager.domain.model.CourseEventStatus
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.ScheduleMode
import java.time.LocalDate

enum class SessionVisual {
    PLANNED,
    CHECKED_IN,
    LEAVE,
    CANCELLED,
    RESCHEDULED,
    MAKEUP,
}

data class CalendarSession(
    val key: String,
    val courseId: String,
    val courseName: String,
    val iconKey: String,
    val colorHex: String,
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
    val visual: SessionVisual,
    val eventId: String?,
    val attendanceId: String?,
    val sourceRuleId: String?,
    val rescheduledFromEventId: String?,
    val rescheduledToEventId: String?,
    val note: String?,
    val deductCreditUnits: Int?,
    val occupiesTime: Boolean,
    val canCheckIn: Boolean,
)

data class Deduction(
    val sourceKey: String,
    val units: Int,
)

/**
 * Weekly rules are a template. Dated attendance is stored separately and is never
 * rewritten when the template changes. Leave, cancel, reschedule, and makeup are
 * course events that override one generated slot. Credits change only through attendance.
 */
object CalendarSchedule {
    fun generatesFromRule(status: CourseStatus, date: LocalDate, today: LocalDate): Boolean {
        return when (status) {
            CourseStatus.ACTIVE -> true
            CourseStatus.PAUSED -> date.isBefore(today)
            CourseStatus.ARCHIVED -> false
        }
    }

    fun assemble(
        courses: List<CourseDetails>,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): List<CalendarSession> {
        val events = courses.flatMap { course -> course.events.map { course to it } }
        val eventsByDate = events.groupBy { it.second.eventDate }
        val attendanceByDate = courses.flatMap { course ->
            course.attendances.map { course to it }
        }.groupBy { it.second.attendanceDate }
        val childByParent = events.mapNotNull { (_, event) ->
            event.rescheduledFromEventId?.let { parent -> parent to event.id }
        }.toMap()
        val result = mutableListOf<CalendarSession>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            result += sessionsOn(cursor, courses, eventsByDate, attendanceByDate, childByParent, today)
            cursor = cursor.plusDays(1)
        }
        return result.sortedWith(compareBy({ it.date }, { it.startMinutes }, { it.courseName }, { it.key }))
    }

    fun overlaps(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
        if (endA <= startA || endB <= startB) return false
        return startA < endB && startB < endA
    }

    fun conflictLines(sessions: List<CalendarSession>): List<String> {
        val occupying = sessions.filter { it.occupiesTime && it.endMinutes > it.startMinutes }
        val lines = mutableListOf<String>()
        for (index in occupying.indices) {
            for (other in index + 1 until occupying.size) {
                val left = occupying[index]
                val right = occupying[other]
                if (overlaps(left.startMinutes, left.endMinutes, right.startMinutes, right.endMinutes)) {
                    lines += "${left.courseName} ${formatMinuteRange(left.startMinutes, left.endMinutes)} 与 " +
                        "${right.courseName} ${formatMinuteRange(right.startMinutes, right.endMinutes)} 重叠"
                }
            }
        }
        return lines
    }

    fun conflicts(
        date: LocalDate,
        startMinutes: Int,
        endMinutes: Int,
        sessions: List<CalendarSession>,
        ignoreEventIds: Set<String> = emptySet(),
        ignoreRuleId: String? = null,
    ): List<CalendarSession> {
        return sessions.filter { session ->
            session.date == date &&
                session.occupiesTime &&
                session.eventId !in ignoreEventIds &&
                !(ignoreRuleId != null && session.eventId == null && session.sourceRuleId == ignoreRuleId) &&
                overlaps(startMinutes, endMinutes, session.startMinutes, session.endMinutes)
        }
    }

    private fun sessionsOn(
        date: LocalDate,
        courses: List<CourseDetails>,
        eventsByDate: Map<String, List<Pair<CourseDetails, CourseEventInfo>>>,
        attendanceByDate: Map<String, List<Pair<CourseDetails, AttendanceInfo>>>,
        childByParent: Map<String, String>,
        today: LocalDate,
    ): List<CalendarSession> {
        val dateKey = date.toString()
        val virtual = mutableListOf<CalendarSession>()
        for (course in courses) {
            if (course.scheduleMode != ScheduleMode.WEEKLY) continue
            if (!generatesFromRule(course.status, date, today)) continue
            for (rule in course.rules) {
                if (rule.dayOfWeek != date.dayOfWeek.value) continue
                virtual += baseSession(
                    key = "rule:${course.id}:${rule.id}:$dateKey",
                    course = course,
                    date = date,
                    startMinutes = rule.startMinutes,
                    endMinutes = rule.endMinutes,
                    visual = SessionVisual.PLANNED,
                    eventId = null,
                    sourceRuleId = rule.id,
                    rescheduledFromEventId = null,
                    rescheduledToEventId = null,
                    note = null,
                    deductCreditUnits = null,
                    occupiesTime = true,
                    canCheckIn = true,
                )
            }
        }
        val dayEvents = eventsByDate[dateKey].orEmpty()
        val suppressed = dayEvents.mapNotNull { (course, event) ->
            event.sourceRuleId?.let { course.id to it }
        }.toSet()
        val visibleVirtual = virtual.filter { (it.courseId to it.sourceRuleId) !in suppressed }
        val stored = dayEvents.map { (course, event) -> eventSession(course, event, date, childByParent) }
        val combined = (visibleVirtual + stored).toMutableList()
        val consumed = mutableSetOf<String>()
        for ((course, attendance) in attendanceByDate[dateKey].orEmpty()) {
            val index = combined.indexOfFirst { session ->
                session.courseId == course.id && session.attendanceId == null && (
                    (attendance.eventId != null && session.eventId == attendance.eventId) ||
                        (attendance.eventId == null && session.canCheckIn)
                    )
            }
            if (index >= 0) {
                val current = combined[index]
                val checkedIn = current.visual == SessionVisual.PLANNED ||
                    current.visual == SessionVisual.MAKEUP ||
                    (current.visual == SessionVisual.RESCHEDULED && current.occupiesTime)
                combined[index] = current.copy(
                    attendanceId = attendance.id,
                    visual = if (checkedIn) SessionVisual.CHECKED_IN else current.visual,
                    canCheckIn = false,
                )
                consumed += attendance.id
            } else if (attendance.id !in consumed) {
                combined += baseSession(
                    key = "attendance:${attendance.id}",
                    course = course,
                    date = date,
                    startMinutes = 0,
                    endMinutes = 0,
                    visual = SessionVisual.CHECKED_IN,
                    eventId = attendance.eventId,
                    sourceRuleId = null,
                    rescheduledFromEventId = null,
                    rescheduledToEventId = null,
                    note = attendance.note,
                    deductCreditUnits = attendance.creditUnits,
                    occupiesTime = false,
                    canCheckIn = false,
                ).copy(attendanceId = attendance.id)
                consumed += attendance.id
            }
        }
        return combined
    }

    private fun eventSession(
        course: CourseDetails,
        event: CourseEventInfo,
        date: LocalDate,
        childByParent: Map<String, String>,
    ): CalendarSession {
        val status = event.status.toEventStatus()
        val movedHere = status == CourseEventStatus.PLANNED && event.rescheduledFromEventId != null
        val visual = when {
            movedHere -> SessionVisual.RESCHEDULED
            status == CourseEventStatus.LEAVE -> SessionVisual.LEAVE
            status == CourseEventStatus.CANCELLED -> SessionVisual.CANCELLED
            status == CourseEventStatus.RESCHEDULED -> SessionVisual.RESCHEDULED
            status == CourseEventStatus.MAKEUP -> SessionVisual.MAKEUP
            else -> SessionVisual.PLANNED
        }
        val occupies = visual == SessionVisual.PLANNED ||
            visual == SessionVisual.MAKEUP ||
            movedHere
        return baseSession(
            key = "event:${event.id}",
            course = course,
            date = date,
            startMinutes = event.startMinutes ?: 0,
            endMinutes = event.endMinutes ?: 0,
            visual = visual,
            eventId = event.id,
            sourceRuleId = event.sourceRuleId,
            rescheduledFromEventId = event.rescheduledFromEventId,
            rescheduledToEventId = childByParent[event.id],
            note = event.note,
            deductCreditUnits = event.deductCreditUnits,
            occupiesTime = occupies,
            canCheckIn = occupies,
        )
    }

    private fun baseSession(
        key: String,
        course: CourseDetails,
        date: LocalDate,
        startMinutes: Int,
        endMinutes: Int,
        visual: SessionVisual,
        eventId: String?,
        sourceRuleId: String?,
        rescheduledFromEventId: String?,
        rescheduledToEventId: String?,
        note: String?,
        deductCreditUnits: Int?,
        occupiesTime: Boolean,
        canCheckIn: Boolean,
    ) = CalendarSession(
        key = key,
        courseId = course.id,
        courseName = course.name,
        iconKey = course.iconKey,
        colorHex = course.colorHex,
        date = date,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        visual = visual,
        eventId = eventId,
        attendanceId = null,
        sourceRuleId = sourceRuleId,
        rescheduledFromEventId = rescheduledFromEventId,
        rescheduledToEventId = rescheduledToEventId,
        note = note,
        deductCreditUnits = deductCreditUnits,
        occupiesTime = occupiesTime,
        canCheckIn = canCheckIn,
    )
}

/**
 * Every credit change is one attendance row. Repeating check-in, leave-with-deduct,
 * or reschedule against the same lesson keeps the existing row instead of adding another.
 */
object SessionCredits {
    fun leave(existing: List<Deduction>, eventKey: String, deductUnits: Int?): List<Deduction> {
        if (deductUnits == null || deductUnits <= 0) return existing
        if (existing.any { it.sourceKey == eventKey }) return existing
        return existing + Deduction(eventKey, deductUnits)
    }

    fun checkIn(existing: List<Deduction>, eventKey: String, units: Int): List<Deduction> {
        if (existing.any { it.sourceKey == eventKey }) return existing
        return existing + Deduction(eventKey, units)
    }

    fun reschedule(existing: List<Deduction>, fromKey: String, toKey: String): List<Deduction> {
        return existing.map { row -> if (row.sourceKey == fromKey) row.copy(sourceKey = toKey) else row }
    }

    fun rescheduleThenCheckIn(
        existing: List<Deduction>,
        fromKey: String,
        toKey: String,
        units: Int,
    ): List<Deduction> {
        return checkIn(reschedule(existing, fromKey, toKey), toKey, units)
    }

    fun total(rows: List<Deduction>): Int = rows.sumOf { it.units }
}

private fun String.toEventStatus(): CourseEventStatus {
    return CourseEventStatus.entries.firstOrNull { it.name == this } ?: CourseEventStatus.PLANNED
}
