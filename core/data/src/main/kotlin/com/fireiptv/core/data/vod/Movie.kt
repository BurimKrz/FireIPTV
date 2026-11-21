package com.fireiptv.core.data.vod

import com.fireiptv.core.data.db.MovieEntity
import com.fireiptv.core.data.db.WatchProgressEntity

/**
 * What a movie's page and the player need, from its row in a downloaded category or, after a
 * refresh dropped the categories, from its Continue watching entry.
 */
data class Movie(
    val playlistId: Long,
    /** The provider's `stream_id`. */
    val streamId: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val rating: Float?,
    /** File type: mp4, mkv, avi… */
    val extension: String,
)

/** A movie's file. [url] carries the login: never log it. */
data class MovieFile(val url: String, val userAgent: String?)

internal fun MovieEntity.toMovie() = Movie(playlistId, streamId, name, year, posterUrl, rating, extension)

internal fun WatchProgressEntity.toMovie() = Movie(playlistId, streamId, name, year, posterUrl, rating, extension)
