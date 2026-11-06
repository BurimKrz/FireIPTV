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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The user's own categories, and pinned groups, across refreshes. */
@RunWith(AndroidJUnit4::class)
class UserListTest {

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
    fun categoryKeepsItsChannelsAcrossARefresh() = runTest {
        import(playlist(30))
        val sport = repository.createList(playlistId, "Sport")
        assertTrue(repository.toggleInList(sport, channelNamed("Channel 4").id))
        assertTrue(repository.toggleInList(sport, channelNamed("Channel 21").id))

        import(playlist(40)) // every channel row is replaced

        val lists = repository.userLists(playlistId).first()
        assertEquals(listOf("Sport"), lists.map { it.title })
        assertEquals(2, lists.single().channelCount)
        assertEquals(listOf(sport), repository.listsWith(channelNamed("Channel 21")).first())
    }

    @Test
    fun categoryFollowsAChannelWhoseUrlChanged() = runTest {
        import(playlist(10))
        val list = repository.createList(playlistId, "Mine")
        repository.toggleInList(list, channelNamed("Channel 3").id)

        import(playlist(10, urlPrefix = "moved")) // same names, new URLs

        assertEquals(listOf(list), repository.listsWith(channelNamed("Channel 3")).first())
    }

    @Test
    fun zappingACategoryGoesInTheOrderChannelsWereAddedAndWraps() = runTest {
        import(playlist(30))
        val list = repository.createList(playlistId, "Mine")
        val order = listOf("Channel 20", "Channel 2", "Channel 11").map { channelNamed(it) }
        order.forEach {
            repository.toggleInList(list, it.id)
            Thread.sleep(2) // added-at order needs distinct times
        }
        val custom = ChannelList.Custom(list)

        assertEquals("Channel 2", repository.neighbour(order[0], custom, forward = true)?.name)
        assertEquals("Channel 20", repository.neighbour(order[2], custom, forward = true)?.name)
        assertEquals("Channel 11", repository.neighbour(order[0], custom, forward = false)?.name)
        assertEquals(1, repository.position(order[1], custom))
    }

    @Test
    fun secondToggleTakesTheChannelOutAndDeleteRemovesTheCategory() = runTest {
        import(playlist(10))
        val list = repository.createList(playlistId, "Mine")
        val channel = channelNamed("Channel 1")
        repository.toggleInList(list, channel.id)

        assertFalse(repository.toggleInList(list, channel.id))
        assertEquals(0, repository.userLists(playlistId).first().single().channelCount)

        repository.deleteList(list)
        assertTrue(repository.userLists(playlistId).first().isEmpty())
    }

    @Test
    fun pinnedGroupStaysPinnedAfterARefresh() = runTest {
        import(playlist(30))
        val group = repository.groups(playlistId).first().first { it.title == "Group 2" }
        repository.setPinned(ChannelList.Group(group.id), pinned = true)

        import(playlist(30)) // a refresh recreates every group

        val groups = repository.groups(playlistId).first()
        assertNotNull(groups.first { it.title == "Group 2" }.pinnedAt)
        assertNull(groups.first { it.title == "Group 1" }.pinnedAt)
    }
}
