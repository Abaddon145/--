@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.backup.BackupDecode
import com.luca.classmanager.domain.logic.CsvExport
import com.luca.classmanager.domain.logic.ReminderNotice
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.reminder.ReminderNotifications
import com.luca.classmanager.reminder.ReminderScheduler
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import java.time.LocalDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MaintenanceViewModel(
    private val settings: SettingsRepository,
    private val repository: CourseRepository,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.remindersEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val lead: StateFlow<Int> = settings.reminderLeadMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)
    val hour: StateFlow<Int> = settings.uncheckedReminderHour.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 20)

    fun setEnabled(value: Boolean, context: Context) {
        viewModelScope.launch {
            settings.setRemindersEnabled(value)
            ReminderScheduler.refresh(context.applicationContext)
        }
    }

    fun setLead(minutes: Int, context: Context) {
        viewModelScope.launch {
            settings.setReminderLeadMinutes(minutes)
            ReminderScheduler.refresh(context.applicationContext)
        }
    }

    fun setHour(hour: Int, context: Context) {
        viewModelScope.launch {
            settings.setUncheckedReminderHour(hour)
            ReminderScheduler.refresh(context.applicationContext)
        }
    }

    fun sendTest(context: Context) {
        ReminderNotifications.ensureChannel(context)
        ReminderNotifications.show(
            context,
            ReminderNotice(
                key = "test:${LocalDateTime.now()}",
                title = "提醒已打开",
                body = "这是一条本地测试通知，不会上传到任何服务器。",
            ),
        )
    }

    suspend fun backupText(): String = repository.exportBackup()

    suspend fun inspect(text: String): BackupDecode = repository.inspectBackup(text)

    suspend fun restore(text: String): BackupDecode {
        val result = repository.restoreBackup(text)
        return result
    }

    suspend fun coursesCsv(): String = CsvExport.courses(repository.observeCourses().first())

    suspend fun attendanceCsv(): String = CsvExport.attendance(repository.observeCourses().first())

    suspend fun paymentsCsv(): String = CsvExport.payments(repository.observeCourses().first())

    companion object {
        fun factory(settings: SettingsRepository, repository: CourseRepository): ViewModelProvider.Factory =
            simpleFactory { MaintenanceViewModel(settings, repository) }
    }
}

@Composable
fun MaintenanceSection(
    settings: SettingsRepository,
    repository: CourseRepository,
    onNotice: (String) -> Unit,
) {
    val viewModel: MaintenanceViewModel = viewModel(factory = MaintenanceViewModel.factory(settings, repository))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val lead by viewModel.lead.collectAsStateWithLifecycle()
    val hour by viewModel.hour.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var pendingSummary by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setEnabled(true, context) else onNotice("没有通知权限，提醒不会出现。可以稍后在系统设置里允许。")
    }
    fun enableReminders() {
        val needsRuntime = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntime) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setEnabled(true, context)
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = viewModel.backupText()
            val wrote = writeText(context, uri, text)
            onNotice(if (wrote) "备份已保存" else "没有写到所选位置")
        }
    }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = readText(context, uri)
            if (text == null) {
                onNotice("没有读到备份文件")
                return@launch
            }
            when (val decoded = viewModel.inspect(text)) {
                is BackupDecode.Rejected -> onNotice(decoded.message)
                is BackupDecode.Ready -> {
                    pendingRestore = text
                    pendingSummary = "这份备份有 ${decoded.bundle.courses.size} 门课程、${decoded.bundle.attendance.size} 条打卡、${decoded.bundle.payments.size} 条缴费、${decoded.bundle.files.size} 个照片文件。"
                }
            }
        }
    }
    val saveCourses = csvLauncher(context, scope, onNotice) { viewModel.coursesCsv() }
    val saveAttendance = csvLauncher(context, scope, onNotice) { viewModel.attendanceCsv() }
    val savePayments = csvLauncher(context, scope, onNotice) { viewModel.paymentsCsv() }

    SectionTitle("本地提醒")
    Text("只在这台手机上提醒，不使用云端推送。上课前提醒用系统允许的非精确闹钟；另外每 15 分钟检查一次未打卡、续费和暂停课程。手机关机后，系统会在允许的时候重新登记这些任务。如果在系统里强制停止应用，要再次打开应用后提醒才会恢复。")
    Card(
        onClick = { if (enabled) viewModel.setEnabled(false, context) else enableReminders() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = enabled, onClick = null)
            Text(if (enabled) "提醒已开启" else "提醒未开启", modifier = Modifier.padding(start = 8.dp))
        }
    }
    Text("上课前提醒")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 30, 60, 120).forEach { minutes ->
            FilterChip(
                selected = lead == minutes,
                onClick = { viewModel.setLead(minutes, context) },
                label = { Text(if (minutes < 60) "${minutes}分钟" else "${minutes / 60}小时") },
            )
        }
    }
    Text("当天未打卡提醒时间：${hour} 点")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LucaOutlinedButton("提早", { viewModel.setHour(hour - 1, context) }, enabled = hour > 12, modifier = Modifier.weight(1f))
        LucaOutlinedButton("推迟", { viewModel.setHour(hour + 1, context) }, enabled = hour < 22, modifier = Modifier.weight(1f))
    }
    LucaOutlinedButton("发送一条测试提醒", {
        if (!enabled) enableReminders() else viewModel.sendTest(context)
    })
    SectionTitle("备份与导出")
    Text("JSON 备份包含课程、打卡、缴费、设置，以及已经复制进应用的照片。保存位置由系统文件选择器决定，不申请存储权限。恢复前会校验版本和内容；校验失败或写入失败时，不会替换现在的数据。")
    LucaPrimaryButton("导出 JSON 备份", { saveJson.launch("luca-backup.json") })
    LucaOutlinedButton("从 JSON 恢复", { openJson.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
    LucaOutlinedButton("导出课程 CSV", { saveCourses.launch("课程.csv") })
    LucaOutlinedButton("导出打卡 CSV", { saveAttendance.launch("打卡.csv") })
    LucaOutlinedButton("导出缴费 CSV", { savePayments.launch("缴费.csv") })
    Text("首次打开会写入三门预置课程。删除或修改之后，重新打开应用不会再生成一遍。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (pendingRestore != null) {
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("用备份覆盖现有数据？") },
            text = { Text(pendingSummary + "确定后，这台设备上的课程、打卡、缴费和照片会被这份备份替换。") },
            confirmButton = {
                TextButton(onClick = {
                    val text = pendingRestore
                    pendingRestore = null
                    if (text != null) {
                        scope.launch {
                            when (val result = viewModel.restore(text)) {
                                is BackupDecode.Ready -> {
                                    ReminderScheduler.refresh(context.applicationContext)
                                    onNotice("已从备份恢复")
                                }
                                is BackupDecode.Rejected -> onNotice(result.message)
                            }
                        }
                    }
                }) { Text("覆盖恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun csvLauncher(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onNotice: (String) -> Unit,
    text: suspend () -> String,
) = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
        val wrote = writeText(context, uri, text())
        onNotice(if (wrote) "CSV 已保存" else "没有写到所选位置")
    }
}

private fun writeText(context: Context, uri: android.net.Uri, text: String): Boolean {
    return try {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        } != null
    } catch (_: Exception) {
        false
    }
}

private fun readText(context: Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }
}
