package com.luca.classmanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.luca.classmanager.data.local.dao.AttachmentDao
import com.luca.classmanager.data.local.dao.AttendanceDao
import com.luca.classmanager.data.local.dao.CourseDao
import com.luca.classmanager.data.local.dao.CourseEventDao
import com.luca.classmanager.data.local.dao.CoursePackageDao
import com.luca.classmanager.data.local.dao.CreditAdjustmentDao
import com.luca.classmanager.data.local.dao.HistoricalAttendanceDao
import com.luca.classmanager.data.local.dao.PaymentDao
import com.luca.classmanager.data.local.dao.ReminderDao
import com.luca.classmanager.data.local.dao.ScheduleRuleDao
import com.luca.classmanager.data.local.entity.AttachmentEntity
import com.luca.classmanager.data.local.entity.AttendanceEntity
import com.luca.classmanager.data.local.entity.CourseEntity
import com.luca.classmanager.data.local.entity.CourseEventEntity
import com.luca.classmanager.data.local.entity.CoursePackageEntity
import com.luca.classmanager.data.local.entity.CourseScheduleRuleEntity
import com.luca.classmanager.data.local.entity.CreditAdjustmentEntity
import com.luca.classmanager.data.local.entity.HistoricalAttendanceEntity
import com.luca.classmanager.data.local.entity.PaymentEntity
import com.luca.classmanager.data.local.entity.ReminderEntity

@Database(
    entities = [
        CourseEntity::class,
        CourseScheduleRuleEntity::class,
        CourseEventEntity::class,
        AttendanceEntity::class,
        CoursePackageEntity::class,
        PaymentEntity::class,
        CreditAdjustmentEntity::class,
        HistoricalAttendanceEntity::class,
        ReminderEntity::class,
        AttachmentEntity::class,
    ],
    version = LucaMigrations.VERSION,
    exportSchema = true,
)
abstract class LucaDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun scheduleRuleDao(): ScheduleRuleDao
    abstract fun coursePackageDao(): CoursePackageDao
    abstract fun historicalAttendanceDao(): HistoricalAttendanceDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun paymentDao(): PaymentDao
    abstract fun creditAdjustmentDao(): CreditAdjustmentDao
    abstract fun courseEventDao(): CourseEventDao
    abstract fun reminderDao(): ReminderDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        @Volatile
        private var instance: LucaDatabase? = null

        fun get(context: Context): LucaDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): LucaDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                LucaDatabase::class.java,
                LucaMigrations.NAME,
            )
                .addMigrations(*LucaMigrations.ALL)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                })
                .build()
        }
    }
}
