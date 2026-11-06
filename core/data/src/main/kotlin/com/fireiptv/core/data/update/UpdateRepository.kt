package com.fireiptv.core.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.JsonReader
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fireiptv.core.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/** A published version of the app: its GitHub release and the APK attached to it. */
data class Release(val version: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    /** No check yet, or the last one was too long ago to trust. */
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState

    /** [release] is set when a newer version is known, so OK can try the download again. */
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

private val Context.updateStore: DataStore<Preferences> by preferencesDataStore(name = "updates")

/**
 * Updates from the GitHub releases of the app's repository. A check is one small API request, made
 * at most once a day when the app opens ([checkIfDue]) or when asked for in Settings ([check]).
 * The APK is downloaded only on request, then handed to the system installer, which asks first.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val client: OkHttpClient,
) {
    private val store = context.updateStore
    private val dir = File(context.cacheDir, "updates")
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installedVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** On app open: reuses today's answer, so opening the app all day costs one request. */
    fun checkIfDue() {
        if (job?.isActive == true) return
        job = scope.launch {
            val prefs = store.data.first()
            val checkedAt = prefs[CHECKED_AT] ?: 0L
            val known = prefs[LATEST_VERSION]?.let { version ->
                Release(version, prefs[LATEST_URL].orEmpty(), prefs[LATEST_SIZE] ?: 0L)
            }
            if (known == null || !isNewer(known.version, installedVersion)) clearDownloads()
            if (System.currentTimeMillis() - checkedAt < CHECK_INTERVAL_MS) {
                _state.value = if (known != null && isNewer(known.version, installedVersion)) {
                    UpdateState.Available(known)
                } else {
                    UpdateState.UpToDate(checkedAt)
                }
            } else {
                runCheck()
            }
        }
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch { runCheck() }
    }

    fun download() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        if (job?.isActive == true) return
        job = scope.launch { runDownload(release) }
    }

    /** The system installer for a downloaded update; it asks the user before installing. */
    fun installIntent(apk: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", apk), APK_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun runCheck() {
        _state.value = UpdateState.Checking
        val release = try {
            fetchLatest()
        } catch (e: SSLException) {
            // Usually the clock: a device date before the certificate's start makes it look invalid.
            _state.value = UpdateState.Failed("Couldn't connect to GitHub securely. Check that the date and time on this device are right")
            return
        } catch (e: IOException) {
            _state.value = UpdateState.Failed("Couldn't reach GitHub: ${e.message ?: "network error"}")
            return
        } catch (e: IllegalStateException) {
            _state.value = UpdateState.Failed("GitHub sent a response this version doesn't understand")
            return
        }
        val now = System.currentTimeMillis()
        store.edit { prefs ->
            prefs[CHECKED_AT] = now
            if (release != null) {
                prefs[LATEST_VERSION] = release.version
                prefs[LATEST_URL] = release.apkUrl
                prefs[LATEST_SIZE] = release.sizeBytes
            }
        }
        _state.value = if (release != null && isNewer(release.version, installedVersion)) {
            UpdateState.Available(release)
        } else {
            UpdateState.UpToDate(now)
        }
    }

    /** The latest published release that carries an APK, or null when there is none yet. */
    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_RELEASE_API).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            JsonReader(response.body.charStream()).use { readRelease(it) }
        }
    }

    private suspend fun runDownload(release: Release) {
        _state.value = UpdateState.Downloading(release, 0)
        val apk = File(dir, "FireIPTV-${release.version}.apk")
        try {
            withContext(Dispatchers.IO) {
                if (!(apk.exists() && apk.length() == release.sizeBytes)) {
                    clearDownloads()
                    dir.mkdirs()
                    fetch(release, apk)
                }
            }
        } catch (e: IOException) {
            apk.delete()
            _state.value = UpdateState.Failed("Download failed: ${e.message ?: "network error"}", release)
            return
        }
        _state.value = if (signedLikeThisApp(apk)) {
            UpdateState.Ready(release, apk)
        } else {
            apk.delete()
            UpdateState.Failed("The download isn't signed with this app's key, so it can't replace this copy.", release)
        }
    }

    private fun fetch(release: Release, apk: File) {
        val part = File(dir, apk.name + ".part")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            response.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(apk)) throw IOException("couldn't save the file")
    }

    /**
     * Android refuses an update signed with another key, after the user has already confirmed.
     * Checking first gives a clear message instead, e.g. for a debug build. Where the platform
     * can't read the archive's signer, the installer still makes the same check.
     */
    @Suppress("DEPRECATION")
    private fun signedLikeThisApp(apk: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != context.packageName) return false
        val update = archive.signingInfo?.apkContentsSigners?.toSet() ?: return true
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.toSet() ?: return true
        return update == installed
    }

    private fun clearDownloads() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/BurimKrz/FireIPTV/releases/latest"
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        val CHECKED_AT = longPreferencesKey("checked_at")
        val LATEST_VERSION = stringPreferencesKey("latest_version")
        val LATEST_URL = stringPreferencesKey("latest_url")
        val LATEST_SIZE = longPreferencesKey("latest_size")
    }
}

/** Reads `tag_name` and the first `.apk` asset of a GitHub release; null when it has no APK. */
internal fun readRelease(reader: JsonReader): Release? {
    var tag: String? = null
    var asset: Pair<String, Long>? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "tag_name" -> tag = reader.nextString()
            "assets" -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    var name: String? = null
                    var url: String? = null
                    var size = 0L
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name" -> name = reader.nextString()
                            "browser_download_url" -> url = reader.nextString()
                            "size" -> size = reader.nextLong()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (asset == null && url != null && name?.endsWith(".apk") == true) asset = url to size
                }
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    val version = tag?.removePrefix("v") ?: return null
    val (url, size) = asset ?: return null
    return Release(version, url, size)
}

/** True when [candidate] (like "0.2.0") is a later version than [installed]. */
internal fun isNewer(candidate: String, installed: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(installed)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
