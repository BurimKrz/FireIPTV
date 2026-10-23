package com.fireiptv.core.data.channel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.playlist.PlaylistImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchTest {

    private lateinit var server: MockWebServer
    private lateinit var db: PlayerDatabase
    private lateinit var repository: ChannelRepository
    private var playlistId = 0L

    private val names = listOf("UK| BBC 1 HD", "UK| BBC 2 HD", "UK| BBC NEWS", "AL| KLAN KOSOVË", "AL| RTK 1", "DE| Das Erste")

    @Before
    fun setUp() = runTest {
        server = MockWebServer().apply { start() }
        db = PlayerDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
        repository = ChannelRepository(db)
        playlistId = db.playlistDao().insert(PlaylistEntity(name = "Test", url = server.url("/list.m3u").toString()))
        val body = buildString {
            append("#EXTM3U\n")
            names.forEachIndexed { i, name -> append("#EXTINF:-1 group-title=\"G\",$name\nhttp://streams.example/$i.ts\n") }
        }
        server.enqueue(MockResponse().setBody(body))
        PlaylistImporter(db, OkHttpClient()).import(playlistId)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun search(text: String) = repository.search(playlistId, text).first().map { it.name }

    @Test
    fun wordsMatchAsPrefixesInAnyCase() = runTest {
        assertEquals(listOf("UK| BBC 1 HD", "UK| BBC 2 HD", "UK| BBC NEWS"), search("bb"))
        assertEquals(listOf("UK| BBC 1 HD"), search("bbc 1"))
        assertEquals(listOf("UK| BBC NEWS"), search("news bbc"))
    }

    @Test
    fun accentsDontMatter() = runTest {
        assertEquals(listOf("AL| KLAN KOSOVË"), search("kosove"))
        assertEquals(listOf("AL| KLAN KOSOVË"), search("Kosovë"))
    }

    @Test
    fun symbolsAreIgnoredAndBlankFindsNothing() = runTest {
        assertEquals(listOf("AL| RTK 1"), search("\"rtk\" | 1*"))
        assertEquals(emptyList<String>(), search("  |  "))
        assertEquals(emptyList<String>(), search("zdf"))
    }
}
