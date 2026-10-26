package com.luca.classmanager.data.repository

import com.luca.classmanager.data.local.dao.CourseWithChildren
import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseEventInfo
import com.luca.classmanager.domain.model.CoursePackageInfo
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.CreditAdjustmentInfo
import com.luca.classmanager.domain.model.HistoricalCredit
import com.luca.classmanager.domain.model.PaymentInfo
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRule

internal fun CourseWithChildren.toDetails(
    photoByAttendanceId: Map<String, String> = emptyMap(),
): CourseDetails {
    val openHistorical = historicalAttendance.filter { it.convertedAttendanceId == null }
    return CourseDetails(
        id = course.id,
        name = course.name,
        iconKey = course.iconKey,
        colorHex = course.colorHex,
        institution = course.institution,
        location = course.location,
        notes = course.notes,
        scheduleMode = course.scheduleMode.toScheduleMode(),
        status = course.status.toCourseStatus(),
        sortOrder = course.sortOrder,
        rules = rules
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startMinutes }))
            .map { rule ->
                ScheduleRule(
                    id = rule.id,
                    dayOfWeek = rule.dayOfWeek,
                    startMinutes = rule.startMinutes,
                    endMinutes = rule.endMinutes,
                )
            },
        packages = packages
            .sortedBy { it.createdAtEpochMs }
            .map { pkg ->
                CoursePackageInfo(
                    id = pkg.id,
                    purchasedCreditUnits = pkg.purchasedCreditUnits,
                    giftCreditUnits = pkg.giftCreditUnits,
                    amountCents = pkg.amountCents,
                    unitPriceCents = pkg.unitPriceCents,
                    paidOn = pkg.paidOn,
                    note = pkg.note,
                    createdAtEpochMs = pkg.createdAtEpochMs,
                )
            },
        adjustmentCreditUnits = adjustments.sumOf { it.deltaCreditUnits },
        attendanceCreditUnits = attendance.sumOf { it.creditUnits },
        recordedAttendanceDates = attendance.map { it.attendanceDate },
        historicalUnrecorded = openHistorical.map { row ->
            HistoricalCredit(id = row.id, creditUnits = row.creditUnits)
        },
        createdAtEpochMs = course.createdAtEpochMs,
        updatedAtEpochMs = course.updatedAtEpochMs,
        renewalThresholdCreditUnits = course.renewalThresholdCreditUnits,
        adjustments = adjustments
            .sortedByDescending { it.createdAtEpochMs }
            .map { row ->
                CreditAdjustmentInfo(
                    id = row.id,
                    deltaCreditUnits = row.deltaCreditUnits,
                    reason = row.reason,
                    createdAtEpochMs = row.createdAtEpochMs,
                )
            },
        payments = payments
            .sortedWith(compareBy<com.luca.classmanager.data.local.entity.PaymentEntity> { it.paidOn ?: "" }.thenBy { it.createdAtEpochMs })
            .map { row ->
                PaymentInfo(
                    id = row.id,
                    packageId = row.packageId,
                    amountCents = row.amountCents,
                    paidOn = row.paidOn,
                    note = row.note,
                    createdAtEpochMs = row.createdAtEpochMs,
                )
            },
        attendances = attendance
            .sortedWith(compareByDescending<com.luca.classmanager.data.local.entity.AttendanceEntity> { it.attendanceDate }.thenByDescending { it.createdAtEpochMs })
            .map { row ->
                AttendanceInfo(
                    id = row.id,
                    attendanceDate = row.attendanceDate,
                    creditUnits = row.creditUnits,
                    note = row.note,
                    historicalSourceId = row.historicalSourceId,
                    photoPath = photoByAttendanceId[row.id],
                    createdAtEpochMs = row.createdAtEpochMs,
                    eventId = row.eventId,
                )
            },
        events = events
            .sortedWith(compareBy({ it.eventDate }, { it.startMinutes ?: 0 }))
            .map { row ->
                CourseEventInfo(
                    id = row.id,
                    eventDate = row.eventDate,
                    startMinutes = row.startMinutes,
                    endMinutes = row.endMinutes,
                    status = row.status,
                    sourceRuleId = row.sourceRuleId,
                    rescheduledFromEventId = row.rescheduledFromEventId,
                    deductCreditUnits = row.deductCreditUnits,
                    note = row.note,
                )
            },
    )
}

private fun String.toScheduleMode(): ScheduleMode {
    return ScheduleMode.entries.firstOrNull { it.name == this } ?: ScheduleMode.WEEKLY
}

private fun String.toCourseStatus(): CourseStatus {
    return CourseStatus.entries.firstOrNull { it.name == this } ?: CourseStatus.ACTIVE
}
