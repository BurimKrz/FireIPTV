package com.fireiptv.core.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import com.fireiptv.core.data.db.FavoriteEntity
import com.fireiptv.core.data.db.HiddenChannelEntity
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.UserListEntity
import com.fireiptv.core.data.db.UserListEntryEntity
import com.fireiptv.core.data.playlist.ImportState
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.settings.StartMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

/** What a backup holds, for the result message. */
data class BackupSummary(
    val favorites: Int,
    val categories: Int,
    val hiddenGroups: Int,
    val hiddenChannels: Int,
    /** Restore only: the playlist in the backup was added, because there was none. */
    val playlistAdded: Boolean = false,
)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Saves what the user set up (favourites, categories, hidden and pinned groups, hidden channels,
 * settings, and the playlist login) to Pictures/FireIPTV-backup.png, and loads it back, e.g.
 * after a reinstall wiped the app's data. Pictures/ is outside the app, so the file survives an
 * uninstall; it is an image because that is the only kind of file Android 11 lets a reinstalled
 * app read back (see [BackupImage]). Nothing goes over the network, except the playlist import a restore may start.
 *
 * The file holds the playlist link with its username and password: it stays on the device.
 */
@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: PlayerDatabase,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
) {
    private val playlistDao = db.playlistDao()
    private val libraryDao = db.libraryDao()
    private val userListDao = db.userListDao()

    /** Where the file goes, for messages. */
    val location: String = "${Environment.DIRECTORY_PICTURES}/$FILE_NAME"

    suspend fun save(): BackupSummary = withContext(Dispatchers.IO) {
        val playlist = playlistDao.first() ?: throw BackupException("There is no playlist yet, so nothing to back up")
        val favorites = libraryDao.favorites(playlist.id)
        val lists = userListDao.lists(playlist.id).map { it to userListDao.entries(it.id) }
        val hiddenGroups = playlistDao.hiddenGroupTitles(playlist.id)
        val pins = playlistDao.groupPins(playlist.id)
        val hiddenChannels = libraryDao.hiddenChannels(playlist.id)

        val text = StringWriter().also { out ->
            JsonWriter(out).use { json ->
                json.setIndent("  ")
                json.beginObject()
                json.name("app").value("FireIPTV")
                json.name("format").value(FORMAT)
                json.name("savedAt").value(System.currentTimeMillis())
                json.name("playlist").beginObject()
                json.name("name").value(playlist.name)
                json.name("url").value(playlist.url)
                json.endObject()
                json.name("settings").beginObject()
                json.name("startMode").value(settings.startMode.value.name)
                json.name("logos").value(settings.logosEnabled.value)
                json.endObject()
                json.name("favorites").beginArray()
                favorites.forEach { json.beginObject().name("url").value(it.streamUrl).name("name").value(it.name).name("order").value(it.sortOrder).endObject() }
                json.endArray()
                json.name("categories").beginArray()
                lists.forEach { (list, entries) ->
                    json.beginObject()
                    json.name("title").value(list.title)
                    json.name("createdAt").value(list.createdAt)
                    list.pinnedAt?.let { json.name("pinnedAt").value(it) }
                    json.name("channels").beginArray()
                    entries.forEach { json.beginObject().name("url").value(it.streamUrl).name("name").value(it.name).name("order").value(it.sortOrder).endObject() }
                    json.endArray()
                    json.endObject()
                }
                json.endArray()
                json.name("hiddenGroups").beginArray()
                hiddenGroups.forEach { json.value(it) }
                json.endArray()
                json.name("pinnedGroups").beginArray()
                pins.forEach { json.beginObject().name("title").value(it.title).name("pinnedAt").value(it.pinnedAt).endObject() }
                json.endArray()
                json.name("hiddenChannels").beginArray()
                hiddenChannels.forEach { json.beginObject().name("url").value(it.streamUrl).name("name").value(it.name).name("hiddenAt").value(it.hiddenAt).endObject() }
                json.endArray()
                json.endObject()
            }
        }.toString()

        try {
            val saved = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
            write(
                BackupImage.encode(
                    text,
                    listOf(
                        "Saved $saved",
                        "${favorites.size} favourites, ${lists.size} categories, ${hiddenGroups.size + hiddenChannels.size} hidden groups and channels",
                        "Restore: Settings → Restore, or Restore from backup after a reinstall.",
                    ),
                ),
            )
        } catch (e: IOException) {
            throw BackupException("Couldn't write $location: ${e.message}", e)
        } catch (e: SecurityException) {
            throw BackupException("Couldn't write $location: ${e.message}", e)
        }
        BackupSummary(favorites.size, lists.size, hiddenGroups.size, hiddenChannels.size)
    }

    /**
     * Loads the backup into the first playlist, adding to what is there. With no playlist yet, it
     * adds the one in the backup and waits for its import first, so groups exist to hide and pin.
     */
    suspend fun restore(): BackupSummary {
        val text = withContext(Dispatchers.IO) {
            try {
                read()?.let { BackupImage.decode(it) }
            } catch (e: IOException) {
                throw BackupException("Couldn't read $location: ${e.message}", e)
            } catch (e: SecurityException) {
                throw BackupException("Couldn't read $location. Allow the app to see photos and media, then try again.", e)
            }
        } ?: throw BackupException("No backup found at $location. After a reinstall, allow photos and media when asked.")
        val backup = try {
            parse(text)
        } catch (e: Exception) {
            throw BackupException("$location isn't a FireIPTV backup", e)
        }

        var added = false
        var playlist = playlistDao.first()
        if (playlist == null) {
            val source = backup.playlist ?: throw BackupException("The backup has no playlist, and there is none here yet")
            val id = playlists.addPlaylist(source.name, source.url)
            added = true
            val result = playlists.importState.first { (it is ImportState.Done || it is ImportState.Failed) && it.playlistIdOrNull() == id }
            if (result is ImportState.Failed) {
                throw BackupException("The playlist was added, but its import failed: ${result.message}. Restore again once it has loaded.")
            }
            playlist = playlistDao.get(id) ?: throw BackupException("The playlist was removed during the restore")
        }
        val playlistId = playlist.id

        db.withTransaction {
            backup.favorites.forEach { libraryDao.addFavorite(FavoriteEntity(playlistId, it.url, it.name, it.order)) }
            backup.categories.forEach { category ->
                val listId = userListDao.listNamed(playlistId, category.title)
                    ?: userListDao.insertList(UserListEntity(playlistId = playlistId, title = category.title, createdAt = category.createdAt))
                category.pinnedAt?.let { userListDao.setPinnedAt(listId, it) }
                category.channels.forEach { userListDao.addEntry(UserListEntryEntity(listId, it.url, it.name, it.order)) }
            }
            backup.hiddenGroups.chunked(500).forEach { playlistDao.hideGroups(playlistId, it) }
            backup.pinnedGroups.forEach { playlistDao.pinGroup(playlistId, it.first, it.second) }
            libraryDao.hideAll(backup.hiddenChannels.map { HiddenChannelEntity(playlistId, it.url, it.name, it.order) })
            // A backup from another login has other stream URLs: find those channels by name.
            libraryDao.relinkAfterImport(playlistId)
            userListDao.relinkAfterImport(playlistId)
        }
        backup.startMode?.let { settings.setStartMode(it) }
        backup.logos?.let { settings.setLogosEnabled(it) }
        return BackupSummary(backup.favorites.size, backup.categories.size, backup.hiddenGroups.size, backup.hiddenChannels.size, added)
    }

    private fun ImportState.playlistIdOrNull(): Long? = when (this) {
        is ImportState.Done -> playlistId
        is ImportState.Failed -> playlistId
        is ImportState.Running -> playlistId
        ImportState.Idle -> null
    }

    // Android 10+ keeps Pictures/ behind MediaStore: the app may write its own images there with
    // no permission, and with the photos permission read images an earlier install left behind.

    private fun write(png: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).apply { mkdirs() }
            File(dir, FILE_NAME).writeBytes(png)
            return
        }
        val resolver = context.contentResolver
        // This install's own backup is overwritten. One left by an earlier install can't be, so
        // MediaStore gives the new file the next free name ("FireIPTV-backup (1).png"); restore
        // reads the newest.
        val uri = backups(ownOnly = true).firstOrNull() ?: resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            },
        ) ?: throw IOException("Pictures/ isn't available")
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("the file couldn't be opened")).use { it.write(png) }
    }

    /** The newest backup's bytes, or null if there is none this app may see. */
    private fun read(): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (uri in backups(ownOnly = false)) {
                val bytes = try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
                if (bytes != null && BackupImage.decode(bytes) != null) return bytes
            }
        }
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FILE_NAME)
        return if (file.canRead()) file.readBytes() else null
    }

    /**
     * Backup images, newest first. MediaStore lists the app's own images, plus other ones when it
     * has the photos permission; [ownOnly] keeps just the app's own.
     */
    private fun backups(ownOnly: Boolean): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = buildString {
            append("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            if (ownOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) append(" AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
        }
        val args = if (ownOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf("FireIPTV-backup%.png", context.packageName)
        } else {
            arrayOf("FireIPTV-backup%.png")
        }
        val uris = ArrayList<Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) uris += ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return uris
    }

    private class Entry(val url: String, val name: String, val order: Long)
    private class Category(val title: String, val createdAt: Long, val pinnedAt: Long?, val channels: List<Entry>)
    private class Source(val name: String, val url: String)
    private class Backup(
        val playlist: Source?,
        val startMode: StartMode?,
        val logos: Boolean?,
        val favorites: List<Entry>,
        val categories: List<Category>,
        val hiddenGroups: List<String>,
        val pinnedGroups: List<Pair<String, Long>>,
        val hiddenChannels: List<Entry>,
    )

    private fun parse(text: String): Backup = JsonReader(StringReader(text)).use { json ->
        var app: String? = null
        var playlist: Source? = null
        var startMode: StartMode? = null
        var logos: Boolean? = null
        var favorites = emptyList<Entry>()
        var categories = emptyList<Category>()
        var hiddenGroups = emptyList<String>()
        var pinnedGroups = emptyList<Pair<String, Long>>()
        var hiddenChannels = emptyList<Entry>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "app" -> app = json.nextString()
                "playlist" -> {
                    val fields = json.readStrings()
                    val url = fields["url"]
                    if (url != null) playlist = Source(fields["name"].orEmpty(), url)
                }
                "settings" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "startMode" -> json.nextString().let { name -> startMode = StartMode.entries.firstOrNull { it.name == name } }
                            "logos" -> logos = json.nextBoolean()
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                }
                "favorites" -> favorites = json.readEntries("order")
                "hiddenChannels" -> hiddenChannels = json.readEntries("hiddenAt")
                "categories" -> categories = json.readList {
                    var title = ""
                    var createdAt = 0L
                    var pinnedAt: Long? = null
                    var channels = emptyList<Entry>()
                    beginObject()
                    while (hasNext()) {
                        when (nextName()) {
                            "title" -> title = nextString()
                            "createdAt" -> createdAt = nextLong()
                            "pinnedAt" -> pinnedAt = nextLong()
                            "channels" -> channels = readEntries("order")
                            else -> skipValue()
                        }
                    }
                    endObject()
                    Category(title, createdAt, pinnedAt, channels)
                }.filter { it.title.isNotBlank() }
                "hiddenGroups" -> hiddenGroups = json.readList { nextString() }
                "pinnedGroups" -> pinnedGroups = json.readList {
                    val fields = readStrings()
                    val title = fields["title"]
                    val at = fields["pinnedAt"]?.toLongOrNull()
                    if (title != null && at != null) title to at else null
                }.filterNotNull()
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (app != "FireIPTV") throw IllegalArgumentException("not a FireIPTV backup")
        Backup(playlist, startMode, logos, favorites, categories, hiddenGroups, pinnedGroups, hiddenChannels)
    }

    private fun <T> JsonReader.readList(item: JsonReader.() -> T): List<T> {
        val items = ArrayList<T>()
        beginArray()
        while (hasNext()) items += item()
        endArray()
        return items
    }

    /** An object of plain values, all read as strings. */
    private fun JsonReader.readStrings(): Map<String, String> {
        val fields = HashMap<String, String>()
        beginObject()
        while (hasNext()) {
            val name = nextName()
            when (peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> fields[name] = nextString()
                JsonToken.BOOLEAN -> fields[name] = nextBoolean().toString()
                else -> skipValue()
            }
        }
        endObject()
        return fields
    }

    private fun JsonReader.readEntries(orderField: String): List<Entry> = readList {
        val fields = readStrings()
        val url = fields["url"]
        if (url == null) null else Entry(url, fields["name"].orEmpty(), fields[orderField]?.toLongOrNull() ?: 0L)
    }.filterNotNull()

    private companion object {
        const val FILE_NAME = "FireIPTV-backup.png"
        const val FORMAT = 1
    }
}
