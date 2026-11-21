package com.fireiptv.feature.player

import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** No key press for this long while playing asks "Still watching?". */
internal val STILL_WATCHING_AFTER_MS = TimeUnit.HOURS.toMillis(3)

private const val STILL_WATCHING_ANSWER_S = 60
private val SLEEP_CHOICES_MIN = listOf(null, 30, 60, 90)
private const val MINUTE_MS = 60_000L
private const val SLEEP_TICK_MS = 15_000L

/** The sleep timer after [current], stepping through off, 30, 60 and 90 minutes. */
internal fun nextSleepChoice(current: Int?): Int? =
    SLEEP_CHOICES_MIN[(SLEEP_CHOICES_MIN.indexOf(current) + 1) % SLEEP_CHOICES_MIN.size]

/** Waits [minutes], reporting the minutes left (rounded up) as they pass; returns when time is up. */
internal suspend fun sleepCountdown(minutes: Int, onMinutesLeft: (Int) -> Unit) {
    val endsAt = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(minutes.toLong())
    while (true) {
        val left = endsAt - SystemClock.elapsedRealtime()
        if (left <= 0) return
        onMinutesLeft(((left + MINUTE_MS - 1) / MINUTE_MS).toInt())
        delay(minOf(left, SLEEP_TICK_MS))
    }
}

/** Gives a minute to answer "Still watching?", reporting the seconds left; returns when nobody did. */
internal suspend fun stillWatchingCountdown(onSecondsLeft: (Int) -> Unit) {
    for (left in STILL_WATCHING_ANSWER_S downTo 1) {
        onSecondsLeft(left)
        delay(1_000)
    }
}
