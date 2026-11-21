package com.fireiptv.feature.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * A language tag worth remembering: null for untagged tracks, which most providers' files are
 * ("und"), and for "several languages".
 */
internal fun trackLanguage(format: Format): String? =
    format.language?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED || it == "mul" || it == "zxx" }

/** "English (5.1, Dolby Digital Plus)", or "Track 2 (Stereo, AAC)" when the file has no language. */
internal fun audioLabel(format: Format, number: Int): String {
    val channels = when (format.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        Format.NO_VALUE -> null
        else -> "${format.channelCount} channels"
    }
    val name = trackName(format, number)
    // Files often name a track by its channels ("Stereo"); don't say it twice.
    val details = listOfNotNull(channels, codecName(format.sampleMimeType)).filterNot { it.equals(name, ignoreCase = true) }
    return if (details.isEmpty()) name else "$name (${details.joinToString(", ")})"
}

/** "Albanian", "English (forced)", or "Subtitles 2" when the file has no language. */
internal fun subtitleLabel(format: Format, number: Int): String {
    val name = format.label ?: trackLanguage(format)?.let(::languageName) ?: "Subtitles $number"
    return if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "$name (forced)" else name
}

private fun trackName(format: Format, number: Int): String =
    format.label ?: trackLanguage(format)?.let(::languageName) ?: "Track $number"

/** "sq" → "Albanian"; a code Android doesn't know stays as it is. */
private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

/** "DTS", "Dolby Digital Plus"…, or null for a format without a common name. */
internal fun codecName(mimeType: String?): String? = when (mimeType) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    else -> null
}
