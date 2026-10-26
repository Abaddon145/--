package com.luca.classmanager.domain

import com.luca.classmanager.domain.logic.FeeAnalytics
import com.luca.classmanager.domain.logic.GiftValuation
import com.luca.classmanager.domain.logic.LessonCalculator
import com.luca.classmanager.domain.logic.MoneyTotal
import com.luca.classmanager.domain.logic.PackagePricing
import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CoursePackageInfo
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.PaymentInfo
import com.luca.classmanager.domain.model.ScheduleMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4RulesTest {
    private val today = LocalDate.parse("2026-10-26")

    @Test
    fun fifoMultiPriceSplitsAcrossMonthsAndMatchesTheTotal() {
        val course = course(
            packages = listOf(
                pkg("a", purchased = 4, amount = 20_000, unit = 10_000, created = 1, paidOn = "2026-01-15"),
                pkg("b", purchased = 4, amount = 40_000, unit = 20_000, created = 2, paidOn = "2026-03-02"),
            ),
            attendances = listOf(
                attendance("oct-1", "2026-10-01", 2),
                attendance("oct-2", "2026-10-20", 2),
                attendance("nov", "2026-11-02", 2),
            ),
        )
        val report = FeeAnalytics.report(listOf(course), today, year = 2026, month = 10, courseId = null)
        assertEquals(MoneyTotal.known(40_000), report.consumed)
        assertEquals(MoneyTotal.known(20_000), report.remainingValue)
        assertEquals(60_000L, report.paid.knownCents)
        assertTrue(report.paid.complete)
        assertEquals(MoneyTotal.known(20_000), report.selectedConsumption)
        assertEquals(2, report.selectedCheckIns)
        val november = report.consumptionByMonth.first { it.month == 11 }
        assertEquals(20_000L, november.value)
        assertTrue(november.complete)
        assertEquals(MoneyTotal.known(60_000), report.yearFees)
        val metrics = LessonCalculator.calculate(
            packages = course.packages.map {
                PackagePricing(it.purchasedCreditUnits, it.giftCreditUnits, it.amountCents, it.unitPriceCents, it.createdAtEpochMs)
            },
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 6,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
        )
        assertEquals(metrics.consumedMoney, report.consumed)
    }

    @Test
    fun unknownMoneyStaysIncompleteAndLinkedPaymentsAreNotAddedTwice() {
        val unknown = course(
            id = "think",
            name = "学而思思维",
            packages = listOf(pkg("u", purchased = 20, amount = null, unit = null, created = 1, paidOn = null)),
            payments = listOf(payment("mirror", packageId = "u", amount = null, paidOn = null)),
        )
        val dated = course(
            packages = listOf(pkg("a", purchased = 4, amount = 20_000, unit = 10_000, created = 1, paidOn = null)),
            payments = listOf(
                payment("mirror", packageId = "a", amount = 20_000, paidOn = "2026-01-01"),
                payment("extra", packageId = null, amount = 5_000, paidOn = "2026-03-02"),
                payment("blank", packageId = null, amount = null, paidOn = "2026-04-01"),
            ),
        )
        val unknownReport = FeeAnalytics.report(listOf(unknown), today, 2026, 10, null)
        assertFalse(unknownReport.paid.complete)
        assertEquals(0L, unknownReport.paid.knownCents)
        assertFalse(unknownReport.paid == MoneyTotal.known(0))
        assertFalse(unknownReport.yearFees.complete)

        val mixed = FeeAnalytics.report(listOf(dated), today, 2026, null, null)
        assertEquals(25_000L, mixed.paid.knownCents)
        assertFalse(mixed.paid.complete)
        assertEquals(5_000L, mixed.yearFees.knownCents)
        assertFalse(mixed.yearFees.complete)
        assertFalse(mixed.feesByMonth.first { it.month == 3 }.complete)
        assertEquals(5_000L, mixed.feesByMonth.first { it.month == 3 }.value)

        val none = FeeAnalytics.report(emptyList(), today, 2026, 10, null)
        assertEquals(MoneyTotal.known(0), none.paid)
        val bare = FeeAnalytics.report(listOf(course()), today, 2026, 10, null)
        assertFalse(bare.paid.complete)
        assertFalse(bare.paid == MoneyTotal.known(0))
    }

    @Test
    fun monthlyAndYearlyRollupsFollowRealDates() {
        val course = course(
            packages = listOf(
                pkg("old", purchased = 2, amount = 8_000, unit = 8_000, created = 1, paidOn = "2025-12-01"),
                pkg("jan", purchased = 2, amount = 20_000, unit = 20_000, created = 2, paidOn = "2026-01-15"),
            ),
            payments = listOf(payment("mar", packageId = null, amount = 5_000, paidOn = "2026-03-02")),
            attendances = listOf(
                attendance("j", "2026-01-20", 2),
                attendance("m", "2026-03-03", 2),
            ),
        )
        val year = FeeAnalytics.report(listOf(course), today, year = 2026, month = null, courseId = null)
        assertEquals(MoneyTotal.known(25_000), year.yearFees)
        assertEquals(1, year.attendanceByMonth.first { it.month == 1 }.value)
        assertEquals(1, year.attendanceByMonth.first { it.month == 3 }.value)
        assertEquals(0L, year.attendanceByMonth.first { it.month == 2 }.value)
        assertEquals(20_000L, year.feesByMonth.first { it.month == 1 }.value)
        assertEquals(5_000L, year.feesByMonth.first { it.month == 3 }.value)
        assertEquals(8_000L, year.feesByYear.first { it.year == 2025 }.money.knownCents)
        assertTrue(year.feesByYear.first { it.year == 2025 }.money.complete)
        val january = FeeAnalytics.report(listOf(course), today, year = 2026, month = 1, courseId = null)
        assertEquals(1, january.selectedCheckIns)
        assertEquals(8_000L, january.selectedConsumption.knownCents)
        assertTrue(january.selectedConsumption.complete)
    }

    @Test
    fun giftLessonsCostNothingUnlessThePurchasedPriceIsAlreadyKnown() {
        val gifted = course(
            packages = listOf(pkg("g", purchased = 4, gift = 2, amount = 20_000, unit = 10_000, created = 1, paidOn = "2026-02-01")),
            attendances = listOf(attendance("all", "2026-02-02", 6)),
        )
        val free = FeeAnalytics.report(listOf(gifted), today, 2026, 2, null, GiftValuation.ZERO)
        assertEquals(MoneyTotal.known(20_000), free.consumed)
        assertEquals(MoneyTotal.known(0), free.remainingValue)
        val matched = FeeAnalytics.report(listOf(gifted), today, 2026, 2, null, GiftValuation.MATCH_PURCHASED)
        assertEquals(MoneyTotal.known(30_000), matched.consumed)
        assertEquals(MoneyTotal.known(0), matched.remainingValue)

        val unpriced = course(
            packages = listOf(pkg("only-gift", purchased = 0, gift = 2, amount = null, unit = null, created = 1, paidOn = null)),
            attendances = listOf(attendance("g", "2026-02-02", 2)),
        )
        val stillFree = LessonCalculator.calculate(
            packages = listOf(PackagePricing(0, 2, null, null, 1)),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 2,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
            giftValuation = GiftValuation.ZERO,
        )
        assertEquals(MoneyTotal.known(0), stillFree.consumedMoney)
        val notInvented = LessonCalculator.calculate(
            packages = listOf(PackagePricing(0, 2, null, null, 1)),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 2,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
            giftValuation = GiftValuation.MATCH_PURCHASED,
        )
        assertFalse(notInvented.consumedMoney.complete)
        assertFalse(notInvented.consumedMoney == MoneyTotal.known(0))
        val report = FeeAnalytics.report(listOf(unpriced), today, 2026, 2, null, GiftValuation.MATCH_PURCHASED)
        assertFalse(report.selectedConsumption.complete)
        assertFalse(report.selectedConsumption == MoneyTotal.known(0))
    }

    private fun attendance(id: String, date: String, units: Int) = AttendanceInfo(
        id = id,
        attendanceDate = date,
        creditUnits = units,
        note = null,
        historicalSourceId = null,
        photoPath = null,
        createdAtEpochMs = 1,
    )

    private fun payment(id: String, packageId: String?, amount: Long?, paidOn: String?) = PaymentInfo(
        id = id,
        packageId = packageId,
        amountCents = amount,
        paidOn = paidOn,
        note = null,
        createdAtEpochMs = 1,
    )

    private fun pkg(
        id: String,
        purchased: Int?,
        gift: Int = 0,
        amount: Long?,
        unit: Long?,
        created: Long,
        paidOn: String?,
    ) = CoursePackageInfo(id, purchased, gift, amount, unit, paidOn, null, created)

    private fun course(
        id: String = "ball",
        name: String = "篮球",
        packages: List<CoursePackageInfo> = emptyList(),
        payments: List<PaymentInfo> = emptyList(),
        attendances: List<AttendanceInfo> = emptyList(),
    ) = CourseDetails(
        id = id,
        name = name,
        iconKey = "🏀",
        colorHex = "#E9A56B",
        institution = null,
        location = null,
        notes = null,
        scheduleMode = ScheduleMode.WEEKLY,
        status = CourseStatus.ACTIVE,
        sortOrder = 0,
        rules = emptyList(),
        packages = packages,
        adjustmentCreditUnits = 0,
        attendanceCreditUnits = attendances.sumOf { it.creditUnits },
        recordedAttendanceDates = attendances.map { it.attendanceDate },
        historicalUnrecorded = emptyList(),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        payments = payments,
        attendances = attendances,
    )
}
