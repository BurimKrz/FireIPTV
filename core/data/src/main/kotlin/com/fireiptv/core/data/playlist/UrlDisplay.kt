package com.fireiptv.core.data.playlist

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A playlist URL safe to put on screen: scheme, host and port only. Provider URLs carry the
 * username and password in the path (`/live/user/pass/1.ts`) or query (`get.php?username=`).
 */
fun displayUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "(invalid URL)"
    val port = if (parsed.port == okhttp3.HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
    return "${parsed.scheme}://${parsed.host}$port"
}

/** Default playlist name when the user gives none: the server's host, never the credentials. */
internal fun defaultPlaylistName(url: String): String = url.toHttpUrlOrNull()?.host ?: "Playlist"
