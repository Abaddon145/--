@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.luca.classmanager.ui.calendar

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.domain.logic.CalendarSchedule
import com.luca.classmanager.domain.logic.CalendarSession
import com.luca.classmanager.domain.logic.SessionVisual
import com.luca.classmanager.domain.logic.formatChineseDate
import com.luca.classmanager.domain.logic.formatMinuteRange
import com.luca.classmanager.domain.logic.sessionVisualLabel
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.SessionDraft
import com.luca.classmanager.domain.model.SessionWriteResult
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.checkin.CheckInController
import com.luca.classmanager.ui.checkin.CheckInDialogLayer
import com.luca.classmanager.ui.checkin.CheckInUiResult
import com.luca.classmanager.ui.checkin.LucaDateDialog
import com.luca.classmanager.ui.components.CourseIcon
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.LucaTextButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import com.luca.classmanager.ui.theme.parseCourseColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CalendarMode { MONTH, WEEK }

private data class Chrome(
    val mode: CalendarMode,
    val month: YearMonth,
    val selected: LocalDate,
    val courseFilter: String?,
    val statusFilter: SessionVisual?,
)

data class CalendarReady(
    val mode: CalendarMode,
    val month: YearMonth,
    val weekStart: LocalDate,
    val selected: LocalDate,
    val dots: Map<LocalDate, List<String>>,
    val selectedSessions: List<CalendarSession>,
    val weekDays: List<Pair<LocalDate, List<CalendarSession>>>,
    val courses: List<CourseDetails>,
    val courseFilter: String?,
    val statusFilter: SessionVisual?,
    val dayConflicts: List<String>,
)

sealed interface CalendarUiState {
    data object Loading : CalendarUiState
    data class Ready(val data: CalendarReady) : CalendarUiState
    data class Error(val message: String) : CalendarUiState
}

sealed interface CalendarDialog {
    data object None : CalendarDialog
    data class Actions(val session: CalendarSession) : CalendarDialog
    data class OneOff(
        val date: LocalDate,
        val courseId: String,
        val start: String,
        val end: String,
        val note: String,
        val error: String?,
    ) : CalendarDialog
    data class Leave(
        val session: CalendarSession,
        val note: String,
        val deduct: Boolean,
        val units: Int,
    ) : CalendarDialog
    data class Move(
        val session: CalendarSession,
        val makeup: Boolean,
        val date: LocalDate,
        val start: String,
        val end: String,
        val note: String,
        val error: String?,
    ) : CalendarDialog
    data class Conflict(val lines: List<String>) : CalendarDialog
}

private sealed interface PendingWrite {
    data class OneOff(val draft: SessionDraft) : PendingWrite
    data class Move(val draft: SessionDraft, val date: String, val start: Int, val end: Int, val makeup: Boolean) : PendingWrite
}

