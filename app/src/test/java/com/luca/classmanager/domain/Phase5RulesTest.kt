package com.luca.classmanager.domain

import com.luca.classmanager.domain.backup.BackupAttendance
import com.luca.classmanager.domain.backup.BackupBundle
import com.luca.classmanager.domain.backup.BackupCodec
import com.luca.classmanager.domain.backup.BackupCourse
import com.luca.classmanager.domain.backup.BackupDecode
import com.luca.classmanager.domain.backup.BackupFile
import com.luca.classmanager.domain.backup.BackupPackage
import com.luca.classmanager.domain.backup.BackupPayment
import com.luca.classmanager.domain.backup.BackupSettings
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.CsvExport
import com.luca.classmanager.domain.logic.FeeAnalytics
import com.luca.classmanager.domain.logic.ReminderPlanner
import com.luca.classmanager.domain.model.AttendanceInfo
import com.luca.classmanager.domain.model.CourseDetails
import com.luca.classmanager.domain.model.CoursePackageInfo
import com.luca.classmanager.domain.model.CourseStatus
import com.luca.classmanager.domain.model.PaymentInfo
import com.luca.classmanager.domain.model.ScheduleMode
import com.luca.classmanager.domain.model.ScheduleRule
import java.time.LocalDateTime
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5RulesTest {
    @Test
    fun backupRoundTripKeepsCoursesAttendancePaymentsAndPhotos() {
        val photo = Base64.getEncoder().encodeToString("photo-bytes".toByteArray())
        val original = sample().copy(
            files = listOf(BackupFile("note.jpg", photo)),
            attendance = listOf(
                BackupAttendance("a1", "ball", null, "2026-10-21", 2, "已上", null, 1, 1),
            ),
            payments = listOf(BackupPayment("p1", "ball", "pkg", 150_000, "2026-01-01", "课时套餐缴费", 1, 1)),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        assertTrue(decoded is BackupDecode.Ready)
        assertEquals(original, (decoded as BackupDecode.Ready).bundle)
    }

    @Test
    fun corruptOrBrokenBackupDoesNotReplaceTheCurrentSnapshot() {
        val current = sample()
        val encoded = BackupCodec.encode(current)
        val flipped = encoded.replace("篮球", "游泳")
        val tampered = BackupCodec.decode(flipped)
        assertTrue(tampered is BackupDecode.Rejected)
        assertEquals(current, BackupCodec.apply(current, tampered))

        val marker = "\"checksum\":\""
        val start = encoded.indexOf(marker) + marker.length
        val replacement = if (encoded.substring(start, start + 4) == "0000") "ffff" else "0000"
        val checksum = BackupCodec.decode(encoded.replaceRange(start, start + 4, replacement))
        assertTrue(checksum is BackupDecode.Rejected)
        assertEquals(current, BackupCodec.apply(current, checksum))

        val dangling = current.copy(
            payments = listOf(BackupPayment("p1", "missing", null, 100, null, null, 1, 1)),
        )
        val rejected = BackupCodec.decode(BackupCodec.encode(dangling))
        assertTrue(rejected is BackupDecode.Rejected)
        assertEquals(current, BackupCodec.apply(current, rejected))

        val unknown = BackupCodec.decode("{\"format\":\"other\",\"version\":1,\"checksum\":\"x\",\"payload\":\"{}\"}")
        assertTrue(unknown is BackupDecode.Rejected)
        assertEquals(current, BackupCodec.apply(current, unknown))
    }

    @Test
    fun remindersFollowLeadTimeThresholdAndPause() {
        val now = LocalDateTime.of(2026, 10, 24, 15, 40)
        val basketball = course(
            rules = listOf(ScheduleRule("sat", 6, 16 * 60, 17 * 60 + 30)),
            packages = listOf(CoursePackageInfo("pkg", 30, 0, 150_000, 10_000, null, null, 1)),
            attendanceUnits = 28,
        )
        val paused = course(id = "shuttle", name = "羽毛球", status = CourseStatus.PAUSED)
        val due = ReminderPlanner.due(listOf(basketball, paused), now, leadMinutes = 30, uncheckedHour = 20, defaultRenewalUnits = 6, enabled = true)
        assertTrue(due.any { it.key.startsWith("class:ball") })
        assertTrue(due.any { it.title == "建议续费" })
        assertTrue(due.any { it.key.startsWith("paused:") && it.body.contains("羽毛球") })
        assertTrue(ReminderPlanner.due(listOf(basketball), now, 30, 20, 6, enabled = false).isEmpty())

        val tooEarly = ReminderPlanner.due(listOf(basketball), now.minusMinutes(20), 30, 20, 6, true)
        assertFalse(tooEarly.any { it.key.startsWith("class:") })

        val checked = basketball.copy(recordedAttendanceDates = listOf("2026-10-24"), attendanceCreditUnits = 2)
        val evening = ReminderPlanner.due(listOf(checked), LocalDateTime.of(2026, 10, 24, 20, 10), 30, 20, 6, true)
        assertFalse(evening.any { it.title == "今天还没打卡" })
        val missed = ReminderPlanner.due(
            listOf(basketball.copy(attendanceCreditUnits = 0, packages = emptyList())),
            LocalDateTime.of(2026, 10, 24, 20, 10),
            30,
            20,
            6,
            true,
        )
        assertTrue(missed.any { it.title == "今天还没打卡" })
    }

    @Test
    fun csvEscapesCommasAndLeavesUnknownMoneyBlank() {
        val course = course(
            payments = listOf(PaymentInfo("p", null, null, null, "有,逗号", 1)),
            attendances = listOf(AttendanceInfo("a", "2026-10-01", 2, "备注", null, null, 1)),
        )
        val payments = CsvExport.payments(listOf(course))
        assertTrue(payments.startsWith("\uFEFF"))
        assertTrue(payments.contains("\"有,逗号\""))
        assertFalse(payments.contains("0.00 元"))
        val attendance = CsvExport.attendance(listOf(course))
        assertTrue(attendance.contains("2026-10-01"))
        assertTrue(CsvExport.courses(listOf(course)).contains("篮球"))
    }

    @Test
    fun oneHundredCoursesStayWithinAShortPlanningPass() {
        val courses = (1..100).map { index ->
            course(
                id = "c$index",
                name = "课程$index",
                rules = listOf(ScheduleRule("r$index", (index % 7) + 1, 16 * 60, 17 * 60)),
                packages = listOf(CoursePackageInfo("p$index", 20, 0, 10_000, 1_000, "2026-01-01", null, index.toLong())),
            )
        }
        val started = System.nanoTime()
        val report = FeeAnalytics.report(courses, java.time.LocalDate.parse("2026-10-26"), 2026, 10, null)
        val notices = ReminderPlanner.due(courses, LocalDateTime.of(2026, 10, 26, 15, 40), 30, 20, 6, true)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(100, report.courseCount)
        assertTrue(notices.isNotEmpty())
        assertTrue("planning took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    private fun sample() = BackupBundle(
        schemaVersion = BackupCodec.SCHEMA_VERSION,
        settings = BackupSettings("SYSTEM", CreditUnits.DEFAULT_RENEWAL_UNITS, "ZERO", false, 30, 20),
        courses = listOf(
            BackupCourse(
                id = "ball",
                name = "篮球",
                iconKey = "🏀",
                colorHex = "#E9A56B",
                scheduleMode = "WEEKLY",
                status = "ACTIVE",
                sortOrder = 0,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            ),
        ),
        packages = listOf(BackupPackage("pkg", "ball", 30, 0, 150_000, 10_000, null, null, 1)),
    )

    private fun course(
        id: String = "ball",
        name: String = "篮球",
        status: CourseStatus = CourseStatus.ACTIVE,
        rules: List<ScheduleRule> = emptyList(),
        packages: List<CoursePackageInfo> = emptyList(),
        payments: List<PaymentInfo> = emptyList(),
        attendances: List<AttendanceInfo> = emptyList(),
        attendanceUnits: Int = attendances.sumOf { it.creditUnits },
    ) = CourseDetails(
        id = id,
        name = name,
        iconKey = "🏀",
        colorHex = "#E9A56B",
        institution = null,
        location = null,
        notes = null,
        scheduleMode = ScheduleMode.WEEKLY,
        status = status,
        sortOrder = 0,
        rules = rules,
        packages = packages,
        adjustmentCreditUnits = 0,
        attendanceCreditUnits = attendanceUnits,
        recordedAttendanceDates = attendances.map { it.attendanceDate },
        historicalUnrecorded = emptyList(),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        payments = payments,
        attendances = attendances,
    )
}
