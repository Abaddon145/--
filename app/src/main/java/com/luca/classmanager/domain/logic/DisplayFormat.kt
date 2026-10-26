package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.DeletionImpact
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRule
import com.luca.classmanager.domain.model.SessionTimeStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun formatCents(cents: Long): String {
    val negative = cents < 0
    val absolute = kotlin.math.abs(cents)
    val yuan = absolute / 100
    val fraction = (absolute % 100).toString().padStart(2, '0')
    val body = "$yuan.$fraction 元"
    return if (negative) "-$body" else body
}

fun formatMoneyTotal(total: MoneyTotal): String = when {
    total.complete -> formatCents(total.knownCents)
    total.knownCents == 0L -> "待补充"
    else -> "待补充（已知 ${formatCents(total.knownCents)}）"
}

fun formatLessons(units: Int): String = "${CreditUnits.format(units)} 课时"

fun formatLessonsOrUnknown(units: Int?): String = if (units == null) "待补充" else formatLessons(units)

fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

fun formatMinuteRange(startMinutes: Int, endMinutes: Int): String {
    return "${formatMinutes(startMinutes)}–${formatMinutes(endMinutes)}"
}

fun weekdayName(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "星期一"
    2 -> "星期二"
    3 -> "星期三"
    4 -> "星期四"
    5 -> "星期五"
    6 -> "星期六"
    7 -> "星期日"
    else -> "未知星期"
}

fun formatChineseDate(date: LocalDate): String {
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日 ${weekdayName(date.dayOfWeek.value)}"
}

fun relativeDayLabel(today: LocalDate, date: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when (days) {
        0L -> "今天"
        1L -> "明天"
        else -> formatChineseDate(date)
    }
}

fun courseStatusLabel(status: CourseStatus): String = when (status) {
    CourseStatus.ACTIVE -> "进行中"
    CourseStatus.PAUSED -> "已暂停"
    CourseStatus.ARCHIVED -> "已归档"
}

fun scheduleModeLabel(mode: ScheduleMode): String = when (mode) {
    ScheduleMode.WEEKLY -> "每周固定"
    ScheduleMode.FLEXIBLE -> "时间不固定"
    ScheduleMode.TEMPORARY -> "临时课程"
}

fun scheduleSummary(mode: ScheduleMode, rules: List<ScheduleRule>): String {
    if (mode == ScheduleMode.FLEXIBLE) return "上课时间不固定"
    if (mode == ScheduleMode.TEMPORARY && rules.isEmpty()) return "临时课程"
    if (rules.isEmpty()) return "尚未设置上课时间"
    return rules
        .sortedWith(compareBy({ it.dayOfWeek }, { it.startMinutes }))
        .joinToString("，") { rule ->
            "${weekdayName(rule.dayOfWeek)} ${formatMinuteRange(rule.startMinutes, rule.endMinutes)}"
        }
}

fun sessionVisualLabel(visual: SessionVisual, occupiesTime: Boolean): String = when (visual) {
    SessionVisual.PLANNED -> "计划"
    SessionVisual.CHECKED_IN -> "已打卡"
    SessionVisual.LEAVE -> "请假"
    SessionVisual.CANCELLED -> "已取消"
    SessionVisual.RESCHEDULED -> if (occupiesTime) "调课" else "已调走"
    SessionVisual.MAKEUP -> "补课"
}

fun sessionStatusLabel(status: SessionTimeStatus, checkedIn: Boolean): String {
    if (checkedIn) return "已打卡"
    return when (status) {
        SessionTimeStatus.NOT_STARTED -> "未开始"
        SessionTimeStatus.IN_PROGRESS -> "进行中"
        SessionTimeStatus.FINISHED -> "已结束"
    }
}

fun giftValuationLabel(mode: GiftValuation): String = when (mode) {
    GiftValuation.ZERO -> "赠送课时按 0 元"
    GiftValuation.MATCH_PURCHASED -> "赠送课时与购买单价相同"
}

fun renewalLabel(metrics: CourseMetrics): String? {
    val remaining = metrics.remainingCreditUnits ?: return null
    return when {
        remaining < 0 -> "已超出课时"
        remaining == 0 -> "课时已用完"
        metrics.needsRenewal -> "建议续费"
        else -> null
    }
}

fun deletionLines(impact: DeletionImpact): List<String> = listOf(
    "课程信息",
    "每周排课规则 ${impact.scheduleRuleCount} 条",
    "课时套餐 ${impact.packageCount} 个",
    "历史未补录记录 ${impact.historicalAttendanceCount} 条",
    "打卡记录 ${impact.attendanceCount} 条",
    "缴费记录 ${impact.paymentCount} 条",
    "课时调整 ${impact.adjustmentCount} 条",
    "课程安排 ${impact.eventCount} 条",
    "提醒 ${impact.reminderCount} 条",
    "附件 ${impact.attachmentCount} 个",
)
