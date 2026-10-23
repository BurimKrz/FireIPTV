package com.fireiptv.core.data.playlist.xtream

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Xtream Codes login, recognised from the `get.php` or `player_api.php` link providers hand out.
 * The API returns live channels only (a few MB) where get.php sends every movie and series too.
 */
internal class XtreamAccount(
    private val baseUrl: HttpUrl,
    private val username: String,
    private val password: String,
    /** `output=` from the get.php link, when the provider put one there. */
    val preferredFormat: String?,
) {
    fun apiUrl(action: String? = null): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .apply { if (action != null) addQueryParameter("action", action) }
        .build()

    fun liveStreamUrl(streamId: String, extension: String): String = baseUrl.newBuilder()
        .addPathSegment("live")
        .addPathSegment(username)
        .addPathSegment(password)
        .addPathSegment("$streamId.$extension")
        .build()
        .toString()

    companion object {
        fun fromUrl(url: String): XtreamAccount? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val endpoint = parsed.pathSegments.lastOrNull()
            if (endpoint != "get.php" && endpoint != "player_api.php") return null
            val username = parsed.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
            val password = parsed.queryParameter("password")?.takeIf { it.isNotEmpty() } ?: return null

            val base = parsed.newBuilder()
                .removePathSegment(parsed.pathSize - 1)
                .query(null)
                .fragment(null)
                .build()
            return XtreamAccount(base, username, password, parsed.queryParameter("output")?.lowercase())
        }
    }
}
