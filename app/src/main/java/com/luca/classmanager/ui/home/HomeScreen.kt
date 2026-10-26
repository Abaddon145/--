package com.luca.classmanager.ui.home

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.domain.logic.HomeBoard
import com.luca.classmanager.domain.logic.HomeBoardBuilder
import com.luca.classmanager.domain.logic.NextSession
import com.luca.classmanager.domain.logic.TodaySession
import com.luca.classmanager.domain.logic.formatChineseDate
import com.luca.classmanager.domain.logic.formatMinuteRange
import com.luca.classmanager.domain.logic.relativeDayLabel
import com.luca.classmanager.domain.logic.sessionStatusLabel
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.checkin.CheckInController
import com.luca.classmanager.ui.checkin.CheckInDialogLayer
import com.luca.classmanager.ui.checkin.CheckInUiResult
import com.luca.classmanager.ui.components.CourseIcon
import com.luca.classmanager.ui.components.CourseSummaryCard
import com.luca.classmanager.ui.components.FeeSummaryCard
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.LucaTextButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.theme.parseCourseColor
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val board: HomeBoard, val renewalThresholdUnits: Int) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

class HomeViewModel(
    private val repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
) : ViewModel() {
    val checkIns = CheckInController(repository, attachments, settings)
    private val refresh = MutableStateFlow(0)
    private val _notices = MutableSharedFlow<CheckInUiResult.Notice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()
    private val clock = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    val uiState: StateFlow<HomeUiState> = refresh.flatMapLatest {
        combine(
            repository.observeCourses(),
            clock,
            settings.renewalThresholdCreditUnits,
            settings.giftValuation,
        ) { courses, now, threshold, gift ->
            HomeUiState.Ready(HomeBoardBuilder.build(courses, now, threshold, gift), threshold) as HomeUiState
        }.catch {
            emit(HomeUiState.Error("课程数据暂时无法读取，请重试"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun retry() {
        refresh.value += 1
    }

    fun quickCheckIn(courseId: String, courseName: String) = launchCheckIn { checkIns.quick(courseId, courseName) }

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
            HomeViewModel(repository, settings, attachments)
        }
    }
}

@Composable
fun HomeScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    onOpenSettings: () -> Unit,
    onOpenCourses: () -> Unit,
    onAddCourse: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onOpenFees: () -> Unit,
    onNotice: (String, String?, (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository, settings, attachments))
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
        HomeUiState.Loading -> LoadingPane(modifier.fillMaxSize())
        is HomeUiState.Error -> MessagePane(current.message, "重试", viewModel::retry)
        is HomeUiState.Ready -> HomeContent(
            board = current.board,
            renewalThresholdUnits = current.renewalThresholdUnits,
            onOpenSettings = onOpenSettings,
            onOpenCourses = onOpenCourses,
            onAddCourse = onAddCourse,
            onOpenCourse = onOpenCourse,
            onOpenFees = onOpenFees,
            onQuickCheckIn = viewModel::quickCheckIn,
            onDetailedCheckIn = viewModel.checkIns::openEditor,
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
private fun HomeContent(
    board: HomeBoard,
    renewalThresholdUnits: Int,
    onOpenSettings: () -> Unit,
    onOpenCourses: () -> Unit,
    onAddCourse: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onOpenFees: () -> Unit,
    onQuickCheckIn: (String, String) -> Unit,
    onDetailedCheckIn: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .animateContentSize()
            .padding(horizontal = PagePadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Luca 兴趣课堂", style = MaterialTheme.typography.headlineSmall)
                Text(
                    formatChineseDate(board.today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (board.todaySessionCount == 0) "今天没有课" else "今天 ${board.todaySessionCount} 节课",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        }

        val hasVisibleCourses = board.activeCourses.isNotEmpty() || board.pausedCourses.isNotEmpty()
        if (!hasVisibleCourses) {
            MessagePane(
                text = if (board.archivedCount > 0) "进行中的课程都已归档。" else "还没有兴趣班。先添加一门课程。",
                action = if (board.archivedCount > 0) "查看全部课程" else null,
                onAction = if (board.archivedCount > 0) onOpenCourses else null,
            )
        } else {
            FeeSummaryCard(board.fee)
            LucaTextButton(text = "查看费用中心", onClick = onOpenFees)
            if (board.archivedCount > 0) {
                Text(
                    "已归档课程未计入以上金额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionTitle("今日课程")
            if (board.todaySessions.isEmpty()) {
                Text(
                    "今天没有按每周课表安排的课程。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                board.todaySessions.forEach { session ->
                    SessionCard(
                        session = session,
                        onClick = { onOpenCourse(session.courseId) },
                        onCheckIn = { onQuickCheckIn(session.courseId, session.courseName) },
                        onDetails = { onDetailedCheckIn(session.courseId, session.courseName) },
                    )
                }
            }
            board.nextSession?.let { next ->
                SectionTitle("下次课程")
                NextCourseCard(next, board.today, onClick = { onOpenCourse(next.courseId) })
            }
            SectionTitle("我的兴趣班")
            board.activeCourses.forEach { course ->
                CourseSummaryCard(
                    course = course,
                    onClick = { onOpenCourse(course.id) },
                    renewalThresholdUnits = renewalThresholdUnits,
                )
            }
            if (board.pausedCourses.isNotEmpty()) {
                SectionTitle("已暂停")
                board.pausedCourses.forEach { course ->
                    CourseSummaryCard(
                        course = course,
                        onClick = { onOpenCourse(course.id) },
                        muted = true,
                        renewalThresholdUnits = renewalThresholdUnits,
                    )
                }
            }
        }
        LucaTextButton(
            text = if (board.archivedCount > 0) "全部课程（含 ${board.archivedCount} 门归档）" else "全部课程",
            onClick = onOpenCourses,
        )
        LucaPrimaryButton(text = "新增兴趣班", onClick = onAddCourse)
    }
}

@Composable
private fun SessionCard(
    session: TodaySession,
    onClick: () -> Unit,
    onCheckIn: () -> Unit,
    onDetails: () -> Unit,
) {
    val color = parseCourseColor(session.colorHex)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CourseIcon(session.iconKey, color)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(session.courseName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "今天 ${formatMinuteRange(session.startMinutes, session.endMinutes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(sessionStatusLabel(session.timeStatus, session.checkedIn))
                }
            }
            LucaPrimaryButton(
                text = if (session.checkedIn) "再记一次" else "打卡",
                onClick = onCheckIn,
            )
            LucaOutlinedButton(text = "补录或备注", onClick = onDetails)
        }
    }
}

@Composable
private fun NextCourseCard(session: NextSession, today: java.time.LocalDate, onClick: () -> Unit) {
    val color = parseCourseColor(session.colorHex)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CourseIcon(session.iconKey, color)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(session.courseName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${relativeDayLabel(today, session.date)} ${formatMinuteRange(session.startMinutes, session.endMinutes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun <T : ViewModel> simpleFactory(create: () -> T): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
}
