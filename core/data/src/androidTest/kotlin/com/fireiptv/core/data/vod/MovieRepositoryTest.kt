package com.fireiptv.core.data.vod

import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.db.MovieEntity
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.playlist.PlaylistImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovieRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: PlayerDatabase
    private lateinit var movies: MovieRepository
    private val requests = mutableListOf<String>()

    // Shapes from the real provider (Nov 2026): ids as numbers or strings, ratings as text.
    private val categories = """[
        {"category_id":"12","category_name":"[EN] THRILLER","parent_id":0},
        {"category_id":5,"category_name":"[IN] BOLLYWOOD","parent_id":0}
    ]"""
    private val thrillers = """[
        {"num":1,"name":"BL| Old One (2019)","stream_type":"movie","stream_id":11,"stream_icon":"http://img.example/11.jpg","rating":"6.1","added":"1600000000","category_id":"12","container_extension":"mkv"},
        {"num":2,"name":"Madden - 2026 [MULTI-SUB]","stream_type":"movie","stream_id":"22","stream_icon":"","rating":0,"added":1700000000,"category_id":"12","container_extension":"MP4"},
        {"num":3,"name":"No Year","stream_type":"movie","stream_id":33,"rating":"","added":null,"category_id":"12"}
    ]"""
    private val info = """{"info":{"plot":"A plot.","genre":"Thriller","director":"Someone","cast":"A, B","releasedate":"2026-01-02",
        "duration":"01:45:00","rating":"7.4","cover_big":"http://img.example/big.jpg","backdrop_path":["x"],
        "video":{"codec_name":"h264","width":1920,"height":800,"tags":{"x":"y"}},"audio":{"codec_name":"aac","channels":6}},
        "movie_data":{"stream_id":22,"container_extension":"mp4"}}"""

    private val live = """[{"num":1,"name":"News","stream_type":"live","stream_id":1,"category_id":"1"}]"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val action = url.queryParameter("action")
                synchronized(requests) { requests += listOfNotNull(action, url.queryParameter("category_id")).joinToString(":") }
                return when (action) {
                    null -> MockResponse().setBody("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}""")
                    "get_live_categories" -> MockResponse().setBody("""[{"category_id":"1","category_name":"News"}]""")
                    "get_live_streams" -> MockResponse().setBody(live)
                    "get_vod_categories" -> MockResponse().setBody(categories)
                    "get_vod_streams" -> MockResponse().setBody(if (url.queryParameter("category_id") == "12") thrillers else "[]")
                    "get_vod_info" -> MockResponse().setBody(info)
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
        db = PlayerDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
        movies = MovieRepository(db, OkHttpClient())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun addPlaylist(): Long {
        val url = server.url("/get.php").newBuilder().addQueryParameter("username", "user").addQueryParameter("password", "pass").build()
        return db.playlistDao().insert(PlaylistEntity(name = "Test", url = url.toString()))
    }

    private suspend fun moviesIn(categoryId: Long): List<MovieEntity> {
        val result = db.movieDao().pageMovies(categoryId).load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun categoriesDownloadOnceInProviderOrder() = runTest {
        val playlistId = addPlaylist()
        movies.loadCategories(playlistId)
        movies.loadCategories(playlistId)
        val rows = movies.categories(playlistId).first()
        assertEquals(listOf("[EN] THRILLER", "[IN] BOLLYWOOD"), rows.map { it.title })
        assertEquals(listOf("12", "5"), rows.map { it.providerId })
        assertEquals(listOf(null, null), rows.map { it.loadedAt })
        assertEquals(1, requests.count { it == "get_vod_categories" })
    }

    @Test
    fun aCategoryDownloadsOnceNewestFirstWithCleanNames() = runTest {
        val playlistId = addPlaylist()
        movies.loadCategories(playlistId)
        val thriller = movies.categories(playlistId).first().first()
        movies.loadMovies(thriller.id)
        movies.loadMovies(thriller.id)
        assertEquals(1, requests.count { it == "get_vod_streams:12" })

        val list = moviesIn(thriller.id)
        assertEquals(listOf("Madden [MULTI-SUB]", "Old One", "No Year"), list.map { it.name })
        assertEquals(listOf(2026, 2019, null), list.map { it.year })
        assertEquals(listOf(null, 6.1f, null), list.map { it.rating })
        assertEquals(listOf("mp4", "mkv", "mp4"), list.map { it.extension })
        assertEquals(listOf(null, "http://img.example/11.jpg", null), list.map { it.posterUrl })
        assertEquals(3, movies.categories(playlistId).first().first().movieCount)
    }

    @Test
    fun infoIsReadLenientlyAndCached() = runTest {
        val playlistId = addPlaylist()
        movies.loadCategories(playlistId)
        val thriller = movies.categories(playlistId).first().first()
        movies.loadMovies(thriller.id)
        val madden = moviesIn(thriller.id).first()

        val details = movies.info(madden)
        movies.info(madden)
        assertEquals(1, requests.count { it == "get_vod_info" })
        assertEquals("A plot.", details.plot)
        assertEquals(6300, details.durationSecs)
        assertEquals(7.4f, details.rating)
        assertEquals("1080p · H.264 · AAC 5.1", details.format)
        assertEquals("http://img.example/big.jpg", details.posterUrl)
        assertEquals(server.url("/movie/user/pass/22.mp4").toString(), movies.streamUrl(madden))
    }

    @Test
    fun aRefreshDropsTheMovieLists() = runTest {
        val playlistId = addPlaylist()
        movies.loadCategories(playlistId)
        movies.loadMovies(movies.categories(playlistId).first().first().id)

        PlaylistImporter(db, OkHttpClient()).import(playlistId)
        assertEquals(emptyList<Any>(), movies.categories(playlistId).first())
        assertNull(db.movieDao().movie(1))
    }

    @Test
    fun namesLoseTheLanguagePrefixAndYear() {
        assertEquals("Bandra" to 2023, cleanMovieName("IN| MALAYALAM| Bandra - 2023"))
        assertEquals("Moral Nights" to 2021, cleanMovieName("IN| MALAYALAM| Moral Nights (2021)"))
        assertEquals("Rolling Loud: The Movie [MULTI-SUB]" to 2026, cleanMovieName("Rolling Loud: The Movie - 2026 [MULTI-SUB]"))
        assertEquals("1917" to 2019, cleanMovieName("1917 (2019)"))
        assertEquals("Blade Runner 2049" to null, cleanMovieName("Blade Runner 2049"))
        assertEquals("Sui Dhaaga - Made in India" to null, cleanMovieName("BL| Sui Dhaaga - Made in India"))
    }
}
