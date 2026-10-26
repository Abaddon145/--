package com.luca.classmanager.domain

import com.luca.classmanager.domain.logic.CalendarSchedule
import com.luca.classmanager.domain.logic.SessionCredits
import com.luca.classmanager.domain.logic.SessionVisual
import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseEventInfo
import com.luca.classmanager.domain.model.CourseEventStatus
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRule
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3RulesTest {
    private val today = LocalDate.parse("2026-10-24")

    @Test
    fun changingWeeklyRulesDoesNotAlterPastAttendance() {
        val past = AttendanceInfo(
            id = "att-1",
            attendanceDate = "2026-10-21",
            creditUnits = 2,
            note = "已上",
            historicalSourceId = null,
            photoPath = null,
            createdAtEpochMs = 1,
        )
        val saturday = course(
            rules = listOf(rule("sat", 6, 16 * 60, 17 * 60 + 30)),
            attendances = listOf(past),
        )
        val monday = saturday.copy(rules = listOf(rule("mon", 1, 9 * 60, 10 * 60)))
        val before = CalendarSchedule.assemble(listOf(saturday), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), today)
        val after = CalendarSchedule.assemble(listOf(monday), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), today)
        assertEquals(listOf(2), saturday.attendances.map { it.creditUnits })
        assertEquals(listOf(2), monday.attendances.map { it.creditUnits })
        assertEquals(listOf("att-1"), before.mapNotNull { it.attendanceId }.distinct())
        assertEquals(listOf("att-1"), after.mapNotNull { it.attendanceId }.distinct())
        assertTrue(after.any { it.attendanceId == "att-1" && it.date == LocalDate.parse("2026-10-21") && it.visual == SessionVisual.CHECKED_IN })
        assertTrue(before.none { it.date == LocalDate.parse("2026-10-21") && it.sourceRuleId == "mon" })
    }

    @Test
    fun pauseStopsFuturePlansAndKeepsPastOnes() {
        val saturday = rule("sat", 6, 16 * 60, 17 * 60)
        val paused = course(status = CourseStatus.PAUSED, rules = listOf(saturday))
        val active = course(id = "active", status = CourseStatus.ACTIVE, rules = listOf(saturday, rule("wed", 3, 18 * 60, 19 * 60)))
        val sessions = CalendarSchedule.assemble(
            listOf(paused, active),
            LocalDate.parse("2026-10-17"),
            LocalDate.parse("2026-10-31"),
            today,
        )
        val pausedDays = sessions.filter { it.courseId == paused.id }.map { it.date }
        assertTrue(pausedDays.contains(LocalDate.parse("2026-10-17")))
        assertFalse(pausedDays.any { !it.isBefore(today) })
        val activeDays = sessions.filter { it.courseId == "active" }.map { it.date }
        assertTrue(activeDays.containsAll(listOf(
            LocalDate.parse("2026-10-17"),
            LocalDate.parse("2026-10-21"),
            LocalDate.parse("2026-10-24"),
            LocalDate.parse("2026-10-28"),
            LocalDate.parse("2026-10-31"),
        )))
    }

    @Test
    fun leaveWithoutDeductDoesNotAddCredits() {
        val none = SessionCredits.leave(emptyList(), "leave-1", deductUnits = null)
        assertTrue(none.isEmpty())
        assertEquals(0, SessionCredits.total(none))
        val deducted = SessionCredits.leave(emptyList(), "leave-1", deductUnits = 2)
        assertEquals(1, deducted.size)
        assertEquals(2, SessionCredits.total(deducted))
        val again = SessionCredits.checkIn(deducted, "leave-1", 2)
        assertEquals(1, again.size)
        assertEquals(2, SessionCredits.total(again))
    }

    @Test
    fun overlappingPlansAreReportedAndCancelledPlansAreNot() {
        val basketball = course(id = "ball", name = "篮球", color = "#E9A56B", rules = listOf(rule("sat", 6, 16 * 60, 17 * 60 + 30)))
        val shuttle = course(id = "shuttle", name = "羽毛球", color = "#7A9E8E", rules = listOf(rule("sat2", 6, 17 * 60, 18 * 60)))
        val day = LocalDate.parse("2026-10-17")
        val overlap = CalendarSchedule.assemble(listOf(basketball, shuttle), day, day, today)
        assertEquals(1, CalendarSchedule.conflictLines(overlap).size)
        val touching = shuttle.copy(rules = listOf(rule("sat2", 6, 17 * 60 + 30, 18 * 60)))
        assertTrue(CalendarSchedule.conflictLines(CalendarSchedule.assemble(listOf(basketball, touching), day, day, today)).isEmpty())
        val cancelled = shuttle.copy(
            rules = emptyList(),
            events = listOf(
                CourseEventInfo(
                    id = "cancel-1",
                    eventDate = day.toString(),
                    startMinutes = 16 * 60,
                    endMinutes = 17 * 60,
                    status = CourseEventStatus.CANCELLED.name,
                    sourceRuleId = null,
                    rescheduledFromEventId = null,
                    deductCreditUnits = null,
                    note = null,
                ),
            ),
        )
        val withCancel = CalendarSchedule.assemble(listOf(basketball, cancelled), day, day, today)
        assertTrue(CalendarSchedule.conflictLines(withCancel).isEmpty())
        assertTrue(withCancel.any { it.visual == SessionVisual.CANCELLED })
    }

    @Test
    fun rescheduleThenCheckInDoesNotDeductTwice() {
        val already = SessionCredits.checkIn(emptyList(), "slot", 2)
        val moved = SessionCredits.reschedule(already, "slot", "moved")
        assertEquals(listOf("moved"), moved.map { it.sourceKey })
        assertEquals(2, SessionCredits.total(moved))
        val checkedAgain = SessionCredits.rescheduleThenCheckIn(already, "slot", "moved", 2)
        assertEquals(1, checkedAgain.size)
        assertEquals(2, SessionCredits.total(checkedAgain))
        val fresh = SessionCredits.rescheduleThenCheckIn(emptyList(), "slot", "moved", 2)
        assertEquals(1, fresh.size)
        assertEquals(2, SessionCredits.total(fresh))
    }

    @Test
    fun leaveCancelAndMakeupStayDistinctFromTheWeeklyPlan() {
        val day = LocalDate.parse("2026-10-17")
        val course = course(
            rules = listOf(rule("sat", 6, 16 * 60, 17 * 60)),
            events = listOf(
                CourseEventInfo("leave", day.toString(), 16 * 60, 17 * 60, CourseEventStatus.LEAVE.name, "sat", null, null, "发烧"),
                CourseEventInfo("makeup", "2026-10-18", 10 * 60, 11 * 60, CourseEventStatus.MAKEUP.name, null, "leave", null, null),
            ),
        )
        val sessions = CalendarSchedule.assemble(listOf(course), day, day.plusDays(1), today)
        assertTrue(sessions.any { it.date == day && it.visual == SessionVisual.LEAVE && it.note == "发烧" })
        assertFalse(sessions.any { it.date == day && it.visual == SessionVisual.PLANNED })
        assertTrue(sessions.any { it.visual == SessionVisual.MAKEUP && it.rescheduledFromEventId == "leave" && it.canCheckIn })
    }

    private fun rule(id: String, day: Int, start: Int, end: Int) = ScheduleRule(id, day, start, end)

    private fun course(
        id: String = "course",
        name: String = "篮球",
        status: CourseStatus = CourseStatus.ACTIVE,
        color: String = "#E9A56B",
        rules: List<ScheduleRule> = emptyList(),
        attendances: List<AttendanceInfo> = emptyList(),
        events: List<CourseEventInfo> = emptyList(),
    ) = CourseDetails(
        id = id,
        name = name,
        iconKey = "🏀",
        colorHex = color,
        institution = null,
        location = null,
        notes = null,
        scheduleMode = ScheduleMode.WEEKLY,
        status = status,
        sortOrder = 0,
        rules = rules,
        packages = emptyList(),
        adjustmentCreditUnits = 0,
        attendanceCreditUnits = attendances.sumOf { it.creditUnits },
        recordedAttendanceDates = attendances.map { it.attendanceDate },
        historicalUnrecorded = emptyList(),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        attendances = attendances,
        events = events,
    )
}
