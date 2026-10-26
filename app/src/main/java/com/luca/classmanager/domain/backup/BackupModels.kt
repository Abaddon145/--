package com.luca.classmanager.domain.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupSettings(
    val themeMode: String,
    val renewalThresholdCreditUnits: Int,
    val giftValuation: String,
    val remindersEnabled: Boolean,
    val leadMinutes: Int,
    val uncheckedHour: Int,
)

@Serializable
data class BackupCourse(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val institution: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val scheduleMode: String,
    val status: String,
    val statusBeforeArchive: String? = null,
    val sortOrder: Int,
    val renewalThresholdCreditUnits: Int? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class BackupRule(
    val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val createdAtEpochMs: Long,
)

@Serializable
data class BackupEvent(
    val id: String,
    val courseId: String,
    val eventDate: String,
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
    val status: String,
    val sourceRuleId: String? = null,
    val rescheduledFromEventId: String? = null,
    val deductCreditUnits: Int? = null,
    val note: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class BackupAttendance(
    val id: String,
    val courseId: String,
    val eventId: String? = null,
    val attendanceDate: String,
    val creditUnits: Int,
    val note: String? = null,
    val historicalSourceId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class BackupPackage(
    val id: String,
    val courseId: String,
    val purchasedCreditUnits: Int? = null,
    val giftCreditUnits: Int,
    val amountCents: Long? = null,
    val unitPriceCents: Long? = null,
    val paidOn: String? = null,
    val note: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class BackupPayment(
    val id: String,
    val courseId: String,
    val packageId: String? = null,
    val amountCents: Long? = null,
    val paidOn: String? = null,
    val note: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class BackupAdjustment(
    val id: String,
    val courseId: String,
    val deltaCreditUnits: Int,
    val reason: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class BackupHistorical(
    val id: String,
    val courseId: String,
    val creditUnits: Int,
    val note: String? = null,
    val convertedAttendanceId: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class BackupReminder(
    val id: String,
    val courseId: String? = null,
    val type: String,
    val enabled: Boolean,
    val leadMinutes: Int? = null,
    val thresholdCreditUnits: Int? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class BackupAttachment(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class BackupFile(
    val fileName: String,
    val base64: String,
)

@Serializable
data class BackupBundle(
    val schemaVersion: Int,
    val settings: BackupSettings,
    val courses: List<BackupCourse> = emptyList(),
    val rules: List<BackupRule> = emptyList(),
    val events: List<BackupEvent> = emptyList(),
    val attendance: List<BackupAttendance> = emptyList(),
    val packages: List<BackupPackage> = emptyList(),
    val payments: List<BackupPayment> = emptyList(),
    val adjustments: List<BackupAdjustment> = emptyList(),
    val historical: List<BackupHistorical> = emptyList(),
    val reminders: List<BackupReminder> = emptyList(),
    val attachments: List<BackupAttachment> = emptyList(),
    val files: List<BackupFile> = emptyList(),
)

sealed interface BackupDecode {
    data class Ready(val bundle: BackupBundle) : BackupDecode
    data class Rejected(val message: String) : BackupDecode
}
