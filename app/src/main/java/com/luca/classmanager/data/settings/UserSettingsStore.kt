package com.luca.classmanager.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.GiftValuation
import androidx.datastore.preferences.preferencesDataStore
import com.luca.classmanager.domain.model.ThemeMode
import com.luca.classmanager.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "luca_settings")

class UserSettingsStore(context: Context) : SettingsRepository {
    private val dataStore = context.applicationContext.settingsDataStore

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[Keys.THEME]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    override val renewalThresholdCreditUnits: Flow<Int> = dataStore.data.map { prefs ->
        val stored = prefs[Keys.RENEWAL]
        if (stored == null || stored < 1) CreditUnits.DEFAULT_RENEWAL_UNITS else stored
    }

    override val giftValuation: Flow<GiftValuation> = dataStore.data.map { prefs ->
        if (prefs[Keys.GIFT] == GiftValuation.MATCH_PURCHASED.name) {
            GiftValuation.MATCH_PURCHASED
        } else {
            GiftValuation.ZERO
        }
    }

    override val remindersEnabled: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.REMINDERS] == true }

    override val reminderLeadMinutes: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[Keys.LEAD] ?: 30).coerceIn(5, 240)
    }

    override val uncheckedReminderHour: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[Keys.UNCHECKED_HOUR] ?: 20).coerceIn(0, 23)
    }

    override suspend fun isSeedCompleted(): Boolean = dataStore.data.first()[Keys.SEED] == true

    override suspend fun setSeedCompleted() {
        dataStore.edit { prefs -> prefs[Keys.SEED] = true }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[Keys.THEME] = mode.name }
    }

    override suspend fun setRenewalThresholdCreditUnits(units: Int) {
        val stored = units.coerceAtLeast(1)
        dataStore.edit { prefs -> prefs[Keys.RENEWAL] = stored }
    }

    override suspend fun setGiftValuation(mode: GiftValuation) {
        dataStore.edit { prefs -> prefs[Keys.GIFT] = mode.name }
    }

    override suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.REMINDERS] = enabled }
    }

    override suspend fun setReminderLeadMinutes(minutes: Int) {
        dataStore.edit { prefs -> prefs[Keys.LEAD] = minutes.coerceIn(5, 240) }
    }

    override suspend fun setUncheckedReminderHour(hour: Int) {
        dataStore.edit { prefs -> prefs[Keys.UNCHECKED_HOUR] = hour.coerceIn(0, 23) }
    }

    override suspend fun firedReminderKeys(): Set<String> = dataStore.data.first()[Keys.FIRED].orEmpty()

    override suspend fun rememberReminderKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        dataStore.edit { prefs ->
            val merged = (prefs[Keys.FIRED].orEmpty() + keys).toList().takeLast(400).toSet()
            prefs[Keys.FIRED] = merged
        }
    }

    private object Keys {
        val SEED = booleanPreferencesKey("seed_completed")
        val THEME = stringPreferencesKey("theme_mode")
        val RENEWAL = intPreferencesKey("renewal_threshold_credit_units")
        val GIFT = stringPreferencesKey("gift_valuation")
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val LEAD = intPreferencesKey("reminder_lead_minutes")
        val UNCHECKED_HOUR = intPreferencesKey("unchecked_reminder_hour")
        val FIRED = stringSetPreferencesKey("fired_reminder_keys")
    }
}
