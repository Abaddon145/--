# Room, WorkManager, and kotlinx.serialization entry points used by backup and reminders.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

-keep class com.luca.classmanager.reminder.ReminderWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keepclassmembers class com.luca.classmanager.domain.backup.** {
    *** Companion;
}
-keep class com.luca.classmanager.domain.backup.**$$serializer { *; }
-keep class com.luca.classmanager.domain.backup.**$Companion { *; }

-keepclassmembers enum com.luca.classmanager.domain.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.luca.classmanager.domain.logic.GiftValuation {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
