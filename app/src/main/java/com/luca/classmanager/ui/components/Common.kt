package com.luca.classmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luca.classmanager.domain.logic.CourseMetrics
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.formatLessonsOrUnknown
import com.luca.classmanager.domain.logic.formatMoneyTotal
import com.luca.classmanager.domain.logic.renewalLabel
import com.luca.classmanager.domain.logic.scheduleSummary
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.logic.courseStatusLabel
import com.luca.classmanager.domain.logic.metrics
import com.luca.classmanager.ui.theme.parseCourseColor

val PagePadding = 16.dp
val CardCorner = 18.dp
val ButtonMinHeight = 48.dp

@Composable
fun LucaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonMinHeight),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text)
    }
}

@Composable
fun LucaOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonMinHeight),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text)
    }
}

@Composable
fun LucaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonMinHeight),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

@Composable
fun FeeSummaryCard(metrics: com.luca.classmanager.domain.logic.FeeRollup, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricLine("累计缴费", formatMoneyTotal(metrics.paid))
            MetricLine("已消耗金额", formatMoneyTotal(metrics.consumed))
            MetricLine("剩余课时价值", formatMoneyTotal(metrics.remainingValue))
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun CourseSummaryCard(
    course: CourseDetails,
    onClick: () -> Unit,
    muted: Boolean = false,
    renewalThresholdUnits: Int = com.luca.classmanager.domain.logic.CreditUnits.DEFAULT_RENEWAL_UNITS,
) {
    val metrics = course.metrics(renewalThresholdUnits)
    val color = parseCourseColor(course.colorHex)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CourseIcon(icon = course.iconKey, color = color)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            course.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val renewal = renewalLabel(metrics)
                        if (renewal != null) {
                            RenewalChip(renewal, metrics)
                        } else if (muted || course.status != CourseStatus.ACTIVE) {
                            Text(
                                courseStatusLabel(course.status),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        scheduleSummary(course.scheduleMode, course.rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LessonLines(metrics)
            LessonProgress(metrics, color)
            course.institution?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CourseIcon(icon: String, color: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(icon, fontSize = 22.sp)
    }
}

@Composable
private fun RenewalChip(text: String, metrics: CourseMetrics) {
    val urgent = (metrics.remainingCreditUnits ?: 1) <= 0
    val bg = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun LessonLines(metrics: CourseMetrics) {
    val consumed = formatLessons(metrics.consumedCreditUnits)
    val total = if (metrics.totalCreditUnits == null) "总课时待补充" else "共 ${formatLessons(metrics.totalCreditUnits)}"
    Text("$consumed · $total", style = MaterialTheme.typography.bodyMedium)
    Text("剩余 ${formatLessonsOrUnknown(metrics.remainingCreditUnits)}", style = MaterialTheme.typography.bodyMedium)
    if (metrics.unrecordedSessionCount > 0) {
        Text(
            "历史未补录 ${metrics.unrecordedSessionCount} 次（未指定日期）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LessonProgress(metrics: CourseMetrics, color: Color) {
    val total = metrics.totalCreditUnits
    if (total == null || total <= 0) {
        Text(
            "进度待补充",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val ratio = (metrics.consumedCreditUnits.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { ratio },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
        color = color,
        trackColor = color.copy(alpha = 0.18f),
    )
}

@Composable
fun LoadingPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
fun MessagePane(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (action != null && onAction != null) {
            LucaPrimaryButton(text = action, onClick = onAction)
        }
        Spacer(Modifier.height(8.dp))
    }
}
