package com.fireiptv.core.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fireiptv.core.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MoviePlayerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var moviePlayer: MoviePlayer
    private val wav = silentWav(seconds = 20)
    private var refusals = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        // Serves byte ranges like a provider's file server; refuses the first [refusals] requests.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(this@MoviePlayerTest) {
                    if (refusals > 0) {
                        refusals--
                        return MockResponse().setResponseCode(403)
                    }
                }
                val start = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLong() ?: 0L
                val body = Buffer().write(wav.copyOfRange(start.toInt(), wav.size))
                return MockResponse()
                    .setResponseCode(if (start > 0) 206 else 200)
                    .setHeader("Content-Type", "audio/wav")
                    .setHeader("Accept-Ranges", "bytes")
                    .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${wav.size - 1}/${wav.size}") }
                    .setBody(body)
            }
        }
        server.start()
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            moviePlayer = MoviePlayer(context, OkHttpClient(), AppLog(context))
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { moviePlayer.release() }
        server.shutdown()
    }

    @Test
    fun aMovieRefusedAtFirstStillStartsAtItsResumePoint() {
        // One-connection accounts refuse for a few seconds after another stream closed.
        refusals = 1
        instrumentation.runOnMainSync {
            moviePlayer.play(StreamRequest(url = server.url("/movie/u/p/1.wav").toString(), label = "test"), startMs = 10_000)
        }

        // The player reads the file's header from the start, then jumps to the resume point.
        var rangeStart: Long? = null
        while (rangeStart == null) {
            val request = server.takeRequest(10, TimeUnit.SECONDS) ?: break
            rangeStart = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLong()?.takeIf { it > 0 }
        }
        // 44-byte header, then 8 kHz 16-bit mono: 16,000 bytes a second.
        assertEquals("asked for the file from 10 s", 44L + 10 * 16_000, rangeStart)

        var status: MovieStatus = MovieStatus.Idle
        var position = 0L
        val deadline = System.currentTimeMillis() + 5_000
        while (status != MovieStatus.Playing && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            instrumentation.runOnMainSync {
                status = moviePlayer.status.value
                position = moviePlayer.positionMs
            }
        }
        assertEquals(MovieStatus.Playing, status)
        assertTrue("plays from 10 s, at $position ms", position >= 10_000)
    }

    private fun silentWav(seconds: Int): ByteArray {
        val rate = 8_000
        val dataSize = rate * 2 * seconds
        return Buffer()
            .writeUtf8("RIFF").writeIntLe(36 + dataSize).writeUtf8("WAVE")
            .writeUtf8("fmt ").writeIntLe(16).writeShortLe(1).writeShortLe(1)
            .writeIntLe(rate).writeIntLe(rate * 2).writeShortLe(2).writeShortLe(16)
            .writeUtf8("data").writeIntLe(dataSize).write(ByteArray(dataSize))
            .readByteArray()
    }
}
