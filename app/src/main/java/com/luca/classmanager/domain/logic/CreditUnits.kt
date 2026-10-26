package com.luca.classmanager.domain.logic

/**
 * Lesson credits are stored as integer half-lesson units.
 * 1 unit = 0.5 lesson, 2 units = 1 lesson.
 */
object CreditUnits {
    const val PER_LESSON = 2
    const val RENEWAL_LESSONS = 3
    const val DEFAULT_RENEWAL_UNITS = RENEWAL_LESSONS * PER_LESSON

    fun format(units: Int): String {
        val negative = units < 0
        val absolute = kotlin.math.abs(units)
        val whole = absolute / PER_LESSON
        val half = absolute % PER_LESSON != 0
        val body = when {
            half && whole == 0 -> "0.5"
            half -> "$whole.5"
            else -> whole.toString()
        }
        return if (negative) "-$body" else body
    }
}
