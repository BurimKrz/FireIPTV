package com.fireiptv.core.data.update

import android.util.JsonReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader

@RunWith(AndroidJUnit4::class)
class ReleaseJsonTest {

    private fun read(json: String) = JsonReader(StringReader(json)).use { readRelease(it) }

    @Test
    fun readsTagAndApkAsset() {
        // Trimmed from a real api.github.com/repos/{owner}/{repo}/releases/latest answer.
        val release = read(
            """
            {"url":"x","tag_name":"v0.2.0","name":"0.2.0","draft":false,"body":null,
             "author":{"login":"a","id":1},
             "assets":[
               {"name":"checksums.txt","size":90,"browser_download_url":"https://example.com/checksums.txt"},
               {"name":"FireIPTV-0.2.0.apk","size":3145728,"uploader":{"id":2},"browser_download_url":"https://example.com/FireIPTV-0.2.0.apk"}
             ]}
            """,
        )
        assertEquals(Release("0.2.0", "https://example.com/FireIPTV-0.2.0.apk", 3_145_728), release)
    }

    @Test
    fun releaseWithoutApkIsIgnored() {
        assertNull(read("""{"tag_name":"v0.2.0","assets":[]}"""))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewer("0.10.0", "0.9.3"))
        assertTrue(isNewer("1.0", "0.9.9"))
        assertFalse(isNewer("0.1.0", "0.1.0"))
        assertFalse(isNewer("0.1.0", "0.2.0"))
        assertFalse(isNewer("0.2.0-beta", "0.2.0"))
    }

    @Test
    fun betasCountUpAndComeBeforeTheirRelease() {
        assertTrue(isNewer("1.1.0-beta.2", "1.1.0-beta.1"))
        assertTrue(isNewer("1.1.0-beta.10", "1.1.0-beta.9"))
        assertTrue(isNewer("1.1.0", "1.1.0-beta.9"))
        assertTrue(isNewer("1.2.0-beta.1", "1.1.0"))
        assertFalse(isNewer("1.1.0-beta.1", "1.1.0"))
        assertFalse(isNewer("1.1.0-beta.1", "1.1.0-beta.1"))
    }

    @Test
    fun betaTakesTheNewestPreRelease() {
        // A /releases list, newest first: a stable release, then two betas, one without an APK yet.
        val beta = JsonReader(StringReader(
            """
            [
              {"tag_name":"v1.0.3","prerelease":false,"assets":[{"name":"FireIPTV-1.0.3.apk","size":1,"browser_download_url":"https://example.com/a.apk"}]},
              {"tag_name":"v1.1.0-beta.3","prerelease":true,"assets":[]},
              {"tag_name":"v1.1.0-beta.2","prerelease":true,"assets":[{"name":"FireIPTV-Beta-1.1.0-beta.2.apk","size":2,"browser_download_url":"https://example.com/b.apk"}]},
              {"tag_name":"v1.1.0-beta.1","prerelease":true,"assets":[{"name":"FireIPTV-Beta-1.1.0-beta.1.apk","size":3,"browser_download_url":"https://example.com/c.apk"}]}
            ]
            """,
        )).use { readNewestBeta(it) }
        assertEquals(Release("1.1.0-beta.2", "https://example.com/b.apk", 2), beta)
        assertNull(JsonReader(StringReader("[]")).use { readNewestBeta(it) })
    }
}
