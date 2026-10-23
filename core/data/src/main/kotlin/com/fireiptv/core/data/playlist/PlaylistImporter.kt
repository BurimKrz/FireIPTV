package com.fireiptv.core.data.playlist

import android.util.JsonReader
import androidx.room.withTransaction
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.data.db.GroupEntity
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.PlaylistEntity
import com.fireiptv.core.data.playlist.xtream.XtreamAccount
import com.fireiptv.core.data.playlist.xtream.XtreamJson
import com.fireiptv.core.parser.m3u.M3uEntry
import com.fireiptv.core.parser.m3u.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ForwardingSource
import okio.GzipSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ImportProgress(val bytesRead: Long, val totalBytes: Long, val channels: Int)

sealed interface ImportOutcome {
    data class Imported(
        val channels: Int,
        val groups: Int,
        val skippedLines: Int,
        /** Xtream movie and series entries, left out because v1 is live TV only. */
        val skippedVod: Int = 0,
    ) : ImportOutcome
    /** The server answered 304: the playlist hasn't changed since the last sync. */
    data object NotModified : ImportOutcome
}

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads a playlist and streams it into Room in batches.
 *
 * Xtream `get.php` / `player_api.php` links go through the Xtream API, which returns live channels
 * only; anything else is read as M3U. A playlist with no channels yet is written in place, so lists
 * fill in while the import runs. A refresh is written to a hidden staging playlist and swapped in
 * with one transaction at the end, so the UI never sees a half-built list and a failed refresh
 * leaves the old one intact.
 */
