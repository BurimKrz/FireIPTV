package com.fireiptv.core.data.vod

import android.util.JsonReader
import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.fireiptv.core.data.db.MovieCategoryEntity
import com.fireiptv.core.data.db.MovieEntity
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.playlist.ImportException
import com.fireiptv.core.data.playlist.xtream.MovieInfo
import com.fireiptv.core.data.playlist.xtream.XtreamAccount
import com.fireiptv.core.data.playlist.xtream.XtreamJson
import com.fireiptv.core.data.playlist.xtream.XtreamMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Movies from an Xtream playlist. Nothing is downloaded up front: the category list (a few kB)
 * the first time Movies is opened, a category's movies (median 16 kB, biggest ~420 kB) the first
 * time that category is opened, and a movie's details (~1.5 kB) when its page is opened. Lists are
 * kept in the database until the playlist's next refresh deletes them; details stay in memory.
 */
@Singleton
class MovieRepository @Inject constructor(
    private val db: PlayerDatabase,
    client: OkHttpClient,
) {
    // A big category's JSON can take a while to build on the provider's side.
    private val client = client.newBuilder().readTimeout(1, TimeUnit.MINUTES).build()
    private val dao = db.movieDao()
    private val playlistDao = db.playlistDao()
    private val lock = Mutex()
    private val infoCache = LruCache<String, MovieInfo>(INFO_CACHE_SIZE)

    /** Movies need the Xtream API; an M3U link has no movie list to page through. */
    fun supportsMovies(playlistUrl: String): Boolean = XtreamAccount.fromUrl(playlistUrl) != null

    fun categories(playlistId: Long): Flow<List<MovieCategoryEntity>> = dao.observeCategories(playlistId)

    /** Downloads the category list unless it's already here. */
    suspend fun loadCategories(playlistId: Long) = lock.withLock {
        if (dao.categoryCount(playlistId) > 0) return@withLock
        val (playlist, account) = account(playlistId)
        val categories = fetchJson(account.apiUrl("get_vod_categories"), playlist) { XtreamJson.readCategories(it) }
        if (categories.isEmpty()) throw ImportException("The provider has no movies on this account")
        dao.insertCategories(
            categories.mapIndexed { index, category ->
                MovieCategoryEntity(playlistId = playlistId, providerId = category.id, title = category.name, sortOrder = index)
            },
        )
    }

    /** Downloads a category's movies unless they're already here. */
    suspend fun loadMovies(categoryId: Long) = lock.withLock {
        val category = dao.category(categoryId) ?: return@withLock
        if (category.loadedAt != null) return@withLock
        val (playlist, account) = account(category.playlistId)
        val url = account.apiUrl("get_vod_streams").newBuilder().addQueryParameter("category_id", category.providerId).build()
        val movies = ArrayList<XtreamMovie>()
        fetchJson(url, playlist) { reader -> XtreamJson.readMovies(reader) { movies += it } }
        // Newest first: the provider sends them in its own order, usually oldest first.
        val sorted = movies.withIndex().sortedWith(compareByDescending<IndexedValue<XtreamMovie>> { it.value.added ?: 0L }.thenBy { it.index })
        db.withTransaction {
            dao.deleteMovies(categoryId)
            sorted.asSequence().mapIndexed { order, (_, movie) -> movie.toEntity(category, order) }.chunked(BATCH_SIZE).forEach {
                currentCoroutineContext().ensureActive()
                dao.insertMovies(it)
            }
            dao.setLoaded(categoryId, System.currentTimeMillis(), sorted.size)
        }
    }

    fun movies(categoryId: Long): Flow<PagingData<MovieEntity>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2, jumpThreshold = PAGE_SIZE * 3)) {
            dao.pageMovies(categoryId)
        }.flow

    suspend fun movie(id: Long): MovieEntity? = dao.movie(id)

    /** The movie's details; downloaded once per app run. */
    suspend fun info(movie: MovieEntity): MovieInfo {
        val key = "${movie.playlistId}/${movie.streamId}"
        infoCache.get(key)?.let { return it }
        val (playlist, account) = account(movie.playlistId)
        val url = account.apiUrl("get_vod_info").newBuilder().addQueryParameter("vod_id", movie.streamId).build()
        return fetchJson(url, playlist) { XtreamJson.readMovieInfo(it) }.also { infoCache.put(key, it) }
    }

    /** Where the movie's file is, for the player. */
    suspend fun streamUrl(movie: MovieEntity): String {
        val (_, account) = account(movie.playlistId)
        return account.movieUrl(movie.streamId, movie.extension)
    }

    private suspend fun account(playlistId: Long): Pair<PlaylistEntity, XtreamAccount> {
        val playlist = playlistDao.get(playlistId) ?: throw ImportException("The playlist was removed")
        val account = XtreamAccount.fromUrl(playlist.url) ?: throw ImportException("Movies need an Xtream login, not an M3U link")
        return playlist to account
    }

    private fun XtreamMovie.toEntity(category: MovieCategoryEntity, order: Int): MovieEntity {
        val (title, year) = cleanMovieName(name)
        return MovieEntity(
            playlistId = category.playlistId,
            categoryId = category.id,
            streamId = streamId,
            name = title.ifEmpty { "Movie $streamId" },
            year = year,
            posterUrl = icon?.takeIf { it.startsWith("http") },
            rating = rating,
            addedAt = added,
            extension = extension ?: "mp4",
            sortOrder = order,
        )
    }

    private suspend fun <T> fetchJson(url: HttpUrl, playlist: PlaylistEntity, read: suspend (JsonReader) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { playlist.userAgent?.let { header("User-Agent", it) } }.build()
        var lastError: IOException? = null
        // One retry after a connection error or a 5xx: providers hiccup, and the user is waiting.
        for (attempt in 0..1) {
            if (attempt > 0) delay(RETRY_DELAY_MS)
            currentCoroutineContext().ensureActive()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in 500..599 && attempt == 0) return@use
                    if (!response.isSuccessful) throw ImportException("The provider answered HTTP ${response.code}")
                    return@withContext try {
                        JsonReader(response.body.charStream()).use { read(it) }
                    } catch (e: IllegalStateException) {
                        throw ImportException("The provider sent a response that isn't Xtream JSON", e)
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw ImportException("Couldn't reach the provider: ${lastError?.message ?: "server error"}", lastError)
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val BATCH_SIZE = 1_000
        const val INFO_CACHE_SIZE = 100
        const val RETRY_DELAY_MS = 2_000L
    }
}

/**
 * Splits a provider's movie name into what to show and the year. Providers put the language in
 * front ("BL| ", "IN| MALAYALAM| "), which the category already says, and the year at the end
 * (" - 2024", " (2024)"), sometimes before tags like "[MULTI-SUB]", which stay.
 */
internal fun cleanMovieName(raw: String): Pair<String, Int?> {
    val name = raw.replace(LANGUAGE_PREFIX, "").trim()
    val match = YEAR.find(name) ?: return name to null
    val year = match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull()
    return name.removeRange(match.range).replace(SPACES, " ").trim() to year
}

private val LANGUAGE_PREFIX = Regex("""^(?:[^|\[\]()]{1,12}\|\s*)+""")

// The year, then only [tags] to the end of the name.
private val YEAR = Regex("""\s*(?:-\s*((?:19|20)\d{2})|[(\[]((?:19|20)\d{2})[)\]])(?=(?:\s*\[[^\]]*])*\s*$)""")
private val SPACES = Regex("""\s{2,}""")
