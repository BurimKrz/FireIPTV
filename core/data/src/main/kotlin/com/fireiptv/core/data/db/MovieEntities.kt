package com.fireiptv.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A movie category from the provider (Xtream `get_vod_categories`). Its movies are downloaded the
 * first time it is opened ([loadedAt]) and kept until the playlist's next refresh, which deletes
 * every category so they load fresh.
 */
@Entity(
    tableName = "movie_category",
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "providerId", unique = true), Index("playlistId", "sortOrder")],
)
data class MovieCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** The provider's `category_id`. */
    val providerId: String,
    val title: String,
    val sortOrder: Int,
    /** Epoch millis when its movies were downloaded; null until the category is first opened. */
    val loadedAt: Long? = null,
    val movieCount: Int? = null,
)

/** A movie in one category (the same movie in two categories is two rows). */
@Entity(
    tableName = "movie",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MovieCategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("categoryId", "sortOrder"), Index("playlistId", "streamId")],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val categoryId: Long,
    /** The provider's `stream_id`: the movie's file is `/movie/<user>/<pass>/<streamId>.<extension>`. */
    val streamId: String,
    /** The provider's name without a trailing year, which is in [year]. */
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    /** Out of 10; null when the provider has none. */
    val rating: Float?,
    /** Epoch seconds when the provider added it. */
    val addedAt: Long?,
    /** File type: mp4, mkv, avi… */
    val extension: String,
    /** Place in its category: newest added first. */
    val sortOrder: Int,
)

/**
 * How far a movie was watched. Keyed by the provider's stream id, not the movie row, because a
 * refresh deletes the movie rows; it keeps what Continue watching shows, so that row works before
 * the movie's category is downloaded again.
 */
@Entity(
    tableName = "watch_progress",
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "streamId", unique = true), Index("playlistId", "updatedAt")],
)
data class WatchProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val streamId: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val rating: Float?,
    val extension: String,
    val positionMs: Long,
    /** 0 when the file doesn't say how long it is. */
    val durationMs: Long,
    /** Watched to the end (95%): it leaves Continue watching and plays from the start next time. */
    val finished: Boolean,
    /** Epoch millis of the last save; Continue watching shows the latest first. */
    val updatedAt: Long,
)
