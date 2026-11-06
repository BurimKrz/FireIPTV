package com.fireiptv.core.data.channel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.db.RecentEntity
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

/** Favourites and recents across refreshes, and zapping through favourites. */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

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

    /** Channels named "Channel i" at `.../<urlPrefix>/i.ts`, in groups of ten. */
    private fun playlist(channels: Int, urlPrefix: String = "live"): String = buildString {
        append("#EXTM3U\n")
        repeat(channels) { i ->
            append("#EXTINF:-1 group-title=\"Group ${i / 10}\",Channel $i\n")
            append("http://streams.example/$urlPrefix/$i.ts\n")
        }
    }

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
    fun favouriteSurvivesARefreshWithTheSameUrls() = runTest {
        import(playlist(50))
        assertTrue(repository.toggleFavorite(channelNamed("Channel 7").id))

        import(playlist(60)) // refresh: every channel row is replaced

        val channel = channelNamed("Channel 7")
        assertTrue(repository.observeFavorite(channel).first())
        assertEquals(1, repository.favoriteCount(playlistId).first())
    }

    @Test
    fun favouriteFollowsItsChannelWhenTheUrlChanges() = runTest {
        import(playlist(50))
        repository.toggleFavorite(channelNamed("Channel 7").id)
        repository.recordWatched(channelNamed("Channel 8"))

        import(playlist(50, urlPrefix = "live2")) // provider moved every stream

        assertTrue(repository.observeFavorite(channelNamed("Channel 7")).first())
        assertEquals(1, repository.favoriteCount(playlistId).first())
        assertEquals(1, repository.recentCount(playlistId).first())
    }

    @Test
    fun favouriteOfARemovedChannelIsHiddenAndComesBack() = runTest {
        import(playlist(50))
        repository.toggleFavorite(channelNamed("Channel 40").id)

        import(playlist(30)) // channel 40 is gone
        assertEquals(0, repository.favoriteCount(playlistId).first())

        import(playlist(50)) // and back
        assertEquals(1, repository.favoriteCount(playlistId).first())
    }

    @Test
    fun zappingThroughFavouritesWrapsAround() = runTest {
        import(playlist(50))
        for (name in listOf("Channel 30", "Channel 5", "Channel 12")) {
            repository.toggleFavorite(channelNamed(name).id)
            Thread.sleep(2) // favourites list in the order added (millisecond timestamps)
        }

        val first = channelNamed("Channel 30")
        val second = repository.neighbour(first, ChannelList.Favorites, forward = true)!!
        val third = repository.neighbour(second, ChannelList.Favorites, forward = true)!!
        val wrapped = repository.neighbour(third, ChannelList.Favorites, forward = true)!!
        assertEquals(listOf("Channel 5", "Channel 12", "Channel 30"), listOf(second.name, third.name, wrapped.name))
        assertEquals("Channel 12", repository.neighbour(first, ChannelList.Favorites, forward = false)!!.name)
        assertEquals(2, repository.position(third, ChannelList.Favorites))
    }

    @Test
    fun recentsKeepTheNewest20() = runTest {
        import(playlist(50))
        for (i in 0 until 35) {
            repository.recordWatched(channelNamed("Channel $i"))
            Thread.sleep(2)
        }
        assertEquals(20, repository.recentCount(playlistId).first())
        assertEquals(0, repository.position(channelNamed("Channel 34"), ChannelList.Recents))
        assertEquals(null, repository.position(channelNamed("Channel 14"), ChannelList.Recents))
        assertFalse(repository.toggleFavorite(-1))
    }

    @Test
    fun longerListFromAnOlderVersionIsTrimmedTo20() = runTest {
        import(playlist(50))
        // Version 0.2 kept 30.
        for (i in 0 until 30) {
            val channel = channelNamed("Channel $i")
            db.libraryDao().upsertRecent(RecentEntity(playlistId, channel.streamUrl, channel.name, watchedAt = 1_000L + i))
        }

        repository.trimRecents(playlistId)

        assertEquals(20, repository.recentCount(playlistId).first())
        assertEquals(0, repository.position(channelNamed("Channel 29"), ChannelList.Recents))
        assertEquals(null, repository.position(channelNamed("Channel 9"), ChannelList.Recents))
    }

    @Test
    fun hiddenGroupLeavesAllChannelsAndStaysHiddenAfterRefresh() = runTest {
        import(playlist(50)) // groups of ten: "Group 0" .. "Group 4"
        val group1 = repository.groupSettings(playlistId).first().first { it.title == "Group 1" }
        repository.setGroupHidden(group1.id, true)

        assertEquals(40, repository.visibleCount(playlistId).first())
        // Zapping through all channels skips the hidden group: 9 is followed by 20.
        assertEquals("Channel 20", repository.neighbour(channelNamed("Channel 9"), ChannelList.All, forward = true)!!.name)
        assertEquals(10, repository.position(channelNamed("Channel 20"), ChannelList.All))

        import(playlist(60)) // refresh replaces every group row

        val hidden = repository.groupSettings(playlistId).first().filter { it.hidden }.map { it.title }
        assertEquals(listOf("Group 1"), hidden)
        assertEquals(50, repository.visibleCount(playlistId).first())
    }
}
