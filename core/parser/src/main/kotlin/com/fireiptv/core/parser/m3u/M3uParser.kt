package com.fireiptv.core.parser.m3u

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

/**
 * Streaming M3U/M3U8 parser. Reads [BufferedSource] line by line and hands each channel to
 * `onEntry` as soon as its URL line is read, so a 50k-channel playlist never sits in memory.
 *
 * Tolerates junk instead of failing: a missing header, CRLF, a UTF-8 BOM, blank names and
 * `#EXTINF` lines without a URL are handled or counted in [M3uResult.skippedLines].
 */
object M3uParser {

    private const val CANCEL_CHECK_INTERVAL = 1_000

    suspend fun parse(
        source: BufferedSource,
        onHeader: suspend (M3uHeader) -> Unit = {},
        onEntry: suspend (M3uEntry) -> Unit,
    ): M3uResult {
        var header: M3uHeader? = null
        var pending: PendingEntry? = null
        var entries = 0
        var skipped = 0
        var lineNumber = 0

        while (true) {
            var line = source.readUtf8Line() ?: break
            if (lineNumber == 0) line = line.removePrefix("﻿")
            if (++lineNumber % CANCEL_CHECK_INTERVAL == 0) currentCoroutineContext().ensureActive()

            line = line.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    if (header == null) {
                        header = parseHeader(line).also { onHeader(it) }
                    }
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val previous = pending
                    if (previous?.hasInfo == true) skipped++ // previous #EXTINF never got a URL
                    pending = parseExtInf(line).also { entry ->
                        // Some playlists put #EXTVLCOPT / #KODIPROP before #EXTINF.
                        if (previous != null && !previous.hasInfo) entry.inheritModifiers(previous)
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val entry = pending ?: PendingEntry().also { pending = it }
                    if (entry.groupTitle == null) entry.groupTitle = line.substringAfter(':').trim().ifEmpty { null }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val entry = pending ?: PendingEntry().also { pending = it }
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim().ifEmpty { null }
                    when (key) {
                        "http-user-agent" -> entry.userAgent = value ?: entry.userAgent
                        "http-referrer", "http-referer" -> entry.referrer = value ?: entry.referrer
                    }
                }

                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val entry = pending ?: PendingEntry().also { pending = it }
                    val prop = line.substringAfter(':')
                    if ('=' in prop) entry.kodiProps[prop.substringBefore('=').trim()] = prop.substringAfter('=').trim()
                }

                line.startsWith("#") -> Unit // comments and tags we don't use

                // Not a stream URL: HTML error pages, stray text. Keeps a pending #EXTINF waiting.
                "://" !in line -> skipped++

                else -> {
                    onEntry((pending ?: PendingEntry()).toEntry(line))
                    entries++
                    pending = null
                }
            }
        }
        if (pending != null) skipped++

        return M3uResult(header = header, entries = entries, skippedLines = skipped)
    }

    private fun parseHeader(line: String): M3uHeader {
        val attrs = HashMap<String, String>()
        scanAttributes(line, start = "#EXTM3U".length, into = attrs)
        val epgUrls = listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return M3uHeader(
            epgUrls = epgUrls,
            catchup = attrs["catchup"],
            catchupSource = attrs["catchup-source"],
            catchupDays = attrs["catchup-days"]?.toIntOrNull(),
            tvgShift = attrs["tvg-shift"]?.toShiftOrNull(),
        )
    }

    private fun parseExtInf(line: String): PendingEntry {
        // #EXTINF:<duration> key="value" key2="value2",Display name
        var i = "#EXTINF:".length
        val durationStart = i
        while (i < line.length && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
        val duration = line.substring(durationStart, i).trim().toFloatOrNull()?.toInt() ?: -1

        val attrs = HashMap<String, String>()
        val nameStart = scanAttributes(line, start = i, into = attrs)
        val name = if (nameStart < line.length) line.substring(nameStart).trim() else ""

        return PendingEntry(
            hasInfo = true,
            name = name,
            duration = duration,
            tvgId = attrs["tvg-id"],
            tvgName = attrs["tvg-name"],
            tvgLogo = attrs["tvg-logo"],
            tvgChno = attrs["tvg-chno"]?.toIntOrNull(),
            groupTitle = attrs["group-title"],
            catchup = attrs["catchup"],
            catchupSource = attrs["catchup-source"],
            catchupDays = attrs["catchup-days"]?.toIntOrNull(),
            tvgShift = attrs["tvg-shift"]?.toShiftOrNull(),
            userAgent = attrs["user-agent"] ?: attrs["http-user-agent"],
            referrer = attrs["referrer"] ?: attrs["referer"] ?: attrs["http-referrer"],
        )
    }

    /**
     * Hand-written `key="value"` scanner (regex is several times slower on large files).
     * Keys are lowercased; empty values are dropped. Stops at the first comma outside quotes,
     * which ends the attribute list on `#EXTINF` lines — display names may contain commas.
     *
     * @return the index just after that comma, or the line length if there is none.
     */
    private fun scanAttributes(line: String, start: Int, into: MutableMap<String, String>): Int {
        var i = start
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == ',') return i + 1
            if (c == ' ' || c == '\t') {
                i++
                continue
            }

            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
            val key = line.substring(keyStart, i).lowercase()
            if (i >= n || line[i] != '=') continue // bare word, no value

            i++ // '='
            val value: String
            if (i < n && (line[i] == '"' || line[i] == '\'')) {
                val quote = line[i++]
                val valueStart = i
                while (i < n && line[i] != quote) i++
                value = line.substring(valueStart, i)
                if (i < n) i++ // closing quote
            } else {
                val valueStart = i
                while (i < n && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
                value = line.substring(valueStart, i)
            }
            val trimmed = value.trim()
            if (key.isNotEmpty() && trimmed.isNotEmpty()) into[key] = trimmed
        }
        return n
    }

    private fun String.toShiftOrNull(): Float? = removePrefix("+").toFloatOrNull()

    private class PendingEntry(
        val hasInfo: Boolean = false,
        var name: String = "",
        var duration: Int = -1,
        var tvgId: String? = null,
        var tvgName: String? = null,
        var tvgLogo: String? = null,
        var tvgChno: Int? = null,
        var groupTitle: String? = null,
        var catchup: String? = null,
        var catchupSource: String? = null,
        var catchupDays: Int? = null,
        var tvgShift: Float? = null,
        var userAgent: String? = null,
        var referrer: String? = null,
        val kodiProps: MutableMap<String, String> = HashMap(),
    ) {
        fun inheritModifiers(from: PendingEntry) {
            groupTitle = groupTitle ?: from.groupTitle
            userAgent = userAgent ?: from.userAgent
            referrer = referrer ?: from.referrer
            from.kodiProps.forEach { (key, value) -> kodiProps.putIfAbsent(key, value) }
        }

        fun toEntry(urlLine: String): M3uEntry {
            // Kodi-style headers after a pipe: http://host/stream|User-Agent=x&Referer=y
            var url = urlLine
            var ua = userAgent
            var ref = referrer
            val pipe = urlLine.indexOf('|')
            if (pipe > 0) {
                url = urlLine.substring(0, pipe).trim()
                urlLine.substring(pipe + 1).split('&').forEach { pair ->
                    val key = pair.substringBefore('=').trim().lowercase()
                    val value = pair.substringAfter('=', "").trim().ifEmpty { null }
                    when (key) {
                        "user-agent" -> ua = ua ?: value
                        "referer", "referrer" -> ref = ref ?: value
                    }
                }
            }

            val displayName = name.ifBlank { tvgName ?: url.substringAfterLast('/').ifBlank { url } }
            return M3uEntry(
                name = displayName,
                url = url,
                duration = duration,
                tvgId = tvgId,
                tvgName = tvgName,
                tvgLogo = tvgLogo,
                tvgChno = tvgChno,
                groupTitle = groupTitle,
                catchup = catchup,
                catchupSource = catchupSource,
                catchupDays = catchupDays,
                tvgShift = tvgShift,
                userAgent = ua,
                referrer = ref,
                kodiProps = kodiProps.toMap(),
            )
        }
    }
}
