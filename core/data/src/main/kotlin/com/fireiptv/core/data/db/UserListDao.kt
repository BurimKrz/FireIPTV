package com.fireiptv.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A user's category with the number of its channels that exist in the playlist now. */
data class UserListSummary(val id: Long, val title: String, val pinnedAt: Long?, val channelCount: Int)

/**
 * The user's own categories. Entries point at channels by stream URL like favourites, and the
 * first channel in playlist order stands for a URL two channels share (`MIN(id)`).
 */
@Dao
interface UserListDao {

    @Insert
    suspend fun insertList(list: UserListEntity): Long

    @Query("UPDATE user_list SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM user_list WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE user_list SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinnedAt(id: Long, pinnedAt: Long?)

    @Query("SELECT title FROM user_list WHERE id = :id")
    suspend fun title(id: Long): String?

    @Query(
        """
        SELECT l.id, l.title, l.pinnedAt, (
            SELECT COUNT(*) FROM user_list_entry e WHERE e.listId = l.id
            AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = l.playlistId AND c.streamUrl = e.streamUrl)
            AND NOT EXISTS(SELECT 1 FROM hidden_channel hc WHERE hc.playlistId = l.playlistId AND hc.streamUrl = e.streamUrl)
        ) AS channelCount
        FROM user_list l
        WHERE l.playlistId = :playlistId
        ORDER BY l.createdAt
        """,
    )
    fun observeLists(playlistId: Long): Flow<List<UserListSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addEntry(entry: UserListEntryEntity)

    @Query("DELETE FROM user_list_entry WHERE listId = :listId AND streamUrl = :streamUrl")
    suspend fun removeEntry(listId: Long, streamUrl: String)

    /** The categories that hold [streamUrl], for the channel options menu. */
    @Query(
        """
        SELECT e.listId FROM user_list_entry e JOIN user_list l ON l.id = e.listId
        WHERE l.playlistId = :playlistId AND e.streamUrl = :streamUrl
        """,
    )
    fun observeListsWith(playlistId: Long, streamUrl: String): Flow<List<Long>>

    @Query("SELECT MAX(sortOrder) FROM user_list_entry WHERE listId = :listId")
    suspend fun lastEntrySortOrder(listId: Long): Long?

    @Query("SELECT sortOrder FROM user_list_entry WHERE listId = :listId AND streamUrl = :streamUrl")
    suspend fun entrySortOrder(listId: Long, streamUrl: String): Long?

    @Query(
        """
        SELECT c.id, c.name, c.number, c.sortOrder, c.logoUrl, $IS_FAVORITE FROM user_list_entry e
        JOIN user_list l ON l.id = e.listId
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = l.playlistId AND streamUrl = e.streamUrl)
        WHERE e.listId = :listId AND $NOT_HIDDEN_CHANNEL
        ORDER BY e.sortOrder
        """,
    )
    fun pageEntries(listId: Long): PagingSource<Int, ChannelListItem>

    // Zapping through a category, in the order channels were added; wraps around like the other lists.

    @Query(
        """
        SELECT c.* FROM user_list_entry e
        JOIN user_list l ON l.id = e.listId
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = l.playlistId AND streamUrl = e.streamUrl)
        WHERE e.listId = :listId AND e.sortOrder > :sortOrder AND $NOT_HIDDEN_CHANNEL
        ORDER BY e.sortOrder LIMIT 1
        """,
    )
    suspend fun entryAfter(listId: Long, sortOrder: Long): ChannelEntity?

    @Query(
        """
        SELECT c.* FROM user_list_entry e
        JOIN user_list l ON l.id = e.listId
        JOIN channel c ON c.id = (SELECT MIN(id) FROM channel WHERE playlistId = l.playlistId AND streamUrl = e.streamUrl)
        WHERE e.listId = :listId AND e.sortOrder < :sortOrder AND $NOT_HIDDEN_CHANNEL
        ORDER BY e.sortOrder DESC LIMIT 1
        """,
    )
    suspend fun entryBefore(listId: Long, sortOrder: Long): ChannelEntity?

    @Query(
        """
        SELECT COUNT(*) FROM user_list_entry e JOIN user_list l ON l.id = e.listId
        WHERE e.listId = :listId AND e.sortOrder < :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = l.playlistId AND c.streamUrl = e.streamUrl)
        AND $ENTRY_NOT_HIDDEN
        """,
    )
    suspend fun entryPosition(listId: Long, sortOrder: Long): Int

    // Reordering, like LibraryDao.moveFavorite: trade places with the next entry shown.

    @Query(
        """
        SELECT e.* FROM user_list_entry e JOIN user_list l ON l.id = e.listId
        WHERE e.listId = :listId AND e.sortOrder < :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = l.playlistId AND c.streamUrl = e.streamUrl)
        AND $ENTRY_NOT_HIDDEN
        ORDER BY e.sortOrder DESC LIMIT 1
        """,
    )
    suspend fun entryShownAbove(listId: Long, sortOrder: Long): UserListEntryEntity?

    @Query(
        """
        SELECT e.* FROM user_list_entry e JOIN user_list l ON l.id = e.listId
        WHERE e.listId = :listId AND e.sortOrder > :sortOrder
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = l.playlistId AND c.streamUrl = e.streamUrl)
        AND $ENTRY_NOT_HIDDEN
        ORDER BY e.sortOrder LIMIT 1
        """,
    )
    suspend fun entryShownBelow(listId: Long, sortOrder: Long): UserListEntryEntity?

    @Query("UPDATE user_list_entry SET sortOrder = :sortOrder WHERE listId = :listId AND streamUrl = :streamUrl")
    suspend fun setEntrySortOrder(listId: Long, streamUrl: String, sortOrder: Long)

    /** Moves a channel one place up or down in its category; returns false at either end. */
    @Transaction
    suspend fun moveEntry(listId: Long, streamUrl: String, up: Boolean): Boolean {
        val own = entrySortOrder(listId, streamUrl) ?: return false
        val other = (if (up) entryShownAbove(listId, own) else entryShownBelow(listId, own)) ?: return false
        setEntrySortOrder(listId, streamUrl, other.sortOrder)
        setEntrySortOrder(listId, other.streamUrl, own)
        return true
    }

    // Backup and restore.

    @Query("SELECT * FROM user_list WHERE playlistId = :playlistId ORDER BY createdAt")
    suspend fun lists(playlistId: Long): List<UserListEntity>

    @Query("SELECT * FROM user_list_entry WHERE listId = :listId ORDER BY sortOrder")
    suspend fun entries(listId: Long): List<UserListEntryEntity>

    @Query("SELECT id FROM user_list WHERE playlistId = :playlistId AND title = :title LIMIT 1")
    suspend fun listNamed(playlistId: Long, title: String): Long?

    // After an import, like LibraryDao.relinkAfterImport: entries take their channel's current
    // name, and an entry whose URL is gone moves to a channel with the same name, if there is one.

    @Query(
        """
        UPDATE user_list_entry SET name = (
            SELECT c.name FROM channel c WHERE c.playlistId = :playlistId AND c.streamUrl = user_list_entry.streamUrl
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE listId IN (SELECT id FROM user_list WHERE playlistId = :playlistId)
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = :playlistId AND c.streamUrl = user_list_entry.streamUrl)
        """,
    )
    suspend fun refreshEntryNames(playlistId: Long)

    @Query(
        """
        UPDATE OR REPLACE user_list_entry SET streamUrl = (
            SELECT c.streamUrl FROM channel c WHERE c.playlistId = :playlistId AND c.name = user_list_entry.name
            ORDER BY c.sortOrder LIMIT 1
        )
        WHERE listId IN (SELECT id FROM user_list WHERE playlistId = :playlistId)
        AND NOT EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = :playlistId AND c.streamUrl = user_list_entry.streamUrl)
        AND EXISTS(SELECT 1 FROM channel c WHERE c.playlistId = :playlistId AND c.name = user_list_entry.name)
        """,
    )
    suspend fun relinkEntriesByName(playlistId: Long)

    @Transaction
    suspend fun relinkAfterImport(playlistId: Long) {
        refreshEntryNames(playlistId)
        relinkEntriesByName(playlistId)
    }
}

/** Category entry `e` (in list `l`) is not a channel the user hid. */
internal const val ENTRY_NOT_HIDDEN =
    "NOT EXISTS(SELECT 1 FROM hidden_channel hc WHERE hc.playlistId = l.playlistId AND hc.streamUrl = e.streamUrl)"
