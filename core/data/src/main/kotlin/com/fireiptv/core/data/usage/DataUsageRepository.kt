package com.fireiptv.core.data.usage

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Bytes the app received today and this calendar month. */
data class DataUsage(val todayBytes: Long = 0, val monthBytes: Long = 0)

private val Context.usageStore: DataStore<Preferences> by preferencesDataStore(name = "data_usage")

/**
 * Counts what the app downloads from the system's own per-app byte counter, so counting costs no
 * request and covers everything: streams, playlist refreshes, logos, update checks.
 *
 * The system counter starts at zero on every boot, so each [sample] adds what arrived since the
 * last one; between samples nothing needs to run, the counter keeps going on its own.
 */
@Singleton
class DataUsageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val store = context.usageStore
    private val uid = Process.myUid()

    private val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    /** Zero for a day or month that has no samples yet. */
    val usage: StateFlow<DataUsage> = prefs
        .map { prefs ->
            DataUsage(
                todayBytes = if (prefs[DAY] == LocalDate.now().toString()) prefs[DAY_BYTES] ?: 0 else 0,
                monthBytes = if (prefs[MONTH] == YearMonth.now().toString()) prefs[MONTH_BYTES] ?: 0 else 0,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, DataUsage())

    /** Bytes received since boot, for measuring a rate; null when the device doesn't count. */
    fun receivedBytes(): Long? = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() }

    /** Adds what arrived since the last sample to today and this month. */
    suspend fun sample() {
        val received = receivedBytes() ?: return
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        store.edit { prefs ->
            val last = prefs[LAST_RECEIVED]
            val sameBoot = boot == -1L || prefs[BOOT] == boot
            val added = when {
                // The first sample ever: what came before it is unknown, so start counting here.
                last == null -> 0
                // A reboot started the counter again from zero.
                !sameBoot || received < last -> received
                else -> received - last
            }
            prefs[LAST_RECEIVED] = received
            prefs[BOOT] = boot

            val today = LocalDate.now().toString()
            prefs[DAY_BYTES] = added + if (prefs[DAY] == today) prefs[DAY_BYTES] ?: 0 else 0
            prefs[DAY] = today
            val month = YearMonth.now().toString()
            prefs[MONTH_BYTES] = added + if (prefs[MONTH] == month) prefs[MONTH_BYTES] ?: 0 else 0
            prefs[MONTH] = month
        }
    }

    /** [sample] that finishes even if the caller is going away, e.g. when the app leaves the screen. */
    fun sampleSoon() {
        scope.launch { sample() }
    }

    private companion object {
        val LAST_RECEIVED = longPreferencesKey("last_received")
        val BOOT = longPreferencesKey("boot_count")
        val DAY = stringPreferencesKey("day")
        val DAY_BYTES = longPreferencesKey("day_bytes")
        val MONTH = stringPreferencesKey("month")
        val MONTH_BYTES = longPreferencesKey("month_bytes")
    }
}
