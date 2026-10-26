package com.luca.classmanager.domain.logic

import java.time.LocalDate

/**
 * Mirrors the Room ledger without opening a database.
 * Consumed credits are dated attendance plus historical rows that still have no date.
 * Converting a historical row moves its units into a dated attendance so they are not added twice.
 * Deleting that dated row puts the undated historical row back.
 * Deleting a normal check-in removes its deduction.
 */
data class CreditLedger(
    val purchasedUnits: Int?,
    val giftUnits: Int,
    val adjustmentUnits: Int,
    val attendanceUnits: Int,
    val openHistoricalUnits: Int,
) {
    val availableUnits: Int?
        get() = purchasedUnits?.plus(giftUnits)?.plus(adjustmentUnits)

    val consumedUnits: Int
        get() = attendanceUnits + openHistoricalUnits

    val remainingUnits: Int?
        get() = availableUnits?.minus(consumedUnits)

    fun checkIn(units: Int): CreditLedger = copy(attendanceUnits = attendanceUnits + units)

    fun convertHistorical(historicalUnits: Int, datedUnits: Int): CreditLedger = copy(
        openHistoricalUnits = openHistoricalUnits - historicalUnits,
        attendanceUnits = attendanceUnits + datedUnits,
    )

    fun deleteCheckIn(units: Int): CreditLedger = copy(attendanceUnits = attendanceUnits - units)

    fun deleteConverted(datedUnits: Int, historicalUnits: Int): CreditLedger = copy(
        attendanceUnits = attendanceUnits - datedUnits,
        openHistoricalUnits = openHistoricalUnits + historicalUnits,
    )

    fun addPackage(purchasedUnits: Int?, giftUnits: Int): CreditLedger {
        val nextPurchased = when {
            this.purchasedUnits == null || purchasedUnits == null -> null
            else -> this.purchasedUnits + purchasedUnits
        }
        return copy(
            purchasedUnits = nextPurchased,
            giftUnits = this.giftUnits + giftUnits,
        )
    }

    fun adjust(deltaUnits: Int): CreditLedger = copy(adjustmentUnits = adjustmentUnits + deltaUnits)
}

object AttendancePolicy {
    const val MIN_CHECK_IN_UNITS = 1

    fun warnsAboutDuplicate(existingOnDay: Int, confirmed: Boolean): Boolean {
        return existingOnDay > 0 && !confirmed
    }

    fun acceptsCheckInUnits(units: Int): Boolean = units >= MIN_CHECK_IN_UNITS
}

data class BackfillAssignment(
    val historicalId: String,
    val date: String,
)

object BackfillPlanner {
    /**
     * Assigns each open historical row a date, walking backward from [start] and skipping
     * dates that already have attendance. The returned dates are unique.
     */
    fun plan(
        openHistoricalIdsInOrder: List<String>,
        start: LocalDate,
        occupiedDates: Set<String>,
    ): List<BackfillAssignment> {
        val occupied = occupiedDates.toMutableSet()
        var cursor = start
        return openHistoricalIdsInOrder.map { id ->
            while (cursor.toString() in occupied) {
                cursor = cursor.minusDays(1)
            }
            val date = cursor.toString()
            occupied += date
            cursor = cursor.minusDays(1)
            BackfillAssignment(id, date)
        }
    }
}
