@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.course

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.luca.classmanager.domain.logic.BackfillPlanner
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.formatLessonsOrUnknown
import com.luca.classmanager.domain.logic.metrics
import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.HistoricalCredit
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.checkin.CheckInController
import com.luca.classmanager.ui.checkin.CheckInDialogLayer
import com.luca.classmanager.ui.checkin.CheckInUiResult
import com.luca.classmanager.ui.checkin.LucaDateDialog
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class AttendancePage(
    val loading: Boolean = true,
    val course: CourseDetails? = null,
    val threshold: Int = 6,
)

internal class AttendanceViewModel(
    private val repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    private val courseId: String,
) : ViewModel() {
    val checkIns = CheckInController(repository, attachments, settings)
    private val _notices = MutableSharedFlow<CheckInUiResult.Notice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()

    val course = combine(repository.observeCourses(), settings.renewalThresholdCreditUnits) { courses, threshold ->
        AttendancePage(
            loading = false,
            course = courses.firstOrNull { it.id == courseId },
            threshold = threshold,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttendancePage())

    fun quick() {
        val course = course.value.course ?: return
        launchCheckIn { checkIns.quick(course.id, course.name) }
    }

    fun edit(record: AttendanceInfo) {
        val course = course.value.course ?: return
        checkIns.openEdit(course.id, course.name, record)
    }

    fun backfillOne(historical: HistoricalCredit) {
        val course = course.value.course ?: return
        checkIns.openHistorical(course.id, course.name, historical)
    }

    fun saveEditor() = launchCheckIn { checkIns.save() }
    fun deleteEditing() = launchCheckIn { checkIns.deleteEditing() }
    fun confirmDuplicate() = launchCheckIn { checkIns.confirmQuickDuplicate() }
    fun attachPhoto(uri: Uri) {
        viewModelScope.launch { checkIns.importPhoto(uri) }
    }

    fun batch(start: LocalDate) {
        viewModelScope.launch {
            val count = repository.backfillHistorical(courseId, start.toString())
            _notices.emit(
                CheckInUiResult.Notice(
                    if (count == 0) "没有可补录的历史记录" else "已补录 $count 条，已消耗课时没有重复增加",
                    undoAttendanceId = null,
                ),
            )
        }
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
            courseId: String,
        ): ViewModelProvider.Factory = simpleFactory {
            AttendanceViewModel(repository, settings, attachments, courseId)
        }
    }
}

@Composable
fun AttendanceScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    courseId: String,
    onBack: () -> Unit,
    onNotice: (String, String?, (() -> Unit)?) -> Unit,
) {
    val viewModel: AttendanceViewModel = viewModel(
        key = courseId,
        factory = AttendanceViewModel.factory(repository, settings, attachments, courseId),
    )
    val state by viewModel.course.collectAsStateWithLifecycle()
    val course = state.course
    var batchDate by remember { mutableStateOf(LocalDate.now()) }
    var pickingBatch by remember { mutableStateOf(false) }
    var confirmBatch by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            onNotice(
                notice.message,
                notice.undoAttendanceId?.let { "撤销" },
                notice.undoAttendanceId?.let { id -> { viewModel.undo(id) } },
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        LedgerTopBar(title = "打卡记录", onBack = onBack)
        when {
            state.loading -> LoadingPane()
            course == null -> MessagePane("找不到这门课程", "返回", onBack)
            else -> AttendanceBody(
                course = course,
                threshold = state.threshold,
                batchDate = batchDate,
                onQuick = viewModel::quick,
                onEdit = viewModel::edit,
                onBackfillOne = viewModel::backfillOne,
                onPickBatch = { pickingBatch = true },
                onAskBatch = { confirmBatch = true },
            )
        }
    }
    CheckInDialogLayer(
        controller = viewModel.checkIns,
        onSave = viewModel::saveEditor,
        onDelete = viewModel::deleteEditing,
        onConfirmDuplicate = viewModel::confirmDuplicate,
        onAttach = viewModel::attachPhoto,
    )
    if (pickingBatch) {
        LucaDateDialog(
            initial = batchDate,
            onDismiss = { pickingBatch = false },
            onConfirm = {
                batchDate = it
                pickingBatch = false
            },
        )
    }
    if (confirmBatch && course != null) {
        val plan = BackfillPlanner.plan(
            openHistoricalIdsInOrder = course.historicalUnrecorded.map { it.id },
            start = batchDate,
            occupiedDates = course.recordedAttendanceDates.toSet(),
        )
        AlertDialog(
            onDismissRequest = { confirmBatch = false },
            title = { Text("批量补录") },
            text = {
                Text(
                    if (plan.isEmpty()) {
                        "没有未补录的历史记录。"
                    } else {
                        "将把 ${plan.size} 条没有日期的记录，从 $batchDate 起往前写成打卡。已经有打卡的日期会跳过。补录不会把已消耗课时再加一次。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBatch = false
                        viewModel.batch(batchDate)
                    },
                    enabled = plan.isNotEmpty(),
                ) { Text("开始补录") }
            },
            dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AttendanceBody(
    course: CourseDetails,
    threshold: Int,
    batchDate: LocalDate,
    onQuick: () -> Unit,
    onEdit: (AttendanceInfo) -> Unit,
    onBackfillOne: (HistoricalCredit) -> Unit,
    onPickBatch: () -> Unit,
    onAskBatch: () -> Unit,
) {
    val metrics = course.metrics(threshold)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(course.name, style = MaterialTheme.typography.titleMedium)
        Text("已消耗 ${formatLessons(metrics.consumedCreditUnits)}")
        Text("剩余 ${formatLessonsOrUnknown(metrics.remainingCreditUnits)}")
        LucaPrimaryButton(text = "打卡（今天 1 课时）", onClick = onQuick)
        if (course.historicalUnrecorded.isNotEmpty()) {
            SectionTitle("还没有日期的历史记录")
            Text(
                "这些次数已经算进已消耗课时。补上日期后仍只算一次。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            course.historicalUnrecorded.forEach { row ->
                Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${formatLessons(row.creditUnits)} · 日期待补")
                        LucaOutlinedButton(text = "补上日期", onClick = { onBackfillOne(row) })
                    }
                }
            }
            LucaOutlinedButton(text = "批量起始日 $batchDate", onClick = onPickBatch)
            LucaPrimaryButton(text = "从这一天往前批量补录", onClick = onAskBatch)
        }
        SectionTitle("已记录的打卡")
        if (course.attendances.isEmpty()) {
            Text("还没有带日期的打卡。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            course.attendances.forEach { record ->
                Card(
                    onClick = { onEdit(record) },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.attendanceDate} · ${formatLessons(record.creditUnits)}", style = MaterialTheme.typography.titleMedium)
                        Text(record.note ?: "无备注", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.historicalSourceId != null) {
                            Text("由未补录历史转来", style = MaterialTheme.typography.bodySmall)
                        }
                        if (record.photoPath != null) {
                            Text("有 1 张照片", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("点按可改日期、课时，或删除", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
