package com.fireiptv.core.data.playlist

import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.db.PlaylistSummary
import com.fireiptv.core.data.di.ApplicationScope
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val playlistId: Long, val playlistName: String, val progress: ImportProgress?) : ImportState
    data class Done(val playlistId: Long, val playlistName: String, val outcome: ImportOutcome) : ImportState
    data class Failed(val playlistId: Long, val playlistName: String, val message: String) : ImportState
}

/**
 * Entry point for playlists. Imports run in the application scope, one at a time, so they keep
 * going when the user leaves the screen that started them.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    db: PlayerDatabase,
    private val importer: PlaylistImporter,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val log: AppLog,
    private val settings: SettingsRepository,
) {
    private val playlistDao = db.playlistDao()
    private val importLock = Mutex()
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)

    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.observeSummaries()

    /**
     * The playlist home, search and the per-playlist settings work on: the one picked last, or the
     * first one when none was picked or the picked one was deleted.
     */
    val activePlaylist: Flow<PlaylistSummary?> = combine(playlists, settings.activePlaylistId) { all, id ->
        all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    /** [activePlaylist], read once. */
    suspend fun activePlaylistEntity(): PlaylistEntity? =
        settings.activePlaylistId.first()?.let { playlistDao.get(it)?.takeUnless { p -> p.isStaging } } ?: playlistDao.first()

    suspend fun setActive(playlistId: Long) = settings.setActivePlaylistId(playlistId)

    init {
        scope.launch { importLock.withLock { playlistDao.deleteAllStaging() } }
    }

    suspend fun hasPlaylists(): Boolean = playlistDao.hasAny()

    /** Saves the playlist, makes it the one home shows, and starts its first import. */
    suspend fun addPlaylist(name: String, url: String): Long {
        val id = playlistDao.insert(PlaylistEntity(name = name.ifBlank { uniqueName(defaultPlaylistName(url)) }, url = url))
        setActive(id)
        refresh(id)
        return id
    }

    /** Two lists from the same server would both be named after it: number the later ones so the picker tells them apart. */
    private suspend fun uniqueName(base: String): String {
        val taken = playlists.first().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it !in taken }
    }

    fun refresh(playlistId: Long): Job = scope.launch {
        importLock.withLock {
            val playlist = playlistDao.get(playlistId) ?: return@withLock
            _importState.value = ImportState.Running(playlistId, playlist.name, progress = null)
            _importState.value = try {
                val outcome = importer.import(playlistId) { progress ->
                    _importState.value = ImportState.Running(playlistId, playlist.name, progress)
                }
                ImportState.Done(playlistId, playlist.name, outcome)
            } catch (e: CancellationException) {
                _importState.value = ImportState.Idle
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                log.error("Refreshing ${playlist.name} failed: $message")
                ImportState.Failed(playlistId, playlist.name, message)
            }
        }
    }

    suspend fun delete(playlistId: Long) = importLock.withLock { playlistDao.delete(playlistId) }
}
