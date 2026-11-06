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
}
