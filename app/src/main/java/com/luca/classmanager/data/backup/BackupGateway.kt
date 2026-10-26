package com.luca.classmanager.data.backup

import androidx.room.withTransaction
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.data.local.LucaDatabase
import com.luca.classmanager.data.local.entity.AttachmentEntity
import com.luca.classmanager.data.local.entity.AttendanceEntity
import com.luca.classmanager.data.local.entity.CourseEntity
import com.luca.classmanager.data.local.entity.CourseEventEntity
import com.luca.classmanager.data.local.entity.CoursePackageEntity
import com.luca.classmanager.data.local.entity.CourseScheduleRuleEntity
import com.luca.classmanager.data.local.entity.CreditAdjustmentEntity
import com.luca.classmanager.data.local.entity.HistoricalAttendanceEntity
import com.luca.classmanager.data.local.entity.PaymentEntity
import com.luca.classmanager.data.local.entity.ReminderEntity
import com.luca.classmanager.domain.backup.BackupAdjustment
import com.luca.classmanager.domain.backup.BackupAttachment
import com.luca.classmanager.domain.backup.BackupAttendance
import com.luca.classmanager.domain.backup.BackupBundle
import com.luca.classmanager.domain.backup.BackupCodec
import com.luca.classmanager.domain.backup.BackupCourse
import com.luca.classmanager.domain.backup.BackupDecode
import com.luca.classmanager.domain.backup.BackupEvent
import com.luca.classmanager.domain.backup.BackupFile
import com.luca.classmanager.domain.backup.BackupHistorical
import com.luca.classmanager.domain.backup.BackupPackage
import com.luca.classmanager.domain.backup.BackupPayment
import com.luca.classmanager.domain.backup.BackupReminder
import com.luca.classmanager.domain.backup.BackupRule
import com.luca.classmanager.domain.backup.BackupSettings
import com.luca.classmanager.domain.model.ThemeMode
import com.luca.classmanager.domain.repository.SettingsRepository
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first

