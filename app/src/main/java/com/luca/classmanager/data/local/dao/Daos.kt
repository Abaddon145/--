package com.luca.classmanager.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.luca.classmanager.data.local.entity.AttendanceEntity
import com.luca.classmanager.data.local.entity.CourseEntity
import com.luca.classmanager.data.local.entity.CourseEventEntity
import com.luca.classmanager.data.local.entity.CoursePackageEntity
import com.luca.classmanager.data.local.entity.CourseScheduleRuleEntity
import com.luca.classmanager.data.local.entity.CreditAdjustmentEntity
import com.luca.classmanager.data.local.entity.HistoricalAttendanceEntity
import com.luca.classmanager.data.local.entity.PaymentEntity
import com.luca.classmanager.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

data class CourseWithChildren(
    @Embedded val course: CourseEntity,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val rules: List<CourseScheduleRuleEntity>,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val packages: List<CoursePackageEntity>,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val adjustments: List<CreditAdjustmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val attendance: List<AttendanceEntity>,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val historicalAttendance: List<HistoricalAttendanceEntity>,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val payments: List<PaymentEntity>,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val events: List<CourseEventEntity>,
)

@Dao
interface CourseDao {
    @Transaction
    @Query("SELECT * FROM courses ORDER BY sortOrder ASC, createdAtEpochMs ASC")
    fun observeAllWithChildren(): Flow<List<CourseWithChildren>>

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getWithChildren(id: String): CourseWithChildren?

    @Transaction
    @Query("SELECT * FROM courses")
    suspend fun listWithChildren(): List<CourseWithChildren>

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun count(): Int

    @Query("SELECT * FROM courses ORDER BY sortOrder ASC, createdAtEpochMs ASC")
    suspend fun listAll(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: String): CourseEntity?

    @Insert
    suspend fun insert(entity: CourseEntity)

    @Update
    suspend fun update(entity: CourseEntity)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM courses")
    suspend fun deleteAll()

    @Query("UPDATE courses SET sortOrder = :sortOrder, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateSort(id: String, sortOrder: Int, now: Long)

    @Query(
        "UPDATE courses SET status = :status, statusBeforeArchive = :statusBeforeArchive, " +
            "updatedAtEpochMs = :now WHERE id = :id",
    )
    suspend fun updateStatus(id: String, status: String, statusBeforeArchive: String?, now: Long)

    @Query(
        "UPDATE courses SET renewalThresholdCreditUnits = :units, updatedAtEpochMs = :now WHERE id = :id",
    )
    suspend fun updateRenewalThreshold(id: String, units: Int?, now: Long)
}

@Dao
interface ScheduleRuleDao {
    @Insert
    suspend fun insert(entity: CourseScheduleRuleEntity)

    @Query("SELECT * FROM course_schedule_rules")
    suspend fun listAll(): List<CourseScheduleRuleEntity>

    @Query("DELETE FROM course_schedule_rules")
    suspend fun deleteAll()

