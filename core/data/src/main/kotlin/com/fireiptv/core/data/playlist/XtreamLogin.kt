package com.fireiptv.core.data.playlist

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An Xtream login as the add screen shows it: the server, and the account on it. */
data class XtreamLogin(val server: String, val username: String, val password: String)

/**
 * The playlist link for an Xtream login: `<server>/player_api.php?username=…&password=…`, which
 * the importer reads through the Xtream API (live channels only). [server] may be typed without
 * `http://`, or be a whole get.php link pasted in; only its server part is kept. Null if it isn't
 * a server address.
 */
fun xtreamPlaylistUrl(server: String, username: String, password: String): String? {
    val typed = server.trim()
    if (typed.isEmpty() || username.isEmpty() || password.isEmpty()) return null
    val withScheme = if (typed.contains("://")) typed else "http://$typed"
    val parsed = withScheme.trimEnd('/').toHttpUrlOrNull() ?: return null
    return serverOf(parsed).newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()
}

/** The login inside an Xtream `get.php` or `player_api.php` link, or null for any other link. */
fun parseXtreamLogin(url: String): XtreamLogin? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return null
    if (parsed.pathSegments.lastOrNull() !in XTREAM_ENDPOINTS) return null
    val username = parsed.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
    val password = parsed.queryParameter("password")?.takeIf { it.isNotEmpty() } ?: return null
    return XtreamLogin(serverOf(parsed).toString().trimEnd('/'), username, password)
}

/** Scheme, host, port and any path before the Xtream endpoint; no query. */
private fun serverOf(url: HttpUrl): HttpUrl {
    val path = url.pathSegments.filter { it.isNotEmpty() && it !in XTREAM_ENDPOINTS }
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .apply { path.forEach { addPathSegment(it) } }
        .build()
}

private val XTREAM_ENDPOINTS = setOf("get.php", "player_api.php")
