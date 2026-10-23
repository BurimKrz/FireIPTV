package com.fireiptv.core.parser.m3u

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M3uParserTest {

    private fun fixture(name: String): BufferedSource =
        requireNotNull(javaClass.getResourceAsStream("/m3u/$name")) { "missing fixture $name" }.source().buffer()

    private suspend fun parseAll(source: BufferedSource): Pair<M3uResult, List<M3uEntry>> {
        val entries = mutableListOf<M3uEntry>()
        val result = M3uParser.parse(source) { entries += it }
        return result to entries
    }

    @Test
    fun `parses header attributes`() = runTest {
        val (result, _) = parseAll(fixture("basic.m3u"))
        val header = requireNotNull(result.header)
        assertEquals(listOf("https://example.com/epg.xml.gz"), header.epgUrls)
        assertEquals("default", header.catchup)
        assertEquals(3, header.catchupDays)
        assertEquals(1f, header.tvgShift)
    }

    @Test
    fun `parses entries with attributes and modifier lines`() = runTest {
        val (result, entries) = parseAll(fixture("basic.m3u"))
        assertEquals(3, result.entries)
        assertEquals(0, result.skippedLines)

        val news = entries[0]
        assertEquals("News UK HD", news.name)
        assertEquals("https://example.com/live/news.m3u8", news.url)
        assertEquals("news.uk", news.tvgId)
        assertEquals("News UK", news.tvgName)
        assertEquals("https://example.com/news.png", news.tvgLogo)
        assertEquals(101, news.tvgChno)
        assertEquals("News", news.groupTitle)

        val sport = entries[1]
        assertEquals("shift", sport.catchup)
        assertEquals(7, sport.catchupDays)
        assertEquals("TestAgent/1.0", sport.userAgent)
        assertEquals("https://example.com/", sport.referrer)

        val docs = entries[2]
        assertEquals("Documentaries, Nature & History", docs.name) // commas in names survive
        assertEquals("Documentary", docs.groupTitle)
        assertEquals(mapOf("inputstream.adaptive.manifest_type" to "mpd"), docs.kodiProps)
    }

    @Test
    fun `tolerates junk without failing the import`() = runTest {
        val (result, entries) = parseAll(fixture("junk.m3u"))
        assertNull(result.header)
        assertEquals(listOf("Fallback Name", "Pipe Headers", "Early Option", "bare.ts"), entries.map { it.name })
        assertEquals(3, result.skippedLines) // "Orphan without URL", the HTML line, "Trailing orphan"

        val pipe = entries[1]
        assertEquals("http://b.example/2.ts", pipe.url)
        assertEquals("PipeAgent", pipe.userAgent)
        assertEquals("http://ref.example/", pipe.referrer)
        assertEquals("Movies", pipe.groupTitle) // unquoted attribute value

        assertEquals("EarlyAgent", entries[2].userAgent)
        assertTrue(entries.none { it.url.endsWith("\r") })
    }

    @Test
    fun `streams a 50k-channel playlist`() = runTest {
        val playlist = Buffer().writeUtf8("#EXTM3U\n")
        repeat(50_000) { i ->
            playlist.writeUtf8(
                "#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"Channel $i\" tvg-logo=\"https://logos.example/$i.png\" " +
                    "group-title=\"Group ${i % 40}\",Channel $i HD\nhttp://streams.example/live/$i.ts\n",
            )
        }

        var count = 0
        var last: M3uEntry? = null
        val started = System.nanoTime()
        val result = M3uParser.parse(playlist) { count++; last = it }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(50_000, result.entries)
        assertEquals(50_000, count)
        assertEquals("Group 39", last?.groupTitle)
        // Loose regression guard on a dev machine; the real 10 s budget is measured on the stick.
        assertTrue("50k parse took $elapsedMs ms", elapsedMs < 3_000)
    }

    @Test
    fun `stops when the coroutine is cancelled`() = runTest {
        val playlist = Buffer()
        repeat(10_000) { playlist.writeUtf8("#EXTINF:-1,Ch $it\nhttp://x.example/$it\n") }

        var seen = 0
        try {
            coroutineScope {
                M3uParser.parse(playlist) { if (++seen == 100) cancel() }
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            assertTrue("parser kept going after cancel: $seen entries", seen < 1_000)
        }
    }
}
