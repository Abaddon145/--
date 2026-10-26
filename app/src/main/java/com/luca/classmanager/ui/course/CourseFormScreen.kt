@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.logic.formatMinutes
import com.luca.classmanager.domain.logic.scheduleModeLabel
import com.luca.classmanager.domain.logic.weekdayName
import com.luca.classmanager.domain.model.CourseDraft
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRuleDraft
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.home.simpleFactory
import com.luca.classmanager.ui.theme.parseCourseColor
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val iconChoices = listOf("🏀", "🏸", "🧠", "📚", "🎹", "🎨", "⚽", "🏊", "🎻", "🎲", "💃", "🧪", "📝", "🎤", "🏓", "🥋")
private val colorChoices = listOf("#7A9E8E", "#E9A56B", "#D95C59", "#8C8CC7", "#6B8CAE", "#C48B9F", "#5E8C7B", "#D4A054")

data class EditableRule(
    val key: String = UUID.randomUUID().toString(),
    val dayOfWeek: Int = 6,
    val startMinutes: Int = 16 * 60,
    val endMinutes: Int = 17 * 60 + 30,
)

data class CourseFormState(
    val loading: Boolean = false,
    val missing: Boolean = false,
    val name: String = "",
    val iconKey: String = "📚",
    val colorHex: String = "#7A9E8E",
    val institution: String = "",
    val location: String = "",
    val notes: String = "",
    val scheduleMode: ScheduleMode = ScheduleMode.WEEKLY,
    val rules: List<EditableRule> = emptyList(),
    val nameError: String? = null,
    val formError: String? = null,
    val saving: Boolean = false,
)

