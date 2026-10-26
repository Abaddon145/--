package com.luca.classmanager.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.logic.FeeAnalytics
import com.luca.classmanager.domain.logic.FeeReport
import com.luca.classmanager.domain.logic.GiftValuation
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.formatLessonsOrUnknown
import com.luca.classmanager.domain.logic.formatMoneyTotal
import com.luca.classmanager.domain.logic.FeeRollup
import com.luca.classmanager.domain.logic.giftValuationLabel
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.components.FeeSummaryCard
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import com.luca.classmanager.ui.theme.parseCourseColor
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class StatsReady(
    val report: FeeReport,
    val year: Int,
    val month: Int?,
    val courseId: String?,
    val courses: List<CourseDetails>,
    val gift: GiftValuation,
)

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Ready(val data: StatsReady) : StatsUiState
    data class Error(val message: String) : StatsUiState
}

class StatsViewModel(
    repository: CourseRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val year = MutableStateFlow(LocalDate.now().year)
    private val month = MutableStateFlow<Int?>(LocalDate.now().monthValue)
    private val courseId = MutableStateFlow<String?>(null)
    private val selection = combine(year, month, courseId) { selectedYear, selectedMonth, selectedCourse ->
        Triple(selectedYear, selectedMonth, selectedCourse)
    }

    val uiState: StateFlow<StatsUiState> = refresh.flatMapLatest {
        combine(
            repository.observeCourses(),
            settings.renewalThresholdCreditUnits,
            settings.giftValuation,
            selection,
        ) { courses, threshold, gift, selected ->
            val (selectedYear, selectedMonth, selectedCourse) = selected
            val visible = courses.filter { it.status != CourseStatus.ARCHIVED }.sortedBy { it.sortOrder }
            StatsUiState.Ready(
                StatsReady(
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
                    gift = gift,
                ),
            ) as StatsUiState
        }.catch { emit(StatsUiState.Error("统计数据暂时无法读取")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState.Loading)

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

    companion object {
        fun factory(repository: CourseRepository, settings: SettingsRepository): ViewModelProvider.Factory =
            simpleFactory { StatsViewModel(repository, settings) }
    }
}

@Composable
fun StatsScreen(
    repository: CourseRepository,
    settings: SettingsRepository,
    onOpenCourse: (String) -> Unit,
    onOpenFees: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(repository, settings))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        StatsUiState.Loading -> LoadingPane(modifier)
        is StatsUiState.Error -> MessagePane(current.message, "重试", viewModel::retry)
        is StatsUiState.Ready -> StatsBody(current.data, viewModel, onOpenCourse, onOpenFees, modifier)
    }
}

@Composable
private fun StatsBody(
    data: StatsReady,
    viewModel: StatsViewModel,
    onOpenCourse: (String) -> Unit,
    onOpenFees: () -> Unit,
    modifier: Modifier,
) {
    val report = data.report
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("统计", style = MaterialTheme.typography.headlineSmall)
        Text(
            "数字和柱状图都来自这台设备上的课程、打卡和缴费。${giftValuationLabel(data.gift)}。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前课程 ${report.courseCount} 门")
                Text("累计上课 ${report.checkIns} 次")
                Text("累计消耗 ${formatLessons(report.consumedUnits)}")
                Text("本月上课 ${report.thisMonthCheckIns} 次")
                Text("${periodLabel(data.year, data.month)}上课 ${report.selectedCheckIns} 次")
            }
        }
        SectionTitle("费用")
        FeeSummaryCard(
            FeeRollup(
                paid = report.paid,
                consumed = report.consumed,
                remainingValue = report.remainingValue,
            ),
        )
        Text("已归档课程未计入。未知金额显示为待补充。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${data.year}年累计费用 ${formatMoneyTotal(report.yearFees)}")
        Text("${periodLabel(data.year, data.month)}上课消耗 ${formatMoneyTotal(report.selectedConsumption)}")
        LucaPrimaryButton(text = "打开费用中心", onClick = onOpenFees)
        SectionTitle("即将续费")
        if (report.renewal.isEmpty()) {
            Text("现在没有课程低于续费线。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            report.renewal.forEach { row ->
                CourseStatCard(row, onOpenCourse)
            }
        }
        SectionTitle("各课程剩余课时")
        if (report.courses.isEmpty()) {
            Text("还没有进行中或暂停的课程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            report.courses.forEach { row ->
                CourseStatCard(row, onOpenCourse)
            }
        }
        SectionTitle("${data.year}年每月上课")
        MonthBarChart(report.attendanceByMonth, money = false)
        SectionTitle("${data.year}年费用消耗")
        Text("按先购买先消耗计算。赠送课时${if (data.gift == GiftValuation.ZERO) "计 0 元" else "沿用该套餐已知的购买单价"}。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        MonthBarChart(report.consumptionByMonth, money = true)
        SectionTitle("各年缴费")
        YearBarChart(report.feesByYear)
    }
}

@Composable
private fun CourseStatCard(row: com.luca.classmanager.domain.logic.CourseFeeRow, onOpenCourse: (String) -> Unit) {
    Card(
        onClick = { onOpenCourse(row.courseId) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${row.iconKey}  ${row.name}", style = MaterialTheme.typography.titleMedium, color = parseCourseColor(row.colorHex))
            Text("剩余 ${formatLessonsOrUnknown(row.remainingUnits)}")
            Text("已消耗 ${formatLessons(row.consumedUnits)}，上课 ${row.checkIns} 次")
            Text("费用 ${formatMoneyTotal(row.paid)}，已消耗 ${formatMoneyTotal(row.consumed)}")
            if (row.needsRenewal) {
                Text(renewalLabelFromRemaining(row), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun renewalLabelFromRemaining(row: com.luca.classmanager.domain.logic.CourseFeeRow): String {
    val remaining = row.remainingUnits
    return when {
        remaining == null -> "建议续费"
        remaining < 0 -> "已超出课时"
        remaining == 0 -> "课时已用完"
        else -> "建议续费"
    }
}

private fun periodLabel(year: Int, month: Int?): String {
    return if (month == null) "${year}年" else "${year}年${month}月"
}
