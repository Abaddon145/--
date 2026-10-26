package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CourseStatus
import java.time.LocalDate
import java.time.YearMonth

data class MonthBar(
    val year: Int,
    val month: Int,
    val value: Long,
    val complete: Boolean,
)

data class YearBar(
    val year: Int,
    val money: MoneyTotal,
)

data class CourseFeeRow(
    val courseId: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val paid: MoneyTotal,
    val consumed: MoneyTotal,
    val remainingValue: MoneyTotal,
    val remainingUnits: Int?,
    val consumedUnits: Int,
    val checkIns: Int,
    val needsRenewal: Boolean,
)

data class FeeReport(
    val paid: MoneyTotal,
    val consumed: MoneyTotal,
    val remainingValue: MoneyTotal,
    val selectedConsumption: MoneyTotal,
    val selectedCheckIns: Int,
    val yearFees: MoneyTotal,
    val courseCount: Int,
    val checkIns: Int,
    val consumedUnits: Int,
    val thisMonthCheckIns: Int,
    val courses: List<CourseFeeRow>,
    val renewal: List<CourseFeeRow>,
    val attendanceByMonth: List<MonthBar>,
    val consumptionByMonth: List<MonthBar>,
    val feesByMonth: List<MonthBar>,
    val feesByYear: List<YearBar>,
)

/**
 * Package amounts are the fee for those lessons. A payment that points at a package
 * is the same money and is not added again. Payments with no package are extra fees.
 * Dated consumption walks the same FIFO buckets as [LessonCalculator], after undated history.
 */
object FeeAnalytics {
    fun report(
        courses: List<CourseDetails>,
        today: LocalDate,
        year: Int,
        month: Int?,
        courseId: String?,
        giftValuation: GiftValuation = GiftValuation.ZERO,
        defaultRenewalUnits: Int = CreditUnits.DEFAULT_RENEWAL_UNITS,
    ): FeeReport {
        val scoped = courses
            .filter { it.status != CourseStatus.ARCHIVED }
            .filter { courseId == null || it.id == courseId }
            .sortedBy { it.sortOrder }
        if (scoped.isEmpty()) {
            return emptyReport(year)
        }
        val rows = scoped.map { course -> courseRow(course, giftValuation, defaultRenewalUnits) }
        val paid = sumMoney(rows.map { it.paid })
        val consumed = sumMoney(rows.map { it.consumed })
        val remaining = sumMoney(rows.map { it.remainingValue })
        val attendanceByMonth = (1..12).map { monthNumber ->
            MonthBar(
                year = year,
                month = monthNumber,
                value = scoped.sumOf { course ->
                    course.attendances.count { attendanceMonth(it) == YearMonth.of(year, monthNumber) }.toLong()
                },
                complete = true,
            )
        }
        val consumptionByMonth = consumptionMonths(scoped, year, giftValuation)
        val feesByMonth = (1..12).map { monthNumber ->
            val money = periodFees(scoped, year, monthNumber)
            MonthBar(year, monthNumber, money.knownCents, money.complete)
        }
        val selectedConsumption = if (month == null) {
            sumMoney(consumptionByMonth.map { MoneyTotal(it.value, it.complete) })
        } else {
            val bar = consumptionByMonth.first { it.month == month }
            MoneyTotal(bar.value, bar.complete)
        }
        val selectedCheckIns = if (month == null) {
            attendanceByMonth.sumOf { it.value }.toInt()
        } else {
            attendanceByMonth.first { it.month == month }.value.toInt()
        }
        val thisMonth = YearMonth.from(today)
        val thisMonthCheckIns = scoped.sumOf { course ->
            course.attendances.count { attendanceMonth(it) == thisMonth }
        }
        return FeeReport(
            paid = paid,
            consumed = consumed,
            remainingValue = remaining,
            selectedConsumption = selectedConsumption,
            selectedCheckIns = selectedCheckIns,
            yearFees = periodFees(scoped, year, month = null),
            courseCount = scoped.size,
            checkIns = scoped.sumOf { it.attendances.size + it.historicalUnrecorded.size },
            consumedUnits = rows.sumOf { it.consumedUnits },
            thisMonthCheckIns = thisMonthCheckIns,
            courses = rows.sortedWith(compareByDescending<CourseFeeRow> { it.paid.knownCents }.thenBy { it.name }),
            renewal = rows.filter { it.needsRenewal },
            attendanceByMonth = attendanceByMonth,
            consumptionByMonth = consumptionByMonth,
            feesByMonth = feesByMonth,
            feesByYear = yearBars(scoped, year, today.year),
        )
    }

    fun allTimePaid(course: CourseDetails): MoneyTotal {
        val extras = course.payments.filter { it.packageId == null }
        if (course.packages.isEmpty() && extras.isEmpty()) return MoneyTotal.UNKNOWN
        val packageMoney = amounts(course.packages.map { it.amountCents })
        val extraMoney = amounts(extras.map { it.amountCents })
        if (course.packages.isEmpty()) return extraMoney
        if (extras.isEmpty()) return packageMoney
        return MoneyTotal(
            knownCents = packageMoney.knownCents + extraMoney.knownCents,
            complete = packageMoney.complete && extraMoney.complete,
        )
    }

    private fun courseRow(
        course: CourseDetails,
        giftValuation: GiftValuation,
        defaultRenewalUnits: Int,
    ): CourseFeeRow {
        val metrics = course.metrics(defaultRenewalUnits, giftValuation)
        return CourseFeeRow(
            courseId = course.id,
            name = course.name,
            iconKey = course.iconKey,
            colorHex = course.colorHex,
            paid = allTimePaid(course),
            consumed = metrics.consumedMoney,
            remainingValue = metrics.remainingValue,
            remainingUnits = metrics.remainingCreditUnits,
            consumedUnits = metrics.consumedCreditUnits,
            checkIns = course.attendances.size + course.historicalUnrecorded.size,
            needsRenewal = metrics.needsRenewal,
        )
    }

