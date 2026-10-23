package com.fireiptv.core.parser.m3u

/** Attributes from the `#EXTM3U` header line. */
data class M3uHeader(
    /** EPG URLs from `url-tvg` / `x-tvg-url`; a header may list several, comma-separated. */
    val epgUrls: List<String> = emptyList(),
    val catchup: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    /** EPG time shift in hours, applied at query time. */
    val tvgShift: Float? = null,
)

/** One playable channel: an `#EXTINF` line, its modifier lines, and the URL line. */
data class M3uEntry(
    val name: String,
    val url: String,
    val duration: Int = -1,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val tvgChno: Int? = null,
    val groupTitle: String? = null,
    val catchup: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    val tvgShift: Float? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** `#KODIPROP:key=value` lines, kept as-is for the player to interpret. */
    val kodiProps: Map<String, String> = emptyMap(),
)

data class M3uResult(
    val header: M3uHeader?,
    val entries: Int,
    /** Lines that could not be used, e.g. an `#EXTINF` with no URL after it. */
    val skippedLines: Int,
)
