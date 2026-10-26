package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.SessionTimeStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class WeeklySlot(
    val courseId: String,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
)

data class SlotOccurrence(
    val courseId: String,
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
)

object SchedulePlanner {
    fun slotsOn(date: LocalDate, slots: List<WeeklySlot>): List<WeeklySlot> {
        return slots.filter { it.dayOfWeek == date.dayOfWeek.value }
            .sortedBy { it.startMinutes }
    }

    fun statusAt(
        now: LocalDateTime,
        date: LocalDate,
        startMinutes: Int,
        endMinutes: Int,
    ): SessionTimeStatus {
        if (date.isAfter(now.toLocalDate())) return SessionTimeStatus.NOT_STARTED
        if (date.isBefore(now.toLocalDate())) return SessionTimeStatus.FINISHED
        val nowMinutes = now.hour * 60 + now.minute
        return when {
            nowMinutes < startMinutes -> SessionTimeStatus.NOT_STARTED
            nowMinutes < endMinutes -> SessionTimeStatus.IN_PROGRESS
            else -> SessionTimeStatus.FINISHED
        }
    }

    /** Next class whose start time is strictly after [now]. */
    fun nextStartAfter(now: LocalDateTime, slots: List<WeeklySlot>): SlotOccurrence? {
        if (slots.isEmpty()) return null
        val nowMinutes = now.hour * 60 + now.minute
        return slots.map { slot ->
            var delta = slot.dayOfWeek - now.dayOfWeek.value
            if (delta < 0) delta += 7
            if (delta == 0 && nowMinutes >= slot.startMinutes) delta = 7
            SlotOccurrence(
                courseId = slot.courseId,
                date = now.toLocalDate().plusDays(delta.toLong()),
                startMinutes = slot.startMinutes,
                endMinutes = slot.endMinutes,
            )
        }.minWith(compareBy({ it.date }, { it.startMinutes }, { it.courseId }))
    }
}
