package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.CourseDetails

object CsvExport {
    fun courses(courses: List<CourseDetails>): String = build(listOf("课程", "状态", "上课时间", "机构", "地点", "备注")) { append ->
        courses.forEach { course ->
            append(
                listOf(
                    course.name,
                    courseStatusLabel(course.status),
                    scheduleSummary(course.scheduleMode, course.rules),
                    course.institution.orEmpty(),
                    course.location.orEmpty(),
                    course.notes.orEmpty(),
                ),
            )
        }
    }

    fun attendance(courses: List<CourseDetails>): String = build(listOf("课程", "日期", "课时", "备注", "来源")) { append ->
        courses.forEach { course ->
            course.attendances.sortedBy { it.attendanceDate }.forEach { row ->
                append(
                    listOf(
                        course.name,
                        row.attendanceDate,
                        CreditUnits.format(row.creditUnits),
                        row.note.orEmpty(),
                        if (row.historicalSourceId != null) "历史补录" else "打卡",
                    ),
                )
            }
        }
    }

    fun payments(courses: List<CourseDetails>): String = build(listOf("课程", "金额", "缴费日期", "备注", "关联")) { append ->
        courses.forEach { course ->
            course.payments.forEach { row ->
                append(
                    listOf(
                        course.name,
                        row.amountCents?.let(::formatCents).orEmpty(),
                        row.paidOn.orEmpty(),
                        row.note.orEmpty(),
                        if (row.packageId != null) "课时套餐" else "额外缴费",
                    ),
                )
            }
        }
    }

    private fun build(header: List<String>, body: (append: (List<String>) -> Unit) -> Unit): String {
        val lines = mutableListOf(header)
        body { lines += it }
        return "\uFEFF" + lines.joinToString("\n") { row -> row.joinToString(",") { cell -> escape(cell) } }
    }

    private fun escape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }
}