    private fun consumptionMonths(
        courses: List<CourseDetails>,
        year: Int,
        giftValuation: GiftValuation,
    ): List<MonthBar> {
        val totals = (1..12).associateWith { MoneyTotal.known(0) }.toMutableMap()
        courses.forEach { course ->
            val slices = consumptionSlices(course)
            val costs = LessonCalculator.allocate(
                packages = course.packages.map { pkg ->
                    PackagePricing(
                        purchasedCreditUnits = pkg.purchasedCreditUnits,
                        giftCreditUnits = pkg.giftCreditUnits,
                        amountCents = pkg.amountCents,
                        unitPriceCents = pkg.unitPriceCents,
                        createdAtEpochMs = pkg.createdAtEpochMs,
                    )
                },
                slices = slices.map { ConsumptionSlice(it.key, it.units) },
                giftValuation = giftValuation,
            )
            val byKey = costs.associateBy { it.key }
            slices.forEach { slice ->
                val month = slice.month ?: return@forEach
                if (month.year != year) return@forEach
                val money = byKey[slice.key]?.money ?: MoneyTotal.UNKNOWN
                val current = totals.getValue(month.monthValue)
                totals[month.monthValue] = MoneyTotal(
                    knownCents = current.knownCents + money.knownCents,
                    complete = current.complete && money.complete,
                )
            }
        }
        return (1..12).map { month ->
            val money = totals.getValue(month)
            MonthBar(year, month, money.knownCents, money.complete)
        }
    }

    private fun consumptionSlices(course: CourseDetails): List<DatedSlice> {
        val dated = course.attendances.sortedWith(compareBy({ it.attendanceDate }, { it.createdAtEpochMs }, { it.id }))
        return buildList {
            val historical = course.historicalUnrecorded.sumOf { it.creditUnits }
            if (historical > 0) add(DatedSlice("historical:${course.id}", historical, null))
            dated.forEach { attendance ->
                add(DatedSlice(attendance.id, attendance.creditUnits, attendanceMonth(attendance)))
            }
        }
    }

    private fun periodFees(courses: List<CourseDetails>, year: Int, month: Int?): MoneyTotal {
        val entries = courses.flatMap { feeEntries(it) }
        if (entries.isEmpty()) return MoneyTotal.UNKNOWN
        var cents = 0L
        var complete = true
        entries.forEach { entry ->
            val date = entry.paidOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val inPeriod = date != null && date.year == year && (month == null || date.monthValue == month)
            when {
                entry.amountCents == null -> complete = false
                date == null -> complete = false
                inPeriod -> cents += entry.amountCents
            }
        }
        return MoneyTotal(knownCents = cents, complete = complete)
    }

    private fun feeEntries(course: CourseDetails): List<FeeEntry> {
        val packages = course.packages.map { FeeEntry(it.amountCents, it.paidOn) }
        val extras = course.payments.filter { it.packageId == null }.map { FeeEntry(it.amountCents, it.paidOn) }
        return packages + extras
    }

    private fun yearBars(courses: List<CourseDetails>, selectedYear: Int, todayYear: Int): List<YearBar> {
        val years = mutableSetOf(selectedYear, todayYear)
        courses.flatMap { feeEntries(it) }.forEach { entry ->
            entry.paidOn?.let { raw ->
                runCatching { LocalDate.parse(raw).year }.getOrNull()?.let { years += it }
            }
        }
        courses.flatMap { it.attendances }.forEach { attendance ->
            attendanceMonth(attendance)?.year?.let { years += it }
        }
        return years.sorted().map { year -> YearBar(year, periodFees(courses, year, month = null)) }
    }

    private fun attendanceMonth(attendance: AttendanceInfo): YearMonth? {
        return runCatching { YearMonth.from(LocalDate.parse(attendance.attendanceDate)) }.getOrNull()
    }

    private fun amounts(values: List<Long?>): MoneyTotal {
        if (values.isEmpty()) return MoneyTotal.known(0)
        return MoneyTotal(
            knownCents = values.sumOf { it ?: 0L },
            complete = values.all { it != null },
        )
    }

    private fun sumMoney(parts: List<MoneyTotal>): MoneyTotal {
        if (parts.isEmpty()) return MoneyTotal.known(0)
        return MoneyTotal(
            knownCents = parts.sumOf { it.knownCents },
            complete = parts.all { it.complete },
        )
    }

    private fun emptyReport(year: Int): FeeReport {
        val months = (1..12).map { MonthBar(year, it, 0, true) }
        return FeeReport(
            paid = MoneyTotal.known(0),
            consumed = MoneyTotal.known(0),
            remainingValue = MoneyTotal.known(0),
            selectedConsumption = MoneyTotal.known(0),
            selectedCheckIns = 0,
            yearFees = MoneyTotal.known(0),
            courseCount = 0,
            checkIns = 0,
            consumedUnits = 0,
            thisMonthCheckIns = 0,
            courses = emptyList(),
            renewal = emptyList(),
            attendanceByMonth = months,
            consumptionByMonth = months,
            feesByMonth = months.map { it.copy(complete = true) },
            feesByYear = listOf(YearBar(year, MoneyTotal.known(0))),
        )
    }

    private data class DatedSlice(val key: String, val units: Int, val month: YearMonth?)
    private data class FeeEntry(val amountCents: Long?, val paidOn: String?)
}
