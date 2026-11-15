package com.fireiptv.core.data.backup

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupImageTest {

    @Test
    fun carriesTheJsonAndStaysAValidImage() {
        val json = """{"app":"FireIPTV","favorites":[{"name":"AL| KOSOVË TV","url":"http://x/live/u/p/1.ts"}],"note":"Первый"}"""
        val png = BackupImage.encode(json, listOf("Saved today"))
        assertEquals(json, BackupImage.decode(png))
        assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertNull(BackupImage.decode("not a png".toByteArray()))
        assertNull(BackupImage.decode(ByteArray(0)))
    }
}
