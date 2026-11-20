package com.fireiptv.core.data.playlist.xtream

import android.util.JsonReader
import android.util.JsonToken

internal data class XtreamUserInfo(
    val authenticated: Boolean,
    val status: String?,
    val formats: List<String>,
    val username: String? = null,
    /** Expiry as epoch seconds; null for accounts that don't expire. */
    val expiresAt: Long? = null,
    val isTrial: Boolean = false,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    val createdAt: Long? = null,
)

internal data class XtreamCategory(val id: String, val name: String)

internal data class XtreamLiveStream(
    val streamId: String,
    /** The provider's channel number, the same in every app; stays put when we leave channels out. */
    val number: Int?,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val type: String?,
)

internal data class XtreamMovie(
    val streamId: String,
    val name: String,
    val icon: String?,
    /** Out of 10, as the provider sends it. */
    val rating: Float?,
    /** Epoch seconds. */
    val added: Long?,
    val extension: String?,
)

/** What `get_vod_info` says about one movie; every field may be missing. */
data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val country: String?,
    val durationSecs: Int?,
    val rating: Float?,
    val posterUrl: String?,
    /** For example "1080p · H.264 · AAC 5.1", from the file's own details when the provider has them. */
    val format: String?,
)

/**
 * Readers for the few Xtream API fields we use. Panels disagree on types (ids as numbers or
 * strings, `false` for missing values), so every scalar is read leniently and unknown keys skipped.
 */
internal object XtreamJson {

    fun readUserInfo(reader: JsonReader): XtreamUserInfo {
        var info = XtreamUserInfo(authenticated = false, status = null, formats = emptyList())
        reader.readObject { key ->
            if (key == "user_info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                var auth = false
                var status: String? = null
                var formats = emptyList<String>()
                var username: String? = null
                var expires: String? = null
                var trial: String? = null
                var active: String? = null
                var max: String? = null
                var created: String? = null
                reader.readObject { field ->
                    when (field) {
                        "auth" -> auth = reader.nextScalar() == "1"
                        "status" -> status = reader.nextScalar()
                        "allowed_output_formats" -> formats = reader.readStrings()
                        "username" -> username = reader.nextScalar()
                        "exp_date" -> expires = reader.nextScalar()
                        "is_trial" -> trial = reader.nextScalar()
                        "active_cons" -> active = reader.nextScalar()
                        "max_connections" -> max = reader.nextScalar()
                        "created_at" -> created = reader.nextScalar()
                        else -> reader.skipValue()
                    }
                }
                info = XtreamUserInfo(
                    authenticated = auth,
                    status = status,
                    formats = formats,
                    username = username,
                    expiresAt = expires?.toLongOrNull()?.takeIf { it > 0 },
                    isTrial = trial == "1",
                    activeConnections = active?.toIntOrNull(),
                    maxConnections = max?.toIntOrNull(),
                    createdAt = created?.toLongOrNull()?.takeIf { it > 0 },
                )
            } else {
                reader.skipValue()
            }
        }
        return info
    }

