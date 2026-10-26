package com.luca.classmanager.domain

import com.luca.classmanager.data.local.LucaMigrations
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.HistoricalSeedRecord
import com.luca.classmanager.domain.logic.HomeBoardBuilder
import com.luca.classmanager.domain.logic.LessonCalculator
import com.luca.classmanager.domain.logic.MoneyTotal
import com.luca.classmanager.domain.logic.PackagePricing
import com.luca.classmanager.domain.logic.SchedulePlanner
import com.luca.classmanager.domain.logic.SeedCatalog
import com.luca.classmanager.domain.logic.SeedPolicy
import com.luca.classmanager.domain.logic.WeeklySlot
import com.luca.classmanager.domain.logic.deletionLines
import com.luca.classmanager.domain.logic.formatCents
import com.luca.classmanager.domain.logic.formatMoneyTotal
import com.luca.classmanager.domain.logic.metrics
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CoursePackageInfo
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.DeletionImpact
import com.luca.classmanager.domain.model.HistoricalCredit
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRule
import com.luca.classmanager.domain.model.SessionTimeStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1RulesTest {
    @Test
    fun creditUnitsUseHalfLessonIntegers() {
        assertEquals(2, CreditUnits.PER_LESSON)
        assertEquals("0", CreditUnits.format(0))
        assertEquals("0.5", CreditUnits.format(1))
        assertEquals("1", CreditUnits.format(2))
        assertEquals("1.5", CreditUnits.format(3))
        assertEquals("15", CreditUnits.format(30))
        assertEquals("-0.5", CreditUnits.format(-1))
    }

    @Test
    fun moneyStaysInIntegerCents() {
        assertEquals("0.00 元", formatCents(0))
        assertEquals("0.01 元", formatCents(1))
        assertEquals("0.99 元", formatCents(99))
        assertEquals("1.00 元", formatCents(100))
        assertEquals("1500.00 元", formatCents(150_000))
        assertEquals("-1500.00 元", formatCents(-150_000))
        assertEquals("待补充", formatMoneyTotal(MoneyTotal.UNKNOWN))
        assertEquals("待补充（已知 2800.00 元）", formatMoneyTotal(MoneyTotal(280_000, complete = false)))
        assertEquals("2490.00 元", formatMoneyTotal(MoneyTotal.known(249_000)))
    }

    @Test
    fun seedMatchesSectionFifteenAndDoesNotInventDates() {
        val snapshot = SeedCatalog.build(createdAtEpochMs = 1_700_000_000_000)
        assertEquals(listOf("篮球", "学而思思维", "羽毛球"), snapshot.courses.map { it.name })

        val basketball = snapshot.packages.first { it.courseId == "seed-course-basketball" }
        val thinking = snapshot.packages.first { it.courseId == "seed-course-xueersi" }
        val badminton = snapshot.packages.first { it.courseId == "seed-course-badminton" }
        val basketballRule = snapshot.rules.first { it.courseId == basketball.courseId }
        val thinkingRule = snapshot.rules.first { it.courseId == thinking.courseId }
        val badmintonRule = snapshot.rules.first { it.courseId == badminton.courseId }

        assertEquals(6, basketballRule.dayOfWeek)
        assertEquals(16 * 60, basketballRule.startMinutes)
        assertEquals(17 * 60 + 30, basketballRule.endMinutes)
        assertEquals(30, basketball.purchasedCreditUnits)
        assertEquals(150_000L, basketball.amountCents)
        assertEquals(10_000L, basketball.unitPriceCents)
        assertNull(basketball.paidOn)

        assertEquals(7, thinkingRule.dayOfWeek)
        assertEquals(8 * 60, thinkingRule.startMinutes)
        assertEquals(10 * 60, thinkingRule.endMinutes)
        assertNull(thinking.purchasedCreditUnits)
        assertNull(thinking.amountCents)
        assertEquals(25_000L, thinking.unitPriceCents)
        assertNull(thinking.paidOn)

        assertEquals(7, badmintonRule.dayOfWeek)
        assertEquals(16 * 60, badmintonRule.startMinutes)
        assertEquals(17 * 60, badmintonRule.endMinutes)
        assertEquals(20, badminton.purchasedCreditUnits)
        assertEquals(130_000L, badminton.amountCents)
        assertEquals(13_000L, badminton.unitPriceCents)
        assertNull(badminton.paidOn)

        assertEquals(2, snapshot.historical.count { it.courseId == basketball.courseId })
        assertEquals(5, snapshot.historical.count { it.courseId == thinking.courseId })
        assertEquals(8, snapshot.historical.count { it.courseId == badminton.courseId })
        assertTrue(snapshot.historical.all { it.creditUnits == CreditUnits.PER_LESSON })
        assertTrue(snapshot.historical.all { it.note == SeedCatalog.HISTORICAL_NOTE })
        assertEquals(15, snapshot.historical.size)
        val historicalFields = HistoricalSeedRecord::class.java.declaredFields.map { it.name }
        assertTrue(historicalFields.containsAll(listOf("id", "courseId", "creditUnits", "note")))
        assertTrue(
            historicalFields.none { it == "date" || it == "attendanceDate" || it == "eventDate" || it == "paidOn" },
        )
    }

    @Test
    fun unknownTotalsStayUnknownAndKnownConsumptionIsPriced() {
        val snapshot = SeedCatalog.build(0)
        val metrics = snapshot.courses.associate { course ->
            val pkg = snapshot.packages.first { it.courseId == course.id }
            val historical = snapshot.historical.filter { it.courseId == course.id }
            course.name to LessonCalculator.calculate(
                packages = listOf(
                    PackagePricing(
                        purchasedCreditUnits = pkg.purchasedCreditUnits,
                        giftCreditUnits = pkg.giftCreditUnits,
                        amountCents = pkg.amountCents,
                        unitPriceCents = pkg.unitPriceCents,
                        createdAtEpochMs = pkg.createdAtEpochMs,
                    ),
                ),
                adjustmentCreditUnits = 0,
                attendanceCreditUnits = 0,
                historicalUnrecordedUnits = historical.sumOf { it.creditUnits },
                historicalUnrecordedCount = historical.size,
            )
        }
        val basketball = metrics.getValue("篮球")
        assertEquals(30, basketball.totalCreditUnits)
        assertEquals(4, basketball.consumedCreditUnits)
        assertEquals(26, basketball.remainingCreditUnits)
        assertEquals(MoneyTotal.known(150_000), basketball.paid)
        assertEquals(MoneyTotal.known(20_000), basketball.consumedMoney)
        assertEquals(MoneyTotal.known(130_000), basketball.remainingValue)
        assertFalse(basketball.needsRenewal)

        val thinking = metrics.getValue("学而思思维")
        assertNull(thinking.totalCreditUnits)
        assertEquals(10, thinking.consumedCreditUnits)
        assertNull(thinking.remainingCreditUnits)
        assertFalse(thinking.paid.complete)
        assertEquals(0L, thinking.paid.knownCents)
        assertEquals(MoneyTotal.known(125_000), thinking.consumedMoney)
        assertFalse(thinking.remainingValue.complete)
        assertFalse(thinking.needsRenewal)

        val badminton = metrics.getValue("羽毛球")
        assertEquals(20, badminton.totalCreditUnits)
        assertEquals(16, badminton.consumedCreditUnits)
        assertEquals(4, badminton.remainingCreditUnits)
        assertEquals(MoneyTotal.known(130_000), badminton.paid)
        assertEquals(MoneyTotal.known(104_000), badminton.consumedMoney)
        assertEquals(MoneyTotal.known(26_000), badminton.remainingValue)
        assertTrue(badminton.needsRenewal)

        val rollup = LessonCalculator.rollup(metrics.values.toList())
        assertEquals(MoneyTotal(280_000, complete = false), rollup.paid)
        assertEquals(MoneyTotal.known(249_000), rollup.consumed)
        assertEquals(MoneyTotal(156_000, complete = false), rollup.remainingValue)
    }

    @Test
    fun renewalStartsOnlyBelowThreeLessons() {
        val exactlyThree = metricsFor(totalUnits = 6, consumedUnits = 0, unitPrice = 10_000, amount = 30_000)
        val justUnder = metricsFor(totalUnits = 6, consumedUnits = 1, unitPrice = 10_000, amount = 30_000)
        assertFalse(exactlyThree.needsRenewal)
        assertTrue(justUnder.needsRenewal)
    }

    @Test
    fun giftLessonsAreFreeAndUnknownSizeWithoutPriceStaysUnknown() {
        val gifted = LessonCalculator.calculate(
            packages = listOf(
                PackagePricing(
                    purchasedCreditUnits = 4,
                    giftCreditUnits = 2,
                    amountCents = 20_000,
                    unitPriceCents = 10_000,
                    createdAtEpochMs = 1,
                ),
            ),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 6,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
        )
        assertEquals(6, gifted.totalCreditUnits)
        assertEquals(0, gifted.remainingCreditUnits)
        assertEquals(MoneyTotal.known(20_000), gifted.consumedMoney)
        assertEquals(MoneyTotal.known(0), gifted.remainingValue)

        val unknown = LessonCalculator.calculate(
            packages = listOf(
                PackagePricing(null, 0, null, null, 1),
            ),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 4,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
        )
        assertNull(unknown.totalCreditUnits)
        assertFalse(unknown.consumedMoney.complete)
        assertFalse(unknown.paid.complete)
    }

    @Test
    fun emptyCourseIsNotTreatedAsZeroLessonsOrZeroFee() {
        val metrics = LessonCalculator.calculate(
            packages = emptyList(),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = 0,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
        )
        assertNull(metrics.totalCreditUnits)
        assertEquals(0, metrics.consumedCreditUnits)
        assertNull(metrics.remainingCreditUnits)
        assertFalse(metrics.paid.complete)
        assertEquals(MoneyTotal.known(0), metrics.consumedMoney)
        assertFalse(metrics.remainingValue.complete)
    }

    @Test
    fun seedIsWrittenOnlyOnFirstEmptyDatabase() {
        assertTrue(SeedPolicy.shouldInsert(seedCompleted = false, existingCourses = 0))
        assertFalse(SeedPolicy.shouldInsert(seedCompleted = true, existingCourses = 0))
        assertFalse(SeedPolicy.shouldInsert(seedCompleted = false, existingCourses = 3))
        assertFalse(SeedPolicy.shouldInsert(seedCompleted = true, existingCourses = 3))
    }

    @Test
    fun weeklyScheduleDoesNotInventAClassThatAlreadyStarted() {
        val saturday = LocalDate.of(2026, 10, 24)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val slots = listOf(
            WeeklySlot("basketball", 6, 16 * 60, 17 * 60 + 30),
            WeeklySlot("thinking", 7, 8 * 60, 10 * 60),
            WeeklySlot("badminton", 7, 16 * 60, 17 * 60),
        )
        val beforeBasketball = SchedulePlanner.nextStartAfter(saturday.atTime(15, 0), slots)
        assertEquals(saturday, beforeBasketball?.date)
        assertEquals("basketball", beforeBasketball?.courseId)

        val duringThinking = LocalDate.of(2026, 10, 25).atTime(9, 0)
        val next = SchedulePlanner.nextStartAfter(duringThinking, slots)
        assertEquals(LocalDate.of(2026, 10, 25), next?.date)
        assertEquals("badminton", next?.courseId)

        val monday = SchedulePlanner.nextStartAfter(LocalDate.of(2026, 10, 26).atTime(10, 0), slots)
        assertEquals(LocalDate.of(2026, 10, 31), monday?.date)
        assertEquals("basketball", monday?.courseId)
        assertEquals(
            SessionTimeStatus.IN_PROGRESS,
            SchedulePlanner.statusAt(duringThinking, duringThinking.toLocalDate(), 8 * 60, 10 * 60),
        )
    }

    @Test
    fun homeBoardReadsScheduleAndSkipsPausedCourses() {
        val saturdayMorning = LocalDateTime.of(2026, 10, 24, 15, 0)
        val board = HomeBoardBuilder.build(
            listOf(
                course("basketball", "篮球", CourseStatus.ACTIVE, rule(6, 16 * 60, 17 * 60 + 30)),
                course("thinking", "学而思思维", CourseStatus.PAUSED, rule(6, 9 * 60, 10 * 60)),
                course("flex", "临时绘画", CourseStatus.ACTIVE, rules = emptyList(), mode = ScheduleMode.FLEXIBLE),
            ),
            saturdayMorning,
        )
        assertEquals(1, board.todaySessionCount)
        assertEquals("篮球", board.todaySessions.single().courseName)
        assertEquals(SessionTimeStatus.NOT_STARTED, board.todaySessions.single().timeStatus)
        assertEquals(listOf("篮球", "临时绘画"), board.activeCourses.map { it.name })
        assertEquals(listOf("学而思思维"), board.pausedCourses.map { it.name })
        assertNull(board.nextSession)
    }

    @Test
    fun deletionCopyListsRelatedRecords() {
        val lines = deletionLines(
            DeletionImpact(
                courseId = "1",
                courseName = "篮球",
                archived = false,
                scheduleRuleCount = 1,
                packageCount = 1,
                historicalAttendanceCount = 2,
                attendanceCount = 0,
                paymentCount = 0,
                adjustmentCount = 0,
                eventCount = 0,
                reminderCount = 0,
                attachmentCount = 0,
            ),
        )
        assertTrue(lines.contains("每周排课规则 1 条"))
        assertTrue(lines.contains("历史未补录记录 2 条"))
        assertTrue(lines.contains("打卡记录 0 条"))
    }

    @Test
    fun databaseUpgradeAddsThresholdWithoutDestructiveFallback() {
        assertEquals(2, LucaMigrations.VERSION)
        assertEquals("luca_interest_classroom.db", LucaMigrations.NAME)
        assertEquals(1, LucaMigrations.ALL.size)
        assertEquals(1, LucaMigrations.ALL[0].startVersion)
        assertEquals(2, LucaMigrations.ALL[0].endVersion)
        assertTrue(LucaMigrations.ADD_RENEWAL_THRESHOLD.startsWith("ALTER TABLE"))
        assertFalse(LucaMigrations.ADD_RENEWAL_THRESHOLD.contains("DROP"))
    }

    private fun metricsFor(totalUnits: Int, consumedUnits: Int, unitPrice: Long, amount: Long) =
        LessonCalculator.calculate(
            packages = listOf(PackagePricing(totalUnits, 0, amount, unitPrice, 1)),
            adjustmentCreditUnits = 0,
            attendanceCreditUnits = consumedUnits,
            historicalUnrecordedUnits = 0,
            historicalUnrecordedCount = 0,
        )

    private fun rule(day: Int, start: Int, end: Int) = ScheduleRule("rule-$day-$start", day, start, end)

    private fun course(
        id: String,
        name: String,
        status: CourseStatus,
        rule: ScheduleRule? = null,
        rules: List<ScheduleRule> = listOfNotNull(rule),
        mode: ScheduleMode = ScheduleMode.WEEKLY,
    ) = CourseDetails(
        id = id,
        name = name,
        iconKey = "📚",
        colorHex = "#7A9E8E",
        institution = null,
        location = null,
        notes = null,
        scheduleMode = mode,
        status = status,
        sortOrder = 0,
        rules = rules,
        packages = listOf(
            CoursePackageInfo("pkg-$id", 4, 0, 20_000, 10_000, null, null, 1),
        ),
        adjustmentCreditUnits = 0,
        attendanceCreditUnits = 0,
        recordedAttendanceDates = emptyList(),
        historicalUnrecorded = listOf(HistoricalCredit("h-$id", 2)),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
