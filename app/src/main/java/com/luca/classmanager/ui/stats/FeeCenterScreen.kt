@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.stats

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.logic.AmountParser
import com.luca.classmanager.domain.logic.CourseFeeRow
import com.luca.classmanager.domain.logic.FeeAnalytics
import com.luca.classmanager.domain.logic.FeeReport
import com.luca.classmanager.domain.logic.GiftValuation
import com.luca.classmanager.domain.logic.ParseOutcome
import com.luca.classmanager.domain.logic.formatCents
import com.luca.classmanager.domain.logic.formatMoneyTotal
import com.luca.classmanager.domain.logic.giftValuationLabel
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.PaymentDraft
import com.luca.classmanager.domain.model.PaymentInfo
import com.luca.classmanager.domain.model.PaymentWriteResult
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.checkin.LucaDateDialog
import com.luca.classmanager.ui.components.FeeSummaryCard
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import com.luca.classmanager.ui.theme.parseCourseColor
import java.time.LocalDate
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

data class PaymentLine(
    val payment: PaymentInfo,
    val courseId: String,
    val courseName: String,
)

data class FeeReady(
    val report: FeeReport,
    val year: Int,
    val month: Int?,
    val courseId: String?,
    val courses: List<CourseDetails>,
    val payments: List<PaymentLine>,
    val gift: GiftValuation,
)

data class PaymentForm(
    val paymentId: String?,
    val courseId: String,
    val linked: Boolean,
    val amount: String,
    val paidOn: LocalDate?,
    val note: String,
    val error: String?,
)

sealed interface FeeUiState {
    data object Loading : FeeUiState
    data class Ready(val data: FeeReady) : FeeUiState
    data class Error(val message: String) : FeeUiState
}

