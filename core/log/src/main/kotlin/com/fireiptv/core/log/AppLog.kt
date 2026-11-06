package com.fireiptv.core.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(val time: Long, val kind: Kind, val message: String) {
    enum class Kind { CRASH, ERROR, RETRY }
}

/**
 * Crashes and player trouble, kept on the device so they can be read in Settings: without adb on
 * the TV there is no other way to see them. Only the newest [MAX_ENTRIES] are kept. Web addresses
 * are masked before anything is written, since stream and playlist URLs carry the login.
 */
@Singleton
class AppLog @Inject constructor(@param:ApplicationContext context: Context) {
    private val file = File(context.filesDir, "app-log.txt")
    private val lock = Any()
    private val _entries = MutableStateFlow(read())

    /** Newest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun error(message: String) = add(LogEntry.Kind.ERROR, message)

    fun retry(message: String) = add(LogEntry.Kind.RETRY, message)

    /** Records the crash, then lets the platform handler end the app as usual. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { add(LogEntry.Kind.CRASH, describe(thread, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun clear() = synchronized(lock) {
        file.delete()
        _entries.value = emptyList()
    }

    private fun add(kind: LogEntry.Kind, message: String) = synchronized(lock) {
        val entry = LogEntry(System.currentTimeMillis(), kind, mask(message))
        val kept = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = kept
        try {
            file.writeText(kept.asReversed().joinToString("") { it.encode() + "\n" })
        } catch (_: IOException) {
            // A full disk loses the log, never the app.
        }
    }

    private fun read(): List<LogEntry> = try {
        if (file.exists()) file.readLines().mapNotNull(::decode).asReversed() else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    private fun LogEntry.encode() = "$time\t$kind\t" + message.replace("\\", "\\\\").replace("\n", "\\n")

    private fun decode(line: String): LogEntry? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val kind = LogEntry.Kind.entries.firstOrNull { it.name == parts[1] } ?: return null
        val message = buildString {
            var escaped = false
            for (c in parts[2]) {
                when {
                    escaped -> {
                        append(if (c == 'n') '\n' else c)
                        escaped = false
                    }
                    c == '\\' -> escaped = true
                    else -> append(c)
                }
            }
        }
        return LogEntry(time, kind, message)
    }

    private fun describe(thread: Thread, throwable: Throwable): String = buildString {
        append("On thread ${thread.name}: ")
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < MAX_CAUSES) {
            if (depth > 0) append("\nCaused by: ")
            append(cause.toString())
            cause.stackTrace.take(STACK_LINES).forEach { append("\n  at ").append(it) }
            cause = cause.cause.takeIf { it !== cause }
            depth++
        }
    }

    private companion object {
        const val MAX_ENTRIES = 100
        const val STACK_LINES = 12
        const val MAX_CAUSES = 3
        val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")

        fun mask(text: String) = URL.replace(text, "<link>")
    }
}
