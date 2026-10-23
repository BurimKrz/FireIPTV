package com.fireiptv.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val epgUrl: String? = null,
    val userAgent: String? = null,
    /** Epoch millis of the last successful sync, or null before the first one. */
    val lastSync: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** A refresh imports into a hidden staging row, then swaps it in (see [PlaylistDao.swapStaging]). */
    val isStaging: Boolean = false,
)

@Entity(
    tableName = "channel_group",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId", "sortOrder")],
)
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
)

@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(GroupEntity::class, ["id"], ["groupId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("playlistId", "groupId", "sortOrder"),
        // The all-channels list pages 28k+ rows in playlist order; without this every page sorts them all.
        Index("playlistId", "sortOrder"),
        // Favourites and recents find their channel by stream URL, which survives a refresh.
        Index("playlistId", "streamUrl"),
        Index("groupId"),
        Index("tvgId"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val groupId: Long?,
    val name: String,
    val number: Int?,
    val streamUrl: String,
    val logoUrl: String?,
    val tvgId: String?,
    val tvgName: String?,
    val catchupType: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    /** EPG shift in hours. */
    val tvgShift: Float?,
    val userAgent: String?,
    val referrer: String?,
    val sortOrder: Int,
)

// unicode61 folds case and accents for every script, so "kosove" finds "KOSOVË".
@Fts4(contentEntity = ChannelEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "channel_fts")
data class ChannelFts(
    @ColumnInfo(name = "name") val name: String,
)

/**
 * A favourite channel. Keyed by stream URL, not channel id: a refresh replaces every channel row,
 * but the provider's URL for a channel stays the same. [name] relinks it if the URL changes.
 */
@Entity(
    tableName = "favorite",
    primaryKeys = ["playlistId", "streamUrl"],
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "sortOrder")],
)
data class FavoriteEntity(
    val playlistId: Long,
    val streamUrl: String,
    val name: String,
    /** Epoch millis when added; favourites list in the order they were added. */
    val sortOrder: Long,
)

/** A recently watched channel, keyed like [FavoriteEntity]. */
@Entity(
    tableName = "recent",
    primaryKeys = ["playlistId", "streamUrl"],
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "watchedAt")],
)
data class RecentEntity(
    val playlistId: Long,
    val streamUrl: String,
    val name: String,
    val watchedAt: Long,
)
