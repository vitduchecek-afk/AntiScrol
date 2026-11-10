package cz.antiscrol.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** Whether profile pages may be opened. Off by default. */
    val profilesEnabled: Flow<Boolean> = store.data
        .catch { emit(emptyPreferences()) }
        .map { it[PROFILES_ENABLED] ?: false }

    suspend fun setProfilesEnabled(enabled: Boolean) {
        store.edit { it[PROFILES_ENABLED] = enabled }
    }

    private companion object {
        val PROFILES_ENABLED = booleanPreferencesKey("profiles_enabled")
    }
}
