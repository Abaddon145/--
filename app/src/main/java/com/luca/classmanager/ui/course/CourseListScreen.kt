@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.course

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.luca.classmanager.domain.logic.courseStatusLabel
import com.luca.classmanager.domain.logic.scheduleSummary
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseFilter
import com.luca.classmanager.domain.model.matches
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.ui.components.CourseIcon
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.home.simpleFactory
import com.luca.classmanager.ui.theme.parseCourseColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CourseListState(
    val loading: Boolean = true,
    val error: String? = null,
    val filter: CourseFilter = CourseFilter.ALL,
    val courses: List<CourseDetails> = emptyList(),
)

class CourseListViewModel(
    private val repository: CourseRepository,
    initialFilter: CourseFilter,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val filter = MutableStateFlow(initialFilter)

    val uiState: StateFlow<CourseListState> = refresh.flatMapLatest {
        combine(repository.observeCourses(), filter) { courses, selected ->
            CourseListState(
                loading = false,
                filter = selected,
                courses = courses.filter { it.matches(selected) }.sortedBy { it.sortOrder },
            )
        }.catch {
            emit(CourseListState(loading = false, error = "课程列表暂时无法读取"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseListState())

    fun setFilter(value: CourseFilter) {
        filter.value = value
    }

    fun retry() {
        refresh.value += 1
    }

    fun move(id: String, direction: Int) {
        val selected = filter.value
        viewModelScope.launch { repository.move(id, direction, selected) }
    }

    companion object {
        fun factory(repository: CourseRepository, initialFilter: CourseFilter): ViewModelProvider.Factory =
            simpleFactory { CourseListViewModel(repository, initialFilter) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListScreen(
    repository: CourseRepository,
    initialFilter: CourseFilter,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CourseListViewModel = viewModel(
        factory = CourseListViewModel.factory(repository, initialFilter),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("全部课程") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "新增兴趣班")
                }
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        when {
            state.loading -> LoadingPane()
            state.error != null -> MessagePane(state.error ?: "", "重试", viewModel::retry)
            else -> CourseListBody(state, viewModel::setFilter, viewModel::move, onOpen, onAdd)
        }
    }
}

@Composable
private fun CourseListBody(
    state: CourseListState,
    onFilter: (CourseFilter) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CourseFilterChip(state.filter == CourseFilter.ALL, { onFilter(CourseFilter.ALL) }, "全部")
            CourseFilterChip(state.filter == CourseFilter.ACTIVE, { onFilter(CourseFilter.ACTIVE) }, "进行中")
            CourseFilterChip(state.filter == CourseFilter.PAUSED, { onFilter(CourseFilter.PAUSED) }, "已暂停")
            CourseFilterChip(state.filter == CourseFilter.ARCHIVED, { onFilter(CourseFilter.ARCHIVED) }, "已归档")
        }
        Text(
            "使用箭头调整顺序，首页会按这个顺序显示。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.courses.isEmpty()) {
            MessagePane(
                text = when (state.filter) {
                    CourseFilter.ARCHIVED -> "没有归档课程。"
                    CourseFilter.PAUSED -> "没有暂停的课程。"
                    else -> "还没有课程。"
                },
            )
        } else {
            state.courses.forEachIndexed { index, course ->
                CourseManageRow(
                    course = course,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.courses.lastIndex,
                    onOpen = { onOpen(course.id) },
                    onMoveUp = { onMove(course.id, -1) },
                    onMoveDown = { onMove(course.id, 1) },
                )
            }
        }
        LucaPrimaryButton(text = "新增兴趣班", onClick = onAdd)
    }
}

@Composable
private fun CourseFilterChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun CourseManageRow(
    course: CourseDetails,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CourseIcon(course.iconKey, parseCourseColor(course.colorHex))
            Column(modifier = Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${courseStatusLabel(course.status)} · ${scheduleSummary(course.scheduleMode, course.rules)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
            }
        }
    }
}
