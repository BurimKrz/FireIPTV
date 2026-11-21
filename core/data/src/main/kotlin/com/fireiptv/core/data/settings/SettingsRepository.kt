package com.fireiptv.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fireiptv.core.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How a channel starts: as soon as possible, or after a longer buffer that rides out weak Wi-Fi. */
enum class StartMode { FAST, STABLE }

/** Subtitles chosen in a movie, applied to the next ones. */
sealed interface SubtitleChoice {
    /** Never chosen: only subtitles the file marks as default or forced show. */
    data object Unset : SubtitleChoice
    data object Off : SubtitleChoice

    /** A language code as the file tags it, e.g. "sq": that language whenever a movie has it. */
    data class Language(val code: String) : SubtitleChoice
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings, read once at start and kept current, so screens can use the value without waiting. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    @param:ApplicationScope scope: CoroutineScope,
) {
    private val store = context.settingsStore

    // An unreadable settings file falls back to defaults instead of crashing the app.
    private val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val startMode: StateFlow<StartMode> = prefs
        .map { prefs -> prefs[START_MODE]?.let { name -> StartMode.entries.firstOrNull { it.name == name } } ?: StartMode.FAST }
        .stateIn(scope, SharingStarted.Eagerly, StartMode.FAST)

    /** Channel logos; off means the app makes no image requests at all. */
    val logosEnabled: StateFlow<Boolean> = prefs
        .map { it[LOGOS_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * The playlist home shows, or null for the first one. A plain flow, not a state with a default,
     * so home doesn't flash the first playlist before the saved choice is read.
     */
    val activePlaylistId: Flow<Long?> = prefs.map { it[ACTIVE_PLAYLIST] }.distinctUntilChanged()

    /** The movie audio language last chosen (a language code), or null when never chosen. */
    val audioLanguage: StateFlow<String?> = prefs
        .map { it[AUDIO_LANGUAGE] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val subtitleChoice: StateFlow<SubtitleChoice> = prefs
        .map { prefs ->
            when (val value = prefs[SUBTITLES]) {
                null -> SubtitleChoice.Unset
                SUBTITLES_OFF -> SubtitleChoice.Off
                else -> SubtitleChoice.Language(value)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, SubtitleChoice.Unset)

    suspend fun setAudioLanguage(code: String) {
        store.edit { it[AUDIO_LANGUAGE] = code }
    }

    suspend fun setSubtitlesOff() {
        store.edit { it[SUBTITLES] = SUBTITLES_OFF }
    }

    suspend fun setSubtitleLanguage(code: String) {
        store.edit { it[SUBTITLES] = code }
    }

    suspend fun setActivePlaylistId(id: Long) {
        store.edit { it[ACTIVE_PLAYLIST] = id }
    }

    suspend fun setStartMode(mode: StartMode) {
        store.edit { it[START_MODE] = mode.name }
    }

    suspend fun setLogosEnabled(enabled: Boolean) {
        store.edit { it[LOGOS_ENABLED] = enabled }
    }

    private companion object {
        val START_MODE = stringPreferencesKey("start_mode")
        val LOGOS_ENABLED = booleanPreferencesKey("logos_enabled")
        val ACTIVE_PLAYLIST = longPreferencesKey("active_playlist")
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLES = stringPreferencesKey("subtitles")

        // Not a language code (those are 2–3 letters, or tags like "pt-BR").
        const val SUBTITLES_OFF = "off"
    }
}
