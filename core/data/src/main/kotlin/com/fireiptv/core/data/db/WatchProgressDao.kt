package com.fireiptv.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND streamId = :streamId")
    suspend fun get(playlistId: Long, streamId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND streamId = :streamId")
    fun observe(playlistId: Long, streamId: String): Flow<WatchProgressEntity?>

    /** Replaces by [WatchProgressEntity.id]: pass the existing row's id, or 0 for a new movie. */
    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    /** Movies stopped part-way, at [minPositionMs] or later; the latest first. */
    @Query(
        "SELECT * FROM watch_progress WHERE playlistId = :playlistId AND NOT finished AND positionMs >= :minPositionMs " +
            "ORDER BY updatedAt DESC",
    )
    fun pageUnfinished(playlistId: Long, minPositionMs: Long): PagingSource<Int, WatchProgressEntity>

    @Query("SELECT COUNT(*) FROM watch_progress WHERE playlistId = :playlistId AND NOT finished AND positionMs >= :minPositionMs")
    fun observeUnfinishedCount(playlistId: Long, minPositionMs: Long): Flow<Int>
}
