package com.fireiptv.core.data.playlist

import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.account.AccountRepository
import com.fireiptv.core.data.account.AccountResult
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XtreamImportTest {

    private lateinit var server: MockWebServer
    private lateinit var db: PlayerDatabase
    private lateinit var importer: PlaylistImporter

    private var account = """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]},"server_info":{"port":"80"}}"""

    // Real panels mix types: ids as numbers or strings, false/null for missing values.
    private var categories = """[
        {"category_id":"7","category_name":"Sports","parent_id":0},
        {"category_id":"3","category_name":"News","parent_id":0}
    ]"""
    private var streams = """[
        {"num":1,"name":"News One","stream_type":"live","stream_id":101,"stream_icon":"http://logos.example/1.png","epg_channel_id":null,"category_id":"3","tv_archive":0,"direct_source":""},
        {"num":2,"name":"Sport HD","stream_type":"live","stream_id":"202","stream_icon":"","epg_channel_id":"sport.hd","category_id":7,"category_ids":[7],"tv_archive":false},
        {"num":3,"name":"Mystery","stream_type":"live","stream_id":303,"category_id":"99"},
        {"num":4,"name":"A Film","stream_type":"movie","stream_id":404,"category_id":"3"}
    ]"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath != "/player_api.php") return MockResponse().setResponseCode(404)
                if (url.queryParameter("username") != "user" || url.queryParameter("password") != "pass") {
                    return MockResponse().setBody("""{"user_info":{"auth":0}}""")
                }
                return when (url.queryParameter("action")) {
                    null -> MockResponse().setBody(account)
                    "get_live_categories" -> MockResponse().setBody(categories)
                    "get_live_streams" -> MockResponse().setBody(streams)
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
        db = PlayerDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
        importer = PlaylistImporter(db, OkHttpClient())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun addPlaylist(user: String = "user", pass: String = "pass", output: String = "ts"): Long {
        val url = server.url("/get.php?username=$user&password=$pass&type=m3u_plus&output=$output").toString()
        return db.playlistDao().insert(PlaylistEntity(name = "Provider", url = url))
    }

    private suspend fun channels(playlistId: Long): List<ChannelEntity> {
        val page = db.channelDao().pageByPlaylist(playlistId)
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false))
        return (page as PagingSource.LoadResult.Page).data
    }

    @Test
    fun importsLiveChannelsThroughTheApi() = runTest {
        val id = addPlaylist()

        val outcome = importer.import(id)

        assertEquals(ImportOutcome.Imported(channels = 3, groups = 2, skippedLines = 0), outcome)
        val saved = channels(id)
        assertEquals(listOf("News One", "Sport HD", "Mystery"), saved.map { it.name })
        assertEquals(server.url("/live/user/pass/101.ts").toString(), saved[0].streamUrl)
        assertEquals(server.url("/live/user/pass/202.ts").toString(), saved[1].streamUrl)
        assertEquals("http://logos.example/1.png", saved[0].logoUrl)
        assertEquals(null, saved[1].logoUrl) // empty icon string
        assertEquals("sport.hd", saved[1].tvgId)
        assertEquals(null, saved[2].groupId) // category missing from the list

        // Groups follow the provider's category order, not the order channels arrive in.
        val groups = db.groupDao().observeGroups(id).first()
        assertEquals(listOf("Sports", "News"), groups.map { it.title })

        val paths = (1..server.requestCount).map { server.takeRequest().requestUrl!!.queryParameter("action") }
        assertEquals(listOf(null, "get_live_categories", "get_live_streams"), paths)
    }

    @Test
    fun usesHlsWhenTheLinkAsksForIt() = runTest {
        val id = addPlaylist(output = "m3u8")
        importer.import(id)
        assertTrue(channels(id)[0].streamUrl.endsWith("/live/user/pass/101.m3u8"))
    }

    @Test
    fun wrongLoginIsAClearError() = runTest {
        val id = addPlaylist(pass = "wrong")
        try {
            importer.import(id)
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertEquals("The provider rejected the username or password", e.message)
        }
        assertEquals(0, db.channelDao().count(id))
    }

    @Test
    fun expiredAccountIsAClearError() = runTest {
        account = """{"user_info":{"auth":1,"status":"Expired"}}"""
        val id = addPlaylist()
        try {
            importer.import(id)
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertEquals("The provider reports this account as Expired", e.message)
        }
    }

    @Test
    fun refreshReplacesTheChannels() = runTest {
        val id = addPlaylist()
        importer.import(id)
        importer.import(id)
        assertEquals(3, db.channelDao().count(id))
        assertEquals(1, db.playlistDao().observeSummaries().first().size)
    }

    @Test
    fun providerAccountInfoIsLeftOut() = runTest {
        categories = """[
            {"category_id":"1","category_name":"Accout Information"},
            {"category_id":"3","category_name":"News"}
        ]"""
        streams = """[
            {"name":"Account Information","stream_type":"live","stream_id":1,"category_id":"1"},
            {"name":"ACCOUNT INFO","stream_type":"live","stream_id":2,"category_id":"3"},
            {"num":3,"name":"News One","stream_type":"live","stream_id":101,"category_id":"3"}
        ]"""
        val id = addPlaylist()

        val outcome = importer.import(id)

        assertEquals(ImportOutcome.Imported(channels = 1, groups = 1, skippedLines = 0), outcome)
        assertEquals(listOf("News"), db.groupDao().observeGroups(id).first().map { it.title })
        // The provider's number is kept, so leaving channels out doesn't renumber the rest.
        assertEquals(3, db.channelDao().first(id, null)!!.number)
    }

    @Test
    fun accountDetailsAreRead() = runTest {
        account = """{"user_info":{"username":"user","auth":1,"status":"Active","exp_date":"1814918400","is_trial":"0",
            "active_cons":0,"max_connections":"1","created_at":"1729641600","allowed_output_formats":["ts"]}}"""
        addPlaylist()
        val repository = AccountRepository(
            db,
            PlaylistRepository(db, importer, CoroutineScope(Dispatchers.Unconfined)),
            OkHttpClient(),
        )

        val result = repository.load() as AccountResult.Loaded

        assertEquals("user", result.info.username)
        assertEquals("Active", result.info.status)
        assertEquals(1_814_918_400L, result.info.expiresAt)
        assertEquals(0, result.info.activeConnections)
        assertEquals(1, result.info.maxConnections)
        assertEquals(false, result.info.isTrial)
        assertEquals(1_729_641_600L, result.info.createdAt)
        // Saved, so home can warn about expiry without asking again.
        assertEquals(1_814_918_400L, db.playlistDao().first()!!.accountExpiresAt)
    }
}
