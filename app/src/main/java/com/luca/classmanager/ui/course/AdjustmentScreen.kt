@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.model.CreditAdjustmentInfo
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.LucaTextButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AdjustmentUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val courseName: String = "",
    val deltaUnits: Int = CreditUnits.PER_LESSON,
    val reason: String = "",
    val error: String? = null,
    val adjustments: List<CreditAdjustmentInfo> = emptyList(),
)

class AdjustmentViewModel(
    private val repository: CourseRepository,
    private val courseId: String,
) : ViewModel() {
    private val draft = MutableStateFlow(AdjustmentDraft())
    val uiState = draft.flatMapLatest { current ->
        repository.observeCourses().map { courses ->
            val course = courses.firstOrNull { it.id == courseId }
            if (course == null) {
                AdjustmentUi(loading = false, missing = true)
            } else {
                AdjustmentUi(
                    loading = false,
                    courseName = course.name,
                    deltaUnits = current.deltaUnits,
                    reason = current.reason,
                    error = current.error,
                    adjustments = course.adjustments,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdjustmentUi())

    fun setDelta(units: Int) {
        draft.value = draft.value.copy(deltaUnits = units.coerceIn(-40, 40), error = null)
    }

    fun setReason(value: String) {
        draft.value = draft.value.copy(reason = value.take(200), error = null)
    }

    fun save() {
        val current = draft.value
        if (current.deltaUnits == 0) {
            draft.value = current.copy(error = "调整课时不能为 0")
            return
        }
        viewModelScope.launch {
            val id = repository.addAdjustment(courseId, current.deltaUnits, current.reason)
            if (id == null) {
                draft.value = current.copy(error = "没有保存成功")
            } else {
                draft.value = AdjustmentDraft()
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteAdjustment(id) }
    }

    companion object {
        fun factory(repository: CourseRepository, courseId: String): ViewModelProvider.Factory = simpleFactory {
            AdjustmentViewModel(repository, courseId)
        }
    }
}

private data class AdjustmentDraft(
    val deltaUnits: Int = CreditUnits.PER_LESSON,
    val reason: String = "",
    val error: String? = null,
)

@Composable
fun AdjustmentScreen(
    repository: CourseRepository,
    courseId: String,
    onBack: () -> Unit,
) {
    val viewModel: AdjustmentViewModel = viewModel(
        key = courseId,
        factory = AdjustmentViewModel.factory(repository, courseId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        LedgerTopBar(title = "课时调整", onBack = onBack)
        when {
            state.loading -> LoadingPane()
            state.missing -> MessagePane("找不到这门课程", "返回", onBack)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.courseName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "手动增加或减少可用课时。正数是补回，负数是扣掉。调整不会改购买金额。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("调整", modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.setDelta(state.deltaUnits - 1) }, enabled = state.deltaUnits > -40) {
                        Text("减少")
                    }
                    Text(signedLessons(state.deltaUnits))
                    TextButton(onClick = { viewModel.setDelta(state.deltaUnits + 1) }, enabled = state.deltaUnits < 40) {
                        Text("增加")
                    }
                }
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = viewModel::setReason,
                    label = { Text("原因") },
                    placeholder = { Text("选填，例如机构赠课、记错后纠正") },
                    minLines = 2,
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LucaPrimaryButton(text = "保存调整", onClick = viewModel::save, enabled = state.deltaUnits != 0)
                SectionTitle("调整记录")
                if (state.adjustments.isEmpty()) {
                    Text("还没有手动调整。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.adjustments.forEach { row ->
                        Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(signedLessons(row.deltaCreditUnits), style = MaterialTheme.typography.titleMedium)
                                Text(formatEpoch(row.createdAtEpochMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(row.reason ?: "未填写原因")
                                LucaTextButton(
                                    text = "删除这条调整",
                                    onClick = { viewModel.delete(row.id) },
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun signedLessons(units: Int): String {
    val body = formatLessons(units)
    return if (units > 0) "+$body" else body
}

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun formatEpoch(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dayFormatter)
}