class CourseFormViewModel(
    private val repository: CourseRepository,
    private val courseId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(CourseFormState(loading = courseId != null))
    val state: StateFlow<CourseFormState> = _state.asStateFlow()

    init {
        val id = courseId
        if (id != null) viewModelScope.launch {
            val course = repository.getCourse(id)
            if (course == null) {
                _state.value = CourseFormState(loading = false, missing = true)
            } else {
                _state.value = CourseFormState(
                    loading = false,
                    name = course.name,
                    iconKey = course.iconKey,
                    colorHex = course.colorHex,
                    institution = course.institution.orEmpty(),
                    location = course.location.orEmpty(),
                    notes = course.notes.orEmpty(),
                    scheduleMode = course.scheduleMode,
                    rules = course.rules.map {
                        EditableRule(
                            dayOfWeek = it.dayOfWeek,
                            startMinutes = it.startMinutes,
                            endMinutes = it.endMinutes,
                        )
                    },
                )
            }
        }
    }

    fun setName(value: String) = update { it.copy(name = value.take(40), nameError = null) }
    fun setIcon(value: String) = update { it.copy(iconKey = value) }
    fun setColor(value: String) = update { it.copy(colorHex = value) }
    fun setInstitution(value: String) = update { it.copy(institution = value.take(40)) }
    fun setLocation(value: String) = update { it.copy(location = value.take(40)) }
    fun setNotes(value: String) = update { it.copy(notes = value.take(200)) }
    fun setMode(value: ScheduleMode) = update { it.copy(scheduleMode = value, formError = null) }

    fun addRule() = update { state ->
        state.copy(rules = state.rules + EditableRule(), formError = null)
    }

    fun deleteRule(key: String) = update { state ->
        state.copy(rules = state.rules.filterNot { it.key == key })
    }

    fun updateRule(key: String, block: (EditableRule) -> EditableRule) = update { state ->
        state.copy(rules = state.rules.map { if (it.key == key) block(it) else it }, formError = null)
    }

    fun save(onSaved: (String) -> Unit) {
        val current = _state.value
        val name = current.name.trim()
        if (name.isEmpty()) {
            update { it.copy(nameError = "请填写课程名称") }
            return
        }
        if (current.scheduleMode == ScheduleMode.WEEKLY && current.rules.isEmpty()) {
            update { it.copy(formError = "固定每周上课时，请至少添加一个上课时间") }
            return
        }
        if (current.scheduleMode == ScheduleMode.WEEKLY && current.rules.any { it.endMinutes <= it.startMinutes }) {
            update { it.copy(formError = "结束时间需要晚于开始时间") }
            return
        }
        val duplicated = current.rules
            .groupBy { Triple(it.dayOfWeek, it.startMinutes, it.endMinutes) }
            .any { it.value.size > 1 }
        if (current.scheduleMode == ScheduleMode.WEEKLY && duplicated) {
            update { it.copy(formError = "存在完全相同的上课时间") }
            return
        }
        update { it.copy(saving = true, formError = null, nameError = null) }
        viewModelScope.launch {
            val draft = CourseDraft(
                name = name,
                iconKey = current.iconKey,
                colorHex = current.colorHex,
                institution = current.institution.nullIfBlank(),
                location = current.location.nullIfBlank(),
                notes = current.notes.nullIfBlank(),
                scheduleMode = current.scheduleMode,
                rules = if (current.scheduleMode == ScheduleMode.WEEKLY) {
                    current.rules.map {
                        ScheduleRuleDraft(it.dayOfWeek, it.startMinutes, it.endMinutes)
                    }
                } else {
                    emptyList()
                },
            )
            runCatching {
                val id = courseId
                if (id == null) repository.createCourse(draft) else {
                    repository.updateCourse(id, draft)
                    id
                }
            }.onSuccess { id ->
                update { it.copy(saving = false) }
                onSaved(id)
            }.onFailure {
                update { it.copy(saving = false, formError = "保存失败，请重试") }
            }
        }
    }

    private fun update(block: (CourseFormState) -> CourseFormState) {
        _state.value = block(_state.value)
    }

    private fun String.nullIfBlank(): String? = trim().ifBlank { null }

    companion object {
        fun factory(repository: CourseRepository, courseId: String?): ViewModelProvider.Factory =
            simpleFactory { CourseFormViewModel(repository, courseId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseFormScreen(
    repository: CourseRepository,
    courseId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CourseFormViewModel = viewModel(
        key = courseId ?: "new",
        factory = CourseFormViewModel.factory(repository, courseId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (courseId == null) "新增兴趣班" else "编辑课程") },
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
            state.missing -> MessagePane("找不到这门课程", "返回", onBack)
            else -> FormBody(state, viewModel, onSaved)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormBody(
    state: CourseFormState,
    viewModel: CourseFormViewModel,
    onSaved: (String) -> Unit,
) {
    var dayPicker by remember { mutableStateOf<String?>(null) }
    var timePicker by remember { mutableStateOf<TimeEdit?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("课程名称") },
            singleLine = true,
            isError = state.nameError != null,
            supportingText = state.nameError?.let { message -> { Text(message) } },
        )
        Text("图标", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            iconChoices.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { icon ->
                        ChoiceCircle(
                            selected = state.iconKey == icon,
                            onClick = { viewModel.setIcon(icon) },
                        ) {
                            Text(icon, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
        Text("颜色", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            colorChoices.forEach { hex ->
                ChoiceCircle(selected = state.colorHex.equals(hex, ignoreCase = true), onClick = { viewModel.setColor(hex) }) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(parseCourseColor(hex))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.institution,
            onValueChange = viewModel::setInstitution,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("培训机构") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.location,
            onValueChange = viewModel::setLocation,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("上课地点") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注") },
            minLines = 2,
        )
        Text("上课安排", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScheduleMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.scheduleMode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(scheduleModeLabel(mode)) },
                )
            }
        }
        if (state.scheduleMode == ScheduleMode.WEEKLY) {
            state.rules.forEach { rule ->
                RuleRow(
                    rule = rule,
                    onPickDay = { dayPicker = rule.key },
                    onPickStart = { timePicker = TimeEdit(rule.key, start = true, rule.startMinutes) },
                    onPickEnd = { timePicker = TimeEdit(rule.key, start = false, rule.endMinutes) },
                    onDelete = { viewModel.deleteRule(rule.key) },
                )
            }
            TextButton(onClick = viewModel::addRule, modifier = Modifier.fillMaxWidth()) {
                Text("添加上课时间")
            }
        } else {
            Text(
                "这种课程不会出现在每周安排里。之后可以改回每周固定。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.formError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        LucaPrimaryButton(
            text = if (state.saving) "正在保存…" else "保存",
            onClick = { viewModel.save(onSaved) },
            enabled = !state.saving,
        )
    }

    dayPicker?.let { key ->
        val rule = state.rules.firstOrNull { it.key == key }
        if (rule != null) {
            AlertDialog(
                onDismissRequest = { dayPicker = null },
                title = { Text("上课星期") },
                text = {
                    Column {
                        (1..7).forEach { day ->
                            TextButton(
                                onClick = {
                                    viewModel.updateRule(key) { it.copy(dayOfWeek = day) }
                                    dayPicker = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (day == rule.dayOfWeek) "${weekdayName(day)}（当前）" else weekdayName(day))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dayPicker = null }) { Text("关闭") }
                },
            )
        }
    }

    timePicker?.let { edit ->
        TimeDialog(
            title = if (edit.start) "开始时间" else "结束时间",
            initialMinutes = edit.minutes,
            onDismiss = { timePicker = null },
            onConfirm = { minutes ->
                viewModel.updateRule(edit.key) { rule ->
                    if (edit.start) rule.copy(startMinutes = minutes) else rule.copy(endMinutes = minutes)
                }
                timePicker = null
            },
        )
    }
}

private data class TimeEdit(val key: String, val start: Boolean, val minutes: Int)

@Composable
private fun RuleRow(
    rule: EditableRule,
    onPickDay: () -> Unit,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onPickDay) { Text(weekdayName(rule.dayOfWeek)) }
        TextButton(onClick = onPickStart) { Text(formatMinutes(rule.startMinutes)) }
        Text("–")
        TextButton(onClick = onPickEnd) { Text(formatMinutes(rule.endMinutes)) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "删除这个上课时间")
        }
    }
}

@Composable
private fun ChoiceCircle(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