    @Query("DELETE FROM course_schedule_rules WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM course_schedule_rules WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface CoursePackageDao {
    @Insert
    suspend fun insert(entity: CoursePackageEntity)

    @Update
    suspend fun update(entity: CoursePackageEntity)

    @Query("SELECT * FROM course_packages WHERE id = :id")
    suspend fun getById(id: String): CoursePackageEntity?

    @Query("SELECT * FROM course_packages")
    suspend fun listAll(): List<CoursePackageEntity>

    @Query("DELETE FROM course_packages")
    suspend fun deleteAll()

    @Query("DELETE FROM course_packages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM course_packages WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM course_packages WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface HistoricalAttendanceDao {
    @Insert
    suspend fun insert(entity: HistoricalAttendanceEntity)

    @Query("SELECT * FROM historical_attendance")
    suspend fun listAll(): List<HistoricalAttendanceEntity>

    @Query("DELETE FROM historical_attendance")
    suspend fun deleteAll()

    @Query("SELECT * FROM historical_attendance WHERE id = :id")
    suspend fun getById(id: String): HistoricalAttendanceEntity?

    @Query(
        """
        SELECT * FROM historical_attendance
        WHERE courseId = :courseId AND convertedAttendanceId IS NULL
        ORDER BY createdAtEpochMs ASC, id ASC
        """,
    )
    suspend fun listOpen(courseId: String): List<HistoricalAttendanceEntity>

    @Query("UPDATE historical_attendance SET convertedAttendanceId = :attendanceId WHERE id = :id")
    suspend fun markConverted(id: String, attendanceId: String?)

    @Query("DELETE FROM historical_attendance WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM historical_attendance WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface AttendanceDao {
    @Insert
    suspend fun insert(entity: AttendanceEntity)

    @Query("SELECT * FROM attendance")
    suspend fun listAll(): List<AttendanceEntity>

    @Query("DELETE FROM attendance")
    suspend fun deleteAll()

    @Update
    suspend fun update(entity: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun getById(id: String): AttendanceEntity?

    @Query("DELETE FROM attendance WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT COUNT(*) FROM attendance
        WHERE courseId = :courseId AND attendanceDate = :date AND id != :excludeId
        """,
    )
    suspend fun countOnDate(courseId: String, date: String, excludeId: String): Int

    @Query("SELECT attendanceDate FROM attendance WHERE courseId = :courseId")
    suspend fun datesForCourse(courseId: String): List<String>

    @Query("SELECT * FROM attendance WHERE eventId = :eventId")
    suspend fun listByEvent(eventId: String): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE courseId = :courseId AND attendanceDate = :date")
    suspend fun listForCourseOnDate(courseId: String, date: String): List<AttendanceEntity>

    @Query("DELETE FROM attendance WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM attendance WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface PaymentDao {
    @Insert
    suspend fun insert(entity: PaymentEntity)

    @Update
    suspend fun update(entity: PaymentEntity)

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun getById(id: String): PaymentEntity?

    @Query("SELECT * FROM payments WHERE packageId = :packageId LIMIT 1")
    suspend fun findByPackage(packageId: String): PaymentEntity?

    @Query("DELETE FROM payments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM payments")
    suspend fun listAll(): List<PaymentEntity>

    @Query("DELETE FROM payments")
    suspend fun deleteAll()

    @Query("SELECT packageId FROM payments WHERE packageId IS NOT NULL")
    suspend fun linkedPackageIds(): List<String>

    @Query("DELETE FROM payments WHERE packageId = :packageId")
    suspend fun deleteForPackage(packageId: String)

    @Query("DELETE FROM payments WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM payments WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface CreditAdjustmentDao {
    @Insert
    suspend fun insert(entity: CreditAdjustmentEntity)

    @Query("SELECT * FROM credit_adjustments")
    suspend fun listAll(): List<CreditAdjustmentEntity>

    @Query("DELETE FROM credit_adjustments")
    suspend fun deleteAll()

    @Query("DELETE FROM credit_adjustments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM credit_adjustments WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM credit_adjustments WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface CourseEventDao {
    @Insert
    suspend fun insert(entity: CourseEventEntity)

    @Query("SELECT * FROM course_events")
    suspend fun listAll(): List<CourseEventEntity>

    @Query("DELETE FROM course_events")
    suspend fun deleteAll()

    @Update
    suspend fun update(entity: CourseEventEntity)

    @Query("SELECT * FROM course_events WHERE id = :id")
    suspend fun getById(id: String): CourseEventEntity?

    @Query("SELECT * FROM course_events WHERE courseId = :courseId AND eventDate = :date")
    suspend fun listForCourseOnDate(courseId: String, date: String): List<CourseEventEntity>

    @Query("SELECT * FROM course_events WHERE rescheduledFromEventId = :eventId")
    suspend fun listChildren(eventId: String): List<CourseEventEntity>

    @Query("DELETE FROM course_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM course_events WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM course_events WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface ReminderDao {
    @androidx.room.Insert
    suspend fun insert(entity: com.luca.classmanager.data.local.entity.ReminderEntity)

    @Query("SELECT * FROM reminders")
    suspend fun listAll(): List<com.luca.classmanager.data.local.entity.ReminderEntity>

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    @Query("DELETE FROM reminders WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: String)

    @Query("SELECT COUNT(*) FROM reminders WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: String): Int
}

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(entity: com.luca.classmanager.data.local.entity.AttachmentEntity)

    @Query("SELECT * FROM attachments")
    suspend fun listAll(): List<com.luca.classmanager.data.local.entity.AttachmentEntity>

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType")
    fun observeByOwnerType(ownerType: String): Flow<List<com.luca.classmanager.data.local.entity.AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType")
    suspend fun listByOwnerType(ownerType: String): List<com.luca.classmanager.data.local.entity.AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun listForOwner(ownerType: String, ownerId: String): List<com.luca.classmanager.data.local.entity.AttachmentEntity>

    @Query("DELETE FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteForOwner(ownerType: String, ownerId: String)

    @Query(
        """
        SELECT uri FROM attachments
        WHERE (ownerType = 'COURSE' AND ownerId = :courseId)
           OR (ownerType = 'ATTENDANCE' AND ownerId IN (SELECT id FROM attendance WHERE courseId = :courseId))
           OR (ownerType = 'EVENT' AND ownerId IN (SELECT id FROM course_events WHERE courseId = :courseId))
        """,
    )
    suspend fun listUrisForCourse(courseId: String): List<String>

    @Query(
        """
        DELETE FROM attachments
        WHERE (ownerType = 'COURSE' AND ownerId = :courseId)
           OR (ownerType = 'ATTENDANCE' AND ownerId IN (SELECT id FROM attendance WHERE courseId = :courseId))
           OR (ownerType = 'EVENT' AND ownerId IN (SELECT id FROM course_events WHERE courseId = :courseId))
        """,
    )
    suspend fun deleteForCourse(courseId: String)

    @Query(
        """
        SELECT COUNT(*) FROM attachments
        WHERE (ownerType = 'COURSE' AND ownerId = :courseId)
           OR (ownerType = 'ATTENDANCE' AND ownerId IN (SELECT id FROM attendance WHERE courseId = :courseId))
           OR (ownerType = 'EVENT' AND ownerId IN (SELECT id FROM course_events WHERE courseId = :courseId))
        """,
    )
    suspend fun countForCourse(courseId: String): Int
}
