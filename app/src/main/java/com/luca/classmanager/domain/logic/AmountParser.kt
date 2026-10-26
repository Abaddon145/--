package com.luca.classmanager.domain.logic

sealed interface ParseOutcome<out T> {
    data object Empty : ParseOutcome<Nothing>
    data object Invalid : ParseOutcome<Nothing>
    data class Value<T>(val value: T) : ParseOutcome<T>
}

/**
 * Yuan text becomes integer cents without floating point.
 * Blank means unknown. "0" is a known zero. At most two decimal digits.
 */
object AmountParser {
    fun parseYuanToCents(raw: String): ParseOutcome<Long> {
        val text = raw.trim()
        if (text.isEmpty()) return ParseOutcome.Empty
        if (!text.matches(Regex("\\d+(\\.\\d{1,2})?"))) return ParseOutcome.Invalid
        val parts = text.split('.')
        val yuan = parts[0].toLongOrNull() ?: return ParseOutcome.Invalid
        val fen = when (parts.size) {
            1 -> 0
            else -> {
                val digits = parts[1]
                val value = digits.toIntOrNull() ?: return ParseOutcome.Invalid
                if (digits.length == 1) value * 10 else value
            }
        }
        return ParseOutcome.Value(yuan * 100 + fen)
    }

    fun formatYuanInput(cents: Long): String {
        val negative = cents < 0
        val absolute = kotlin.math.abs(cents)
        val yuan = absolute / 100
        val fen = (absolute % 100).toInt()
        val body = if (fen == 0) {
            yuan.toString()
        } else {
            val fraction = fen.toString().padStart(2, '0').trimEnd('0')
            "$yuan.$fraction"
        }
        return if (negative) "-$body" else body
    }
}

/**
 * Lesson text is whole lessons or half lessons only. Stored as half-units.
 * Blank is unknown for optional fields. Explicit 0 is a known zero.
 */
object LessonParser {
    fun parse(raw: String, allowNegative: Boolean = false): ParseOutcome<Int> {
        val text = raw.trim()
        if (text.isEmpty()) return ParseOutcome.Empty
        val negative = text.startsWith("-")
        if ((negative && !allowNegative) || text.startsWith("+")) return ParseOutcome.Invalid
        val body = if (negative) text.drop(1) else text
        if (body.isEmpty()) return ParseOutcome.Invalid
        val units = when {
            body.matches(Regex("\\d+")) -> {
                val whole = body.toIntOrNull() ?: return ParseOutcome.Invalid
                whole * CreditUnits.PER_LESSON
            }
            body.matches(Regex("\\d+\\.0")) -> {
                val whole = body.substringBefore('.').toIntOrNull() ?: return ParseOutcome.Invalid
                whole * CreditUnits.PER_LESSON
            }
            body.matches(Regex("\\d+\\.5")) -> {
                val whole = body.substringBefore('.').toIntOrNull() ?: return ParseOutcome.Invalid
                whole * CreditUnits.PER_LESSON + 1
            }
            else -> return ParseOutcome.Invalid
        }
        if (units > 20_000) return ParseOutcome.Invalid
        return ParseOutcome.Value(if (negative) -units else units)
    }
}
