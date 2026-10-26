package com.luca.classmanager.data.repository

import androidx.room.withTransaction
import com.luca.classmanager.data.backup.BackupGateway
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.data.local.LucaDatabase
import com.luca.classmanager.data.local.entity.AttachmentEntity
import com.luca.classmanager.data.local.entity.AttendanceEntity
import com.luca.classmanager.data.local.entity.CourseEventEntity
import com.luca.classmanager.data.local.entity.CourseEntity
import com.luca.classmanager.data.local.entity.CoursePackageEntity
import com.luca.classmanager.data.local.entity.CourseScheduleRuleEntity
import com.luca.classmanager.data.local.entity.CreditAdjustmentEntity
import com.luca.classmanager.data.local.entity.HistoricalAttendanceEntity
import com.luca.classmanager.data.local.entity.PaymentEntity
import com.luca.classmanager.domain.logic.AttendancePolicy
import com.luca.classmanager.domain.logic.BackfillPlanner
import com.luca.classmanager.domain.logic.CalendarSchedule
import com.luca.classmanager.domain.logic.LessonCalculator
import com.luca.classmanager.domain.logic.formatMinuteRange
import com.luca.classmanager.domain.logic.SeedCatalog
import com.luca.classmanager.domain.logic.SeedPolicy
import com.luca.classmanager.domain.model.AttendanceEdit
import com.luca.classmanager.domain.model.CheckInOutcome
import com.luca.classmanager.domain.model.CheckInRequest
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseDraft
import com.luca.classmanager.domain.model.CourseFilter
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.DeletionImpact
import com.luca.classmanager.domain.model.PackageDraft
import com.luca.classmanager.domain.model.PaymentDraft
import com.luca.classmanager.domain.model.PaymentWriteResult
import com.luca.classmanager.domain.model.CourseEventStatus
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.SessionDraft
import com.luca.classmanager.domain.model.SessionWriteResult
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CourseRepositoryImpl(
    private val database: LucaDatabase,
    private val settings: SettingsRepository,
    private val attachments: AttachmentStore,
) : CourseRepository {
    private val courseDao = database.courseDao()
    private val ruleDao = database.scheduleRuleDao()
    private val packageDao = database.coursePackageDao()
    private val historicalDao = database.historicalAttendanceDao()
    private val attendanceDao = database.attendanceDao()
    private val paymentDao = database.paymentDao()
    private val adjustmentDao = database.creditAdjustmentDao()
    private val eventDao = database.courseEventDao()
    private val reminderDao = database.reminderDao()
    private val attachmentDao = database.attachmentDao()
    private val backups = BackupGateway(database, attachments, settings)
    private val seedMutex = Mutex()
    private var seedChecked = false

    override fun observeCourses(): Flow<List<CourseDetails>> = flow {
        seedIfNeeded()
        emitAll(
            combine(
                courseDao.observeAllWithChildren(),
                attachmentDao.observeByOwnerType(OWNER_ATTENDANCE),
            ) { rows, photos ->
                val photoByAttendance = photos.associate { it.ownerId to it.uri }
                rows.map { it.toDetails(photoByAttendance) }
            },
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun getCourse(id: String): CourseDetails? {
        seedIfNeeded()
        val row = courseDao.getWithChildren(id) ?: return null
        val photos = attachmentDao.listByOwnerType(OWNER_ATTENDANCE).associate { it.ownerId to it.uri }
        return row.toDetails(photos)
    }

    override suspend fun createCourse(draft: CourseDraft): String = database.withTransaction {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val sortOrder = (courseDao.listAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        courseDao.insert(draft.toEntity(id = id, sortOrder = sortOrder, now = now, existing = null))
        insertRules(id, draft, now)
        id
    }

    override suspend fun updateCourse(id: String, draft: CourseDraft) {
        database.withTransaction {
            val existing = courseDao.getById(id) ?: return@withTransaction
            val now = System.currentTimeMillis()
            courseDao.update(
                draft.toEntity(
                    id = id,
                    sortOrder = existing.sortOrder,
                    now = now,
                    existing = existing,
                ),
            )
            ruleDao.deleteForCourse(id)
            insertRules(id, draft, now)
        }
    }

    override suspend fun archive(id: String) {
        database.withTransaction {
            val existing = courseDao.getById(id) ?: return@withTransaction
            if (existing.status == CourseStatus.ARCHIVED.name) return@withTransaction
            courseDao.updateStatus(
                id = id,
                status = CourseStatus.ARCHIVED.name,
                statusBeforeArchive = existing.status,
                now = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun restore(id: String) {
        database.withTransaction {
            val existing = courseDao.getById(id) ?: return@withTransaction
            val restored = existing.statusBeforeArchive
                ?.takeUnless { it == CourseStatus.ARCHIVED.name }
                ?: CourseStatus.ACTIVE.name
            courseDao.updateStatus(
                id = id,
                status = restored,
                statusBeforeArchive = null,
                now = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun pause(id: String) {
        database.withTransaction {
            val existing = courseDao.getById(id) ?: return@withTransaction
            if (existing.status != CourseStatus.ACTIVE.name) return@withTransaction
            courseDao.updateStatus(
                id = id,
                status = CourseStatus.PAUSED.name,
                statusBeforeArchive = existing.statusBeforeArchive,
                now = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun resume(id: String) {
        database.withTransaction {
            val existing = courseDao.getById(id) ?: return@withTransaction
            if (existing.status != CourseStatus.PAUSED.name) return@withTransaction
            courseDao.updateStatus(
                id = id,
                status = CourseStatus.ACTIVE.name,
                statusBeforeArchive = null,
                now = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun move(id: String, direction: Int, filter: CourseFilter) {
        database.withTransaction {
            val all = courseDao.listAll().sortedWith(compareBy({ it.sortOrder }, { it.createdAtEpochMs }))
            val visible = all.filter { it.status.matches(filter) }
            val index = visible.indexOfFirst { it.id == id }
            val target = index + direction
            if (index < 0 || target !in visible.indices) return@withTransaction
            val swapped = visible.toMutableList()
            val tmp = swapped[index]
            swapped[index] = swapped[target]
            swapped[target] = tmp
            val queue = ArrayDeque(swapped.map { it.id })
            val visibleIds = visible.map { it.id }.toSet()
            val merged = all.map { course ->
                if (course.id in visibleIds) queue.removeFirst() else course.id
            }
            val now = System.currentTimeMillis()
            merged.forEachIndexed { order, courseId ->
                courseDao.updateSort(courseId, order, now)
            }
        }
    }

    override suspend fun deletionImpact(id: String): DeletionImpact? {
        val course = courseDao.getById(id) ?: return null
        return DeletionImpact(
            courseId = id,
            courseName = course.name,
            archived = course.status == CourseStatus.ARCHIVED.name,
            scheduleRuleCount = ruleDao.countForCourse(id),
            packageCount = packageDao.countForCourse(id),
            historicalAttendanceCount = historicalDao.countForCourse(id),
            attendanceCount = attendanceDao.countForCourse(id),
            paymentCount = paymentDao.countForCourse(id),
            adjustmentCount = adjustmentDao.countForCourse(id),
            eventCount = eventDao.countForCourse(id),
            reminderCount = reminderDao.countForCourse(id),
            attachmentCount = attachmentDao.countForCourse(id),
        )
    }

    override suspend fun deletePermanently(id: String) {
        val paths = database.withTransaction {
            val uris = attachmentDao.listUrisForCourse(id)
            attachmentDao.deleteForCourse(id)
            reminderDao.deleteForCourse(id)
            adjustmentDao.deleteForCourse(id)
            paymentDao.deleteForCourse(id)
            historicalDao.deleteForCourse(id)
            attendanceDao.deleteForCourse(id)
            eventDao.deleteForCourse(id)
            packageDao.deleteForCourse(id)
            ruleDao.deleteForCourse(id)
            courseDao.deleteById(id)
            uris
        }
        paths.forEach { attachments.delete(it) }
    }

    override suspend fun savePackage(courseId: String, packageId: String?, draft: PackageDraft): String? {
        return database.withTransaction {
            if (courseDao.getById(courseId) == null) return@withTransaction null
            val now = System.currentTimeMillis()
            val existing = packageId?.let { packageDao.getById(it) }
            if (packageId != null && (existing == null || existing.courseId != courseId)) {
                return@withTransaction null
            }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val entity = CoursePackageEntity(
                id = id,
                courseId = courseId,
                purchasedCreditUnits = draft.purchasedCreditUnits,
                giftCreditUnits = draft.giftCreditUnits.coerceAtLeast(0),
                amountCents = draft.amountCents,
                unitPriceCents = draft.unitPriceCents,
                paidOn = draft.paidOn,
                note = draft.note?.trim()?.ifBlank { null },
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            )
            if (existing == null) packageDao.insert(entity) else packageDao.update(entity)
            upsertPackagePayment(entity, now)
            id
        }
    }

    override suspend fun deletePackage(packageId: String) {
        database.withTransaction {
            if (packageDao.getById(packageId) == null) return@withTransaction
            paymentDao.deleteForPackage(packageId)
            packageDao.deleteById(packageId)
        }
    }

    override suspend fun savePayment(courseId: String, paymentId: String?, draft: PaymentDraft): PaymentWriteResult {
        if (draft.paidOn != null && runCatching { LocalDate.parse(draft.paidOn) }.isFailure) {
            return PaymentWriteResult.InvalidDate
        }
        return database.withTransaction {
            if (courseDao.getById(courseId) == null) return@withTransaction PaymentWriteResult.Missing
            val now = System.currentTimeMillis()
            val existing = paymentId?.let { paymentDao.getById(it) }
            if (paymentId != null && (existing == null || existing.courseId != courseId)) {
                return@withTransaction PaymentWriteResult.Missing
            }
            val note = draft.note?.trim()?.ifBlank { null }
            if (existing?.packageId != null) {
                val pkg = packageDao.getById(existing.packageId) ?: return@withTransaction PaymentWriteResult.Missing
                packageDao.update(
                    pkg.copy(
                        amountCents = draft.amountCents,
                        paidOn = draft.paidOn,
                        note = note,
                        unitPriceCents = reconciledUnitPrice(pkg, draft.amountCents),
                    ),
                )
                paymentDao.update(
                    existing.copy(
                        amountCents = draft.amountCents,
                        paidOn = draft.paidOn,
                        note = note ?: "课时套餐缴费",
                        updatedAtEpochMs = now,
                    ),
                )
            } else if (existing == null) {
                paymentDao.insert(
                    PaymentEntity(
                        id = UUID.randomUUID().toString(),
                        courseId = courseId,
                        packageId = null,
                        amountCents = draft.amountCents,
                        paidOn = draft.paidOn,
                        note = note,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
            } else {
                paymentDao.update(
                    existing.copy(
                        amountCents = draft.amountCents,
                        paidOn = draft.paidOn,
                        note = note,
                        updatedAtEpochMs = now,
                    ),
                )
            }
            PaymentWriteResult.Saved
        }
    }

    override suspend fun exportBackup(): String = backups.export()

    override suspend fun inspectBackup(text: String) = backups.inspect(text)

    override suspend fun restoreBackup(text: String) = backups.restore(text)

    override suspend fun deletePayment(paymentId: String): PaymentWriteResult {
        return database.withTransaction {
            val existing = paymentDao.getById(paymentId) ?: return@withTransaction PaymentWriteResult.Missing
            if (existing.packageId != null) return@withTransaction PaymentWriteResult.LinkedToPackage
            paymentDao.deleteById(paymentId)
            PaymentWriteResult.Saved
        }
    }

    override suspend fun addAdjustment(courseId: String, deltaCreditUnits: Int, reason: String?): String? {
        if (deltaCreditUnits == 0) return null
        return database.withTransaction {
            if (courseDao.getById(courseId) == null) return@withTransaction null
            val id = UUID.randomUUID().toString()
            adjustmentDao.insert(
                CreditAdjustmentEntity(
                    id = id,
                    courseId = courseId,
                    deltaCreditUnits = deltaCreditUnits,
                    reason = reason?.trim()?.ifBlank { null },
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            id
        }
    }

    override suspend fun deleteAdjustment(adjustmentId: String) {
        adjustmentDao.deleteById(adjustmentId)
    }

    override suspend fun setRenewalThreshold(courseId: String, units: Int?) {
        val stored = units?.coerceAtLeast(1)
        courseDao.updateRenewalThreshold(courseId, stored, System.currentTimeMillis())
    }

    override suspend fun checkIn(request: CheckInRequest): CheckInOutcome {
        if (!AttendancePolicy.acceptsCheckInUnits(request.creditUnits)) return CheckInOutcome.InvalidCredit
        if (runCatching { LocalDate.parse(request.date) }.isFailure) return CheckInOutcome.InvalidCredit
        return database.withTransaction {
            if (courseDao.getById(request.courseId) == null) return@withTransaction CheckInOutcome.CourseMissing
            val historical = request.historicalId?.let { historicalDao.getById(it) }
            if (request.historicalId != null) {
                if (historical == null || historical.courseId != request.courseId) {
                    return@withTransaction CheckInOutcome.CourseMissing
                }
                if (historical.convertedAttendanceId != null) {
                    return@withTransaction CheckInOutcome.HistoricalAlreadyUsed
                }
            }
            if (request.eventId != null) {
                val linked = eventDao.getById(request.eventId)
                if (linked == null || linked.courseId != request.courseId) {
                    return@withTransaction CheckInOutcome.CourseMissing
                }
                if (attendanceDao.listByEvent(request.eventId).isNotEmpty()) {
                    return@withTransaction CheckInOutcome.AlreadyDeducted
                }
            }
            val existing = attendanceDao.countOnDate(request.courseId, request.date, excludeId = "")
            if (AttendancePolicy.warnsAboutDuplicate(existing, request.confirmDuplicate)) {
                return@withTransaction CheckInOutcome.NeedsDuplicateConfirm
            }
            val now = System.currentTimeMillis()
            val attendanceId = UUID.randomUUID().toString()
            attendanceDao.insert(
                AttendanceEntity(
                    id = attendanceId,
                    courseId = request.courseId,
                    eventId = request.eventId,
                    attendanceDate = request.date,
                    creditUnits = request.creditUnits,
                    note = request.note?.trim()?.ifBlank { null },
                    historicalSourceId = historical?.id,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            if (historical != null) {
                historicalDao.markConverted(historical.id, attendanceId)
            }
            request.photoPath?.let { path ->
                attachmentDao.insert(
                    AttachmentEntity(
                        id = UUID.randomUUID().toString(),
                        ownerType = OWNER_ATTENDANCE,
                        ownerId = attendanceId,
                        uri = path,
                        mimeType = "image/*",
                        createdAtEpochMs = now,
                    ),
                )
            }
            CheckInOutcome.Saved(attendanceId)
        }
    }

    override suspend fun updateAttendance(edit: AttendanceEdit): CheckInOutcome {
        if (!AttendancePolicy.acceptsCheckInUnits(edit.creditUnits)) return CheckInOutcome.InvalidCredit
        if (runCatching { LocalDate.parse(edit.date) }.isFailure) return CheckInOutcome.InvalidCredit
        val replacedPaths = database.withTransaction {
            val existing = attendanceDao.getById(edit.attendanceId) ?: return@withTransaction null
            val existingCount = attendanceDao.countOnDate(existing.courseId, edit.date, excludeId = existing.id)
            if (AttendancePolicy.warnsAboutDuplicate(existingCount, edit.confirmDuplicate)) {
                return@withTransaction UpdateAttendance.Duplicate
            }
            val now = System.currentTimeMillis()
            attendanceDao.update(
                existing.copy(
                    attendanceDate = edit.date,
                    creditUnits = edit.creditUnits,
                    note = edit.note?.trim()?.ifBlank { null },
                    updatedAtEpochMs = now,
                ),
            )
            val removed = mutableListOf<String>()
            if (edit.clearPhoto || edit.photoPath != null) {
                val current = attachmentDao.listForOwner(OWNER_ATTENDANCE, existing.id)
                removed += current.map { it.uri }
                attachmentDao.deleteForOwner(OWNER_ATTENDANCE, existing.id)
                val nextPath = edit.photoPath
                if (nextPath != null) {
                    attachmentDao.insert(
                        AttachmentEntity(
                            id = UUID.randomUUID().toString(),
                            ownerType = OWNER_ATTENDANCE,
                            ownerId = existing.id,
                            uri = nextPath,
                            mimeType = "image/*",
                            createdAtEpochMs = now,
                        ),
                    )
                    removed.remove(nextPath)
                }
            }
            UpdateAttendance.Saved(removed)
        }
        return when (replacedPaths) {
            null -> CheckInOutcome.AttendanceMissing
            UpdateAttendance.Duplicate -> CheckInOutcome.NeedsDuplicateConfirm
            is UpdateAttendance.Saved -> {
                replacedPaths.paths.forEach { attachments.delete(it) }
                CheckInOutcome.Saved(edit.attendanceId)
            }
        }
    }

    override suspend fun deleteAttendance(attendanceId: String): Boolean {
        val paths = database.withTransaction {
            val existing = attendanceDao.getById(attendanceId) ?: return@withTransaction null
            val historicalId = existing.historicalSourceId
            if (historicalId != null) {
                val historical = historicalDao.getById(historicalId)
                if (historical?.convertedAttendanceId == attendanceId) {
                    historicalDao.markConverted(historicalId, null)
                }
            }
            val uris = attachmentDao.listForOwner(OWNER_ATTENDANCE, attendanceId).map { it.uri }
            attachmentDao.deleteForOwner(OWNER_ATTENDANCE, attendanceId)
            attendanceDao.deleteById(attendanceId)
            uris
        } ?: return false
        paths.forEach { attachments.delete(it) }
        return true
    }

    override suspend fun backfillHistorical(courseId: String, startDate: String): Int {
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return 0
        return database.withTransaction {
            if (courseDao.getById(courseId) == null) return@withTransaction 0
            val open = historicalDao.listOpen(courseId)
            if (open.isEmpty()) return@withTransaction 0
            val occupied = attendanceDao.datesForCourse(courseId).toSet()
            val plan = BackfillPlanner.plan(open.map { it.id }, start, occupied)
            val byId = open.associateBy { it.id }
            val now = System.currentTimeMillis()
            var written = 0
            plan.forEach { assignment ->
                val historical = byId[assignment.historicalId] ?: return@forEach
                if (historical.creditUnits < AttendancePolicy.MIN_CHECK_IN_UNITS) return@forEach
                val attendanceId = UUID.randomUUID().toString()
                attendanceDao.insert(
                    AttendanceEntity(
                        id = attendanceId,
                        courseId = courseId,
                        eventId = null,
                        attendanceDate = assignment.date,
                        creditUnits = historical.creditUnits,
                        note = historical.note,
                        historicalSourceId = historical.id,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
                historicalDao.markConverted(historical.id, attendanceId)
                written += 1
            }
            written
        }
    }

    override suspend fun ensurePlannedEvent(draft: SessionDraft): String? {
        if (!validRange(draft.startMinutes, draft.endMinutes)) return null
        return database.withTransaction {
            if (courseDao.getById(draft.courseId) == null) return@withTransaction null
            val existing = slotEvent(draft)
            if (existing != null) {
                val blocked = existing.status == CourseEventStatus.LEAVE.name ||
                    existing.status == CourseEventStatus.CANCELLED.name ||
                    existing.status == CourseEventStatus.RESCHEDULED.name
                if (blocked) return@withTransaction null
                return@withTransaction existing.id
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            eventDao.insert(eventEntity(id, draft, CourseEventStatus.PLANNED, null, null, now))
            id
        }
    }

    override suspend fun addOneOff(draft: SessionDraft): SessionWriteResult {
        if (!validRange(draft.startMinutes, draft.endMinutes)) return SessionWriteResult.InvalidTime
        val conflicts = conflictLabels(draft.date, draft.startMinutes, draft.endMinutes, emptySet(), null)
        if (conflicts.isNotEmpty() && !draft.confirmConflict) {
            return SessionWriteResult.NeedsConflictConfirm(conflicts)
        }
        return database.withTransaction {
            if (courseDao.getById(draft.courseId) == null) return@withTransaction SessionWriteResult.Missing
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            eventDao.insert(
                eventEntity(id, draft.copy(sourceRuleId = null), CourseEventStatus.PLANNED, null, null, now),
            )
            SessionWriteResult.Saved(id, "已添加这次课程安排")
        }
    }

    override suspend fun markLeave(draft: SessionDraft, deductCreditUnits: Int?): SessionWriteResult {
        if (!validRange(draft.startMinutes, draft.endMinutes)) return SessionWriteResult.InvalidTime
        val deduct = deductCreditUnits?.takeIf { it >= AttendancePolicy.MIN_CHECK_IN_UNITS }
        return database.withTransaction {
            if (courseDao.getById(draft.courseId) == null) return@withTransaction SessionWriteResult.Missing
            val now = System.currentTimeMillis()
            val event = upsertSlot(draft, CourseEventStatus.LEAVE, deduct, null, now)
            val linked = attendanceDao.listByEvent(event.id)
            val unlinked = attendanceDao.listForCourseOnDate(draft.courseId, draft.date)
                .filter { it.eventId == null }
            val createdDeduction = deduct != null && linked.isEmpty() && unlinked.isEmpty()
            if (deduct != null && linked.isEmpty()) {
                if (unlinked.isEmpty()) {
                    attendanceDao.insert(
                        AttendanceEntity(
                            id = UUID.randomUUID().toString(),
                            courseId = draft.courseId,
                            eventId = event.id,
                            attendanceDate = draft.date,
                            creditUnits = deduct,
                            note = draft.note?.trim()?.ifBlank { null },
                            historicalSourceId = null,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                        ),
                    )
                } else {
                    attendanceDao.update(unlinked.first().copy(eventId = event.id, updatedAtEpochMs = now))
                }
            }
            val message = when {
                deduct == null -> "已请假，没有扣课时"
                createdDeduction -> "已请假，并扣了课时"
                else -> "已请假。这节课原来就扣过课时，没有再扣"
            }
            SessionWriteResult.Saved(event.id, message)
        }
    }

    override suspend fun cancelSession(draft: SessionDraft): SessionWriteResult {
        if (!validRange(draft.startMinutes, draft.endMinutes)) return SessionWriteResult.InvalidTime
        return database.withTransaction {
            if (courseDao.getById(draft.courseId) == null) return@withTransaction SessionWriteResult.Missing
            val event = upsertSlot(draft, CourseEventStatus.CANCELLED, null, null, System.currentTimeMillis())
            SessionWriteResult.Saved(event.id, "已取消这节课，已扣课时不会自动退回")
        }
    }

    override suspend fun rescheduleSession(
        draft: SessionDraft,
        newDate: String,
        newStart: Int,
        newEnd: Int,
    ): SessionWriteResult {
        if (!validRange(draft.startMinutes, draft.endMinutes) || !validRange(newStart, newEnd)) {
            return SessionWriteResult.InvalidTime
        }
        if (runCatching { LocalDate.parse(newDate) }.isFailure) return SessionWriteResult.InvalidTime
            val ignore = draft.existingEventId?.let { setOf(it) } ?: emptySet()
            val ignoreRule = if (newDate == draft.date) draft.sourceRuleId else null
            val conflicts = conflictLabels(newDate, newStart, newEnd, ignore, ignoreRule)
        if (conflicts.isNotEmpty() && !draft.confirmConflict) {
            return SessionWriteResult.NeedsConflictConfirm(conflicts)
        }
        return database.withTransaction {
            if (courseDao.getById(draft.courseId) == null) return@withTransaction SessionWriteResult.Missing
            val now = System.currentTimeMillis()
            val original = upsertSlot(draft, CourseEventStatus.RESCHEDULED, null, null, now)
            val destinationId = UUID.randomUUID().toString()
            eventDao.insert(
                CourseEventEntity(
                    id = destinationId,
                    courseId = draft.courseId,
                    eventDate = newDate,
                    startMinutes = newStart,
                    endMinutes = newEnd,
                    status = CourseEventStatus.PLANNED.name,
                    sourceRuleId = null,
                    rescheduledFromEventId = original.id,
                    deductCreditUnits = null,
                    note = draft.note?.trim()?.ifBlank { null },
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            val moved = attendanceDao.listByEvent(original.id).toMutableList()
            if (moved.isEmpty()) {
                val unlinked = attendanceDao.listForCourseOnDate(draft.courseId, draft.date)
                    .filter { it.eventId == null }
                if (unlinked.size == 1) moved += unlinked.first()
            }
            moved.forEach { row ->
                attendanceDao.update(
                    row.copy(eventId = destinationId, attendanceDate = newDate, updatedAtEpochMs = now),
                )
            }
            SessionWriteResult.Saved(destinationId, "已调课。课时只保留原来的扣减，没有再扣一次")
        }
    }

    override suspend fun addMakeup(draft: SessionDraft, fromEventId: String?): SessionWriteResult {
        if (!validRange(draft.startMinutes, draft.endMinutes)) return SessionWriteResult.InvalidTime
        val conflicts = conflictLabels(draft.date, draft.startMinutes, draft.endMinutes, emptySet(), null)
        if (conflicts.isNotEmpty() && !draft.confirmConflict) {
            return SessionWriteResult.NeedsConflictConfirm(conflicts)
        }
        return database.withTransaction {
            if (courseDao.getById(draft.courseId) == null) return@withTransaction SessionWriteResult.Missing
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            eventDao.insert(
                eventEntity(id, draft.copy(sourceRuleId = null), CourseEventStatus.MAKEUP, null, fromEventId, now),
            )
            SessionWriteResult.Saved(id, "已添加补课，打卡时才会扣课时")
        }
    }

    override suspend fun clearException(eventId: String): Boolean {
        return database.withTransaction {
            val event = eventDao.getById(eventId) ?: return@withTransaction false
            val status = event.status
            if (status != CourseEventStatus.LEAVE.name && status != CourseEventStatus.CANCELLED.name) {
                return@withTransaction false
            }
            eventDao.deleteById(eventId)
            true
        }
    }

    override suspend fun undoReschedule(originalEventId: String): Boolean {
        return database.withTransaction {
            val original = eventDao.getById(originalEventId) ?: return@withTransaction false
            if (original.status != CourseEventStatus.RESCHEDULED.name) return@withTransaction false
            val children = eventDao.listChildren(original.id)
            val now = System.currentTimeMillis()
            val rows = attendanceDao.listByEvent(original.id) + children.flatMap { attendanceDao.listByEvent(it.id) }
            rows.distinctBy { it.id }.forEach { row ->
                attendanceDao.update(
                    row.copy(
                        eventId = null,
                        attendanceDate = original.eventDate,
                        updatedAtEpochMs = now,
                    ),
                )
            }
            children.forEach { eventDao.deleteById(it.id) }
            eventDao.deleteById(original.id)
            true
        }
    }

    private suspend fun seedIfNeeded() {
        if (seedChecked) return
        seedMutex.withLock {
            if (seedChecked) return
            if (!settings.isSeedCompleted()) {
                val count = courseDao.count()
                if (SeedPolicy.shouldInsert(seedCompleted = false, existingCourses = count)) {
                    writeSeed()
                }
                settings.setSeedCompleted()
            }
            ensurePackagePayments()
            seedChecked = true
        }
    }

    private suspend fun writeSeed() {
        val snapshot = SeedCatalog.build(createdAtEpochMs = System.currentTimeMillis())
        database.withTransaction {
            snapshot.courses.forEach { row ->
                courseDao.insert(
                    CourseEntity(
                        id = row.id,
                        name = row.name,
                        iconKey = row.iconKey,
                        colorHex = row.colorHex,
                        institution = null,
                        location = null,
                        notes = null,
                        scheduleMode = row.scheduleMode.name,
                        status = row.status.name,
                        statusBeforeArchive = null,
                        sortOrder = row.sortOrder,
                        createdAtEpochMs = row.createdAtEpochMs,
                        updatedAtEpochMs = row.createdAtEpochMs,
                    ),
                )
            }
            snapshot.rules.forEach { row ->
                ruleDao.insert(
                    CourseScheduleRuleEntity(
                        id = row.id,
                        courseId = row.courseId,
                        dayOfWeek = row.dayOfWeek,
                        startMinutes = row.startMinutes,
                        endMinutes = row.endMinutes,
                        createdAtEpochMs = row.createdAtEpochMs,
                    ),
                )
            }
            snapshot.packages.forEach { row ->
                packageDao.insert(
                    CoursePackageEntity(
                        id = row.id,
                        courseId = row.courseId,
                        purchasedCreditUnits = row.purchasedCreditUnits,
                        giftCreditUnits = row.giftCreditUnits,
                        amountCents = row.amountCents,
                        unitPriceCents = row.unitPriceCents,
                        paidOn = row.paidOn,
                        note = null,
                        createdAtEpochMs = row.createdAtEpochMs,
                    ),
                )
            }
            snapshot.historical.forEach { row ->
                historicalDao.insert(
                    HistoricalAttendanceEntity(
                        id = row.id,
                        courseId = row.courseId,
                        creditUnits = row.creditUnits,
                        note = row.note,
                        convertedAttendanceId = null,
                        createdAtEpochMs = row.createdAtEpochMs,
                    ),
                )
            }
        }
    }

    private suspend fun insertRules(courseId: String, draft: CourseDraft, now: Long) {
        if (draft.scheduleMode != ScheduleMode.WEEKLY) return
        draft.rules.forEach { rule ->
            ruleDao.insert(
                CourseScheduleRuleEntity(
                    id = UUID.randomUUID().toString(),
                    courseId = courseId,
                    dayOfWeek = rule.dayOfWeek,
                    startMinutes = rule.startMinutes,
                    endMinutes = rule.endMinutes,
                    createdAtEpochMs = now,
                ),
            )
        }
    }

    private fun CourseDraft.toEntity(
        id: String,
        sortOrder: Int,
        now: Long,
        existing: CourseEntity?,
    ): CourseEntity {
        return CourseEntity(
            id = id,
            name = name.trim(),
            iconKey = iconKey,
            colorHex = colorHex,
            institution = institution,
            location = location,
            notes = notes,
            scheduleMode = scheduleMode.name,
            status = existing?.status ?: CourseStatus.ACTIVE.name,
            statusBeforeArchive = existing?.statusBeforeArchive,
            sortOrder = sortOrder,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            renewalThresholdCreditUnits = existing?.renewalThresholdCreditUnits,
        )
    }

    private suspend fun ensurePackagePayments() {
        database.withTransaction {
            val linked = paymentDao.linkedPackageIds().toSet()
            val now = System.currentTimeMillis()
            packageDao.listAll().forEach { pkg ->
                if (pkg.id !in linked) upsertPackagePayment(pkg, now)
            }
        }
    }

    private suspend fun upsertPackagePayment(pkg: CoursePackageEntity, now: Long) {
        val existing = paymentDao.findByPackage(pkg.id)
        val entity = PaymentEntity(
            id = existing?.id ?: "pay-${pkg.id}",
            courseId = pkg.courseId,
            packageId = pkg.id,
            amountCents = pkg.amountCents,
            paidOn = pkg.paidOn,
            note = pkg.note ?: "课时套餐缴费",
            createdAtEpochMs = existing?.createdAtEpochMs ?: pkg.createdAtEpochMs,
            updatedAtEpochMs = now,
        )
        if (existing == null) paymentDao.insert(entity) else paymentDao.update(entity)
    }

    private sealed interface UpdateAttendance {
        data object Duplicate : UpdateAttendance
        data class Saved(val paths: List<String>) : UpdateAttendance
    }

    private suspend fun slotEvent(draft: SessionDraft): CourseEventEntity? {
        draft.existingEventId?.let { return eventDao.getById(it) }
        val rows = eventDao.listForCourseOnDate(draft.courseId, draft.date)
        return rows.firstOrNull { draft.sourceRuleId != null && it.sourceRuleId == draft.sourceRuleId }
    }

    private suspend fun upsertSlot(
        draft: SessionDraft,
        status: CourseEventStatus,
        deduct: Int?,
        rescheduledFrom: String?,
        now: Long,
    ): CourseEventEntity {
        val existing = slotEvent(draft)
        val entity = if (existing == null) {
            eventEntity(UUID.randomUUID().toString(), draft, status, deduct, rescheduledFrom, now)
        } else {
            existing.copy(
                startMinutes = draft.startMinutes,
                endMinutes = draft.endMinutes,
                status = status.name,
                sourceRuleId = draft.sourceRuleId ?: existing.sourceRuleId,
                deductCreditUnits = deduct,
                note = draft.note?.trim()?.ifBlank { null },
                updatedAtEpochMs = now,
            )
        }
        if (existing == null) eventDao.insert(entity) else eventDao.update(entity)
        return entity
    }

    private fun eventEntity(
        id: String,
        draft: SessionDraft,
        status: CourseEventStatus,
        deduct: Int?,
        rescheduledFrom: String?,
        now: Long,
    ) = CourseEventEntity(
        id = id,
        courseId = draft.courseId,
        eventDate = draft.date,
        startMinutes = draft.startMinutes,
        endMinutes = draft.endMinutes,
        status = status.name,
        sourceRuleId = draft.sourceRuleId,
        rescheduledFromEventId = rescheduledFrom,
        deductCreditUnits = deduct,
        note = draft.note?.trim()?.ifBlank { null },
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
    )

    private suspend fun conflictLabels(
        date: String,
        start: Int,
        end: Int,
        ignoreEventIds: Set<String>,
        ignoreRuleId: String?,
    ): List<String> {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return emptyList()
        val courses = courseDao.listWithChildren().map { it.toDetails() }
        val sessions = CalendarSchedule.assemble(courses, day, day, LocalDate.now())
        return CalendarSchedule.conflicts(day, start, end, sessions, ignoreEventIds, ignoreRuleId)
            .map { session -> "${session.courseName} ${formatMinuteRange(session.startMinutes, session.endMinutes)}" }
    }

    private fun validRange(start: Int, end: Int): Boolean = start in 0 until end && end <= 24 * 60

    private fun reconciledUnitPrice(pkg: CoursePackageEntity, newAmount: Long?): Long? {
        val current = pkg.unitPriceCents ?: return null
        val oldDerived = LessonCalculator.derivedUnitPrice(pkg.amountCents, pkg.purchasedCreditUnits)
        if (oldDerived != null && current == oldDerived) {
            return LessonCalculator.derivedUnitPrice(newAmount, pkg.purchasedCreditUnits)
        }
        return current
    }

    private companion object {
        const val OWNER_ATTENDANCE = "ATTENDANCE"
    }

    private fun String.matches(filter: CourseFilter): Boolean = when (filter) {
        CourseFilter.ALL -> true
        CourseFilter.ACTIVE -> this == CourseStatus.ACTIVE.name
        CourseFilter.PAUSED -> this == CourseStatus.PAUSED.name
        CourseFilter.ARCHIVED -> this == CourseStatus.ARCHIVED.name
    }
}
