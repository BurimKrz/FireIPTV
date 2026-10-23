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

/** A group with its channel count, for the browse screen's groups column. */
data class GroupSummary(val id: Long, val title: String, val channelCount: Int)

/** The columns the channel list shows, so paging doesn't load stream URLs and catch-up fields. */
data class ChannelListItem(val id: Long, val name: String, val number: Int?, val sortOrder: Int)

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

    /** Replaces [targetId]'s channels and groups with the staging playlist's, in one transaction. */
    @Transaction
    suspend fun swapStaging(stagingId: Long, targetId: Long) {
        deleteChannels(targetId)
        deleteGroups(targetId)
        moveGroups(stagingId, targetId)
        moveChannels(stagingId, targetId)
        delete(stagingId)
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
}

@Dao
interface ChannelDao {

    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun pageByPlaylist(playlistId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channel WHERE groupId = :groupId ORDER BY sortOrder")
    fun pageByGroup(groupId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT id, name, number, sortOrder FROM channel WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun pageListByPlaylist(playlistId: Long): PagingSource<Int, ChannelListItem>

    // playlistId keeps the query on the (playlistId, groupId, sortOrder) index, so it needs no sort.
    @Query(
        """
        SELECT id, name, number, sortOrder FROM channel
        WHERE playlistId = :playlistId AND groupId = :groupId
        ORDER BY sortOrder
        """,
    )
    fun pageListByGroup(playlistId: Long, groupId: Long): PagingSource<Int, ChannelListItem>

    @Query(
        """
        SELECT channel.* FROM channel
        JOIN channel_fts ON channel.rowid = channel_fts.rowid
        WHERE channel_fts MATCH :query
        ORDER BY channel.sortOrder
        """,
    )
    fun search(query: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int
}
