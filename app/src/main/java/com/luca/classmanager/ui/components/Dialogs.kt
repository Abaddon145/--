package com.luca.classmanager.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luca.classmanager.domain.logic.deletionLines
import com.luca.classmanager.domain.model.DeletionImpact

@Composable
fun ArchiveCourseDialog(
    courseName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档这门课程？") },
        text = {
            Text("归档后，「$courseName」会从首页和今日安排中隐藏。排课、课时和历史记录都会保留，可以随时恢复。")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = ButtonMinHeight),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("确认归档")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ButtonMinHeight)) {
                Text("取消")
            }
        },
    )
}

@Composable
fun PermanentDeleteDialog(
    impact: DeletionImpact,
    onDismiss: () -> Unit,
    onArchiveInstead: (() -> Unit)?,
    onConfirmDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("永久删除课程？") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("建议优先归档。归档会保留全部记录，并可以随时恢复。")
                Text(
                    "如果永久删除「${impact.courseName}」，以下数据会一并删除，而且无法恢复：",
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                deletionLines(impact).forEach { line ->
                    Text("· $line", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (onArchiveInstead != null) {
                    Button(
                        onClick = onArchiveInstead,
                        modifier = Modifier.heightIn(min = ButtonMinHeight),
                    ) {
                        Text("改为归档")
                    }
                }
                TextButton(
                    onClick = onConfirmDelete,
                    modifier = Modifier.heightIn(min = ButtonMinHeight),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("确认永久删除")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ButtonMinHeight)) {
                Text("取消")
            }
        },
    )
}
