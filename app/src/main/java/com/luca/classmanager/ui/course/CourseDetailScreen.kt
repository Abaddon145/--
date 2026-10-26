@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.courseStatusLabel
import com.luca.classmanager.domain.logic.formatCents
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.formatLessonsOrUnknown
import com.luca.classmanager.domain.logic.formatMoneyTotal
import com.luca.classmanager.domain.logic.metrics
import com.luca.classmanager.domain.logic.scheduleModeLabel
import com.luca.classmanager.domain.logic.scheduleSummary
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CoursePackageInfo
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.DeletionImpact
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.checkin.CheckInController
import com.luca.classmanager.ui.checkin.CheckInDialogLayer
import com.luca.classmanager.ui.checkin.CheckInUiResult
import com.luca.classmanager.ui.components.ArchiveCourseDialog
import com.luca.classmanager.ui.components.CourseIcon
import com.luca.classmanager.ui.components.LessonLines
import com.luca.classmanager.ui.components.LessonProgress
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.LucaTextButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.PermanentDeleteDialog
import com.luca.classmanager.ui.home.simpleFactory
import com.luca.classmanager.ui.theme.parseCourseColor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUi(
    val loading: Boolean = true,
    val course: CourseDetails? = null,
    val error: String? = null,
    val confirmArchive: Boolean = false,
    val deleteImpact: DeletionImpact? = null,
)

class CourseDetailViewModel(
    private val repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    private val courseId: String,
) : ViewModel() {
    val checkIns = CheckInController(repository, attachments, settings)
    val renewalThreshold = settings.renewalThresholdCreditUnits.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CreditUnits.DEFAULT_RENEWAL_UNITS,
    )
    val giftValuation = settings.giftValuation.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        com.luca.classmanager.domain.logic.GiftValuation.ZERO,
    )
    private val refresh = MutableStateFlow(0)
    private val dialogs = MutableStateFlow(DialogFlags())
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()
    private val _notices = MutableSharedFlow<CheckInUiResult.Notice>(extraBufferCapacity = 1)
    val notices = _notices.asSharedFlow()
    private val _thresholdDialog = MutableStateFlow(false)
    val thresholdDialog = _thresholdDialog.asStateFlow()
    private val _customThreshold = MutableStateFlow(CreditUnits.DEFAULT_RENEWAL_UNITS)
    val customThreshold = _customThreshold.asStateFlow()

    val uiState: StateFlow<DetailUi> = refresh.flatMapLatest {
        repository.observeCourses()
            .map { courses -> courses.firstOrNull { it.id == courseId } }
            .map { course -> DetailUi(loading = false, course = course) }
            .catch { emit(DetailUi(loading = false, error = "课程详情暂时无法读取")) }
    }.combineDialogs(dialogs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUi())

    fun retry() {
        refresh.value += 1
    }

    fun requestArchive() {
        dialogs.value = dialogs.value.copy(confirmArchive = true)
    }

    fun dismissArchive() {
        dialogs.value = dialogs.value.copy(confirmArchive = false)
    }

    fun requestDelete() {
        viewModelScope.launch {
            dialogs.value = dialogs.value.copy(deleteImpact = repository.deletionImpact(courseId))
        }
    }

    fun dismissDelete() {
        dialogs.value = dialogs.value.copy(deleteImpact = null)
    }

    fun archive() {
        dialogs.value = DialogFlags()
        viewModelScope.launch {
            repository.archive(courseId)
            _messages.emit("已归档，历史记录仍保留")
        }
    }

    fun restore() {
        viewModelScope.launch {
            repository.restore(courseId)
            _messages.emit("已取消归档")
        }
    }

    fun pause() {
        viewModelScope.launch {
            repository.pause(courseId)
            _messages.emit("已暂停，不再出现在今日安排里")
        }
    }

    fun resume() {
        viewModelScope.launch {
            repository.resume(courseId)
            _messages.emit("已恢复上课")
        }
    }

    fun delete(onDeleted: () -> Unit) {
        dialogs.value = DialogFlags()
        viewModelScope.launch {
            repository.deletePermanently(courseId)
            _messages.emit("已永久删除")
            onDeleted()
        }
    }

    fun quickCheckIn() {
        val course = uiState.value.course ?: return
        launchCheckIn { checkIns.quick(course.id, course.name) }
    }

    fun openEditor() {
        val course = uiState.value.course ?: return
        checkIns.openEditor(course.id, course.name)
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

    fun openThreshold(current: Int?) {
        _customThreshold.value = current ?: renewalThreshold.value
        _thresholdDialog.value = true
    }

    fun setCustomThreshold(units: Int) {
        _customThreshold.value = units.coerceIn(1, 60)
    }

    fun dismissThreshold() {
        _thresholdDialog.value = false
    }

    fun saveThreshold(useGlobal: Boolean) {
        viewModelScope.launch {
            repository.setRenewalThreshold(courseId, if (useGlobal) null else _customThreshold.value)
            _thresholdDialog.value = false
            _messages.emit(if (useGlobal) "已改回全局续费提醒" else "已单独设置这门课的续费提醒")
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
            CourseDetailViewModel(repository, settings, attachments, courseId)
        }
    }
}

