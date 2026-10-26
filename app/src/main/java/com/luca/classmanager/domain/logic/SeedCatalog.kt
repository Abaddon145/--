package com.luca.classmanager.domain.logic

import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.ScheduleMode

/**
 * First-install courses from the product spec, section 十五.
 * Historical attendance is a count of undated records. This model has no date field.
 */
object SeedCatalog {
    const val HISTORICAL_NOTE = "首次安装预置：尚未补录具体上课日期"

    fun build(createdAtEpochMs: Long): SeedSnapshot {
        val specs = listOf(
            spec(
                id = "seed-course-basketball",
                name = "篮球",
                iconKey = "🏀",
                colorHex = "#E9A56B",
                dayOfWeek = 6,
                startMinutes = 16 * 60,
                endMinutes = 17 * 60 + 30,
                purchasedCreditUnits = 15 * CreditUnits.PER_LESSON,
                amountCents = 150_000L,
                unitPriceCents = 10_000L,
                historicalSessionCount = 2,
            ),
            spec(
                id = "seed-course-xueersi",
                name = "学而思思维",
                iconKey = "🧠",
                colorHex = "#8C8CC7",
                dayOfWeek = 7,
                startMinutes = 8 * 60,
                endMinutes = 10 * 60,
                purchasedCreditUnits = null,
                amountCents = null,
                unitPriceCents = 25_000L,
                historicalSessionCount = 5,
            ),
            spec(
                id = "seed-course-badminton",
                name = "羽毛球",
                iconKey = "🏸",
                colorHex = "#7A9E8E",
                dayOfWeek = 7,
                startMinutes = 16 * 60,
                endMinutes = 17 * 60,
                purchasedCreditUnits = 10 * CreditUnits.PER_LESSON,
                amountCents = 130_000L,
                unitPriceCents = 13_000L,
                historicalSessionCount = 8,
            ),
        )
        return SeedSnapshot(
            courses = specs.mapIndexed { index, item -> item.course(index, createdAtEpochMs) },
            rules = specs.map { it.rule(createdAtEpochMs) },
            packages = specs.map { it.pkg(createdAtEpochMs) },
            historical = specs.flatMap { it.historical(createdAtEpochMs) },
        )
    }

    private fun spec(
        id: String,
        name: String,
        iconKey: String,
        colorHex: String,
        dayOfWeek: Int,
        startMinutes: Int,
        endMinutes: Int,
        purchasedCreditUnits: Int?,
        amountCents: Long?,
        unitPriceCents: Long?,
        historicalSessionCount: Int,
    ) = Spec(
        id = id,
        name = name,
        iconKey = iconKey,
        colorHex = colorHex,
        dayOfWeek = dayOfWeek,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        purchasedCreditUnits = purchasedCreditUnits,
        amountCents = amountCents,
        unitPriceCents = unitPriceCents,
        historicalSessionCount = historicalSessionCount,
    )

    private data class Spec(
        val id: String,
        val name: String,
        val iconKey: String,
        val colorHex: String,
        val dayOfWeek: Int,
        val startMinutes: Int,
        val endMinutes: Int,
        val purchasedCreditUnits: Int?,
        val amountCents: Long?,
        val unitPriceCents: Long?,
        val historicalSessionCount: Int,
    ) {
        fun course(sortOrder: Int, createdAtEpochMs: Long) = SeedCourseRow(
            id = id,
            name = name,
            iconKey = iconKey,
            colorHex = colorHex,
            scheduleMode = ScheduleMode.WEEKLY,
            status = CourseStatus.ACTIVE,
            sortOrder = sortOrder,
            createdAtEpochMs = createdAtEpochMs,
        )

        fun rule(createdAtEpochMs: Long) = SeedRuleRow(
            id = "$id-rule",
            courseId = id,
            dayOfWeek = dayOfWeek,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            createdAtEpochMs = createdAtEpochMs,
        )

        fun pkg(createdAtEpochMs: Long) = SeedPackageRow(
            id = "$id-pkg",
            courseId = id,
            purchasedCreditUnits = purchasedCreditUnits,
            giftCreditUnits = 0,
            amountCents = amountCents,
            unitPriceCents = unitPriceCents,
            paidOn = null,
            createdAtEpochMs = createdAtEpochMs,
        )

        fun historical(createdAtEpochMs: Long): List<HistoricalSeedRecord> {
            return List(historicalSessionCount) { index ->
                HistoricalSeedRecord(
                    id = "$id-hist-${index + 1}",
                    courseId = id,
                    creditUnits = CreditUnits.PER_LESSON,
                    note = HISTORICAL_NOTE,
                    createdAtEpochMs = createdAtEpochMs,
                )
            }
        }
    }
}

data class SeedSnapshot(
    val courses: List<SeedCourseRow>,
    val rules: List<SeedRuleRow>,
    val packages: List<SeedPackageRow>,
    val historical: List<HistoricalSeedRecord>,
)

data class SeedCourseRow(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val scheduleMode: ScheduleMode,
    val status: CourseStatus,
    val sortOrder: Int,
    val createdAtEpochMs: Long,
)

data class SeedRuleRow(
    val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val createdAtEpochMs: Long,
)

data class SeedPackageRow(
    val id: String,
    val courseId: String,
    val purchasedCreditUnits: Int?,
    val giftCreditUnits: Int,
    val amountCents: Long?,
    val unitPriceCents: Long?,
    val paidOn: String?,
    val createdAtEpochMs: Long,
)

data class HistoricalSeedRecord(
    val id: String,
    val courseId: String,
    val creditUnits: Int,
    val note: String,
    val createdAtEpochMs: Long,
)

object SeedPolicy {
    fun shouldInsert(seedCompleted: Boolean, existingCourses: Int): Boolean {
        return !seedCompleted && existingCourses == 0
    }
}
