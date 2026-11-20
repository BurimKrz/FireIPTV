package com.fireiptv.feature.browse.movies

/**
 * Most posters come from TMDB's image server, which serves every image at several widths. The
 * provider links a big one (600×900, ~185 kB); a list thumbnail needs 92 px wide (~6 kB) and the
 * movie's page 342 px (~58 kB). Other servers' links are left alone.
 */
internal enum class PosterSize(val path: String) {
    /** 36×54 dp list thumbnails: 72×108 px on a 1080p TV. */
    Thumbnail("w92"),

    /** The 240×360 dp poster on a movie's page. */
    Page("w342"),
}

internal fun posterUrl(url: String?, size: PosterSize): String? = url?.replace(TMDB_SIZE, "$1${size.path}/")

private val TMDB_SIZE = Regex("""^(https?://image\.tmdb\.org/t/p/)[^/]+/""")