class BackupGateway(
    private val database: LucaDatabase,
    private val attachments: AttachmentStore,
    private val settings: SettingsRepository,
) {
    suspend fun export(): String {
        val bundle = readBundle()
        return BackupCodec.encode(bundle)
    }

    fun inspect(text: String): BackupDecode = BackupCodec.decode(text)

    suspend fun restore(text: String): BackupDecode {
        val decoded = BackupCodec.decode(text)
        if (decoded !is BackupDecode.Ready) return decoded
        val previousFiles = database.attachmentDao().listAll().map { it.uri }
        val written = mutableListOf<String>()
        return try {
            val filesByName = decoded.bundle.files.associate { file ->
                file.fileName to Base64.getDecoder().decode(file.base64)
            }
            val attachmentRows = decoded.bundle.attachments.map { row ->
                val bytes = row.fileName?.let { filesByName[it] }
                val path = if (row.fileName != null && bytes != null) {
                    attachments.writeNamed(row.fileName, bytes).also { written += it }
                } else {
                    ""
                }
                row to path
            }
            database.withTransaction {
                clearTables()
                insertBundle(decoded.bundle, attachmentRows)
            }
            previousFiles.filter { it.isNotBlank() && it !in written }.forEach { attachments.delete(it) }
            writeSettings(decoded.bundle.settings)
            decoded
        } catch (_: Exception) {
            written.filter { it !in previousFiles }.forEach { attachments.delete(it) }
            BackupDecode.Rejected("恢复没有完成，原来的数据还在")
        }
    }

    private suspend fun readBundle(): BackupBundle {
        val attachmentRows = database.attachmentDao().listAll()
        val files = mutableListOf<BackupFile>()
        val attachmentsOut = attachmentRows.map { row ->
            val bytes = attachments.read(row.uri)
            val name = if (bytes != null) File(row.uri).name else null
            if (bytes != null && name != null) {
                files += BackupFile(name, Base64.getEncoder().encodeToString(bytes))
            }
            BackupAttachment(row.id, row.ownerType, row.ownerId, name, row.mimeType, row.createdAtEpochMs)
        }
        return BackupBundle(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            settings = BackupSettings(
                themeMode = settings.themeMode.first().name,
                renewalThresholdCreditUnits = settings.renewalThresholdCreditUnits.first(),
                giftValuation = settings.giftValuation.first().name,
                remindersEnabled = settings.remindersEnabled.first(),
                leadMinutes = settings.reminderLeadMinutes.first(),
                uncheckedHour = settings.uncheckedReminderHour.first(),
            ),
            courses = database.courseDao().listAll().map { row ->
                BackupCourse(
                    row.id, row.name, row.iconKey, row.colorHex, row.institution, row.location, row.notes,
                    row.scheduleMode, row.status, row.statusBeforeArchive, row.sortOrder,
                    row.renewalThresholdCreditUnits, row.createdAtEpochMs, row.updatedAtEpochMs,
                )
            },
            rules = database.scheduleRuleDao().listAll().map { row ->
                BackupRule(row.id, row.courseId, row.dayOfWeek, row.startMinutes, row.endMinutes, row.createdAtEpochMs)
            },
            events = database.courseEventDao().listAll().map { row ->
                BackupEvent(
                    row.id, row.courseId, row.eventDate, row.startMinutes, row.endMinutes, row.status,
                    row.sourceRuleId, row.rescheduledFromEventId, row.deductCreditUnits, row.note,
                    row.createdAtEpochMs, row.updatedAtEpochMs,
                )
            },
            attendance = database.attendanceDao().listAll().map { row ->
                BackupAttendance(
                    row.id, row.courseId, row.eventId, row.attendanceDate, row.creditUnits, row.note,
                    row.historicalSourceId, row.createdAtEpochMs, row.updatedAtEpochMs,
                )
            },
            packages = database.coursePackageDao().listAll().map { row ->
                BackupPackage(
                    row.id, row.courseId, row.purchasedCreditUnits, row.giftCreditUnits, row.amountCents,
                    row.unitPriceCents, row.paidOn, row.note, row.createdAtEpochMs,
                )
            },
            payments = database.paymentDao().listAll().map { row ->
                BackupPayment(
                    row.id, row.courseId, row.packageId, row.amountCents, row.paidOn, row.note,
                    row.createdAtEpochMs, row.updatedAtEpochMs,
                )
            },
            adjustments = database.creditAdjustmentDao().listAll().map { row ->
                BackupAdjustment(row.id, row.courseId, row.deltaCreditUnits, row.reason, row.createdAtEpochMs)
            },
            historical = database.historicalAttendanceDao().listAll().map { row ->
                BackupHistorical(row.id, row.courseId, row.creditUnits, row.note, row.convertedAttendanceId, row.createdAtEpochMs)
            },
            reminders = database.reminderDao().listAll().map { row ->
                BackupReminder(
                    row.id, row.courseId, row.type, row.enabled, row.leadMinutes,
                    row.thresholdCreditUnits, row.createdAtEpochMs,
                )
            },
            attachments = attachmentsOut,
            files = files,
        )
    }

    private suspend fun clearTables() {
        database.attachmentDao().deleteAll()
        database.reminderDao().deleteAll()
        database.creditAdjustmentDao().deleteAll()
        database.paymentDao().deleteAll()
        database.historicalAttendanceDao().deleteAll()
        database.attendanceDao().deleteAll()
        database.courseEventDao().deleteAll()
        database.coursePackageDao().deleteAll()
        database.scheduleRuleDao().deleteAll()
        database.courseDao().deleteAll()
    }

    private suspend fun insertBundle(bundle: BackupBundle, attachmentRows: List<Pair<BackupAttachment, String>>) {
        bundle.courses.forEach { row ->
            database.courseDao().insert(
                CourseEntity(
                    id = row.id,
                    name = row.name,
                    iconKey = row.iconKey,
                    colorHex = row.colorHex,
                    institution = row.institution,
                    location = row.location,
                    notes = row.notes,
                    scheduleMode = row.scheduleMode,
                    status = row.status,
                    statusBeforeArchive = row.statusBeforeArchive,
                    sortOrder = row.sortOrder,
                    createdAtEpochMs = row.createdAtEpochMs,
                    updatedAtEpochMs = row.updatedAtEpochMs,
                    renewalThresholdCreditUnits = row.renewalThresholdCreditUnits,
                ),
            )
        }
        bundle.rules.forEach { row ->
            database.scheduleRuleDao().insert(
                CourseScheduleRuleEntity(row.id, row.courseId, row.dayOfWeek, row.startMinutes, row.endMinutes, row.createdAtEpochMs),
            )
        }
        bundle.packages.forEach { row ->
            database.coursePackageDao().insert(
                CoursePackageEntity(
                    row.id, row.courseId, row.purchasedCreditUnits, row.giftCreditUnits, row.amountCents,
                    row.unitPriceCents, row.paidOn, row.note, row.createdAtEpochMs,
                ),
            )
        }
        bundle.events.forEach { row ->
            database.courseEventDao().insert(
                CourseEventEntity(
                    row.id, row.courseId, row.eventDate, row.startMinutes, row.endMinutes, row.status,
                    row.sourceRuleId, row.rescheduledFromEventId, row.deductCreditUnits, row.note,
                    row.createdAtEpochMs, row.updatedAtEpochMs,
                ),
            )
        }
        bundle.historical.forEach { row ->
            database.historicalAttendanceDao().insert(
                HistoricalAttendanceEntity(row.id, row.courseId, row.creditUnits, row.note, row.convertedAttendanceId, row.createdAtEpochMs),
            )
        }
        bundle.attendance.forEach { row ->
            database.attendanceDao().insert(
                AttendanceEntity(
                    row.id, row.courseId, row.eventId, row.attendanceDate, row.creditUnits, row.note,
                    row.historicalSourceId, row.createdAtEpochMs, row.updatedAtEpochMs,
                ),
            )
        }
        bundle.payments.forEach { row ->
            database.paymentDao().insert(
                PaymentEntity(
                    row.id, row.courseId, row.packageId, row.amountCents, row.paidOn, row.note,
                    row.createdAtEpochMs, row.updatedAtEpochMs,
                ),
            )
        }
        bundle.adjustments.forEach { row ->
            database.creditAdjustmentDao().insert(
                CreditAdjustmentEntity(row.id, row.courseId, row.deltaCreditUnits, row.reason, row.createdAtEpochMs),
            )
        }
        bundle.reminders.forEach { row ->
            database.reminderDao().insert(
                ReminderEntity(
                    row.id, row.courseId, row.type, row.enabled, row.leadMinutes,
                    row.thresholdCreditUnits, row.createdAtEpochMs,
                ),
            )
        }
        attachmentRows.forEach { (row, path) ->
            if (path.isBlank()) return@forEach
            database.attachmentDao().insert(
                AttachmentEntity(row.id, row.ownerType, row.ownerId, path, row.mimeType, row.createdAtEpochMs),
            )
        }
    }

    private suspend fun writeSettings(snapshot: BackupSettings) {
        val theme = runCatching { ThemeMode.valueOf(snapshot.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
        settings.setThemeMode(theme)
        settings.setRenewalThresholdCreditUnits(snapshot.renewalThresholdCreditUnits)
        val gift = runCatching {
            com.luca.classmanager.domain.logic.GiftValuation.valueOf(snapshot.giftValuation)
        }.getOrDefault(com.luca.classmanager.domain.logic.GiftValuation.ZERO)
        settings.setGiftValuation(gift)
        settings.setRemindersEnabled(snapshot.remindersEnabled)
        settings.setReminderLeadMinutes(snapshot.leadMinutes)
        settings.setUncheckedReminderHour(snapshot.uncheckedHour)
    }
}
