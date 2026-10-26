package com.luca.classmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 1 schema includes the later-phase tables from the product spec so upgrades
 * can add columns with Migrations instead of rebuilding the database.
 *
 * Money is integer cents. Lesson credits are integer half-units (1 unit = 0.5 lesson).
 * Null money or purchased credits means unknown, not zero.
 * [HistoricalAttendanceEntity] intentionally has no date column.
 */

private const val COURSE_ID = "courseId"

@Entity(
    tableName = "courses",
    indices = [Index("status"), Index("sortOrder")],
)
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val institution: String?,
    val location: String?,
    val notes: String?,
    val scheduleMode: String,
    val status: String,
    val statusBeforeArchive: String?,
    val sortOrder: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Null uses the global setting. Half-units. Remaining below this value suggests renewal. */
    val renewalThresholdCreditUnits: Int? = null,
)

@Entity(
    tableName = "course_schedule_rules",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID)],
)
data class CourseScheduleRuleEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "course_events",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID), Index("eventDate")],
)
data class CourseEventEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val eventDate: String,
    val startMinutes: Int?,
    val endMinutes: Int?,
    val status: String,
    val sourceRuleId: String?,
    val rescheduledFromEventId: String?,
    val deductCreditUnits: Int?,
    val note: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "attendance",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID), Index(value = [COURSE_ID, "attendanceDate"])],
)
data class AttendanceEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val eventId: String?,
    val attendanceDate: String,
    val creditUnits: Int,
    val note: String?,
    val historicalSourceId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "course_packages",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID)],
)
data class CoursePackageEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val purchasedCreditUnits: Int?,
    val giftCreditUnits: Int,
    val amountCents: Long?,
    /** Price of one full lesson (2 credit units), in cents. Null means unknown. */
    val unitPriceCents: Long?,
    val paidOn: String?,
    val note: String?,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID), Index("packageId")],
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val packageId: String?,
    val amountCents: Long?,
    val paidOn: String?,
    val note: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "credit_adjustments",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID)],
)
data class CreditAdjustmentEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val deltaCreditUnits: Int,
    val reason: String?,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "historical_attendance",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID)],
)
data class HistoricalAttendanceEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val creditUnits: Int,
    val note: String?,
    val convertedAttendanceId: String?,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = [COURSE_ID],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(COURSE_ID)],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val courseId: String?,
    val type: String,
    val enabled: Boolean,
    val leadMinutes: Int?,
    val thresholdCreditUnits: Int?,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["ownerType", "ownerId"])],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val ownerType: String,
    val ownerId: String,
    val uri: String,
    val mimeType: String?,
    val createdAtEpochMs: Long,
)
