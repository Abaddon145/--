package com.luca.classmanager.domain.repository

import com.luca.classmanager.domain.model.AttendanceEdit
import com.luca.classmanager.domain.model.CheckInOutcome
import com.luca.classmanager.domain.model.CheckInRequest
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseDraft
import com.luca.classmanager.domain.model.CourseFilter
import com.luca.classmanager.domain.model.DeletionImpact
import com.luca.classmanager.domain.backup.BackupDecode
import com.luca.classmanager.domain.logic.GiftValuation
import com.luca.classmanager.domain.model.PackageDraft
import com.luca.classmanager.domain.model.PaymentDraft
import com.luca.classmanager.domain.model.PaymentWriteResult
import com.luca.classmanager.domain.model.SessionDraft
import com.luca.classmanager.domain.model.SessionWriteResult
import com.luca.classmanager.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    val renewalThresholdCreditUnits: Flow<Int>
    val giftValuation: Flow<GiftValuation>
    val remindersEnabled: Flow<Boolean>
    val reminderLeadMinutes: Flow<Int>
    val uncheckedReminderHour: Flow<Int>
    suspend fun isSeedCompleted(): Boolean
    suspend fun setSeedCompleted()
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setRenewalThresholdCreditUnits(units: Int)
    suspend fun setGiftValuation(mode: GiftValuation)
    suspend fun setRemindersEnabled(enabled: Boolean)
    suspend fun setReminderLeadMinutes(minutes: Int)
    suspend fun setUncheckedReminderHour(hour: Int)
    suspend fun firedReminderKeys(): Set<String>
    suspend fun rememberReminderKeys(keys: Set<String>)
}

interface CourseRepository {
    fun observeCourses(): Flow<List<CourseDetails>>
    suspend fun getCourse(id: String): CourseDetails?
    suspend fun createCourse(draft: CourseDraft): String
    suspend fun updateCourse(id: String, draft: CourseDraft)
    suspend fun archive(id: String)
    suspend fun restore(id: String)
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun move(id: String, direction: Int, filter: CourseFilter)
    suspend fun deletionImpact(id: String): DeletionImpact?
    suspend fun deletePermanently(id: String)
    suspend fun savePackage(courseId: String, packageId: String?, draft: PackageDraft): String?
    suspend fun deletePackage(packageId: String)
    suspend fun savePayment(courseId: String, paymentId: String?, draft: PaymentDraft): PaymentWriteResult
    suspend fun deletePayment(paymentId: String): PaymentWriteResult
    suspend fun addAdjustment(courseId: String, deltaCreditUnits: Int, reason: String?): String?
    suspend fun deleteAdjustment(adjustmentId: String)
    suspend fun setRenewalThreshold(courseId: String, units: Int?)
    suspend fun checkIn(request: CheckInRequest): CheckInOutcome
    suspend fun updateAttendance(edit: AttendanceEdit): CheckInOutcome
    suspend fun deleteAttendance(attendanceId: String): Boolean
    suspend fun backfillHistorical(courseId: String, startDate: String): Int
    suspend fun ensurePlannedEvent(draft: SessionDraft): String?
    suspend fun addOneOff(draft: SessionDraft): SessionWriteResult
    suspend fun markLeave(draft: SessionDraft, deductCreditUnits: Int?): SessionWriteResult
    suspend fun cancelSession(draft: SessionDraft): SessionWriteResult
    suspend fun rescheduleSession(draft: SessionDraft, newDate: String, newStart: Int, newEnd: Int): SessionWriteResult
    suspend fun addMakeup(draft: SessionDraft, fromEventId: String?): SessionWriteResult
    suspend fun clearException(eventId: String): Boolean
    suspend fun undoReschedule(originalEventId: String): Boolean
    suspend fun exportBackup(): String
    suspend fun inspectBackup(text: String): BackupDecode
    suspend fun restoreBackup(text: String): BackupDecode
}
