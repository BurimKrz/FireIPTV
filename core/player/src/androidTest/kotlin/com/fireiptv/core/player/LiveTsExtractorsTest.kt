package com.fireiptv.core.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Runs a broadcast-style TS stream through Media3's real TS extractor, as the player does for
 * channels that aren't HLS, and checks what the video decoder would be given.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LiveTsExtractorsTest {

    @Test
    fun eachPictureOfAStreamWithAudsIsOneSample() {
        val samples = videoSamples(liveTsExtractors(), transportStream(PICTURES + listOf(AUD)))

        // One sample per picture, holding the AUD and headers in front of it: a header-only sample
        // would be an extra entry with the same timestamp as the picture after it.
        assertEquals(PICTURES.map { it.size }, samples.map { it.size })
        assertEquals(List(PICTURES.size) { it * FRAME_US }, samples.map { it.timeUs - samples.first().timeUs })
    }

    private data class Sample(val timeUs: Long, val size: Int)

    /** Extracts [stream] with whichever of [factory]'s extractors recognises it, like ProgressiveMediaPeriod. */
    private fun videoSamples(factory: ExtractorsFactory, stream: ByteArray): List<Sample> {
        val source = ByteArrayDataSource(stream)
        source.open(DataSpec(Uri.EMPTY))
        // Unknown length, like a live channel: the extractor reads straight through.
        val input = DefaultExtractorInput(source, 0, C.LENGTH_UNSET.toLong())
        val extractor = factory.createExtractors().first { candidate: Extractor ->
            candidate.sniff(input).also { input.resetPeekPosition() }
        }
        val samples = mutableListOf<Sample>()
        extractor.init(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = recordingTrack(if (type == C.TRACK_TYPE_VIDEO) samples else null)
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            },
        )
        val position = PositionHolder()
        while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) Unit
        return samples
    }

    /** Takes the track's bytes and records its samples in [samples], when given. */
    private fun recordingTrack(samples: MutableList<Sample>?): TrackOutput = object : TrackOutput {
        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            input.read(ByteArray(length), 0, length)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples?.add(Sample(timeUs, size))
        }
    }

    private companion object {
        const val FRAME_US = 40_000L
        const val VIDEO_PID = 0x100
        const val PMT_PID = 0x1000

        val AUD = annexB("09f0")

        /**
         * A 25 fps stream laid out like a broadcast: every picture starts with an AUD, the keyframe
         * also carries SPS and PPS. The SPS, PPS and slice headers are the first five pictures of a
         * public broadcaster's HLS segment (RTK 3); slices are cut after 16 bytes, past their header,
         * which is all the extractor reads.
         */
        val PICTURES = listOf(
            annexB("09f0", "6764001fac2ca50360f79bc04400000fa00003a98210", "68eb8f2c", "65b80200025f0c22bfce3bb9a9c0f65c"),
            annexB("09f0", "61e020d90a7f000011350b0b1cea3b19"),
            annexB("09f0", "01a810139049ff00000302cd35d7048f"),
            annexB("09f0", "01a810239045ff0000077fb05da6b3b2"),
            annexB("09f0", "61e04114429f000014310b0a878996f9"),
        )

        /** NAL units in hex, each after a 3-byte start code. */
        fun annexB(vararg nals: String): ByteArray =
            nals.fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 1) + nal.chunked(2).map { it.toInt(16).toByte() } }

        /** PAT, PMT with one H.264 stream, then one PES per picture, 25 per second. */
        fun transportStream(pictures: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(packets(0, byteArrayOf(0) + section(0x00, "0001" + "c10000" + "0001" + "f000"), firstCounter = 0))
            out.write(packets(PMT_PID, byteArrayOf(0) + section(0x02, "0001" + "c10000" + "e100" + "f000" + "1be100f000"), firstCounter = 0))
            var counter = 0
            pictures.forEachIndexed { index, picture ->
                val pes = pesHeader(900_000L + index * 3_600L) + picture
                out.write(packets(VIDEO_PID, pes, counter))
                counter += (pes.size + 183) / 184
            }
            return out.toByteArray()
        }

        /** A PSI section: table id, length, [body] in hex, CRC. */
        fun section(tableId: Int, body: String): ByteArray {
            val content = body.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val length = content.size + 4
            val head = byteArrayOf(tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte()) + content
            val crc = crc32Mpeg(head)
            return head + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        }

        /** PES header for video with only a PTS (90 kHz) and an unbounded length, as broadcasts send it. */
        fun pesHeader(pts: Long): ByteArray = byteArrayOf(
            0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21L or ((pts shr 29) and 0x0E)).toByte(),
            (pts shr 22).toByte(),
            (0x01L or ((pts shr 14) and 0xFE)).toByte(),
            (pts shr 7).toByte(),
            (0x01L or ((pts shl 1) and 0xFE)).toByte(),
        )

        /** [payload] in 188-byte TS packets on [pid]; the last one padded with adaptation-field stuffing. */
        fun packets(pid: Int, payload: ByteArray, firstCounter: Int): ByteArray {
            val out = ByteArrayOutputStream()
            payload.toList().chunked(184).forEachIndexed { index, chunk ->
                val start = if (index == 0) 0x40 else 0
                val counter = (firstCounter + index) and 0x0F
                val stuffing = 184 - chunk.size
                out.write(byteArrayOf(0x47, (start or (pid shr 8)).toByte(), pid.toByte()))
                if (stuffing == 0) {
                    out.write(0x10 or counter)
                } else {
                    out.write(0x30 or counter)
                    // Adaptation field: its length byte, then (if any room) a flags byte and 0xFF stuffing.
                    out.write(stuffing - 1)
                    if (stuffing > 1) out.write(byteArrayOf(0) + ByteArray(stuffing - 2) { 0xFF.toByte() })
                }
                out.write(chunk.toByteArray())
            }
            return out.toByteArray()
        }

        /** CRC-32/MPEG-2, which TS sections end with. */
        fun crc32Mpeg(bytes: ByteArray): Int = bytes.fold(-1) { crc, byte ->
            (0 until 8).fold(crc xor ((byte.toInt() and 0xFF) shl 24)) { c, _ -> if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
    }
}
