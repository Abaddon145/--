package com.luca.classmanager.ui.checkin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.HomeBoard
import com.luca.classmanager.domain.logic.HomeBoardBuilder
import com.luca.classmanager.domain.logic.TodaySession
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.formatLessonsOrUnknown
import com.luca.classmanager.domain.logic.formatMinuteRange
import com.luca.classmanager.domain.logic.metrics
import com.luca.classmanager.domain.logic.renewalLabel
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.LucaTextButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CheckInReady(
    val board: HomeBoard,
    val courses: List<CourseDetails>,
    val renewalThresholdUnits: Int,
)

sealed interface CheckInUiState {
    data object Loading : CheckInUiState
    data class Ready(val data: CheckInReady) : CheckInUiState
    data class Error(val message: String) : CheckInUiState
}

class CheckInViewModel(
    private val repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
) : ViewModel() {
    val checkIns = CheckInController(repository, attachments, settings)
    private val refresh = MutableStateFlow(0)
    private val _notices = MutableSharedFlow<CheckInUiResult.Notice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()

    val uiState: StateFlow<CheckInUiState> = refresh.flatMapLatest {
        combine(
            repository.observeCourses(),
            settings.renewalThresholdCreditUnits,
            settings.giftValuation,
        ) { courses, threshold, gift ->
            CheckInUiState.Ready(
                CheckInReady(
                    board = HomeBoardBuilder.build(courses, LocalDateTime.now(), threshold, gift),
                    courses = courses.filter { it.status != CourseStatus.ARCHIVED }.sortedBy { it.sortOrder },
                    renewalThresholdUnits = threshold,
                ),
            ) as CheckInUiState
        }.catch { emit(CheckInUiState.Error("打卡数据暂时无法读取")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckInUiState.Loading)

    fun retry() {
        refresh.value += 1
    }

    fun quick(courseId: String, courseName: String) = launchCheckIn { checkIns.quick(courseId, courseName) }

    fun confirmDuplicate() = launchCheckIn { checkIns.confirmQuickDuplicate() }

    fun saveEditor() = launchCheckIn { checkIns.save() }

    fun deleteEditing() = launchCheckIn { checkIns.deleteEditing() }

    fun attachPhoto(uri: Uri) {
        viewModelScope.launch { checkIns.importPhoto(uri) }
    }

    fun undo(attendanceId: String) {
        viewModelScope.launch {
            val removed = repository.deleteAttendance(attendanceId)
            _notices.emit(
                CheckInUiResult.Notice(
                    if (removed) "已撤销这次打卡，课时和费用已按记录恢复" else "这次打卡已经不在记录里",
                    undoAttendanceId = null,
                ),
            )
        }
    }

    private fun launchCheckIn(block: suspend () -> CheckInUiResult) {
        viewModelScope.launch {
            val result = block()
            if (result is CheckInUiResult.Notice) _notices.emit(result)
        }
    }

    companion object {
        fun factory(
            repository: CourseRepository,
            settings: SettingsRepository,
            attachments: AttachmentStore,
        ): ViewModelProvider.Factory = simpleFactory {
            CheckInViewModel(repository, settings, attachments)
        }
    }
}

@Composable
fun CheckInScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    onOpenCourse: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onNotice: (String, String?, (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CheckInViewModel = viewModel(
        factory = CheckInViewModel.factory(repository, settings, attachments),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
        CheckInUiState.Loading -> LoadingPane(modifier)
        is CheckInUiState.Error -> MessagePane(current.message, "重试", viewModel::retry)
        is CheckInUiState.Ready -> CheckInBody(
            data = current.data,
            onOpenCourse = onOpenCourse,
            onOpenHistory = onOpenHistory,
            onQuick = viewModel::quick,
            onEditor = viewModel.checkIns::openEditor,
            modifier = modifier,
        )
    }
    CheckInDialogLayer(
        controller = viewModel.checkIns,
        onSave = viewModel::saveEditor,
        onDelete = viewModel::deleteEditing,
        onConfirmDuplicate = viewModel::confirmDuplicate,
        onAttach = viewModel::attachPhoto,
    )
}

@Composable
private fun CheckInBody(
    data: CheckInReady,
    onOpenCourse: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onQuick: (String, String) -> Unit,
    onEditor: (String, String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("打卡", style = MaterialTheme.typography.headlineSmall)
        Text(
            "一键打卡记今天、扣 1 课时。补录可以改日期、课时、备注和照片。同一天再打会先提醒。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle("今日安排")
        if (data.board.todaySessions.isEmpty()) {
            Text("今天没有按每周课表安排的课程。下面仍可以给任意课程打卡或补录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            data.board.todaySessions.forEach { session ->
                TodayCheckInCard(session, onQuick, onEditor)
            }
        }
        SectionTitle("全部课程")
        if (data.courses.isEmpty()) {
            Text("还没有可打卡的课程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            data.courses.forEach { course ->
                val metrics = course.metrics(data.renewalThresholdUnits)
                Card(
                    onClick = { onOpenCourse(course.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${course.iconKey}  ${course.name}", style = MaterialTheme.typography.titleMedium)
                        Text("已消耗 ${formatLessons(metrics.consumedCreditUnits)}")
                        Text("剩余 ${formatLessonsOrUnknown(metrics.remainingCreditUnits)}")
                        renewalLabel(metrics)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(
                            if (metrics.unrecordedSessionCount == 0) "没有未补录的历史记录"
                            else "历史未补录 ${metrics.unrecordedSessionCount} 次，点记录里可以补上日期",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LucaPrimaryButton(
                                text = "打卡",
                                onClick = { onQuick(course.id, course.name) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        LucaOutlinedButton(text = "补录或备注", onClick = { onEditor(course.id, course.name) })
                        LucaTextButton(text = "打卡记录", onClick = { onOpenHistory(course.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCheckInCard(
    session: TodaySession,
    onQuick: (String, String) -> Unit,
    onEditor: (String, String) -> Unit,
) {
    val cardColor by animateColorAsState(
        targetValue = if (session.checkedIn) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(280),
        label = "check-in",
    )
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.courseName, style = MaterialTheme.typography.titleMedium)
            Text(formatMinuteRange(session.startMinutes, session.endMinutes))
            Text(if (session.checkedIn) "今天已经有打卡记录" else "今天还没有打卡")
            LucaPrimaryButton(
                text = if (session.checkedIn) "再记一次" else "打卡",
                onClick = { onQuick(session.courseId, session.courseName) },
            )
            LucaOutlinedButton(
                text = "补录或备注",
                onClick = { onEditor(session.courseId, session.courseName) },
            )
        }
    }
}

@Composable
internal fun CheckInDialogLayer(
    controller: CheckInController,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDuplicate: () -> Unit,
    onAttach: (Uri) -> Unit,
) {
    val editor by controller.editor.collectAsStateWithLifecycle()
    val duplicate by controller.duplicate.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAttach(uri)
    }
    editor?.let { current ->
        CheckInEditorDialog(
            editor = current,
            showDatePicker = showDate,
            onPickDate = { showDate = true },
            onDatePicked = {
                controller.setDate(it)
                showDate = false
            },
            onDismissDate = { showDate = false },
            onUnits = controller::setUnits,
            onNote = controller::setNote,
            onPickPhoto = { picker.launch("image/*") },
            onClearPhoto = controller::clearPhoto,
            onDismiss = {
                showDate = false
                controller.dismissEditor()
            },
            onSave = onSave,
            onDelete = onDelete,
        )
    }
    duplicate?.let { prompt ->
        DuplicateCheckInDialog(
            prompt = prompt,
            onDismiss = controller::dismissDuplicate,
            onConfirm = onConfirmDuplicate,
        )
    }
}
