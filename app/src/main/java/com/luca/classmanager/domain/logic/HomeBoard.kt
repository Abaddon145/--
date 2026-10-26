package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRule
import com.luca.classmanager.domain.model.SessionTimeStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class TodaySession(
    val courseId: String,
    val courseName: String,
    val iconKey: String,
    val colorHex: String,
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
    val timeStatus: SessionTimeStatus,
    val checkedIn: Boolean,
)

data class NextSession(
    val courseId: String,
    val courseName: String,
    val iconKey: String,
    val colorHex: String,
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
)

data class HomeBoard(
    val today: LocalDate,
    val todaySessionCount: Int,
    val archivedCount: Int,
    val fee: FeeRollup,
    val todaySessions: List<TodaySession>,
    val nextSession: NextSession?,
    val activeCourses: List<CourseDetails>,
    val pausedCourses: List<CourseDetails>,
)

object HomeBoardBuilder {
    fun build(
        courses: List<CourseDetails>,
        now: LocalDateTime,
        defaultRenewalUnits: Int = CreditUnits.DEFAULT_RENEWAL_UNITS,
        giftValuation: GiftValuation = GiftValuation.ZERO,
    ): HomeBoard {
        val active = courses.filter { it.status == CourseStatus.ACTIVE }.sortedBy { it.sortOrder }
        val paused = courses.filter { it.status == CourseStatus.PAUSED }.sortedBy { it.sortOrder }
        val billable = courses.filter { it.status != CourseStatus.ARCHIVED }
        val slots = active
            .filter { it.scheduleMode == ScheduleMode.WEEKLY }
            .flatMap { course -> course.rules.map { rule -> course to rule } }
        val today = now.toLocalDate()
        val todaySessions = slots
            .filter { (_, rule) -> rule.dayOfWeek == today.dayOfWeek.value }
            .sortedBy { (_, rule) -> rule.startMinutes }
            .map { (course, rule) ->
                TodaySession(
                    courseId = course.id,
                    courseName = course.name,
                    iconKey = course.iconKey,
                    colorHex = course.colorHex,
                    date = today,
                    startMinutes = rule.startMinutes,
                    endMinutes = rule.endMinutes,
                    timeStatus = SchedulePlanner.statusAt(now, today, rule.startMinutes, rule.endMinutes),
                    checkedIn = course.recordedAttendanceDates.contains(today.toString()),
                )
            }
        val next = SchedulePlanner.nextStartAfter(now, slots.map { it.toWeeklySlot() })
        val nextSession = next?.takeIf { it.date != today }?.let { occurrence ->
            active.firstOrNull { it.id == occurrence.courseId }?.let { course ->
                NextSession(
                    courseId = course.id,
                    courseName = course.name,
                    iconKey = course.iconKey,
                    colorHex = course.colorHex,
                    date = occurrence.date,
                    startMinutes = occurrence.startMinutes,
                    endMinutes = occurrence.endMinutes,
                )
            }
        }
        return HomeBoard(
            today = today,
            todaySessionCount = todaySessions.size,
            archivedCount = courses.count { it.status == CourseStatus.ARCHIVED },
            fee = LessonCalculator.rollup(billable.map { it.metrics(defaultRenewalUnits, giftValuation) }),
            todaySessions = todaySessions,
            nextSession = nextSession,
            activeCourses = active,
            pausedCourses = paused,
        )
    }
}

fun CourseDetails.metrics(
    defaultRenewalUnits: Int = CreditUnits.DEFAULT_RENEWAL_UNITS,
    giftValuation: GiftValuation = GiftValuation.ZERO,
): CourseMetrics = LessonCalculator.calculate(
    packages = packages.map { pkg ->
        PackagePricing(
            purchasedCreditUnits = pkg.purchasedCreditUnits,
            giftCreditUnits = pkg.giftCreditUnits,
            amountCents = pkg.amountCents,
            unitPriceCents = pkg.unitPriceCents,
            createdAtEpochMs = pkg.createdAtEpochMs,
        )
    },
    adjustmentCreditUnits = adjustmentCreditUnits,
    attendanceCreditUnits = attendanceCreditUnits,
    historicalUnrecordedUnits = historicalUnrecorded.sumOf { it.creditUnits },
    historicalUnrecordedCount = historicalUnrecorded.size,
    renewalThresholdCreditUnits = renewalThresholdCreditUnits ?: defaultRenewalUnits,
    giftValuation = giftValuation,
)

private fun Pair<CourseDetails, ScheduleRule>.toWeeklySlot(): WeeklySlot {
    val (course, rule) = this
    return WeeklySlot(
        courseId = course.id,
        dayOfWeek = rule.dayOfWeek,
        startMinutes = rule.startMinutes,
        endMinutes = rule.endMinutes,
    )
}
