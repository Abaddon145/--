package com.luca.classmanager.ui.checkin

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.formatChineseDate
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaTextButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun DuplicateCheckInDialog(
    prompt: DuplicatePrompt,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这一天已经打过卡") },
        text = {
            Text("${prompt.courseName} 在 ${formatChineseDate(prompt.date)} 已有打卡记录。再记一次会多扣课时，不会并进原来的记录。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !prompt.saving) { Text("仍要打卡") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !prompt.saving) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInEditorDialog(
    editor: CheckEditor,
    showDatePicker: Boolean,
    onPickDate: () -> Unit,
    onDatePicked: (LocalDate) -> Unit,
    onDismissDate: () -> Unit,
    onUnits: (Int) -> Unit,
    onNote: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = when {
        editor.editingAttendanceId != null -> "修改打卡"
        editor.historicalId != null -> "补录历史打卡"
        else -> "打卡"
    }
    AlertDialog(
        onDismissRequest = { if (!editor.saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(editor.courseName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (editor.historicalId != null) {
                        "补录后，这条没有日期的历史记录会转成当天的打卡，已消耗课时不会再加一次。"
                    } else {
                        "默认记今天、消耗 1 课时。可以改日期、课时、备注，也可以加一张保存在本机的照片。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LucaOutlinedButton(text = formatChineseDate(editor.date), onClick = onPickDate, enabled = !editor.saving)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("消耗", modifier = Modifier.weight(1f))
                    TextButton(onClick = { onUnits(editor.creditUnits - 1) }, enabled = editor.creditUnits > 1 && !editor.saving) {
                        Text("减少")
                    }
                    Text("${CreditUnits.format(editor.creditUnits)} 课时")
                    TextButton(onClick = { onUnits(editor.creditUnits + 1) }, enabled = editor.creditUnits < 40 && !editor.saving) {
                        Text("增加")
                    }
                }
                OutlinedTextField(
                    value = editor.note,
                    onValueChange = onNote,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注") },
                    placeholder = { Text("选填") },
                    enabled = !editor.saving,
                    minLines = 2,
                )
                if (editor.photoPath != null) {
                    PhotoPreview(editor.photoPath)
                    LucaTextButton(text = "移除照片", onClick = onClearPhoto)
                } else {
                    LucaOutlinedButton(text = "添加照片", onClick = onPickPhoto, enabled = !editor.saving)
                }
                editor.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !editor.saving) {
                Text(if (editor.duplicateWarning) "仍要保存" else "保存")
            }
        },
        dismissButton = {
            Row {
                if (editor.editingAttendanceId != null) {
                    TextButton(onClick = onDelete, enabled = !editor.saving) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !editor.saving) { Text("取消") }
            }
        },
    )
    if (showDatePicker) {
        LucaDateDialog(initial = editor.date, onDismiss = onDismissDate, onConfirm = onDatePicked)
    }
}

@Composable
private fun PhotoPreview(path: String) {
    val bitmap = remember(path) {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "打卡照片",
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Text("已附加 1 张照片", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LucaDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}
