package com.fireiptv.core.data.channel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.db.ChannelEntity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Hidden channels, reordering favourites and categories, and a channel's other versions. */
@RunWith(AndroidJUnit4::class)
class HiddenAndOrderTest {

    private lateinit var server: MockWebServer
    private lateinit var db: PlayerDatabase
    private lateinit var importer: PlaylistImporter
    private lateinit var repository: ChannelRepository
    private var playlistId = 0L

    @Before
    fun setUp() = runTest {
        server = MockWebServer().apply { start() }
        db = PlayerDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
        importer = PlaylistImporter(db, OkHttpClient())
        repository = ChannelRepository(db)
        playlistId = db.playlistDao().insert(PlaylistEntity(name = "Test", url = server.url("/list.m3u").toString()))
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun playlist(names: List<String>, urlPrefix: String = "live"): String = buildString {
        append("#EXTM3U\n")
        names.forEachIndexed { i, name ->
            append("#EXTINF:-1 group-title=\"Group ${i / 10}\",$name\n")
            append("http://streams.example/$urlPrefix/$i.ts\n")
        }
    }

    private fun numbered(count: Int) = List(count) { "Channel $it" }

    private suspend fun import(body: String) {
        server.enqueue(MockResponse().setBody(body))
        importer.import(playlistId)
    }

    private suspend fun channelNamed(name: String): ChannelEntity {
        val id = db.openHelper.readableDatabase.query("SELECT id FROM channel WHERE name = ?", arrayOf(name)).use { cursor ->
            check(cursor.moveToFirst()) { "no channel named $name" }
            cursor.getLong(0)
        }
        return db.channelDao().get(id)!!
    }

    @Test
    fun hiddenChannelLeavesListsAndZappingButNotSearch() = runTest {
        import(playlist(numbered(20)))
        val five = channelNamed("Channel 5")
        repository.toggleFavorite(five.id)
        assertTrue(repository.toggleHidden(five.id))

        assertEquals(19, repository.visibleCount(playlistId).first())
        assertEquals(0, repository.favoriteCount(playlistId).first())
        assertEquals(9, repository.groups(playlistId).first().first().channelCount)
        // Zapping from 4 skips 5, in all channels and in its group.
        val four = channelNamed("Channel 4")
        assertEquals("Channel 6", repository.neighbour(four, ChannelList.All, forward = true)?.name)
        assertEquals("Channel 6", repository.neighbour(four, ChannelList.Group(four.groupId!!), forward = true)?.name)
        assertEquals("Channel 4", repository.neighbour(channelNamed("Channel 6"), ChannelList.All, forward = false)?.name)

        val found = repository.search(playlistId, "channel 5").first().single { it.name == "Channel 5" }
        assertTrue(found.hidden)
        assertFalse(repository.search(playlistId, "channel 6").first().single { it.name == "Channel 6" }.hidden)

        assertFalse(repository.toggleHidden(five.id))
        assertEquals(20, repository.visibleCount(playlistId).first())
        assertEquals(1, repository.favoriteCount(playlistId).first())
    }

    @Test
    fun hiddenChannelStaysHiddenAfterARefresh() = runTest {
        import(playlist(numbered(20)))
        repository.toggleHidden(channelNamed("Channel 3").id)
        repository.toggleHidden(channelNamed("Channel 7").id)

        import(playlist(numbered(25))) // same URLs, every row replaced
        assertEquals(23, repository.visibleCount(playlistId).first())

        // New URLs (a new login): found again by name.
        import(playlist(numbered(25), urlPrefix = "new"))
        assertEquals(23, repository.visibleCount(playlistId).first())
        assertEquals(listOf("Channel 7", "Channel 3"), repository.hiddenChannels(playlistId).first().map { it.name })
        assertTrue(repository.hiddenChannels(playlistId).first().all { it.groupTitle == "Group 0" })

        repository.showAllChannels(playlistId)
        assertEquals(25, repository.visibleCount(playlistId).first())
    }

    @Test
    fun favouritesAndCategoriesMoveUpAndDown() = runTest {
        import(playlist(numbered(10)))
        listOf(1, 2, 3, 4).forEach { repository.toggleFavorite(channelNamed("Channel $it").id) }
        fun order() = db.libraryDao().let { dao -> kotlinx.coroutines.runBlocking { dao.favorites(playlistId).map { it.name } } }

        assertTrue(repository.move(channelNamed("Channel 3").id, ChannelList.Favorites, up = true))
        assertEquals(listOf("Channel 1", "Channel 3", "Channel 2", "Channel 4"), order())
        assertFalse(repository.move(channelNamed("Channel 1").id, ChannelList.Favorites, up = true))
        // A hidden favourite isn't shown, so moving steps over it.
        repository.toggleHidden(channelNamed("Channel 2").id)
        assertTrue(repository.move(channelNamed("Channel 3").id, ChannelList.Favorites, up = false))
        assertEquals(listOf("Channel 1", "Channel 4", "Channel 2", "Channel 3"), order())
        assertFalse(repository.move(channelNamed("Channel 3").id, ChannelList.Favorites, up = false))
        assertFalse(repository.move(channelNamed("Channel 3").id, ChannelList.All, up = true))

        val mine = repository.createList(playlistId, "Mine")
        listOf(5, 6, 7).forEach { repository.toggleInList(mine, channelNamed("Channel $it").id) }
        assertTrue(repository.move(channelNamed("Channel 7").id, ChannelList.Custom(mine), up = true))
        val list = ChannelList.Custom(mine)
        assertEquals(1, repository.position(channelNamed("Channel 7"), list))
        assertEquals(2, repository.position(channelNamed("Channel 6"), list))
        assertEquals("Channel 6", repository.neighbour(channelNamed("Channel 7"), list, forward = true)?.name)
    }

    @Test
    fun otherVersionsMatchTheNameWithoutQualityMarks() = runTest {
        import(
            playlist(
                listOf(
                    "AL| TOP CHANNEL FHD", "AL| TOP CHANNEL HD", "AL| Top Channel SD [BK]", "AL| TOP CHANNEL +1",
                    "AL| TOP NEWS HD", "AL| KLAN HD", "UK| TOP CHANNEL HD",
                ),
            ),
        )
        val fhd = channelNamed("AL| TOP CHANNEL FHD")
        assertEquals(listOf("AL| TOP CHANNEL HD", "AL| Top Channel SD [BK]"), repository.otherVersions(fhd).map { it.name })
        repository.toggleHidden(channelNamed("AL| TOP CHANNEL HD").id)
        assertEquals(listOf("AL| Top Channel SD [BK]"), repository.otherVersions(fhd).map { it.name })
        assertEquals(emptyList<String>(), repository.otherVersions(channelNamed("AL| KLAN HD")).map { it.name })
    }

    @Test
    fun versionKeys() {
        assertEquals("al top channel", versionKey("AL| TOP CHANNEL HD [BK]"))
        assertEquals("al top channel", versionKey("AL: Top Channel (FHD) 1080p"))
        assertEquals("uk bbc one 1", versionKey("UK| BBC ONE +1 HD"))
        assertEquals("", versionKey("HD"))
    }
}
