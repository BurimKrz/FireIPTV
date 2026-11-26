package com.fireiptv.core.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * Extractors for channels that aren't HLS, i.e. raw MPEG-TS over HTTP.
 *
 * Many IPTV TS streams start without an IDR frame: other keyframes are accepted so video starts
 * sooner. Streams without access unit delimiters (AUDs) are cut into pictures by their slice headers.
 * That same detection splits streams *with* AUDs (most broadcasts) into two samples per picture, see
 * [H264HeaderJoiningTrackOutput], so H.264 video in TS goes through it.
 */
@OptIn(UnstableApi::class)
internal fun liveTsExtractors(): ExtractorsFactory {
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )
    return object : ForwardingExtractorsFactory(extractors) {
        override fun createExtractors(): Array<Extractor> = super.createExtractors().map(::joiningH264Headers).toTypedArray()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            super.createExtractors(uri, responseHeaders).map(::joiningH264Headers).toTypedArray()
    }
}

@OptIn(UnstableApi::class)
private fun joiningH264Headers(extractor: Extractor): Extractor =
    if (extractor is TsExtractor) H264HeaderJoiningExtractor(extractor) else extractor

/** A TS extractor whose video tracks go through [H264HeaderJoiningTrackOutput]. */
@OptIn(UnstableApi::class)
private class H264HeaderJoiningExtractor(extractor: TsExtractor) : ForwardingExtractor(extractor) {
    private val videoOutputs = mutableMapOf<Int, H264HeaderJoiningTrackOutput>()

    override fun init(output: ExtractorOutput) {
        videoOutputs.clear()
        super.init(
            object : ForwardingExtractorOutput(output) {
                override fun track(id: Int, type: Int): TrackOutput {
                    val track = super.track(id, type)
                    if (type != C.TRACK_TYPE_VIDEO) return track
                    // The same id gets the same sample queue, so it must get the same byte count too.
                    return videoOutputs.getOrPut(id) { H264HeaderJoiningTrackOutput(track) }
                }
            },
        )
    }

    override fun seek(position: Long, timeUs: Long) {
        // The sample queues restart; anything held back belongs to data they drop.
        videoOutputs.values.forEach { it.reset() }
        super.seek(position, timeUs)
    }
}

/**
 * With FLAG_DETECT_ACCESS_UNITS, Media3's H.264 reader starts a sample at each AUD and again at the
 * first slice of each picture, so a broadcast's `AUD [SPS PPS SEI] picture` becomes a sample with
 * only the headers, then the picture, both with the same timestamp. Some hardware decoders lose
 * pictures on that: a 25 fps channel played at about 21 fps, and stuttered, on a Sony Android TV.
 *
 * This holds back each sample that has no picture slice and gives its bytes and flags to the
 * picture after it, so the decoder gets one sample per picture, as the stream intended.
 * Only sample boundaries move: every byte reaches the decoder, in the same order.
 */
@OptIn(UnstableApi::class)
private class H264HeaderJoiningTrackOutput(track: TrackOutput) : ForwardingTrackOutput(track) {

    /** A header-only sample not passed on yet: the byte range [start, end) of everything written. */
    private data class Held(val timeUs: Long, val flags: Int, val start: Long, val end: Long)

    /** Position (in bytes written) and type of each NAL unit not yet assigned to a sample. */
    private data class Nal(val position: Long, val type: Int)

    private var isH264 = false
    private var written = 0L
    private val nals = ArrayDeque<Nal>()
    private val prefixFlags = BooleanArray(3)
    private var held: Held? = null

    fun reset() {
        written = 0L
        nals.clear()
        prefixFlags.fill(false)
        held = null
    }

    override fun format(format: Format) {
        isH264 = format.sampleMimeType == MimeTypes.VIDEO_H264
        super.format(format)
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        check(sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            "TS video carried sample data part $sampleDataPart; only clear video (part 0) is expected"
        }
        // Scanned before the format is known too: the first SPS and PPS arrive before it.
        findNals(data.data, data.position, data.position + length)
        super.sampleData(data, length, sampleDataPart)
        written += length
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        error("TS video arrived through a DataReader: its NAL units can't be seen, so samples can't be joined")

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val end = written - offset
        val start = end - size
        var hasNal = false
        var hasPicture = false
        while (nals.isNotEmpty() && nals.first().position < end) {
            val nal = nals.removeFirst()
            if (nal.position < start) continue
            hasNal = true
            if (nal.type in PICTURE_SLICE_TYPES) hasPicture = true
        }
        // The held header sample, when this sample starts right where it ends.
        val joined = held?.takeIf { it.end == start }
        when {
            !isH264 || cryptoData != null -> {
                passHeld()
                super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            }
            hasNal && !hasPicture && joined != null -> held = joined.copy(flags = joined.flags or flags, end = end)
            hasNal && !hasPicture -> {
                passHeld()
                held = Held(timeUs, flags, start, end)
            }
            joined != null -> {
                held = null
                super.sampleMetadata(timeUs, flags or joined.flags, (end - joined.start).toInt(), offset, null)
            }
            else -> {
                passHeld()
                super.sampleMetadata(timeUs, flags, size, offset, null)
            }
        }
    }

    /** Passes a held sample on unchanged, when the next sample doesn't directly follow it. */
    private fun passHeld() {
        val sample = held ?: return
        held = null
        super.sampleMetadata(sample.timeUs, sample.flags, (sample.end - sample.start).toInt(), (written - sample.end).toInt(), null)
    }

    /** Records the NAL units that start in data[start, end), the next bytes after [written]. */
    private fun findNals(data: ByteArray, start: Int, end: Int) {
        var offset = start
        while (offset < end) {
            // A start code split over two writes is reported before [start]; its type byte is always in this write.
            val prefix = NalUnitUtil.findNalUnit(data, offset, end, prefixFlags)
            if (prefix == end) return
            val typeIndex = prefix + 3
            check(typeIndex in start until end) { "NAL type byte at $typeIndex is outside the written bytes [$start, $end)" }
            nals.addLast(Nal(written + typeIndex - start, data[typeIndex].toInt() and 0x1F))
            offset = typeIndex + 1
        }
    }

    private companion object {
        /** H.264 NAL unit types 1–5: coded slices, i.e. the picture itself. */
        val PICTURE_SLICE_TYPES = 1..5
    }
}
