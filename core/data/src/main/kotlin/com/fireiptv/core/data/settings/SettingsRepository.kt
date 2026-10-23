package com.fireiptv.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fireiptv.core.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How a channel starts: as soon as possible, or after a longer buffer that rides out weak Wi-Fi. */
enum class StartMode { FAST, STABLE }

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings, read once at start and kept current, so screens can use the value without waiting. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    @param:ApplicationScope scope: CoroutineScope,
) {
    private val store = context.settingsStore

    val startMode: StateFlow<StartMode> = store.data
        // An unreadable settings file falls back to defaults instead of crashing the app.
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[START_MODE]?.let { name -> StartMode.entries.firstOrNull { it.name == name } } ?: StartMode.FAST }
        .stateIn(scope, SharingStarted.Eagerly, StartMode.FAST)

    suspend fun setStartMode(mode: StartMode) {
        store.edit { it[START_MODE] = mode.name }
    }

    private companion object {
        val START_MODE = stringPreferencesKey("start_mode")
    }
}