class PlaylistImporter @Inject constructor(
    private val db: PlayerDatabase,
    client: OkHttpClient,
) {
    // Xtream panels build get.php output before sending a byte (20 s+ seen on a real provider),
    // far past the 15 s read timeout the player uses. Same connection pool, longer wait.
    private val client = client.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()

    private val playlistDao = db.playlistDao()
    private val groupDao = db.groupDao()
    private val channelDao = db.channelDao()

    suspend fun import(
        playlistId: Long,
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val playlist = playlistDao.get(playlistId) ?: throw ImportException("Playlist $playlistId not found")
        val xtream = XtreamAccount.fromUrl(playlist.url)
        if (xtream != null) importXtream(playlist, xtream, onProgress) else importM3u(playlist, onProgress)
    }

    private suspend fun importM3u(playlist: PlaylistEntity, onProgress: suspend (ImportProgress) -> Unit): ImportOutcome {
        val inPlace = channelDao.count(playlist.id) == 0
        val request = Request.Builder().url(playlist.url).apply {
            playlist.userAgent?.let { header("User-Agent", it) }
            if (!inPlace) {
                playlist.etag?.let { header("If-None-Match", it) }
                playlist.lastModified?.let { header("If-Modified-Since", it) }
            }
        }.build()

        return execute(request).use { response ->
            if (response.code == 304) {
                playlistDao.update(playlist.copy(lastSync = System.currentTimeMillis()))
                return ImportOutcome.NotModified
            }
            if (!response.isSuccessful) throw ImportException("Server answered HTTP ${response.code}")

            val body = response.body
            val counter = CountingSource(body.source())
            val source = counter.buffer().let { if (it.isGzip()) GzipSource(it).buffer() else it }
            var epgUrl: String? = null
            var skippedLines = 0

            val written = writeChannels(playlist, inPlace, emptyMessage = "No channels found. Is this an M3U playlist?") { sink ->
                val result = M3uParser.parse(source, onHeader = { epgUrl = it.epgUrls.firstOrNull() }) { entry ->
                    when {
                        entry.isXtreamVod() -> sink.skipVod()
                        isProviderAccountInfo(entry.groupTitle) || isProviderAccountInfo(entry.name) -> Unit
                        else -> sink.add(entry, groupKey = entry.groupTitle)
                    }
                    if (sink.flushedBatch) onProgress(ImportProgress(counter.bytesRead, body.contentLength(), sink.count))
                }
                skippedLines = result.skippedLines
            }
            finish(playlist, inPlace, written) {
                copy(
                    epgUrl = epgUrl ?: this.epgUrl,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                )
            }
            ImportOutcome.Imported(written.channels, written.groups, skippedLines, written.skippedVod)
        }
    }

    private suspend fun importXtream(
        playlist: PlaylistEntity,
        account: XtreamAccount,
        onProgress: suspend (ImportProgress) -> Unit,
    ): ImportOutcome {
        val user = fetchJson(account.apiUrl(), playlist) { XtreamJson.readUserInfo(it) }
        if (!user.authenticated) throw ImportException("The provider rejected the username or password")
        if (user.status != null && !user.status.equals("Active", ignoreCase = true)) {
            throw ImportException("The provider reports this account as ${user.status}")
        }
        // MPEG-TS has no playlist to poll, so it uses less data than HLS for the same stream.
        val extension = account.preferredFormat?.takeIf { it in user.formats || user.formats.isEmpty() }
            ?: if ("ts" in user.formats || user.formats.isEmpty()) "ts" else "m3u8"

        val allCategories = fetchJson(account.apiUrl("get_live_categories"), playlist) { XtreamJson.readCategories(it) }
        val (infoCategories, categories) = allCategories.partition { isProviderAccountInfo(it.name) }
        val infoCategoryIds = infoCategories.map { it.id }.toSet()
        val categoryNames = categories.associate { it.id to it.name }
        val categoryOrder = categories.withIndex().associate { (index, category) -> category.id to index }

        val inPlace = channelDao.count(playlist.id) == 0
        val written = writeChannels(playlist, inPlace, emptyMessage = "The provider returned no live channels") { sink ->
            sink.groupOrder = categoryOrder
            fetchJson(account.apiUrl("get_live_streams"), playlist) { reader ->
                XtreamJson.readLiveStreams(reader) { stream ->
                    if (stream.type != null && stream.type != "live") return@readLiveStreams
                    if (stream.categoryId in infoCategoryIds || isProviderAccountInfo(stream.name)) return@readLiveStreams
                    val entry = M3uEntry(
                        name = stream.name.ifEmpty { "Channel ${stream.streamId}" },
                        url = account.liveStreamUrl(stream.streamId, extension),
                        tvgChno = stream.number,
                        tvgId = stream.epgChannelId,
                        tvgLogo = stream.icon,
                        groupTitle = stream.categoryId?.let { categoryNames[it] },
                    )
                    sink.add(entry, groupKey = stream.categoryId?.takeIf { it in categoryNames })
                    if (sink.flushedBatch) onProgress(ImportProgress(bytesRead = 0, totalBytes = -1, sink.count))
                }
            }
        }
        finish(playlist, inPlace, written) { copy(etag = null, lastModified = null, accountExpiresAt = user.expiresAt) }
        return ImportOutcome.Imported(written.channels, written.groups, skippedLines = 0)
    }

    private suspend fun <T> fetchJson(url: HttpUrl, playlist: PlaylistEntity, read: suspend (JsonReader) -> T): T {
        val request = Request.Builder().url(url).apply { playlist.userAgent?.let { header("User-Agent", it) } }.build()
        return execute(request).use { response ->
            if (!response.isSuccessful) throw ImportException("Server answered HTTP ${response.code}")
            try {
                JsonReader(response.body.charStream()).use { read(it) }
            } catch (e: IllegalStateException) {
                throw ImportException("The provider sent a response that isn't Xtream JSON", e)
            } catch (e: IOException) {
                throw ImportException("Download failed: ${e.message}", e)
            }
        }
    }

    /**
     * Network calls retry on connection errors and 5xx, so an import started before Wi-Fi is up,
     * or during a provider hiccup, doesn't fail. 4xx answers are final.
     */
    private suspend fun execute(request: Request): Response {
        var lastError: IOException? = null
        for (attempt in 0 until RETRY_DELAYS_MS.size + 1) {
            if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
            currentCoroutineContext().ensureActive()
            try {
                val response = client.newCall(request).execute()
                if (response.code !in 500..599 || attempt == RETRY_DELAYS_MS.size) return response
                response.close()
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw ImportException("Couldn't reach the server: ${lastError?.message ?: "server error"}", lastError)
    }

    private class Written(val channels: Int, val groups: Int, val skippedVod: Int, val targetId: Long)

    /** Runs [produce] into a [ChannelSink]; on any failure removes what it wrote. */
    private suspend fun writeChannels(
        playlist: PlaylistEntity,
        inPlace: Boolean,
        emptyMessage: String,
        produce: suspend (ChannelSink) -> Unit,
    ): Written {
        val targetId = if (inPlace) playlist.id else playlistDao.insert(playlist.copy(id = 0, isStaging = true))
        try {
            val sink = ChannelSink(targetId)
            produce(sink)
            sink.flush()
            if (sink.count == 0) throw ImportException(emptyMessage)
            return Written(sink.count, sink.groupCount, sink.skippedVod, targetId)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (inPlace) {
                    playlistDao.deleteChannels(playlist.id)
                    playlistDao.deleteGroups(playlist.id)
                } else {
                    playlistDao.delete(targetId) // cascades to the staging channels
                }
            }
            if (e is IOException) throw ImportException("Download failed: ${e.message}", e)
            throw e
        }
    }

    private suspend fun finish(
        playlist: PlaylistEntity,
        inPlace: Boolean,
        written: Written,
        update: PlaylistEntity.() -> PlaylistEntity,
    ) {
        db.withTransaction {
            if (!inPlace) playlistDao.swapStaging(stagingId = written.targetId, targetId = playlist.id)
            db.libraryDao().relinkAfterImport(playlist.id)
            playlistDao.update(playlist.update().copy(lastSync = System.currentTimeMillis()))
        }
    }

    /** Batches channels into Room and creates groups on first sight. */
    private inner class ChannelSink(private val playlistId: Long) {
        private val groupIds = HashMap<String, Long>()
        private val batch = ArrayList<ChannelEntity>(BATCH_SIZE)

        /** Preferred group order by key (Xtream category order); unknown keys go after, in order seen. */
        var groupOrder: Map<String, Int> = emptyMap()
        var count = 0
            private set
        var skippedVod = 0
            private set

        /** True right after an [add] that wrote a batch; callers report progress then. */
        var flushedBatch = false
            private set
        val groupCount: Int get() = groupIds.size

        fun skipVod() {
            skippedVod++
            flushedBatch = false
        }

        suspend fun add(entry: M3uEntry, groupKey: String?) {
            val groupId = groupKey?.let { key ->
                groupIds.getOrPut(key) {
                    val order = groupOrder[key] ?: (groupOrder.size + groupIds.size)
                    groupDao.insert(GroupEntity(playlistId = playlistId, title = entry.groupTitle ?: key, sortOrder = order))
                }
            }
            batch += entry.toChannel(playlistId, groupId, sortOrder = count++)
            flushedBatch = batch.size == BATCH_SIZE
            if (flushedBatch) {
                currentCoroutineContext().ensureActive()
                flush()
            }
        }

        suspend fun flush() {
            if (batch.isEmpty()) return
            channelDao.insertAll(batch)
            batch.clear()
        }
    }

    private fun M3uEntry.toChannel(playlistId: Long, groupId: Long?, sortOrder: Int) = ChannelEntity(
        playlistId = playlistId,
        groupId = groupId,
        name = name,
        number = tvgChno,
        streamUrl = url,
        logoUrl = tvgLogo,
        tvgId = tvgId,
        tvgName = tvgName,
        catchupType = catchup,
        catchupSource = catchupSource,
        catchupDays = catchupDays,
        tvgShift = tvgShift,
        userAgent = userAgent,
        referrer = referrer,
        sortOrder = sortOrder,
    )

    /** Xtream URLs put VOD under /movie/ and /series/; live streams use /live/ or no prefix. */
    private fun M3uEntry.isXtreamVod(): Boolean = "/movie/" in url || "/series/" in url

    private fun BufferedSource.isGzip(): Boolean = request(2) && rangeEquals(0, GZIP_MAGIC)

    private class CountingSource(delegate: Source) : ForwardingSource(delegate) {
        var bytesRead = 0L
            private set

        override fun read(sink: Buffer, byteCount: Long): Long =
            super.read(sink, byteCount).also { if (it > 0) bytesRead += it }
    }

    private companion object {
        const val BATCH_SIZE = 1_000
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000)
        val GZIP_MAGIC = "1f8b".decodeHex()
    }
}
