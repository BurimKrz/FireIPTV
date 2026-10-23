package com.fireiptv.core.data.playlist

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class PlaylistImporterTest {

    private lateinit var server: MockWebServer
    private lateinit var db: PlayerDatabase
    private lateinit var importer: PlaylistImporter

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        db = PlayerDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
        importer = PlaylistImporter(db, OkHttpClient())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun playlist(channels: Int, groups: Int = 40, prefix: String = "Channel"): String = buildString {
        append("#EXTM3U url-tvg=\"https://epg.example/guide.xml.gz\"\n")
        repeat(channels) { i ->
            append("#EXTINF:-1 tvg-id=\"ch$i\" tvg-logo=\"https://logos.example/$i.png\" group-title=\"Group ${i % groups}\",$prefix $i\n")
            append("http://streams.example/live/$i.ts\n")
        }
    }

    private suspend fun addPlaylist(): Long =
        db.playlistDao().insert(PlaylistEntity(name = "Test", url = server.url("/list.m3u").toString()))

    @Test
    fun firstImportOf50kChannels() = runTest(timeout = 2.minutes) {
        server.enqueue(MockResponse().setBody(playlist(50_000)).setHeader("ETag", "\"v1\""))
        val id = addPlaylist()

        var batches = 0
        val started = System.nanoTime()
        val outcome = importer.import(id) { batches++ }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.i("PlaylistImporterTest", "50k import took $elapsedMs ms")

        assertEquals(ImportOutcome.Imported(channels = 50_000, groups = 40, skippedLines = 0), outcome)
        assertEquals(50, batches)
        assertEquals(50_000, db.channelDao().count(id))

        val saved = db.playlistDao().get(id)!!
        assertEquals("https://epg.example/guide.xml.gz", saved.epgUrl)
        assertEquals("\"v1\"", saved.etag)
        assertTrue(saved.lastSync != null)

        val summary = db.playlistDao().observeSummaries().first().single()
        assertEquals(50_000, summary.channelCount)
        assertEquals(40, summary.groupCount)
    }

    @Test
    fun refreshSwapsInTheNewList() = runTest {
        server.enqueue(MockResponse().setBody(playlist(100, prefix = "Old")).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setBody(playlist(250, groups = 5, prefix = "New")).setHeader("ETag", "\"v2\""))
        val id = addPlaylist()

        importer.import(id)
        val outcome = importer.import(id)

        assertEquals(ImportOutcome.Imported(channels = 250, groups = 5, skippedLines = 0), outcome)
        server.takeRequest() // first import
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(250, db.channelDao().count(id))
        val summaries = db.playlistDao().observeSummaries().first()
        assertEquals(1, summaries.size) // staging row is gone
        assertEquals(5, summaries.single().groupCount)
    }

    @Test
    fun notModifiedKeepsTheList() = runTest {
        server.enqueue(MockResponse().setBody(playlist(10)).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        val id = addPlaylist()

        importer.import(id)
        assertEquals(ImportOutcome.NotModified, importer.import(id))
        assertEquals(10, db.channelDao().count(id))
    }

    @Test
    fun failedRefreshKeepsTheOldList() = runTest {
        server.enqueue(MockResponse().setBody(playlist(10)))
        server.enqueue(MockResponse().setBody("<html>Account expired</html>"))
        val id = addPlaylist()

        importer.import(id)
        try {
            importer.import(id)
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertTrue(e.message!!.startsWith("No channels found"))
        }
        assertEquals(10, db.channelDao().count(id))
        assertEquals(1, db.playlistDao().observeSummaries().first().size)
    }

    @Test
    fun failedFirstImportLeavesNoPartialChannels() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val id = addPlaylist()

        try {
            importer.import(id)
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertEquals("Server answered HTTP 404", e.message)
        }
        assertEquals(0, db.channelDao().count(id))
        assertNull(db.playlistDao().get(id)!!.lastSync)
    }

    @Test
    fun leavesOutXtreamMoviesAndSeries() = runTest {
        val body = buildString {
            append("#EXTM3U\n")
            append("#EXTINF:-1 group-title=\"News\",Live 1\nhttp://host.example/user/pass/101\n")
            append("#EXTINF:-1 group-title=\"News\",Live 2\nhttp://host.example/live/user/pass/102.ts\n")
            append("#EXTINF:-1 group-title=\"Movies\",A Film\nhttp://host.example/movie/user/pass/9001.mkv\n")
            append("#EXTINF:-1 group-title=\"Shows\",S01E01\nhttp://host.example/series/user/pass/7001.mp4\n")
        }
        server.enqueue(MockResponse().setBody(body))
        val id = addPlaylist()

        val outcome = importer.import(id)

        assertEquals(ImportOutcome.Imported(channels = 2, groups = 1, skippedLines = 0, skippedVod = 2), outcome)
        assertEquals(2, db.channelDao().count(id))
    }

    @Test
    fun readsGzippedPlaylistFiles() = runTest {
        val gzipped = Buffer()
        GzipSink(gzipped).buffer().use { it.writeUtf8(playlist(20)) }
        server.enqueue(MockResponse().setBody(gzipped))
        val id = addPlaylist()

        importer.import(id)
        assertEquals(20, db.channelDao().count(id))
    }
}
