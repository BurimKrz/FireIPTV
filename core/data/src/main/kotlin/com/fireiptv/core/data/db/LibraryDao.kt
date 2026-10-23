package com.fireiptv.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Favourites and recently watched. Both point at channels by stream URL; when two channels share
 * a URL, the first one in playlist order stands for it (`MIN(id)`).
 */
@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE playlistId = :playlistId AND streamUrl = :streamUrl")
    suspend fun removeFavorite(playlistId: Long, streamUrl: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE playlistId = :playlistId AND streamUrl = :streamUrl)")
    fun observeIsFavorite(playlistId: Long, streamUrl: String): Flow<Boolean>

    @Query("SELECT sortOrder FROM favorite WHERE playlistId = :playlistId AND streamUrl = :streamUrl")
    suspend fun favoriteSortOrder(playlistId: Long, streamUrl: String): Long?

    @Upsert
    suspend fun upsertRecent(recent: RecentEntity)

    @Query(
        """
        DELETE FROM recent WHERE playlistId = :playlistId AND streamUrl NOT IN (
            SELECT streamUrl FROM recent WHERE playlistId = :playlistId ORDER BY watchedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimRecents(playlistId: Long, keep: Int)

    @Query("SELECT watchedAt FROM recent WHERE playlistId = :playlistId AND streamUrl = :streamUrl")
    suspend fun watchedAt(playlistId: Long, streamUrl: String): Long?

    // Lists. Entries whose channel is gone (dropped by the provider) are kept but not shown, so
    // they come back if the channel does.

    @Query(
        """
        SELECT c.id, c.name, c.number, c.sortOrder, c.logoUrl, 1 AS favorite FROM favorite f
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = f.playlistId AND streamUrl = f.streamUrl)
        WHERE f.playlistId = :playlistId
        ORDER BY f.sortOrder
        """,
    )
    fun pageFavorites(playlistId: Long): PagingSource<Int, ChannelListItem>

    @Query(
        """
        SELECT c.id, c.name, c.number, c.sortOrder, c.logoUrl, $IS_FAVORITE FROM recent r
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = r.playlistId AND streamUrl = r.streamUrl)
        WHERE r.playlistId = :playlistId
        ORDER BY r.watchedAt DESC
        """,
    )
    fun pageRecents(playlistId: Long): PagingSource<Int, ChannelListItem>

    @Query(
        """
        SELECT COUNT(*) FROM favorite f WHERE f.playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl)
        """,
    )
    fun observeFavoriteCount(playlistId: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM recent r WHERE r.playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl)
        """,
    )
    fun observeRecentCount(playlistId: Long): Flow<Int>

    // Zapping through favourites, in the order they were added; wraps around like the other lists.

    @Query(
        """
        SELECT c.* FROM favorite f
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = f.playlistId AND streamUrl = f.streamUrl)
        WHERE f.playlistId = :playlistId AND f.sortOrder > :sortOrder
        ORDER BY f.sortOrder LIMIT 1
        """,
    )
    suspend fun favoriteAfter(playlistId: Long, sortOrder: Long): ChannelEntity?

    @Query(
        """
        SELECT c.* FROM favorite f
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = f.playlistId AND streamUrl = f.streamUrl)
        WHERE f.playlistId = :playlistId AND f.sortOrder < :sortOrder
        ORDER BY f.sortOrder DESC LIMIT 1
        """,
    )
    suspend fun favoriteBefore(playlistId: Long, sortOrder: Long): ChannelEntity?

    @Query(
        """
        SELECT COUNT(*) FROM favorite f WHERE f.playlistId = :playlistId AND f.sortOrder < :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl)
        """,
    )
    suspend fun favoritePosition(playlistId: Long, sortOrder: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM recent r WHERE r.playlistId = :playlistId AND r.watchedAt > :watchedAt
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl)
        """,
    )
    suspend fun recentPosition(playlistId: Long, watchedAt: Long): Int

    // After an import: entries whose URL still exists take the channel's current name; entries
    // whose URL is gone move to a channel with the same name, if there is one.

    @Query(
        """
        UPDATE favorite SET name = (
            SELECT c.name FROM channel c WHERE c.playlistId = favorite.playlistId AND c.streamUrl = favorite.streamUrl
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = favorite.playlistId AND c.streamUrl = favorite.streamUrl)
        """,
    )
    suspend fun refreshFavoriteNames(playlistId: Long)

    @Query(
        """
        UPDATE OR REPLACE favorite SET streamUrl = (
            SELECT c.streamUrl FROM channel c WHERE c.playlistId = favorite.playlistId AND c.name = favorite.name
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE playlistId = :playlistId
        AND NOT EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = favorite.playlistId AND c.streamUrl = favorite.streamUrl)
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = favorite.playlistId AND c.name = favorite.name)
        """,
    )
    suspend fun relinkFavoritesByName(playlistId: Long)

    @Query(
        """
        UPDATE recent SET name = (
            SELECT c.name FROM channel c WHERE c.playlistId = recent.playlistId AND c.streamUrl = recent.streamUrl
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = recent.playlistId AND c.streamUrl = recent.streamUrl)
        """,
    )
    suspend fun refreshRecentNames(playlistId: Long)

    @Query(
        """
        UPDATE OR REPLACE recent SET streamUrl = (
            SELECT c.streamUrl FROM channel c WHERE c.playlistId = recent.playlistId AND c.name = recent.name
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE playlistId = :playlistId
        AND NOT EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = recent.playlistId AND c.streamUrl = recent.streamUrl)
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = recent.playlistId AND c.name = recent.name)
        """,
    )
    suspend fun relinkRecentsByName(playlistId: Long)

    /** Keeps favourites and recents pointing at the right channels after an import. */
    @Transaction
    suspend fun relinkAfterImport(playlistId: Long) {
        refreshFavoriteNames(playlistId)
        relinkFavoritesByName(playlistId)
        refreshRecentNames(playlistId)
        relinkRecentsByName(playlistId)
    }
}
