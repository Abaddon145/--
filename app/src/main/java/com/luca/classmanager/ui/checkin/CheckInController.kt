package com.luca.classmanager.ui.checkin

import android.net.Uri
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.metrics
import com.luca.classmanager.domain.logic.renewalLabel
import com.luca.classmanager.domain.model.AttendanceEdit
import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CheckInOutcome
import com.luca.classmanager.domain.model.CheckInRequest
import com.luca.classmanager.domain.model.HistoricalCredit
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class CheckEditor(
    val courseId: String,
    val courseName: String,
    val date: LocalDate,
    val creditUnits: Int,
    val note: String,
    val photoPath: String?,
    val importedPhoto: Boolean,
    val clearExistingPhoto: Boolean,
    val historicalId: String?,
    val editingAttendanceId: String?,
    val duplicateWarning: Boolean,
    val error: String?,
    val saving: Boolean,
)

data class DuplicatePrompt(
    val courseId: String,
    val courseName: String,
    val date: LocalDate,
    val saving: Boolean,
    val eventId: String? = null,
    val creditUnits: Int = CreditUnits.PER_LESSON,
)

sealed interface CheckInUiResult {
    data class Notice(val message: String, val undoAttendanceId: String?) : CheckInUiResult
    data object ShownInDialog : CheckInUiResult
}

