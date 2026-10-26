@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.luca.classmanager.domain.logic.MonthBar
import com.luca.classmanager.domain.logic.YearBar
import com.luca.classmanager.domain.logic.formatCents
import com.luca.classmanager.domain.model.CourseDetails

data class ChartBar(
    val label: String,
    val value: Long,
    val complete: Boolean,
)

@Composable
fun MonthBarChart(bars: List<MonthBar>, money: Boolean, modifier: Modifier = Modifier) {
    ValueBarChart(
        bars = bars.map { ChartBar("${it.month}月", it.value, it.complete) },
        money = money,
        modifier = modifier,
    )
}

@Composable
fun YearBarChart(bars: List<YearBar>, modifier: Modifier = Modifier) {
    ValueBarChart(
        bars = bars.map { ChartBar("${it.year}", it.money.knownCents, it.money.complete) },
        money = true,
        modifier = modifier,
    )
}

@Composable
fun ValueBarChart(bars: List<ChartBar>, money: Boolean, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) {
        Text("还没有可绘制的数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val max = bars.maxOf { it.value }.coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    val partialColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { bar ->
            val unknown = !bar.complete && bar.value == 0L
            val target = when {
                unknown -> 0.08f
                bar.value == 0L -> 0.04f
                else -> (bar.value.toFloat() / max.toFloat()).coerceIn(0.08f, 1f)
            }
            val fraction by animateFloatAsState(target, tween(320), label = "bar")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                Text(
                    caption(bar, money),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Box(
                    modifier = Modifier.height(96.dp).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (bar.complete) barColor else partialColor),
                    )
                }
                Text(bar.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun ReportFilters(
    courses: List<CourseDetails>,
    courseId: String?,
    year: Int,
    years: List<Int>,
    month: Int?,
    onCourse: (String?) -> Unit,
    onYear: (Int) -> Unit,
    onMonth: (Int?) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = courseId == null, onClick = { onCourse(null) }, label = { Text("全部课程") })
        courses.forEach { course ->
            FilterChip(
                selected = courseId == course.id,
                onClick = { onCourse(course.id) },
                label = { Text(course.name) },
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        years.forEach { value ->
            FilterChip(selected = year == value, onClick = { onYear(value) }, label = { Text("${value}年") })
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = month == null, onClick = { onMonth(null) }, label = { Text("全年") })
        (1..12).forEach { value ->
            FilterChip(selected = month == value, onClick = { onMonth(value) }, label = { Text("${value}月") })
        }
    }
}

private fun caption(bar: ChartBar, money: Boolean): String = when {
    !bar.complete && bar.value == 0L -> "待补充"
    !bar.complete -> "部分"
    money && bar.value == 0L -> "0"
    money -> formatCents(bar.value).removeSuffix(" 元")
    else -> bar.value.toString()
}