class FeeCenterViewModel(
    private val repository: CourseRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val year = MutableStateFlow(LocalDate.now().year)
    private val month = MutableStateFlow<Int?>(LocalDate.now().monthValue)
    private val courseId = MutableStateFlow<String?>(null)
    private val selection = combine(year, month, courseId) { selectedYear, selectedMonth, selectedCourse ->
        Triple(selectedYear, selectedMonth, selectedCourse)
    }
    private val _form = MutableStateFlow<PaymentForm?>(null)
    val form: StateFlow<PaymentForm?> = _form
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    val uiState: StateFlow<FeeUiState> = refresh.flatMapLatest {
        combine(
            repository.observeCourses(),
            settings.renewalThresholdCreditUnits,
            settings.giftValuation,
            selection,
        ) { courses, threshold, gift, selected ->
            val (selectedYear, selectedMonth, selectedCourse) = selected
            val visible = courses.filter { it.status != CourseStatus.ARCHIVED }.sortedBy { it.sortOrder }
            val lines = visible
                .filter { selectedCourse == null || it.id == selectedCourse }
                .flatMap { course ->
                    course.payments.map { payment -> PaymentLine(payment, course.id, course.name) }
                }
                .sortedWith(compareByDescending<PaymentLine> { it.payment.paidOn ?: "" }.thenByDescending { it.payment.createdAtEpochMs })
            FeeUiState.Ready(
                FeeReady(
                    report = FeeAnalytics.report(
                        courses = courses,
                        today = LocalDate.now(),
                        year = selectedYear,
                        month = selectedMonth,
                        courseId = selectedCourse,
                        giftValuation = gift,
                        defaultRenewalUnits = threshold,
                    ),
                    year = selectedYear,
                    month = selectedMonth,
                    courseId = selectedCourse,
                    courses = visible,
                    payments = lines,
                    gift = gift,
                ),
            ) as FeeUiState
        }.catch { emit(FeeUiState.Error("费用数据暂时无法读取")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeeUiState.Loading)

    fun retry() {
        refresh.value += 1
    }

    fun selectCourse(id: String?) {
        courseId.value = id
    }

    fun selectYear(value: Int) {
        year.value = value
    }

    fun selectMonth(value: Int?) {
        month.value = value
    }

    fun startCreate() {
        val current = (uiState.value as? FeeUiState.Ready)?.data ?: return
        val course = current.courseId ?: current.courses.firstOrNull()?.id ?: return
        _form.value = PaymentForm(null, course, linked = false, amount = "", paidOn = LocalDate.now(), note = "", error = null)
    }

    fun startEdit(line: PaymentLine) {
        _form.value = PaymentForm(
            paymentId = line.payment.id,
            courseId = line.courseId,
            linked = line.payment.packageId != null,
            amount = line.payment.amountCents?.let(AmountParser::formatYuanInput).orEmpty(),
            paidOn = line.payment.paidOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            note = line.payment.note.orEmpty(),
            error = null,
        )
    }

    fun updateForm(block: (PaymentForm) -> PaymentForm) {
        val current = _form.value ?: return
        _form.value = block(current)
    }

    fun dismissForm() {
        _form.value = null
    }

    fun saveForm() {
        val current = _form.value ?: return
        val amount = AmountParser.parseYuanToCents(current.amount)
        if (amount is ParseOutcome.Invalid) {
            _form.value = current.copy(error = "金额请填写元，最多两位小数。留空表示待补充")
            return
        }
        viewModelScope.launch {
            val result = repository.savePayment(
                courseId = current.courseId,
                paymentId = current.paymentId,
                draft = PaymentDraft(
                    amountCents = (amount as? ParseOutcome.Value)?.value,
                    paidOn = current.paidOn?.toString(),
                    note = current.note,
                ),
            )
            when (result) {
                PaymentWriteResult.Saved -> {
                    _form.value = null
                    _messages.emit(if (current.linked) "已保存，套餐金额和这笔缴费保持一致" else "已保存这笔缴费")
                }
                PaymentWriteResult.InvalidDate -> _form.value = current.copy(error = "缴费日期无法识别")
                PaymentWriteResult.Missing -> _messages.emit("找不到这门课程或这笔缴费")
                PaymentWriteResult.LinkedToPackage -> Unit
            }
        }
    }

    fun delete(line: PaymentLine) {
        viewModelScope.launch {
            when (repository.deletePayment(line.payment.id)) {
                PaymentWriteResult.Saved -> _messages.emit("已删除这笔缴费")
                PaymentWriteResult.LinkedToPackage -> _messages.emit("这笔记账来自课时套餐，请到课程的课时套餐里删除，避免和套餐金额各算一遍")
                PaymentWriteResult.Missing -> _messages.emit("这笔缴费已经不在记录里")
                PaymentWriteResult.InvalidDate -> Unit
            }
        }
    }

    companion object {
        fun factory(repository: CourseRepository, settings: SettingsRepository): ViewModelProvider.Factory =
            simpleFactory { FeeCenterViewModel(repository, settings) }
    }
}

@Composable
fun FeeCenterScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: FeeCenterViewModel = viewModel(factory = FeeCenterViewModel.factory(repository, settings))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.messages.collect(onNotice)
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("费用中心") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        when (val current = state) {
            FeeUiState.Loading -> LoadingPane()
            is FeeUiState.Error -> MessagePane(current.message, "重试", viewModel::retry)
            is FeeUiState.Ready -> FeeBody(current.data, viewModel, onOpenCourse)
        }
    }
    form?.let { PaymentDialog(it, viewModel) }
}