    fun readCategories(reader: JsonReader): List<XtreamCategory> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val categories = ArrayList<XtreamCategory>()
        reader.beginArray()
        while (reader.hasNext()) {
            var id: String? = null
            var name: String? = null
            reader.readObject { key ->
                when (key) {
                    "category_id" -> id = reader.nextScalar()
                    "category_name" -> name = reader.nextScalar()
                    else -> reader.skipValue()
                }
            }
            if (id != null) categories += XtreamCategory(id!!, name?.trim().orEmpty().ifEmpty { "Other" })
        }
        reader.endArray()
        return categories
    }

    /** Streams the live list one object at a time; 28k channels is ~8 MB of JSON. */
    suspend fun readLiveStreams(reader: JsonReader, onStream: suspend (XtreamLiveStream) -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            var streamId: String? = null
            var number: String? = null
            var name: String? = null
            var icon: String? = null
            var epg: String? = null
            var category: String? = null
            var type: String? = null
            reader.readObject { key ->
                when (key) {
                    "stream_id" -> streamId = reader.nextScalar()
                    "num" -> number = reader.nextScalar()
                    "name" -> name = reader.nextScalar()
                    "stream_icon" -> icon = reader.nextScalar()
                    "epg_channel_id" -> epg = reader.nextScalar()
                    "category_id" -> category = reader.nextScalar()
                    "stream_type" -> type = reader.nextScalar()
                    else -> reader.skipValue()
                }
            }
            val id = streamId ?: continue
            onStream(XtreamLiveStream(id, number?.toIntOrNull()?.takeIf { it > 0 }, name?.trim().orEmpty(), icon, epg, category, type))
        }
        reader.endArray()
    }

    /** Streams a `get_vod_streams` list one object at a time; a big category is ~8k movies. */
    suspend fun readMovies(reader: JsonReader, onMovie: suspend (XtreamMovie) -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            var streamId: String? = null
            var name: String? = null
            var icon: String? = null
            var rating: String? = null
            var added: String? = null
            var extension: String? = null
            reader.readObject { key ->
                when (key) {
                    "stream_id" -> streamId = reader.nextScalar()
                    "name" -> name = reader.nextScalar()
                    "stream_icon" -> icon = reader.nextScalar()
                    "rating" -> rating = reader.nextScalar()
                    "added" -> added = reader.nextScalar()
                    "container_extension" -> extension = reader.nextScalar()
                    else -> reader.skipValue()
                }
            }
            val id = streamId ?: continue
            onMovie(
                XtreamMovie(
                    streamId = id,
                    name = name?.trim().orEmpty(),
                    icon = icon,
                    rating = rating?.toFloatOrNull()?.takeIf { it > 0f && it <= 10f },
                    added = added?.toLongOrNull()?.takeIf { it > 0 },
                    extension = extension?.lowercase(),
                ),
            )
        }
        reader.endArray()
    }

    /** `get_vod_info`: the `info` object; the rest (`movie_data`) repeats what the list had. */
    fun readMovieInfo(reader: JsonReader): MovieInfo {
        var info = MovieInfo(null, null, null, null, null, null, null, null, null, null)
        reader.readObject { key ->
            if (key != "info") {
                reader.skipValue()
                return@readObject
            }
            val text = HashMap<String, String>()
            var video: Map<String, String> = emptyMap()
            var audio: Map<String, String> = emptyMap()
            reader.readObject { field ->
                when (field) {
                    "video" -> video = reader.readFlatObject()
                    "audio" -> audio = reader.readFlatObject()
                    else -> reader.nextScalar()?.let { text[field] = it.trim() }
                }
            }
            fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> text[k]?.takeIf { it.isNotBlank() } }
            info = MovieInfo(
                plot = first("plot", "description"),
                genre = first("genre"),
                director = first("director"),
                cast = first("cast", "actors"),
                releaseDate = first("releasedate", "release_date"),
                country = first("country"),
                durationSecs = first("duration_secs")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: first("duration")?.let(::parseDuration),
                rating = first("rating")?.toFloatOrNull()?.takeIf { it > 0f && it <= 10f },
                posterUrl = first("cover_big", "movie_image"),
                format = formatOf(video, audio),
            )
        }
        return info
    }

    /** "01:45:00" → 6300. */
    private fun parseDuration(text: String): Int? {
        val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    private fun formatOf(video: Map<String, String>, audio: Map<String, String>): String? {
        val height = video["height"]?.toIntOrNull()
        val resolution = when {
            height == null -> null
            height >= 2000 -> "4K"
            height >= 1000 || (video["width"]?.toIntOrNull() ?: 0) >= 1900 -> "1080p"
            height >= 680 || (video["width"]?.toIntOrNull() ?: 0) >= 1260 -> "720p"
            else -> "SD"
        }
        // An attached cover picture is listed as the video stream on some files: say nothing then.
        val videoCodec = video["codec_name"]?.takeUnless { it in setOf("png", "mjpeg", "bmp") }?.let { codecName(it) }
        val channels = when (audio["channels"]) {
            "1" -> "mono"
            "2" -> "stereo"
            "6" -> "5.1"
            "8" -> "7.1"
            else -> null
        }
        val audioText = audio["codec_name"]?.let { listOfNotNull(codecName(it), channels).joinToString(" ") }
        return listOfNotNull(resolution.takeIf { videoCodec != null }, videoCodec, audioText).joinToString(" · ").ifEmpty { null }
    }

    private fun codecName(codec: String): String = when (codec.lowercase()) {
        "h264" -> "H.264"
        "hevc", "h265" -> "HEVC"
        "mpeg4" -> "MPEG-4"
        "aac" -> "AAC"
        "ac3" -> "Dolby Digital"
        "eac3" -> "Dolby Digital Plus"
        "mp3" -> "MP3"
        "dts" -> "DTS"
        "truehd" -> "Dolby TrueHD"
        else -> codec.uppercase()
    }

    /** An object's scalar fields; nested values (like `tags`) are skipped. */
    private fun JsonReader.readFlatObject(): Map<String, String> {
        val values = HashMap<String, String>()
        readObject { key -> nextScalar()?.let { values[key] = it } }
        return values
    }

    private inline fun JsonReader.readObject(onKey: (String) -> Unit) {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            return
        }
        beginObject()
        while (hasNext()) onKey(nextName())
        endObject()
    }

    /** A string, number or boolean as text; null, empty, `false`, arrays and objects as null. */
    private fun JsonReader.nextScalar(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString().takeIf { it.isNotEmpty() }
        JsonToken.BOOLEAN -> if (nextBoolean()) "1" else null
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.readStrings(): List<String> {
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return emptyList()
        }
        val values = ArrayList<String>()
        beginArray()
        while (hasNext()) nextScalar()?.let { values += it.lowercase() }
        endArray()
        return values
    }
}
