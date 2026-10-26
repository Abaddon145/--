package com.luca.classmanager.domain.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class ScheduleMode {
    WEEKLY,
    FLEXIBLE,
    TEMPORARY,
}

enum class CourseStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED,
}

enum class CourseFilter {
    ALL,
    ACTIVE,
    PAUSED,
    ARCHIVED,
}

enum class SessionTimeStatus {
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED,
}

enum class CourseEventStatus {
    PLANNED,
    LEAVE,
    CANCELLED,
    RESCHEDULED,
    MAKEUP,
}

data class ScheduleRule(
    val id: String,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
)

data class ScheduleRuleDraft(
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
)

data class CoursePackageInfo(
    val id: String,
    val purchasedCreditUnits: Int?,
    val giftCreditUnits: Int,
    val amountCents: Long?,
    val unitPriceCents: Long?,
    val paidOn: String?,
    val note: String?,
    val createdAtEpochMs: Long,
)

data class HistoricalCredit(
    val id: String,
    val creditUnits: Int,
)

data class CourseDetails(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val institution: String?,
    val location: String?,
    val notes: String?,
    val scheduleMode: ScheduleMode,
    val status: CourseStatus,
    val sortOrder: Int,
    val rules: List<ScheduleRule>,
    val packages: List<CoursePackageInfo>,
    val adjustmentCreditUnits: Int,
    val attendanceCreditUnits: Int,
    val recordedAttendanceDates: List<String>,
    val historicalUnrecorded: List<HistoricalCredit>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val renewalThresholdCreditUnits: Int? = null,
    val adjustments: List<CreditAdjustmentInfo> = emptyList(),
    val payments: List<PaymentInfo> = emptyList(),
    val attendances: List<AttendanceInfo> = emptyList(),
    val events: List<CourseEventInfo> = emptyList(),
)

data class CourseEventInfo(
    val id: String,
    val eventDate: String,
    val startMinutes: Int?,
    val endMinutes: Int?,
    val status: String,
    val sourceRuleId: String?,
    val rescheduledFromEventId: String?,
    val deductCreditUnits: Int?,
    val note: String?,
)

data class PackageDraft(
    val purchasedCreditUnits: Int?,
    val giftCreditUnits: Int,
    val amountCents: Long?,
    val unitPriceCents: Long?,
    val paidOn: String?,
    val note: String?,
)

data class CreditAdjustmentInfo(
    val id: String,
    val deltaCreditUnits: Int,
    val reason: String?,
    val createdAtEpochMs: Long,
)

data class PaymentInfo(
    val id: String,
    val packageId: String?,
    val amountCents: Long?,
    val paidOn: String?,
    val note: String?,
    val createdAtEpochMs: Long,
)

data class PaymentDraft(
    val amountCents: Long?,
    val paidOn: String?,
    val note: String?,
)

sealed interface PaymentWriteResult {
    data object Saved : PaymentWriteResult
    data object Missing : PaymentWriteResult
    data object InvalidDate : PaymentWriteResult
    data object LinkedToPackage : PaymentWriteResult
}

data class AttendanceInfo(
    val id: String,
    val attendanceDate: String,
    val creditUnits: Int,
    val note: String?,
    val historicalSourceId: String?,
    val photoPath: String?,
    val createdAtEpochMs: Long,
    val eventId: String? = null,
)

data class CheckInRequest(
    val courseId: String,
    val date: String,
    val creditUnits: Int,
    val note: String?,
    val photoPath: String?,
    val confirmDuplicate: Boolean,
    val historicalId: String? = null,
    val eventId: String? = null,
)

data class SessionDraft(
    val courseId: String,
    val date: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val sourceRuleId: String?,
    val note: String?,
    val confirmConflict: Boolean,
    val existingEventId: String? = null,
)

sealed interface SessionWriteResult {
    data class Saved(val eventId: String, val message: String) : SessionWriteResult
    data class NeedsConflictConfirm(val conflicts: List<String>) : SessionWriteResult
    data object Missing : SessionWriteResult
    data object InvalidTime : SessionWriteResult
}

data class AttendanceEdit(
    val attendanceId: String,
    val date: String,
    val creditUnits: Int,
    val note: String?,
    val photoPath: String?,
    val clearPhoto: Boolean,
    val confirmDuplicate: Boolean,
)

sealed interface CheckInOutcome {
    data class Saved(val attendanceId: String) : CheckInOutcome
    data object NeedsDuplicateConfirm : CheckInOutcome
    data object CourseMissing : CheckInOutcome
    data object AttendanceMissing : CheckInOutcome
    data object InvalidCredit : CheckInOutcome
    data object HistoricalAlreadyUsed : CheckInOutcome
    data object AlreadyDeducted : CheckInOutcome
}

data class CourseDraft(
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val institution: String?,
    val location: String?,
    val notes: String?,
    val scheduleMode: ScheduleMode,
    val rules: List<ScheduleRuleDraft>,
)

data class DeletionImpact(
    val courseId: String,
    val courseName: String,
    val archived: Boolean,
    val scheduleRuleCount: Int,
    val packageCount: Int,
    val historicalAttendanceCount: Int,
    val attendanceCount: Int,
    val paymentCount: Int,
    val adjustmentCount: Int,
    val eventCount: Int,
    val reminderCount: Int,
    val attachmentCount: Int,
)

fun CourseDetails.matches(filter: CourseFilter): Boolean = when (filter) {
    CourseFilter.ALL -> true
    CourseFilter.ACTIVE -> status == CourseStatus.ACTIVE
    CourseFilter.PAUSED -> status == CourseStatus.PAUSED
    CourseFilter.ARCHIVED -> status == CourseStatus.ARCHIVED
}
