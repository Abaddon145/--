package com.luca.classmanager.domain

import com.luca.classmanager.domain.logic.AmountParser
import com.luca.classmanager.domain.logic.AttendancePolicy
import com.luca.classmanager.domain.logic.BackfillPlanner
import com.luca.classmanager.domain.logic.CreditLedger
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.LessonCalculator
import com.luca.classmanager.domain.logic.LessonParser
import com.luca.classmanager.domain.logic.PackagePricing
import com.luca.classmanager.domain.logic.ParseOutcome
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2RulesTest {
    @Test
    fun availableCreditsAddPurchasesGiftsAndAdjustments() {
        val ledger = CreditLedger(
            purchasedUnits = 20,
            giftUnits = 2,
            adjustmentUnits = -1,
            attendanceUnits = 4,
            openHistoricalUnits = 2,
        )
        assertEquals(21, ledger.availableUnits)
        assertEquals(6, ledger.consumedUnits)
        assertEquals(15, ledger.remainingUnits)
        assertEquals("10.5", CreditUnits.format(ledger.availableUnits ?: 0))
    }

    @Test
    fun sameDayCheckInWarnsUntilConfirmed() {
        assertTrue(AttendancePolicy.warnsAboutDuplicate(existingOnDay = 1, confirmed = false))
        assertFalse(AttendancePolicy.warnsAboutDuplicate(existingOnDay = 1, confirmed = true))
        assertFalse(AttendancePolicy.warnsAboutDuplicate(existingOnDay = 0, confirmed = false))
        assertFalse(AttendancePolicy.acceptsCheckInUnits(0))
        assertTrue(AttendancePolicy.acceptsCheckInUnits(1))
    }

    @Test
    fun deletingANormalCheckInRestoresRemainingCredits() {
        val afterCheckIn = CreditLedger(
            purchasedUnits = 20,
            giftUnits = 0,
            adjustmentUnits = 0,
            attendanceUnits = 16,
            openHistoricalUnits = 0,
        )
        assertEquals(4, afterCheckIn.remainingUnits)
        val restored = afterCheckIn.deleteCheckIn(units = 2)
        assertEquals(14, restored.consumedUnits)
        assertEquals(6, restored.remainingUnits)
    }

    @Test
    fun deletingAConvertedCheckInPutsTheUndatedRecordBack() {
        val open = CreditLedger(
            purchasedUnits = 20,
            giftUnits = 0,
            adjustmentUnits = 0,
            attendanceUnits = 0,
            openHistoricalUnits = 4,
        )
        val converted = open.convertHistorical(historicalUnits = 2, datedUnits = 2)
        assertEquals(open.consumedUnits, converted.consumedUnits)
        assertEquals(2, converted.attendanceUnits)
        assertEquals(2, converted.openHistoricalUnits)
        val deleted = converted.deleteConverted(datedUnits = 2, historicalUnits = 2)
        assertEquals(open.consumedUnits, deleted.consumedUnits)
        assertEquals(open.remainingUnits, deleted.remainingUnits)
        assertEquals(0, deleted.attendanceUnits)
        assertEquals(4, deleted.openHistoricalUnits)
    }

    @Test
    fun renewalPackageIncreasesRemainingWhenTotalsAreKnown() {
        val before = CreditLedger(
            purchasedUnits = 20,
            giftUnits = 0,
            adjustmentUnits = 0,
            attendanceUnits = 16,
            openHistoricalUnits = 0,
        )
        assertEquals(4, before.remainingUnits)
        val renewed = before.addPackage(purchasedUnits = 10, giftUnits = 2)
        assertEquals(30, renewed.purchasedUnits)
        assertEquals(16, renewed.remainingUnits)
    }

    @Test
    fun unknownPackageSizeKeepsTheTotalUnknown() {
        val unknown = CreditLedger(null, 0, 0, 10, 0).addPackage(purchasedUnits = 8, giftUnits = 0)
        assertNull(unknown.availableUnits)
        assertNull(unknown.remainingUnits)
        assertEquals(10, unknown.consumedUnits)
    }

    @Test
    fun fifoUsesEachPackagePriceInPurchaseOrder() {
        val metrics = LessonCalculator.calculate(
            packages = listOf(
                PackagePricing(
                    purchasedCreditUnits = 4,
                    giftCreditUnits = 0,
                    amountCents = 20_000,
                    unitPriceCents = 10_000,
                    createdAtEpochMs = 1,
                ),
                PackagePricing(
                    purchasedCreditUnits = 4,
                    giftCreditUnits = 0,
                    amountCents = 40_000,
                    unitPriceCents = 20_000,
                    createdAtEpochMs = 2,
                ),
            ),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 6,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
        )
        assertEquals(8, metrics.totalCreditUnits)
        assertEquals(6, metrics.consumedCreditUnits)
        assertEquals(2, metrics.remainingCreditUnits)
        assertEquals(40_000L, metrics.consumedMoney.knownCents)
        assertTrue(metrics.consumedMoney.complete)
        assertEquals(20_000L, metrics.remainingValue.knownCents)
        assertTrue(metrics.remainingValue.complete)
        assertTrue(metrics.needsRenewal)
    }

    @Test
    fun blankMoneyStaysUnknownAndZeroStaysZero() {
        assertTrue(AmountParser.parseYuanToCents("") is ParseOutcome.Empty)
        assertTrue(AmountParser.parseYuanToCents("  ") is ParseOutcome.Empty)
        assertEquals(0L, (AmountParser.parseYuanToCents("0") as ParseOutcome.Value).value)
        assertEquals(150_050L, (AmountParser.parseYuanToCents("1500.5") as ParseOutcome.Value).value)
        assertEquals(150_000L, (AmountParser.parseYuanToCents("1500.00") as ParseOutcome.Value).value)
        assertTrue(AmountParser.parseYuanToCents("15.555") is ParseOutcome.Invalid)
        assertTrue(LessonParser.parse("") is ParseOutcome.Empty)
        assertEquals(0, (LessonParser.parse("0") as ParseOutcome.Value).value)
        assertEquals(1, (LessonParser.parse("0.5") as ParseOutcome.Value).value)
        assertEquals(3, (LessonParser.parse("1.5") as ParseOutcome.Value).value)
        assertEquals(-2, (LessonParser.parse("-1", allowNegative = true) as ParseOutcome.Value).value)
        assertTrue(LessonParser.parse("1.2") is ParseOutcome.Invalid)
    }

    @Test
    fun batchBackfillSkipsDaysThatAlreadyHaveAttendance() {
        val plan = BackfillPlanner.plan(
            openHistoricalIdsInOrder = listOf("h1", "h2", "h3"),
            start = LocalDate.parse("2026-10-26"),
            occupiedDates = setOf("2026-10-26", "2026-10-24"),
        )
        assertEquals(listOf("2026-10-25", "2026-10-23", "2026-10-22"), plan.map { it.date })
        assertEquals(listOf("h1", "h2", "h3"), plan.map { it.historicalId })
        assertEquals(plan.map { it.date }.distinct().size, plan.size)
    }

    @Test
    fun customThresholdCanWarnEarlierThanThreeLessons() {
        val metrics = LessonCalculator.calculate(
            packages = listOf(PackagePricing(8, 0, 40_000, 10_000, 1)),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 4,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
            renewalThresholdCreditUnits = 4,
        )
        assertEquals(4, metrics.remainingCreditUnits)
        assertFalse(metrics.needsRenewal)
        val tighter = LessonCalculator.calculate(
            packages = listOf(PackagePricing(8, 0, 40_000, 10_000, 1)),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 6,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
            renewalThresholdCreditUnits = 4,
        )
        assertEquals(2, tighter.remainingCreditUnits)
        assertTrue(tighter.needsRenewal)
    }
}