@Composable
private fun FeeBody(data: FeeReady, viewModel: FeeCenterViewModel, onOpenCourse: (String) -> Unit) {
    val report = data.report
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(giftValuationLabel(data.gift) + "。套餐对应的缴费不会再加进累计缴费。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReportFilters(
            courses = data.courses,
            courseId = data.courseId,
            year = data.year,
            years = report.feesByYear.map { it.year }.ifEmpty { listOf(data.year) },
            month = data.month,
            onCourse = viewModel::selectCourse,
            onYear = viewModel::selectYear,
            onMonth = viewModel::selectMonth,
        )
        SectionTitle("费用总览")
        FeeSummaryCard(
            com.luca.classmanager.domain.logic.FeeRollup(report.paid, report.consumed, report.remainingValue),
        )
        Text("${data.year}年累计费用 ${formatMoneyTotal(report.yearFees)}")
        Text("${periodName(data.year, data.month)}上课消耗 ${formatMoneyTotal(report.selectedConsumption)}")
        Text("已归档课程未计入。金额留空会显示待补充。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("各课程费用排行")
        if (report.courses.isEmpty()) {
            Text("还没有进行中或暂停的课程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            RankingList(report.courses, onOpenCourse)
        }
        SectionTitle("${data.year}年按月缴费")
        MonthBarChart(report.feesByMonth, money = true)
        SectionTitle("${data.year}年按月消耗")
        MonthBarChart(report.consumptionByMonth, money = true)
        SectionTitle("按年缴费")
        YearBarChart(report.feesByYear)
        SectionTitle("续费与缴费记录")
        if (data.courses.isEmpty()) {
            Text("先添加一门课程，才能记缴费。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LucaPrimaryButton(text = "新增缴费", onClick = viewModel::startCreate)
        }
        if (data.payments.isEmpty()) {
            Text("还没有缴费记录。保存课时套餐后，这里会出现对应的一条，不会重复计入累计缴费。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            data.payments.forEach { line ->
                PaymentCard(line, onEdit = { viewModel.startEdit(line) }, onDelete = { viewModel.delete(line) })
            }
        }
    }
}

@Composable
private fun RankingList(rows: List<CourseFeeRow>, onOpenCourse: (String) -> Unit) {
    val max = rows.maxOf { it.paid.knownCents }.coerceAtLeast(1L)
    rows.forEach { row ->
        Card(
            onClick = { onOpenCourse(row.courseId) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, color = parseCourseColor(row.colorHex))
                Text("累计 ${formatMoneyTotal(row.paid)}")
                Text("已消耗 ${formatMoneyTotal(row.consumed)}，剩余价值 ${formatMoneyTotal(row.remainingValue)}")
                if (row.paid.complete || row.paid.knownCents > 0L) {
                    LinearProgressIndicator(
                        progress = { row.paid.knownCents.toFloat() / max.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentCard(line: PaymentLine, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(line.courseName, style = MaterialTheme.typography.titleMedium)
            Text(line.payment.amountCents?.let(::formatCents) ?: "金额待补充")
            Text(line.payment.paidOn?.let { "缴费日期 $it" } ?: "缴费日期未记录")
            Text(if (line.payment.packageId != null) "来自课时套餐，不重复计入累计缴费" else (line.payment.note ?: "额外缴费"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LucaOutlinedButton(text = "修改", onClick = onEdit, modifier = Modifier.weight(1f))
                LucaOutlinedButton(text = "删除", onClick = onDelete, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PaymentDialog(form: PaymentForm, viewModel: FeeCenterViewModel) {
    val courses = (viewModel.uiState.value as? FeeUiState.Ready)?.data?.courses.orEmpty()
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::dismissForm,
        title = { Text(if (form.paymentId == null) "新增缴费" else "修改缴费") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form.linked) {
                    Text("这笔记账对应课时套餐。保存后套餐金额会一起改，累计缴费仍然只算一次。")
                }
                if (form.paymentId == null) {
                    courses.forEach { course ->
                        androidx.compose.material3.FilterChip(
                            selected = form.courseId == course.id,
                            onClick = { viewModel.updateForm { it.copy(courseId = course.id, error = null) } },
                            label = { Text(course.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { viewModel.updateForm { state -> state.copy(amount = it.take(12), error = null) } },
                    label = { Text("金额（元）") },
                    supportingText = { Text("留空表示待补充，填 0 才是 0 元") },
                )
                LucaOutlinedButton(
                    text = form.paidOn?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "选择缴费日期",
                    onClick = { picking = true },
                )
                if (form.paidOn != null) {
                    TextButton(onClick = { viewModel.updateForm { it.copy(paidOn = null, error = null) } }) { Text("清除缴费日期") }
                }
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { viewModel.updateForm { state -> state.copy(note = it.take(200), error = null) } },
                    label = { Text("备注") },
                )
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveForm) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::dismissForm) { Text("取消") } },
    )
    if (picking) {
        LucaDateDialog(
            initial = form.paidOn ?: LocalDate.now(),
            onDismiss = { picking = false },
            onConfirm = { date ->
                viewModel.updateForm { it.copy(paidOn = date, error = null) }
                picking = false
            },
        )
    }
}

private fun periodName(year: Int, month: Int?): String = if (month == null) "${year}年" else "${year}年${month}月"
