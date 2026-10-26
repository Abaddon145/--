package com.luca.classmanager.di

import android.content.Context
import com.luca.classmanager.data.local.AttachmentStore
import com.luca.classmanager.data.local.LucaDatabase
import com.luca.classmanager.data.repository.CourseRepositoryImpl
import com.luca.classmanager.data.settings.UserSettingsStore
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.domain.repository.SettingsRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: LucaDatabase = LucaDatabase.get(appContext)
    val settingsRepository: SettingsRepository = UserSettingsStore(appContext)
    val attachmentStore: AttachmentStore = AttachmentStore(appContext)
    val courseRepository: CourseRepository = CourseRepositoryImpl(
        database,
        settingsRepository,
        attachmentStore,
    )
}
