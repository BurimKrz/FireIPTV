package com.fireiptv.core.ui

/** A position in a movie as a clock: 2590000 → "43:10", 3735000 → "1:02:15". */
fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