class CheckInController(
    private val repository: CourseRepository,
    private val attachments: AttachmentStore,
    private val settings: SettingsRepository,
) {
    private val _editor = MutableStateFlow<CheckEditor?>(null)
    val editor: StateFlow<CheckEditor?> = _editor.asStateFlow()
    private val _duplicate = MutableStateFlow<DuplicatePrompt?>(null)
    val duplicate: StateFlow<DuplicatePrompt?> = _duplicate.asStateFlow()

    suspend fun quick(
        courseId: String,
        courseName: String,
        date: LocalDate = LocalDate.now(),
        eventId: String? = null,
        creditUnits: Int = CreditUnits.PER_LESSON,
    ): CheckInUiResult {
        val outcome = repository.checkIn(
            CheckInRequest(
                courseId = courseId,
                date = date.toString(),
                creditUnits = creditUnits,
                note = null,
                photoPath = null,
                confirmDuplicate = false,
                eventId = eventId,
            ),
        )
        return when (outcome) {
            is CheckInOutcome.Saved -> noticeFor(courseId, outcome.attendanceId)
            CheckInOutcome.NeedsDuplicateConfirm -> {
                _duplicate.value = DuplicatePrompt(
                    courseId,
                    courseName,
                    date,
                    saving = false,
                    eventId = eventId,
                    creditUnits = creditUnits,
                )
                CheckInUiResult.ShownInDialog
            }
            else -> CheckInUiResult.Notice(messageFor(outcome), undoAttendanceId = null)
        }
    }

    suspend fun confirmQuickDuplicate(): CheckInUiResult {
        val prompt = _duplicate.value ?: return CheckInUiResult.ShownInDialog
        _duplicate.value = prompt.copy(saving = true)
        val outcome = repository.checkIn(
            CheckInRequest(
                courseId = prompt.courseId,
                date = prompt.date.toString(),
                creditUnits = prompt.creditUnits,
                note = null,
                photoPath = null,
                confirmDuplicate = true,
                eventId = prompt.eventId,
            ),
        )
        _duplicate.value = null
        return when (outcome) {
            is CheckInOutcome.Saved -> noticeFor(prompt.courseId, outcome.attendanceId)
            else -> CheckInUiResult.Notice(messageFor(outcome), undoAttendanceId = null)
        }
    }

    fun dismissDuplicate() {
        _duplicate.value = null
    }

    fun openEditor(courseId: String, courseName: String, date: LocalDate = LocalDate.now()) {
        discardImportedPhoto()
        _editor.value = blank(courseId, courseName, date)
    }

    fun openHistorical(courseId: String, courseName: String, historical: HistoricalCredit) {
        discardImportedPhoto()
        _editor.value = blank(courseId, courseName, LocalDate.now()).copy(
            creditUnits = historical.creditUnits.coerceAtLeast(CreditUnits.PER_LESSON / 2).coerceAtLeast(1),
            historicalId = historical.id,
        )
    }

    fun openEdit(courseId: String, courseName: String, record: AttendanceInfo) {
        discardImportedPhoto()
        val date = runCatching { LocalDate.parse(record.attendanceDate) }.getOrDefault(LocalDate.now())
        _editor.value = blank(courseId, courseName, date).copy(
            creditUnits = record.creditUnits.coerceAtLeast(1),
            note = record.note.orEmpty(),
            photoPath = record.photoPath,
            editingAttendanceId = record.id,
        )
    }

    fun setDate(date: LocalDate) = update { it.copy(date = date, duplicateWarning = false) }

    fun setUnits(units: Int) = update {
        it.copy(creditUnits = units.coerceIn(1, 40))
    }

    fun setNote(note: String) = update { it.copy(note = note.take(200)) }

    fun clearPhoto() {
        val current = _editor.value ?: return
        if (current.importedPhoto) attachments.delete(current.photoPath)
        _editor.value = current.copy(
            photoPath = null,
            importedPhoto = false,
            clearExistingPhoto = current.editingAttendanceId != null || current.clearExistingPhoto,
        )
    }

    suspend fun importPhoto(uri: Uri) {
        val current = _editor.value ?: return
        val path = withContext(Dispatchers.IO) { attachments.import(uri) }
        if (path == null) {
            _editor.value = current.copy(error = "这张照片没有保存下来，可以重试或只保留文字备注")
            return
        }
        if (current.importedPhoto) attachments.delete(current.photoPath)
        _editor.value = current.copy(
            photoPath = path,
            importedPhoto = true,
            error = null,
        )
    }

    fun dismissEditor() {
        discardImportedPhoto()
        _editor.value = null
    }

    suspend fun save(): CheckInUiResult {
        val form = _editor.value ?: return CheckInUiResult.ShownInDialog
        if (form.saving) return CheckInUiResult.ShownInDialog
        _editor.value = form.copy(saving = true, error = null)
        val note = form.note.trim().ifBlank { null }
        val outcome = if (form.editingAttendanceId == null) {
            repository.checkIn(
                CheckInRequest(
                    courseId = form.courseId,
                    date = form.date.toString(),
                    creditUnits = form.creditUnits,
                    note = note,
                    photoPath = form.photoPath,
                    confirmDuplicate = form.duplicateWarning,
                    historicalId = form.historicalId,
                ),
            )
        } else {
            repository.updateAttendance(
                AttendanceEdit(
                    attendanceId = form.editingAttendanceId,
                    date = form.date.toString(),
                    creditUnits = form.creditUnits,
                    note = note,
                    photoPath = if (form.importedPhoto) form.photoPath else null,
                    clearPhoto = form.clearExistingPhoto || form.importedPhoto,
                    confirmDuplicate = form.duplicateWarning,
                ),
            )
        }
        return when (outcome) {
            is CheckInOutcome.Saved -> {
                _editor.value = null
                if (form.editingAttendanceId == null) {
                    noticeFor(form.courseId, outcome.attendanceId)
                } else {
                    CheckInUiResult.Notice("已更新打卡记录", undoAttendanceId = null)
                }
            }
            CheckInOutcome.NeedsDuplicateConfirm -> {
                _editor.value = form.copy(
                    saving = false,
                    duplicateWarning = true,
                    error = "这一天已经有打卡记录。再保存一次会多记一条，不会并到原来的记录里。",
                )
                CheckInUiResult.ShownInDialog
            }
            else -> {
                if (form.importedPhoto) attachments.delete(form.photoPath)
                _editor.value = form.copy(
                    saving = false,
                    importedPhoto = false,
                    photoPath = if (form.importedPhoto) null else form.photoPath,
                    error = messageFor(outcome),
                )
                CheckInUiResult.ShownInDialog
            }
        }
    }

    suspend fun deleteEditing(): CheckInUiResult {
        val form = _editor.value ?: return CheckInUiResult.ShownInDialog
        val attendanceId = form.editingAttendanceId ?: return CheckInUiResult.ShownInDialog
        val removed = repository.deleteAttendance(attendanceId)
        return if (removed) {
            if (form.importedPhoto) attachments.delete(form.photoPath)
            _editor.value = null
            CheckInUiResult.Notice("已删除这条打卡，课时和费用已按记录恢复", undoAttendanceId = null)
        } else {
            _editor.value = form.copy(error = "没有找到这条打卡记录")
            CheckInUiResult.ShownInDialog
        }
    }

    private fun blank(courseId: String, courseName: String, date: LocalDate) = CheckEditor(
        courseId = courseId,
        courseName = courseName,
        date = date,
        creditUnits = CreditUnits.PER_LESSON,
        note = "",
        photoPath = null,
        importedPhoto = false,
        clearExistingPhoto = false,
        historicalId = null,
        editingAttendanceId = null,
        duplicateWarning = false,
        error = null,
        saving = false,
    )

    private fun update(block: (CheckEditor) -> CheckEditor) {
        val current = _editor.value ?: return
        _editor.value = block(current)
    }

    private fun discardImportedPhoto() {
        val current = _editor.value ?: return
        if (current.importedPhoto) attachments.delete(current.photoPath)
    }

    private suspend fun noticeFor(courseId: String, attendanceId: String): CheckInUiResult.Notice {
        val threshold = settings.renewalThresholdCreditUnits.first()
        val metrics = repository.getCourse(courseId)?.metrics(threshold)
        val remaining = metrics?.remainingCreditUnits
        val renewal = metrics?.let { renewalLabel(it) }
        val message = when {
            remaining == null -> "已打卡，剩余课时待补充"
            renewal != null -> "已打卡，剩余 ${CreditUnits.format(remaining)} 课时，$renewal"
            else -> "已打卡，剩余 ${CreditUnits.format(remaining)} 课时"
        }
        return CheckInUiResult.Notice(message, attendanceId)
    }

    private fun messageFor(outcome: CheckInOutcome): String = when (outcome) {
        is CheckInOutcome.Saved -> "已打卡"
        CheckInOutcome.NeedsDuplicateConfirm -> "这一天已经有打卡记录"
        CheckInOutcome.CourseMissing -> "找不到这门课程"
        CheckInOutcome.AttendanceMissing -> "找不到这条打卡记录"
        CheckInOutcome.InvalidCredit -> "消耗课时至少为 0.5"
        CheckInOutcome.HistoricalAlreadyUsed -> "这条历史记录已经补录过，没有重复扣课"
        CheckInOutcome.AlreadyDeducted -> "这节课已经扣过课时，不会再扣一次"
    }
}
