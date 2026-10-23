package com.fireiptv.core.data.playlist.xtream

import android.util.JsonReader
import android.util.JsonToken

internal data class XtreamUserInfo(val authenticated: Boolean, val status: String?, val formats: List<String>)

internal data class XtreamCategory(val id: String, val name: String)

internal data class XtreamLiveStream(
    val streamId: String,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val type: String?,
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
                reader.readObject { field ->
                    when (field) {
                        "auth" -> auth = reader.nextScalar() == "1"
                        "status" -> status = reader.nextScalar()
                        "allowed_output_formats" -> formats = reader.readStrings()
                        else -> reader.skipValue()
                    }
                }
                info = XtreamUserInfo(auth, status, formats)
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
            var name: String? = null
            var icon: String? = null
            var epg: String? = null
            var category: String? = null
            var type: String? = null
            reader.readObject { key ->
                when (key) {
                    "stream_id" -> streamId = reader.nextScalar()
                    "name" -> name = reader.nextScalar()
                    "stream_icon" -> icon = reader.nextScalar()
                    "epg_channel_id" -> epg = reader.nextScalar()
                    "category_id" -> category = reader.nextScalar()
                    "stream_type" -> type = reader.nextScalar()
                    else -> reader.skipValue()
                }
            }
            val id = streamId ?: continue
            onStream(XtreamLiveStream(id, name?.trim().orEmpty(), icon, epg, category, type))
        }
        reader.endArray()
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
