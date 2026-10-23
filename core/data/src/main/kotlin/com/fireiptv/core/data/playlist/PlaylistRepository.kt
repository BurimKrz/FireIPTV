package com.fireiptv.core.data.playlist

import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.db.PlaylistSummary
import com.fireiptv.core.data.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val playlistDao = db.playlistDao()
    private val importLock = Mutex()
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)

    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.observeSummaries()

    init {
        scope.launch { importLock.withLock { playlistDao.deleteAllStaging() } }
    }

    suspend fun hasPlaylists(): Boolean = playlistDao.hasAny()

    /** Saves the playlist and starts its first import. */
    suspend fun addPlaylist(name: String, url: String): Long {
        val id = playlistDao.insert(PlaylistEntity(name = name.ifBlank { defaultPlaylistName(url) }, url = url))
        refresh(id)
        return id
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
                ImportState.Failed(playlistId, playlist.name, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun delete(playlistId: Long) = importLock.withLock { playlistDao.delete(playlistId) }
}
