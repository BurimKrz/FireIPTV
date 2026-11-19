package com.fireiptv.core.data.playlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XtreamLoginTest {

    @Test
    fun loginBecomesAPlayerApiLink() {
        val expected = "http://provider.example:8080/player_api.php?username=me&password=p%26ss"
        assertEquals(expected, xtreamPlaylistUrl("provider.example:8080", "me", "p&ss"))
        assertEquals(expected, xtreamPlaylistUrl(" http://provider.example:8080/ ", "me", "p&ss"))
        // A whole get.php link pasted as the server keeps only its server part.
        assertEquals(expected, xtreamPlaylistUrl("http://provider.example:8080/get.php?username=x&password=y&type=m3u_plus", "me", "p&ss"))
        assertEquals("https://p.example/iptv/player_api.php?username=a&password=b", xtreamPlaylistUrl("https://p.example/iptv", "a", "b"))
        assertNull(xtreamPlaylistUrl("", "a", "b"))
        assertNull(xtreamPlaylistUrl("http://", "a", "b"))
        assertNull(xtreamPlaylistUrl("p.example", "", "b"))
    }

    @Test
    fun linksGiveTheirLoginBack() {
        assertEquals(
            XtreamLogin("http://provider.example:8080", "me", "p&ss"),
            parseXtreamLogin("http://provider.example:8080/get.php?username=me&password=p%26ss&type=m3u_plus&output=ts"),
        )
        val url = xtreamPlaylistUrl("https://p.example/iptv", "a", "b")!!
        assertEquals(XtreamLogin("https://p.example/iptv", "a", "b"), parseXtreamLogin(url))
        assertNull(parseXtreamLogin("https://iptv-org.github.io/iptv/index.m3u"))
        assertNull(parseXtreamLogin("http://p.example/get.php?username=a"))
    }
}