private data class DialogFlags(
    val confirmArchive: Boolean = false,
    val deleteImpact: DeletionImpact? = null,
)

private fun kotlinx.coroutines.flow.Flow<DetailUi>.combineDialogs(
    dialogs: StateFlow<DialogFlags>,
): kotlinx.coroutines.flow.Flow<DetailUi> {
    return kotlinx.coroutines.flow.combine(this, dialogs) { base, flags ->
        base.copy(confirmArchive = flags.confirmArchive, deleteImpact = flags.deleteImpact)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    attachments: AttachmentStore,
    courseId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenAdjustments: () -> Unit,
    onOpenAttendance: () -> Unit,
    onNotice: (String, String?, (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CourseDetailViewModel = viewModel(
        key = courseId,
        factory = CourseDetailViewModel.factory(repository, settings, attachments, courseId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val threshold by viewModel.renewalThreshold.collectAsStateWithLifecycle()
    val gift by viewModel.giftValuation.collectAsStateWithLifecycle()
    val showThreshold by viewModel.thresholdDialog.collectAsStateWithLifecycle()
    val customThreshold by viewModel.customThreshold.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { onNotice(it, null, null) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            onNotice(
                notice.message,
                notice.undoAttendanceId?.let { "撤销" },
                notice.undoAttendanceId?.let { id -> { viewModel.undo(id) } },
            )
        }
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.course?.name ?: "课程详情") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        when {
            state.loading -> LoadingPane()
            state.error != null -> MessagePane(state.error ?: "", "重试", viewModel::retry)
            state.course == null -> MessagePane("找不到这门课程", "返回", onBack)
            else -> DetailBody(
                course = state.course!!,
                threshold = threshold,
                gift = gift,
                viewModel = viewModel,
                onEdit = onEdit,
                onOpenPackages = onOpenPackages,
                onOpenAdjustments = onOpenAdjustments,
                onOpenAttendance = onOpenAttendance,
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
    if (showThreshold) {
        ThresholdDialog(
            units = customThreshold,
            onUnits = viewModel::setCustomThreshold,
            onUseGlobal = { viewModel.saveThreshold(useGlobal = true) },
            onSave = { viewModel.saveThreshold(useGlobal = false) },
            onDismiss = viewModel::dismissThreshold,
        )
    }
    if (state.confirmArchive && state.course != null) {
        ArchiveCourseDialog(
            courseName = state.course!!.name,
            onDismiss = viewModel::dismissArchive,
            onConfirm = viewModel::archive,
        )
    }
    state.deleteImpact?.let { impact ->
        PermanentDeleteDialog(
            impact = impact,
            onDismiss = viewModel::dismissDelete,
            onArchiveInstead = if (impact.archived) null else viewModel::archive,
            onConfirmDelete = { viewModel.delete(onDeleted) },
        )
    }
}

@Composable
private fun DetailBody(
    course: CourseDetails,
    threshold: Int,
    gift: com.luca.classmanager.domain.logic.GiftValuation,
    viewModel: CourseDetailViewModel,
    onEdit: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenAdjustments: () -> Unit,
    onOpenAttendance: () -> Unit,
) {
    val metrics = course.metrics(threshold, gift)
    val color = parseCourseColor(course.colorHex)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CourseIcon(course.iconKey, color)
            Column(Modifier.padding(start = 12.dp)) {
                Text(course.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${courseStatusLabel(course.status)} · ${scheduleModeLabel(course.scheduleMode)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (course.status == CourseStatus.ARCHIVED) {
            Text("这门课程已归档，不会出现在首页。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上课时间", style = MaterialTheme.typography.titleMedium)
                Text(scheduleSummary(course.scheduleMode, course.rules))
                course.institution?.let { InfoLine("培训机构", it) }
                course.location?.let { InfoLine("上课地点", it) }
                course.notes?.let { InfoLine("备注", it) }
                if (course.institution == null && course.location == null && course.notes == null) {
                    Text("机构、地点和备注可以稍后在编辑页补充。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("课时与费用", style = MaterialTheme.typography.titleMedium)
                LessonLines(metrics)
                LessonProgress(metrics, color)
                Text("已打卡 ${formatLessons(course.attendanceCreditUnits)}")
                Text(
                    if (metrics.unrecordedSessionCount == 0) {
                        "没有待补录的历史记录"
                    } else {
                        "历史未补录 ${metrics.unrecordedSessionCount} 次，合计 ${formatLessons(course.historicalUnrecorded.sumOf { it.creditUnits })}，这些记录没有具体日期"
                    },
                )
                Text("累计缴费 ${formatMoneyTotal(metrics.paid)}")
                Text("已消耗金额 ${formatMoneyTotal(metrics.consumedMoney)}")
                Text("剩余课时价值 ${formatMoneyTotal(metrics.remainingValue)}")
                if (course.packages.isEmpty()) {
                    Text("还没有课时套餐。未知课时和费用会显示为待补充，不会按 0 计算。")
                } else {
                    course.packages.forEach { pkg -> PackageBlock(pkg) }
                }
                val thresholdUnits = course.renewalThresholdCreditUnits ?: threshold
                Text(
                    "续费提醒：剩余低于 ${com.luca.classmanager.domain.logic.formatLessons(thresholdUnits)} 时提示" +
                        if (course.renewalThresholdCreditUnits == null) "（跟随全局）" else "（本课程单独设置）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LucaPrimaryButton(text = "打卡", onClick = viewModel::quickCheckIn)
        LucaOutlinedButton(text = "补录或备注", onClick = viewModel::openEditor)
        LucaOutlinedButton(text = "打卡记录", onClick = onOpenAttendance)
        LucaOutlinedButton(text = "课时套餐", onClick = onOpenPackages)
        LucaOutlinedButton(text = "课时调整", onClick = onOpenAdjustments)
        LucaOutlinedButton(text = "续费提醒", onClick = { viewModel.openThreshold(course.renewalThresholdCreditUnits) })
        LucaPrimaryButton(text = "编辑课程", onClick = onEdit)
        when (course.status) {
            CourseStatus.ACTIVE -> LucaOutlinedButton(text = "暂停课程", onClick = viewModel::pause)
            CourseStatus.PAUSED -> LucaOutlinedButton(text = "恢复上课", onClick = viewModel::resume)
            CourseStatus.ARCHIVED -> LucaOutlinedButton(text = "恢复课程", onClick = viewModel::restore)
        }
        if (course.status != CourseStatus.ARCHIVED) {
            LucaOutlinedButton(text = "归档课程", onClick = viewModel::requestArchive)
        }
        LucaTextButton(
            text = "永久删除",
            onClick = viewModel::requestDelete,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun ThresholdDialog(
    units: Int,
    onUnits: (Int) -> Unit,
    onUseGlobal: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("续费提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("剩余课时低于这个数字时，首页和课程里会提示续费。全局默认是 3 课时。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onUnits(units - 1) }, enabled = units > 1) { Text("减少") }
                    Text(com.luca.classmanager.domain.logic.formatLessons(units), modifier = Modifier.padding(horizontal = 8.dp))
                    TextButton(onClick = { onUnits(units + 1) }, enabled = units < 60) { Text("增加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("只用于这门课") } },
        dismissButton = {
            Column {
                TextButton(onClick = onUseGlobal) { Text("改回全局") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PackageBlock(pkg: CoursePackageInfo) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "套餐",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("购买课时 ${formatLessonsOrUnknown(pkg.purchasedCreditUnits)}")
        if (pkg.giftCreditUnits > 0) {
            Text("赠送 ${formatLessons(pkg.giftCreditUnits)}")
        }
        Text("缴费金额 ${pkg.amountCents?.let(::formatCents) ?: "待补充"}")
        Text("单价 ${pkg.unitPriceCents?.let { "${formatCents(it)}/课时" } ?: "待补充"}")
        Text(pkg.paidOn?.let { "缴费日期 $it" } ?: "缴费日期未记录")
    }
}
