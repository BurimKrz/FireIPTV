package com.fireiptv.core.data.account

import android.util.JsonReader
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.playlist.xtream.XtreamAccount
import com.fireiptv.core.data.playlist.xtream.XtreamJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The provider account behind an Xtream playlist, as its panel reports it. */
data class AccountInfo(
    val username: String?,
    val status: String?,
    /** Epoch seconds; null when the account doesn't expire. */
    val expiresAt: Long?,
    val isTrial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val createdAt: Long?,
)

sealed interface AccountResult {
    data class Loaded(val info: AccountInfo) : AccountResult
    data class Failed(val message: String) : AccountResult
    /** The playlist is a plain M3U link, which carries no account details. */
    data object NotAvailable : AccountResult
}

/**
 * Account details for Settings. Fetched only when asked (about 1 KB, no video stream, so it
 * doesn't take the account's connection), never in the background.
 */
@Singleton
class AccountRepository @Inject constructor(
    db: PlayerDatabase,
    playlistRepository: PlaylistRepository,
    private val client: OkHttpClient,
) {
    private val playlistDao = db.playlistDao()

    /** True when the first playlist is an Xtream login, so there is an account to show. */
    val available: Flow<Boolean> = playlistRepository.playlists
        .map { playlists -> playlists.firstOrNull()?.url?.let { XtreamAccount.fromUrl(it) } != null }
        .distinctUntilChanged()

    suspend fun load(): AccountResult = withContext(Dispatchers.IO) {
        val playlist = playlistDao.first() ?: return@withContext AccountResult.NotAvailable
        val account = XtreamAccount.fromUrl(playlist.url) ?: return@withContext AccountResult.NotAvailable
        val request = Request.Builder().url(account.apiUrl()).apply { playlist.userAgent?.let { header("User-Agent", it) } }.build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext AccountResult.Failed("The provider answered HTTP ${response.code}")
                val user = JsonReader(response.body.charStream()).use { XtreamJson.readUserInfo(it) }
                if (!user.authenticated) return@withContext AccountResult.Failed("The provider rejected the username or password")
                AccountResult.Loaded(
                    AccountInfo(
                        username = user.username,
                        status = user.status,
                        expiresAt = user.expiresAt,
                        isTrial = user.isTrial,
                        activeConnections = user.activeConnections,
                        maxConnections = user.maxConnections,
                        createdAt = user.createdAt,
                    ),
                )
            }
        } catch (e: IOException) {
            AccountResult.Failed("Couldn't reach the provider: ${e.message ?: "network error"}")
        } catch (e: IllegalStateException) {
            AccountResult.Failed("The provider sent a response that isn't Xtream JSON")
        }
    }
}
