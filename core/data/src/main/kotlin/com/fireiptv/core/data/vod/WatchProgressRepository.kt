package com.fireiptv.core.data.vod

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far each movie was watched, for Resume and Continue watching. Saved by the player every few
 * seconds and when it closes; kept across playlist refreshes, deleted with the playlist.
 */
@Singleton
class WatchProgressRepository @Inject constructor(private val db: PlayerDatabase) {

    private val dao = db.watchProgressDao()

    fun progress(movie: Movie): Flow<WatchProgressEntity?> = dao.observe(movie.playlistId, movie.streamId)

    /** Movies stopped part-way, the latest first. */
    fun continueWatching(playlistId: Long): Flow<PagingData<WatchProgressEntity>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE)) { dao.pageUnfinished(playlistId, RESUMABLE_FROM_MS) }.flow

    fun continueWatchingCount(playlistId: Long): Flow<Int> = dao.observeUnfinishedCount(playlistId, RESUMABLE_FROM_MS)

    /** Saves where [movie] is; past 95% of [durationMs] it counts as watched. */
    suspend fun save(movie: Movie, positionMs: Long, durationMs: Long?) {
        val finished = durationMs != null && positionMs >= durationMs * FINISHED_PERCENT / 100
        write(movie, positionMs, durationMs, finished)
    }

    /** The movie played to its end. */
    suspend fun saveFinished(movie: Movie, durationMs: Long?) {
        write(movie, durationMs ?: 0, durationMs, finished = true)
    }

    private suspend fun write(movie: Movie, positionMs: Long, durationMs: Long?, finished: Boolean) = db.withTransaction {
        val existing = dao.get(movie.playlistId, movie.streamId)
        dao.upsert(
            WatchProgressEntity(
                id = existing?.id ?: 0,
                playlistId = movie.playlistId,
                streamId = movie.streamId,
                name = movie.name,
                year = movie.year,
                posterUrl = movie.posterUrl,
                rating = movie.rating,
                extension = movie.extension,
                positionMs = positionMs,
                durationMs = durationMs ?: 0,
                finished = finished,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        /** Less than this is a peek, not something to resume. */
        const val RESUMABLE_FROM_MS = 30_000L
        private const val FINISHED_PERCENT = 95
        private const val PAGE_SIZE = 40
    }
}
