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
        WHERE f.playlistId = :playlistId AND $NOT_HIDDEN_CHANNEL
        ORDER BY f.sortOrder
        """,
    )
    fun pageFavorites(playlistId: Long): PagingSource<Int, ChannelListItem>

    @Query(
        """
        SELECT c.id, c.name, c.number, c.sortOrder, c.logoUrl, $IS_FAVORITE FROM recent r
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = r.playlistId AND streamUrl = r.streamUrl)
        WHERE r.playlistId = :playlistId AND $NOT_HIDDEN_CHANNEL
        ORDER BY r.watchedAt DESC
        """,
    )
    fun pageRecents(playlistId: Long): PagingSource<Int, ChannelListItem>

    @Query(
        """
        SELECT COUNT(*) FROM favorite f WHERE f.playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl)
        AND $FAVORITE_NOT_HIDDEN
        """,
    )
    fun observeFavoriteCount(playlistId: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM recent r WHERE r.playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl)
        AND NOT EXISTS(SELECT 1 FROM hidden_channel hc WHERE hc.playlistId = r.playlistId AND hc.streamUrl = r.streamUrl)
        """,
    )
    fun observeRecentCount(playlistId: Long): Flow<Int>

    // Zapping through favourites, in the order they were added; wraps around like the other lists.

    @Query(
        """
        SELECT c.* FROM favorite f
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = f.playlistId AND streamUrl = f.streamUrl)
        WHERE f.playlistId = :playlistId AND f.sortOrder > :sortOrder AND $NOT_HIDDEN_CHANNEL
        ORDER BY f.sortOrder LIMIT 1
        """,
    )
    suspend fun favoriteAfter(playlistId: Long, sortOrder: Long): ChannelEntity?

    @Query(
        """
        SELECT c.* FROM favorite f
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = f.playlistId AND streamUrl = f.streamUrl)
        WHERE f.playlistId = :playlistId AND f.sortOrder < :sortOrder AND $NOT_HIDDEN_CHANNEL
        ORDER BY f.sortOrder DESC LIMIT 1
        """,
    )
    suspend fun favoriteBefore(playlistId: Long, sortOrder: Long): ChannelEntity?

    @Query(
        """
        SELECT COUNT(*) FROM favorite f WHERE f.playlistId = :playlistId AND f.sortOrder < :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl)
        AND $FAVORITE_NOT_HIDDEN
        """,
    )
    suspend fun favoritePosition(playlistId: Long, sortOrder: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM recent r WHERE r.playlistId = :playlistId AND r.watchedAt > :watchedAt
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl)
        AND NOT EXISTS(SELECT 1 FROM hidden_channel hc WHERE hc.playlistId = r.playlistId AND hc.streamUrl = r.streamUrl)
        """,
    )
    suspend fun recentPosition(playlistId: Long, watchedAt: Long): Int

    // Reordering: a favourite trades places with the next one shown above or below it. Entries
    // whose channel is gone or hidden aren't shown, so they are stepped over.

    @Query(
        """
        SELECT * FROM favorite f WHERE f.playlistId = :playlistId AND f.sortOrder < :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl)
        AND $FAVORITE_NOT_HIDDEN
        ORDER BY f.sortOrder DESC LIMIT 1
        """,
    )
    suspend fun favoriteShownAbove(playlistId: Long, sortOrder: Long): FavoriteEntity?

    @Query(
        """
        SELECT * FROM favorite f WHERE f.playlistId = :playlistId AND f.sortOrder > :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl)
        AND $FAVORITE_NOT_HIDDEN
        ORDER BY f.sortOrder LIMIT 1
        """,
    )
    suspend fun favoriteShownBelow(playlistId: Long, sortOrder: Long): FavoriteEntity?

    @Query("UPDATE favorite SET sortOrder = :sortOrder WHERE playlistId = :playlistId AND streamUrl = :streamUrl")
    suspend fun setFavoriteSortOrder(playlistId: Long, streamUrl: String, sortOrder: Long)

    /** Moves a favourite one place up or down; returns false at either end. */
    @Transaction
    suspend fun moveFavorite(playlistId: Long, streamUrl: String, up: Boolean): Boolean {
        val own = favoriteSortOrder(playlistId, streamUrl) ?: return false
        val other = (if (up) favoriteShownAbove(playlistId, own) else favoriteShownBelow(playlistId, own)) ?: return false
        setFavoriteSortOrder(playlistId, streamUrl, other.sortOrder)
        setFavoriteSortOrder(playlistId, other.streamUrl, own)
        return true
    }

    @Query("SELECT MAX(sortOrder) FROM favorite WHERE playlistId = :playlistId")
    suspend fun lastFavoriteSortOrder(playlistId: Long): Long?

    @Query("SELECT * FROM favorite WHERE playlistId = :playlistId ORDER BY sortOrder")
    suspend fun favorites(playlistId: Long): List<FavoriteEntity>

    // Hidden channels.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(hidden: HiddenChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideAll(hidden: List<HiddenChannelEntity>)

    @Query("DELETE FROM hidden_channel WHERE playlistId = :playlistId AND streamUrl = :streamUrl")
    suspend fun unhide(playlistId: Long, streamUrl: String)

    @Query("DELETE FROM hidden_channel WHERE playlistId = :playlistId")
    suspend fun unhideAll(playlistId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_channel WHERE playlistId = :playlistId AND streamUrl = :streamUrl)")
    fun observeIsHidden(playlistId: Long, streamUrl: String): Flow<Boolean>

    @Query("SELECT * FROM hidden_channel WHERE playlistId = :playlistId ORDER BY hiddenAt")
    suspend fun hiddenChannels(playlistId: Long): List<HiddenChannelEntity>

    /** Hidden channels with their group, newest first; ones the provider dropped have no group. */
    @Query(
        """
        SELECT h.streamUrl, h.name, (
            SELECT g.title FROM channel c JOIN channel_group g ON g.id = c.groupId
            WHERE c.playlistId = h.playlistId AND c.streamUrl = h.streamUrl LIMIT 1
        ) AS groupTitle, h.hiddenAt
        FROM hidden_channel h WHERE h.playlistId = :playlistId
        ORDER BY h.hiddenAt DESC
        """,
    )
    fun observeHidden(playlistId: Long): Flow<List<HiddenChannelItem>>

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

    @Query(
        """
        UPDATE hidden_channel SET name = (
            SELECT c.name FROM channel c WHERE c.playlistId = hidden_channel.playlistId AND c.streamUrl = hidden_channel.streamUrl
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE playlistId = :playlistId
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = hidden_channel.playlistId AND c.streamUrl = hidden_channel.streamUrl)
        """,
    )
    suspend fun refreshHiddenNames(playlistId: Long)

    @Query(
        """
        UPDATE OR REPLACE hidden_channel SET streamUrl = (
            SELECT c.streamUrl FROM channel c WHERE c.playlistId = hidden_channel.playlistId AND c.name = hidden_channel.name
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE playlistId = :playlistId
        AND NOT EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = hidden_channel.playlistId AND c.streamUrl = hidden_channel.streamUrl)
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = hidden_channel.playlistId AND c.name = hidden_channel.name)
        """,
    )
    suspend fun relinkHiddenByName(playlistId: Long)

    /** Keeps favourites, recents and hidden channels pointing at the right channels after an import. */
    @Transaction
    suspend fun relinkAfterImport(playlistId: Long) {
        refreshFavoriteNames(playlistId)
        relinkFavoritesByName(playlistId)
        refreshRecentNames(playlistId)
        relinkRecentsByName(playlistId)
        refreshHiddenNames(playlistId)
        relinkHiddenByName(playlistId)
    }
}

/** Favourite `f` is not a channel the user hid. */
internal const val FAVORITE_NOT_HIDDEN =
    "NOT EXISTS(SELECT 1 FROM hidden_channel hc WHERE hc.playlistId = f.playlistId AND hc.streamUrl = f.streamUrl)"
