package com.luca.classmanager.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Add a new [Migration] here whenever [VERSION] increases.
 * Do not call fallbackToDestructiveMigration. User data must survive upgrades.
 */
object LucaMigrations {
    const val VERSION = 2
    const val NAME = "luca_interest_classroom.db"
    const val ADD_RENEWAL_THRESHOLD =
        "ALTER TABLE courses ADD COLUMN renewalThresholdCreditUnits INTEGER"

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ADD_RENEWAL_THRESHOLD)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
