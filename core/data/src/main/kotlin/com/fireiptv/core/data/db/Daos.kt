package com.fireiptv.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A playlist with its channel and group counts, for the playlists screen. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val url: String,
    val epgUrl: String?,
    val lastSync: Long?,
    val channelCount: Int,
    val groupCount: Int,
)

/** A group as the groups settings screen shows it. */
data class GroupSetting(val id: Long, val title: String, val channelCount: Int, val hidden: Boolean)

/** A group with its channel count, for the browse screen's groups column. */
data class GroupSummary(val id: Long, val title: String, val channelCount: Int)

/** The columns the channel list shows, so paging doesn't load stream URLs and catch-up fields. */
data class ChannelListItem(val id: Long, val name: String, val number: Int?, val sortOrder: Int, val favorite: Boolean)

/** A search result: a channel with its group, so results from many groups can be told apart. */
data class ChannelSearchItem(
    val id: Long,
    val name: String,
    val number: Int?,
    val sortOrder: Int,
    val groupId: Long?,
    val groupTitle: String?,
    val favorite: Boolean,
)

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query(
        """
        SELECT p.id, p.name, p.url, p.epgUrl, p.lastSync,
            (SELECT COUNT(*) FROM channel c WHERE c.playlistId = p.id) AS channelCount,
            (SELECT COUNT(*) FROM channel_group g WHERE g.playlistId = p.id) AS groupCount
        FROM playlist p
        WHERE p.isStaging = 0
        ORDER BY p.id
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist WHERE isStaging = 0)")
    suspend fun hasAny(): Boolean

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    /** Leftovers from an import that was killed mid-way. */
    @Query("DELETE FROM playlist WHERE isStaging = 1")
    suspend fun deleteAllStaging()

    @Query("DELETE FROM channel WHERE playlistId = :playlistId")
    suspend fun deleteChannels(playlistId: Long)

    @Query("DELETE FROM channel_group WHERE playlistId = :playlistId")
    suspend fun deleteGroups(playlistId: Long)

    @Query("UPDATE channel SET playlistId = :to WHERE playlistId = :from")
    suspend fun moveChannels(from: Long, to: Long)

    @Query("UPDATE channel_group SET playlistId = :to WHERE playlistId = :from")
    suspend fun moveGroups(from: Long, to: Long)

    @Query("SELECT title FROM channel_group WHERE playlistId = :playlistId AND hidden = 1")
    suspend fun hiddenGroupTitles(playlistId: Long): List<String>

    @Query("UPDATE channel_group SET hidden = 1 WHERE playlistId = :playlistId AND title IN (:titles)")
    suspend fun hideGroups(playlistId: Long, titles: List<String>)

    /**
     * Replaces [targetId]'s channels and groups with the staging playlist's, in one transaction.
     * Groups the user hid stay hidden, matched by title.
     */
    @Transaction
    suspend fun swapStaging(stagingId: Long, targetId: Long) {
        val hidden = hiddenGroupTitles(targetId)
        deleteChannels(targetId)
        deleteGroups(targetId)
        moveGroups(stagingId, targetId)
        moveChannels(stagingId, targetId)
        delete(stagingId)
        // Old Android caps a query at 999 arguments.
        hidden.chunked(500).forEach { hideGroups(targetId, it) }
    }
}

@Dao
interface GroupDao {

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Query("SELECT * FROM channel_group WHERE playlistId = :playlistId AND hidden = 0 ORDER BY sortOrder")
    fun observeGroups(playlistId: Long): Flow<List<GroupEntity>>

    @Query(
        """
        SELECT g.id, g.title, (SELECT COUNT(*) FROM channel c WHERE c.groupId = g.id) AS channelCount
        FROM channel_group g
        WHERE g.playlistId = :playlistId AND g.hidden = 0
        ORDER BY g.sortOrder
        """,
    )
    fun observeSummaries(playlistId: Long): Flow<List<GroupSummary>>

    @Query("SELECT title FROM channel_group WHERE id = :id")
    suspend fun title(id: Long): String?

    /** Every group, hidden ones too, for the groups settings screen. */
    @Query(
        """
        SELECT g.id, g.title, (SELECT COUNT(*) FROM channel c WHERE c.groupId = g.id) AS channelCount, g.hidden
        FROM channel_group g
        WHERE g.playlistId = :playlistId
        ORDER BY g.sortOrder
        """,
    )
    fun observeSettings(playlistId: Long): Flow<List<GroupSetting>>

    @Query("UPDATE channel_group SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE channel_group SET hidden = :hidden WHERE playlistId = :playlistId")
    suspend fun setAllHidden(playlistId: Long, hidden: Boolean)
}

@Dao
interface ChannelDao {

    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun pageByPlaylist(playlistId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channel WHERE groupId = :groupId ORDER BY sortOrder")
    fun pageByGroup(groupId: Long): PagingSource<Int, ChannelEntity>

    @Query(
        """
        SELECT id, name, number, sortOrder, $IS_FAVORITE FROM channel c
        WHERE playlistId = :playlistId AND $NOT_IN_HIDDEN_GROUP
        ORDER BY sortOrder
        """,
    )
    fun pageListByPlaylist(playlistId: Long): PagingSource<Int, ChannelListItem>

    // playlistId keeps the query on the (playlistId, groupId, sortOrder) index, so it needs no sort.
    @Query(
        """
        SELECT id, name, number, sortOrder, $IS_FAVORITE FROM channel c
        WHERE playlistId = :playlistId AND groupId = :groupId
        ORDER BY sortOrder
        """,
    )
    fun pageListByGroup(playlistId: Long, groupId: Long): PagingSource<Int, ChannelListItem>

    /** Channels of one playlist whose name matches the FTS [query], in playlist order. */
    @Query(
        """
        SELECT c.id, c.name, c.number, c.sortOrder, c.groupId, g.title AS groupTitle, $IS_FAVORITE
        FROM channel_fts
        JOIN channel c ON c.id = channel_fts.rowid
        LEFT JOIN channel_group g ON g.id = c.groupId
        WHERE channel_fts MATCH :query AND c.playlistId = :playlistId
        ORDER BY c.sortOrder
        LIMIT :limit
        """,
    )
    fun search(playlistId: Long, query: String, limit: Int): Flow<List<ChannelSearchItem>>

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    /** Channels in "All channels": everything outside hidden groups. */
    @Query("SELECT COUNT(*) FROM channel c WHERE playlistId = :playlistId AND $NOT_IN_HIDDEN_GROUP")
    fun observeVisibleCount(playlistId: Long): Flow<Int>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    // Zapping and list positions. A null groupId means all channels outside hidden groups; both
    // use sortOrder, the same order the browse lists show.

    @Query(
        """
        SELECT * FROM channel c
        WHERE playlistId = :playlistId AND (:groupId IS NULL OR groupId = :groupId) AND (:groupId IS NOT NULL OR $NOT_IN_HIDDEN_GROUP) AND sortOrder > :sortOrder
        ORDER BY sortOrder LIMIT 1
        """,
    )
    suspend fun after(playlistId: Long, groupId: Long?, sortOrder: Int): ChannelEntity?

    @Query(
        """
        SELECT * FROM channel c
        WHERE playlistId = :playlistId AND (:groupId IS NULL OR groupId = :groupId) AND (:groupId IS NOT NULL OR $NOT_IN_HIDDEN_GROUP) AND sortOrder < :sortOrder
        ORDER BY sortOrder DESC LIMIT 1
        """,
    )
    suspend fun before(playlistId: Long, groupId: Long?, sortOrder: Int): ChannelEntity?

    @Query(
        """
        SELECT * FROM channel c
        WHERE playlistId = :playlistId AND (:groupId IS NULL OR groupId = :groupId) AND (:groupId IS NOT NULL OR $NOT_IN_HIDDEN_GROUP)
        ORDER BY sortOrder LIMIT 1
        """,
    )
    suspend fun first(playlistId: Long, groupId: Long?): ChannelEntity?

    @Query(
        """
        SELECT * FROM channel c
        WHERE playlistId = :playlistId AND (:groupId IS NULL OR groupId = :groupId) AND (:groupId IS NOT NULL OR $NOT_IN_HIDDEN_GROUP)
        ORDER BY sortOrder DESC LIMIT 1
        """,
    )
    suspend fun last(playlistId: Long, groupId: Long?): ChannelEntity?

    @Query(
        """
        SELECT COUNT(*) FROM channel c
        WHERE playlistId = :playlistId AND (:groupId IS NULL OR groupId = :groupId) AND (:groupId IS NOT NULL OR $NOT_IN_HIDDEN_GROUP) AND sortOrder < :sortOrder
        """,
    )
    suspend fun position(playlistId: Long, groupId: Long?, sortOrder: Int): Int
}

/** Whether channel `c` is a favourite: one primary-key probe per row. */
internal const val IS_FAVORITE =
    "EXISTS(SELECT 1 FROM favorite f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS favorite"

/** Channel `c` is not in a group the user hid. */
internal const val NOT_IN_HIDDEN_GROUP =
    "NOT EXISTS(SELECT 1 FROM channel_group hg WHERE hg.id = c.groupId AND hg.hidden = 1)"
