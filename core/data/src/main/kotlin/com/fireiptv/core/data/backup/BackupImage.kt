package com.fireiptv.core.data.backup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * The backup file is a PNG: a small card that says what it is, with the backup's JSON in an iTXt
 * chunk. Android 11 lets a reinstalled app read images an earlier install left behind (with the
 * photos permission) but no other kind of file, so this is what lets a restore find it.
 */
internal object BackupImage {

    private const val KEYWORD = "FireIPTV-backup"
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** A PNG card reading [lines], carrying [json]. */
    fun encode(json: String, lines: List<String>): ByteArray {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(0x1C, 0x1B, 0x1F))
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
            }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0xC9, 0xC5, 0xD0)
                textSize = 24f
            }
            drawText("FireIPTV backup", 40f, 80f, title)
            lines.forEachIndexed { i, line -> drawText(line, 40f, 140f + i * 40f, body) }
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()

        // iTXt: keyword, 0, compression flag 0, method 0, empty language, 0, empty translation, 0, UTF-8 text.
        val data = KEYWORD.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0, 0) + json.toByteArray(Charsets.UTF_8)
        val chunk = chunk("iTXt", data)
        // The chunk goes just before IEND, the last 12 bytes.
        val iend = png.size - 12
        return png.copyOfRange(0, iend) + chunk + png.copyOfRange(iend, png.size)
    }

    /** The JSON a backup card carries, or null if [png] isn't one. */
    fun decode(png: ByteArray): String? {
        if (png.size < SIGNATURE.size || !png.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) return null
        val buffer = ByteBuffer.wrap(png)
        var at = SIGNATURE.size
        while (at + 12 <= png.size) {
            val length = buffer.getInt(at)
            if (length < 0 || at + 12 + length > png.size) return null
            val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "iTXt") {
                val data = png.copyOfRange(at + 8, at + 8 + length)
                val keywordEnd = data.indexOf(0)
                if (keywordEnd > 0 && String(data, 0, keywordEnd, Charsets.ISO_8859_1) == KEYWORD) {
                    // Skip compression flag and method, then the language tag and translated keyword.
                    var i = keywordEnd + 3
                    repeat(2) {
                        val end = data.indexOf(0, i)
                        if (end < 0) return null
                        i = end + 1
                    }
                    return String(data, i, data.size - i, Charsets.UTF_8)
                }
            }
            if (type == "IEND") return null
            at += 12 + length
        }
        return null
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        return ByteBuffer.allocate(12 + data.size)
            .putInt(data.size)
            .put(typeBytes)
            .put(data)
            .putInt(crc.value.toInt())
            .array()
    }

    private fun ByteArray.indexOf(value: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    private const val WIDTH = 960
    private const val HEIGHT = 300
}
