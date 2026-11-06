package com.fireiptv.core.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fireiptv.core.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayerManagerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var manager: PlayerManager

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // A long read timeout, so only the stall watchdog can explain a quick second request.
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            manager = PlayerManager(context, client, BufferSettings.FAST_START, AppLog(context))
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { manager.release() }
        server.shutdown()
    }

    @Test
    fun streamThatSendsNothingIsReopened() {
        // The provider answers 200 and then sends no bytes, as seen after some zaps.
        repeat(3) {
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody("x").setBodyDelay(60, TimeUnit.SECONDS))
        }
        instrumentation.runOnMainSync {
            manager.play(StreamRequest(url = server.url("/live/1.ts").toString(), label = "test"))
        }

        assertNotNull("first request", server.takeRequest(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertNotNull("stalled stream was not reopened", server.takeRequest(12, TimeUnit.SECONDS))
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("reopened after $waitedMs ms", waitedMs in 6_000..10_000)
    }
}