class CalendarViewModel(
    private val repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
) : ViewModel() {
    val checkIns = CheckInController(repository, attachments, settings)
    private val refresh = MutableStateFlow(0)
    private val mode = MutableStateFlow(CalendarMode.MONTH)
    private val month = MutableStateFlow(YearMonth.from(LocalDate.now()))
    private val selected = MutableStateFlow(LocalDate.now())
    private val courseFilter = MutableStateFlow<String?>(null)
    private val statusFilter = MutableStateFlow<SessionVisual?>(null)
    private val _dialog = MutableStateFlow<CalendarDialog>(CalendarDialog.None)
    val dialog: StateFlow<CalendarDialog> = _dialog.asStateFlow()
    private var pending: PendingWrite? = null
    private var coursesNow: List<CourseDetails> = emptyList()
    private val _notices = MutableSharedFlow<CheckInUiResult.Notice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()

    val uiState: StateFlow<CalendarUiState> = refresh.flatMapLatest {
        val chrome = combine(mode, month, selected, courseFilter, statusFilter) { currentMode, currentMonth, day, courseId, visual ->
            Chrome(currentMode, currentMonth, day, courseId, visual)
        }
        combine(repository.observeCourses(), chrome) { courses, state ->
            coursesNow = courses
            CalendarUiState.Ready(buildReady(courses, state)) as CalendarUiState
        }.catch { emit(CalendarUiState.Error("日历数据暂时无法读取")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState.Loading)

    fun retry() {
        refresh.value += 1
    }

    fun setMode(next: CalendarMode) {
        mode.value = next
    }

    fun shift(delta: Int) {
        if (mode.value == CalendarMode.MONTH) {
            val next = month.value.plusMonths(delta.toLong())
            month.value = next
            val day = selected.value.dayOfMonth.coerceAtMost(next.lengthOfMonth())
            selected.value = next.atDay(day)
        } else {
            select(selected.value.plusDays(delta * 7L))
        }
    }

    fun select(date: LocalDate) {
        selected.value = date
        month.value = YearMonth.from(date)
    }

    fun goToday() {
        select(LocalDate.now())
    }

    fun filterCourse(id: String?) {
        courseFilter.value = id
    }

    fun filterStatus(visual: SessionVisual?) {
        statusFilter.value = visual
    }

    fun openActions(session: CalendarSession) {
        _dialog.value = CalendarDialog.Actions(session)
    }

    fun dismissDialog() {
        _dialog.value = CalendarDialog.None
        pending = null
    }

    fun startOneOff(date: LocalDate) {
        val courseId = courseFilter.value
            ?: coursesNow.firstOrNull { it.status == CourseStatus.ACTIVE }?.id
            ?: coursesNow.firstOrNull()?.id
            ?: return
        _dialog.value = CalendarDialog.OneOff(date, courseId, "16:00", "17:00", "", null)
    }

    fun updateOneOff(block: (CalendarDialog.OneOff) -> CalendarDialog.OneOff) {
        val current = _dialog.value as? CalendarDialog.OneOff ?: return
        _dialog.value = block(current)
    }

    fun startLeave(session: CalendarSession) {
        _dialog.value = CalendarDialog.Leave(session, session.note.orEmpty(), deduct = false, units = 2)
    }

    fun updateLeave(block: (CalendarDialog.Leave) -> CalendarDialog.Leave) {
        val current = _dialog.value as? CalendarDialog.Leave ?: return
        _dialog.value = block(current)
    }

    fun startMove(session: CalendarSession, makeup: Boolean) {
        _dialog.value = CalendarDialog.Move(
            session = session,
            makeup = makeup,
            date = session.date,
            start = formatClock(session.startMinutes),
            end = formatClock(session.endMinutes),
            note = session.note.orEmpty(),
            error = null,
        )
    }

    fun updateMove(block: (CalendarDialog.Move) -> CalendarDialog.Move) {
        val current = _dialog.value as? CalendarDialog.Move ?: return
        _dialog.value = block(current)
    }

    fun saveOneOff() {
        val form = _dialog.value as? CalendarDialog.OneOff ?: return
        val draft = form.toDraft(confirm = false)
        if (draft == null) {
            _dialog.value = form.copy(error = "请填写结束晚于开始的时间，例如 16:00")
            return
        }
        submit(PendingWrite.OneOff(draft)) { pending ->
            repository.addOneOff((pending as PendingWrite.OneOff).draft)
        }
    }

    fun saveLeave() {
        val form = _dialog.value as? CalendarDialog.Leave ?: return
        viewModelScope.launch {
            val result = repository.markLeave(
                draft = form.session.toDraft(form.note, confirm = true),
                deductCreditUnits = if (form.deduct) form.units else null,
            )
            publishWrite(result)
        }
    }

    fun saveMove() {
        val form = _dialog.value as? CalendarDialog.Move ?: return
        val start = parseClock(form.start)
        val end = parseClock(form.end)
        if (start == null || end == null || end <= start) {
            _dialog.value = form.copy(error = "请填写结束晚于开始的时间，例如 16:00")
            return
        }
        val draft = form.session.toDraft(form.note, confirm = false)
        submit(PendingWrite.Move(draft, form.date.toString(), start, end, form.makeup)) { pending ->
            val move = pending as PendingWrite.Move
            if (move.makeup) {
                repository.addMakeup(
                    move.draft.copy(
                        date = move.date,
                        startMinutes = move.start,
                        endMinutes = move.end,
                    ),
                    move.draft.existingEventId,
                )
            } else {
                repository.rescheduleSession(move.draft, move.date, move.start, move.end)
            }
        }
    }

    fun confirmConflict() {
        val current = pending ?: return
        val confirmed = when (current) {
            is PendingWrite.OneOff -> current.copy(draft = current.draft.copy(confirmConflict = true))
            is PendingWrite.Move -> current.copy(draft = current.draft.copy(confirmConflict = true))
        }
        submit(confirmed) { pendingWrite ->
            when (pendingWrite) {
                is PendingWrite.OneOff -> repository.addOneOff(pendingWrite.draft)
                is PendingWrite.Move -> if (pendingWrite.makeup) {
                    repository.addMakeup(
                        pendingWrite.draft.copy(
                            date = pendingWrite.date,
                            startMinutes = pendingWrite.start,
                            endMinutes = pendingWrite.end,
                        ),
                        pendingWrite.draft.existingEventId,
                    )
                } else {
                    repository.rescheduleSession(
                        pendingWrite.draft,
                        pendingWrite.date,
                        pendingWrite.start,
                        pendingWrite.end,
                    )
                }
            }
        }
    }

    fun cancelSession(session: CalendarSession) {
        viewModelScope.launch {
            publishWrite(repository.cancelSession(session.toDraft(session.note, confirm = true)))
        }
    }

    fun clearException(eventId: String?) {
        if (eventId == null) return
        viewModelScope.launch {
            val cleared = repository.clearException(eventId)
            _dialog.value = CalendarDialog.None
            _notices.emit(CheckInUiResult.Notice(if (cleared) "已恢复这节课的原计划" else "没有撤销这条安排", null))
        }
    }

    fun undoReschedule(eventId: String?) {
        if (eventId == null) return
        viewModelScope.launch {
            val undone = repository.undoReschedule(eventId)
            _dialog.value = CalendarDialog.None
            _notices.emit(
                CheckInUiResult.Notice(
                    if (undone) "已撤销调课，扣过的课时仍只算一次" else "没有找到这次调课",
                    null,
                ),
            )
        }
    }

    fun checkIn(session: CalendarSession) {
        viewModelScope.launch {
            val eventId = session.eventId ?: repository.ensurePlannedEvent(session.toDraft(null, confirm = true))
            _dialog.value = CalendarDialog.None
            if (eventId == null) {
                _notices.emit(CheckInUiResult.Notice("这节课现在不能打卡", null))
                return@launch
            }
            publish(checkIns.quick(session.courseId, session.courseName, session.date, eventId))
        }
    }

    fun editCheckIn(session: CalendarSession) {
        val course = coursesNow.firstOrNull { it.id == session.courseId } ?: return
        val record = course.attendances.firstOrNull { it.id == session.attendanceId } ?: return
        _dialog.value = CalendarDialog.None
        checkIns.openEdit(course.id, course.name, record)
    }

    fun openDetailed(session: CalendarSession) {
        _dialog.value = CalendarDialog.None
        checkIns.openEditor(session.courseId, session.courseName, session.date)
    }

    fun backfill(course: CourseDetails, date: LocalDate) {
        val row = course.historicalUnrecorded.firstOrNull() ?: return
        checkIns.openHistorical(course.id, course.name, row)
        checkIns.setDate(date)
    }

    fun saveEditor() = launchCheckIn { checkIns.save() }
    fun deleteEditing() = launchCheckIn { checkIns.deleteEditing() }
    fun confirmDuplicate() = launchCheckIn { checkIns.confirmQuickDuplicate() }
    fun attachPhoto(uri: Uri) {
        viewModelScope.launch { checkIns.importPhoto(uri) }
    }

    fun undo(attendanceId: String) {
        viewModelScope.launch {
            val removed = repository.deleteAttendance(attendanceId)
            _notices.emit(
                CheckInUiResult.Notice(
                    if (removed) "已撤销这次打卡，课时和费用已按记录恢复" else "这次打卡已经不在记录里",
                    null,
                ),
            )
        }
    }

    private fun submit(write: PendingWrite, block: suspend (PendingWrite) -> SessionWriteResult) {
        pending = write
        viewModelScope.launch { publishWrite(block(write)) }
    }

    private suspend fun publishWrite(result: SessionWriteResult) {
        when (result) {
            is SessionWriteResult.Saved -> {
                pending = null
                _dialog.value = CalendarDialog.None
                _notices.emit(CheckInUiResult.Notice(result.message, null))
            }
            is SessionWriteResult.NeedsConflictConfirm -> _dialog.value = CalendarDialog.Conflict(result.conflicts)
            SessionWriteResult.InvalidTime -> _notices.emit(CheckInUiResult.Notice("结束时间要晚于开始时间", null))
            SessionWriteResult.Missing -> _notices.emit(CheckInUiResult.Notice("找不到这门课程", null))
        }
    }

    private fun launchCheckIn(block: suspend () -> CheckInUiResult) {
        viewModelScope.launch {
            val result = block()
            if (result is CheckInUiResult.Notice) _notices.emit(result)
        }
    }

    private fun publish(result: CheckInUiResult) {
        if (result is CheckInUiResult.Notice) {
            viewModelScope.launch { _notices.emit(result) }
        }
    }

    companion object {
        fun factory(
            repository: CourseRepository,
            settings: SettingsRepository,
            attachments: AttachmentStore,
        ): ViewModelProvider.Factory = simpleFactory {
            CalendarViewModel(repository, settings, attachments)
        }
    }
}

private fun buildReady(courses: List<CourseDetails>, chrome: Chrome): CalendarReady {
    val weekStart = chrome.selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val from = minOf(chrome.month.atDay(1), weekStart)
    val to = maxOf(chrome.month.atEndOfMonth(), weekStart.plusDays(6))
    val sessions = CalendarSchedule.assemble(courses, from, to, LocalDate.now())
        .filter { session ->
            (chrome.courseFilter == null || session.courseId == chrome.courseFilter) &&
                (chrome.statusFilter == null || session.visual == chrome.statusFilter)
        }
    val dots = sessions
        .filter { it.visual != SessionVisual.CANCELLED && !(it.visual == SessionVisual.RESCHEDULED && !it.occupiesTime) }
        .groupBy { it.date }
        .mapValues { (_, rows) -> rows.map { it.colorHex }.distinct() }
    val weekDays = (0L..6L).map { offset ->
        val date = weekStart.plusDays(offset)
        date to sessions.filter { it.date == date }
    }
    val selectedSessions = sessions.filter { it.date == chrome.selected }
    return CalendarReady(
        mode = chrome.mode,
        month = chrome.month,
        weekStart = weekStart,
        selected = chrome.selected,
        dots = dots,
        selectedSessions = selectedSessions,
        weekDays = weekDays,
        courses = courses,
        courseFilter = chrome.courseFilter,
        statusFilter = chrome.statusFilter,
        dayConflicts = CalendarSchedule.conflictLines(selectedSessions),
    )
}

private fun CalendarSession.toDraft(note: String?, confirm: Boolean) = SessionDraft(
    courseId = courseId,
    date = date.toString(),
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    sourceRuleId = sourceRuleId,
    note = note?.trim()?.ifBlank { null },
    confirmConflict = confirm,
    existingEventId = eventId,
)

private fun CalendarDialog.OneOff.toDraft(confirm: Boolean): SessionDraft? {
    val startMinutes = parseClock(start)
    val endMinutes = parseClock(end)
    if (startMinutes == null || endMinutes == null || endMinutes <= startMinutes) return null
    return SessionDraft(courseId, date.toString(), startMinutes, endMinutes, null, note.trim().ifBlank { null }, confirm)
}

internal fun parseClock(text: String): Int? {
    val match = Regex("(\\d{1,2}):(\\d{2})").matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun formatClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
fun CalendarScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    onOpenCourse: (String) -> Unit,
    onNotice: (String, String?, (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = viewModel(
        factory = CalendarViewModel.factory(repository, settings, attachments),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            onNotice(
                notice.message,
                notice.undoAttendanceId?.let { "撤销" },
                notice.undoAttendanceId?.let { id -> { viewModel.undo(id) } },
            )
        }
    }
    when (val current = state) {
        CalendarUiState.Loading -> LoadingPane(modifier)
        is CalendarUiState.Error -> MessagePane(current.message, "重试", viewModel::retry)
        is CalendarUiState.Ready -> CalendarBody(current.data, viewModel, onOpenCourse, modifier)
    }
    CalendarDialogs(dialog, viewModel)
    CheckInDialogLayer(
        controller = viewModel.checkIns,
        onSave = viewModel::saveEditor,
        onDelete = viewModel::deleteEditing,
        onConfirmDuplicate = viewModel::confirmDuplicate,
        onAttach = viewModel::attachPhoto,
    )
}

@Composable
private fun CalendarBody(
    data: CalendarReady,
    viewModel: CalendarViewModel,
    onOpenCourse: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = data.mode == CalendarMode.MONTH, onClick = { viewModel.setMode(CalendarMode.MONTH) }, label = { Text("月") })
            FilterChip(
                selected = data.mode == CalendarMode.WEEK,
                onClick = { viewModel.setMode(CalendarMode.WEEK) },
                label = { Text("周") },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.shift(-1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = if (data.mode == CalendarMode.MONTH) "上个月" else "上一周")
            }
            Text(
                if (data.mode == CalendarMode.MONTH) {
                    "${data.month.year}年${data.month.monthValue}月"
                } else {
                    "${data.weekStart.monthValue}月${data.weekStart.dayOfMonth}日 – ${data.weekStart.plusDays(6).monthValue}月${data.weekStart.plusDays(6).dayOfMonth}日"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { viewModel.shift(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = if (data.mode == CalendarMode.MONTH) "下个月" else "下一周")
            }
        }
        TextButton(onClick = viewModel::goToday, modifier = Modifier.fillMaxWidth()) { Text("回到今天") }
        FilterRow(data, viewModel)
        if (data.mode == CalendarMode.MONTH) {
            MonthGrid(data, onSelect = viewModel::select, onLongPress = viewModel::startOneOff)
        } else {
            data.weekDays.forEach { (date, sessions) ->
                Text(
                    formatChineseDate(date),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (date == data.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.combinedClickable(
                        onClick = { viewModel.select(date) },
                        onLongClick = { viewModel.startOneOff(date) },
                    ),
                )
                if (sessions.isEmpty()) {
                    Text("没有课程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    sessions.forEach { session ->
                        SessionCard(session, onClick = { viewModel.openActions(session) })
                    }
                }
            }
        }
        SectionTitle(formatChineseDate(data.selected))
        Text("长按日期可以新增一次单独安排。点一节课可以打卡、请假或调课。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data.dayConflicts.isNotEmpty()) {
            data.dayConflicts.forEach { line ->
                Text(line, color = MaterialTheme.colorScheme.error)
            }
        }
        if (data.selectedSessions.isEmpty()) {
            Text("这一天没有符合筛选的课程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            data.selectedSessions.forEach { session ->
                SessionCard(session, onClick = { viewModel.openActions(session) })
            }
        }
        val historyCourses = data.courses.filter { course ->
            course.historicalUnrecorded.isNotEmpty() &&
                (data.courseFilter == null || course.id == data.courseFilter)
        }
        if (historyCourses.isNotEmpty() && data.statusFilter == null) {
            SectionTitle("补录到这一天")
            historyCourses.forEach { course ->
                LucaOutlinedButton(
                    text = "把${course.name}的一条未补录记到这一天",
                    onClick = { viewModel.backfill(course, data.selected) },
                )
            }
        }
        LucaOutlinedButton(text = "这一天新增安排", onClick = { viewModel.startOneOff(data.selected) })
        val loose = data.courses.filter { it.status != CourseStatus.ARCHIVED && (it.scheduleMode.name != "WEEKLY" || it.rules.isEmpty()) }
        if (loose.isNotEmpty()) {
            SectionTitle("未排入每周课表")
            loose.forEach { course ->
                TextButton(onClick = { onOpenCourse(course.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${course.iconKey}  ${course.name}")
                }
            }
        }
    }
}

@Composable
private fun FilterRow(data: CalendarReady, viewModel: CalendarViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = data.courseFilter == null, onClick = { viewModel.filterCourse(null) }, label = { Text("全部课程") })
        data.courses.forEach { course ->
            FilterChip(
                selected = data.courseFilter == course.id,
                onClick = { viewModel.filterCourse(course.id) },
                label = { Text(course.name) },
            )
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = data.statusFilter == null, onClick = { viewModel.filterStatus(null) }, label = { Text("全部状态") })
        SessionVisual.entries.forEach { visual ->
            FilterChip(
                selected = data.statusFilter == visual,
                onClick = { viewModel.filterStatus(visual) },
                label = { Text(sessionVisualLabel(visual, occupiesTime = true)) },
            )
        }
    }
}

@Composable
private fun MonthGrid(
    data: CalendarReady,
    onSelect: (LocalDate) -> Unit,
    onLongPress: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            Text(
                label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val first = data.month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val rows = (leading + data.month.lengthOfMonth() + 6) / 7
    for (row in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (column in 0 until 7) {
                val dayNumber = row * 7 + column - leading + 1
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (dayNumber in 1..data.month.lengthOfMonth()) {
                        val date = data.month.atDay(dayNumber)
                        DayCell(
                            date = date,
                            selected = date == data.selected,
                            today = date == LocalDate.now(),
                            colors = data.dots[date].orEmpty(),
                            onClick = { onSelect(date) },
                            onLongClick = { onLongPress(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    colors: List<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(2.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            colors.take(3).forEach { hex ->
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(parseCourseColor(hex)),
                )
            }
        }
    }
}

@Composable
private fun SessionCard(session: CalendarSession, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CourseIcon(session.iconKey, parseCourseColor(session.colorHex))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.courseName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (session.endMinutes > session.startMinutes) {
                        formatMinuteRange(session.startMinutes, session.endMinutes)
                    } else {
                        "打卡未附上课时间"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(sessionVisualLabel(session.visual, session.occupiesTime), color = MaterialTheme.colorScheme.primary)
                session.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun CalendarDialogs(dialog: CalendarDialog, viewModel: CalendarViewModel) {
    when (val current = dialog) {
        CalendarDialog.None -> Unit
        is CalendarDialog.Actions -> ActionDialog(current.session, viewModel)
        is CalendarDialog.OneOff -> OneOffDialog(current, viewModel)
        is CalendarDialog.Leave -> LeaveDialog(current, viewModel)
        is CalendarDialog.Move -> MoveDialog(current, viewModel)
        is CalendarDialog.Conflict -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("时间有重叠") },
            text = { Text(current.lines.joinToString("\n") + "\n仍要保存这次安排吗？") },
            confirmButton = { TextButton(onClick = viewModel::confirmConflict) { Text("仍要保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("返回修改") } },
        )
    }
}

@Composable
private fun ActionDialog(session: CalendarSession, viewModel: CalendarViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("${session.courseName} · ${sessionVisualLabel(session.visual, session.occupiesTime)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.endMinutes > session.startMinutes) {
                    Text(formatMinuteRange(session.startMinutes, session.endMinutes))
                }
                session.rescheduledToEventId?.let { Text("这节课已经调到新的时间。") }
                session.rescheduledFromEventId?.let { Text("这是从原来的时间调过来的。") }
                session.note?.let { Text(it) }
                if (session.canCheckIn) {
                    LucaPrimaryButton(text = "打卡", onClick = { viewModel.checkIn(session) })
                    LucaOutlinedButton(text = "补录或备注", onClick = { viewModel.openDetailed(session) })
                    LucaOutlinedButton(text = "请假", onClick = { viewModel.startLeave(session) })
                    LucaOutlinedButton(text = "调课", onClick = { viewModel.startMove(session, makeup = false) })
                    LucaOutlinedButton(text = "取消这节课", onClick = { viewModel.cancelSession(session) })
                }
                if (session.visual == SessionVisual.CHECKED_IN) {
                    LucaPrimaryButton(text = "修改这节打卡", onClick = { viewModel.editCheckIn(session) })
                }
                if (session.visual == SessionVisual.LEAVE || session.visual == SessionVisual.CANCELLED) {
                    LucaPrimaryButton(text = "创建补课", onClick = { viewModel.startMove(session, makeup = true) })
                    LucaOutlinedButton(text = "恢复原计划", onClick = { viewModel.clearException(session.eventId) })
                }
                if (session.visual == SessionVisual.RESCHEDULED && !session.occupiesTime) {
                    LucaOutlinedButton(text = "撤销调课", onClick = { viewModel.undoReschedule(session.eventId) })
                }
                if (session.visual == SessionVisual.MAKEUP && session.canCheckIn) {
                    LucaOutlinedButton(text = "再添加一节补课", onClick = { viewModel.startMove(session, makeup = true) })
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissDialog) { Text("关闭") } },
    )
}

@Composable
private fun OneOffDialog(form: CalendarDialog.OneOff, viewModel: CalendarViewModel) {
    val courses = (viewModel.uiState.value as? CalendarUiState.Ready)?.data?.courses.orEmpty()
        .filter { it.status != CourseStatus.ARCHIVED }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("新增这次安排") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatChineseDate(form.date))
                courses.forEach { course ->
                    FilterChip(
                        selected = form.courseId == course.id,
                        onClick = { viewModel.updateOneOff { it.copy(courseId = course.id, error = null) } },
                        label = { Text(course.name) },
                    )
                }
                OutlinedTextField(form.start, { viewModel.updateOneOff { state -> state.copy(start = it, error = null) } }, label = { Text("开始") })
                OutlinedTextField(form.end, { viewModel.updateOneOff { state -> state.copy(end = it, error = null) } }, label = { Text("结束") })
                OutlinedTextField(form.note, { viewModel.updateOneOff { state -> state.copy(note = it.take(200), error = null) } }, label = { Text("备注") })
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveOneOff) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("取消") } },
    )
}

@Composable
private fun LeaveDialog(form: CalendarDialog.Leave, viewModel: CalendarViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("请假") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${form.session.courseName} 默认不扣课时。")
                OutlinedTextField(
                    form.note,
                    { viewModel.updateLeave { state -> state.copy(note = it.take(200)) } },
                    label = { Text("请假备注") },
                )
                FilterChip(
                    selected = form.deduct,
                    onClick = { viewModel.updateLeave { it.copy(deduct = !it.deduct) } },
                    label = { Text(if (form.deduct) "这节课扣课时" else "不扣课时") },
                )
                if (form.deduct) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.updateLeave { it.copy(units = (it.units - 1).coerceAtLeast(1)) } }) { Text("减少") }
                        Text(com.luca.classmanager.domain.logic.CreditUnits.format(form.units) + " 课时")
                        TextButton(onClick = { viewModel.updateLeave { it.copy(units = (it.units + 1).coerceAtMost(4)) } }) { Text("增加") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveLeave) { Text("保存请假") } },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("取消") } },
    )
}

@Composable
private fun MoveDialog(form: CalendarDialog.Move, viewModel: CalendarViewModel) {
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text(if (form.makeup) "创建补课" else "调课") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (form.makeup) "补课打卡后才扣课时。" else "调课会留下原计划和现在的关联，不会重复扣课时。")
                LucaOutlinedButton(text = formatChineseDate(form.date), onClick = { picking = true })
                OutlinedTextField(form.start, { viewModel.updateMove { state -> state.copy(start = it, error = null) } }, label = { Text("开始") })
                OutlinedTextField(form.end, { viewModel.updateMove { state -> state.copy(end = it, error = null) } }, label = { Text("结束") })
                OutlinedTextField(form.note, { viewModel.updateMove { state -> state.copy(note = it.take(200), error = null) } }, label = { Text("备注") })
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveMove) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("取消") } },
    )
    if (picking) {
        LucaDateDialog(
            initial = form.date,
            onDismiss = { picking = false },
            onConfirm = {
                viewModel.updateMove { state -> state.copy(date = it, error = null) }
                picking = false
            },
        )
    }
}
