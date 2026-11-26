package com.fireiptv.feature.player

import android.view.Display
import kotlin.math.abs

/**
 * The screen refresh rate that shows [frameRate] evenly: 50 Hz for 25 and 50 fps (European TV),
 * 60 Hz for 30 and 60 fps (also 29.97 / 59.94). Null for any other rate, e.g. 24 fps films,
 * which leaves the screen as it is.
 *
 * 25 fps on a 60 Hz screen holds frames for 2 and 3 refreshes in turn (60 / 25 = 2.4): a steady judder.
 */
fun refreshRateFor(frameRate: Float): Float? = when {
    frameRate in 24.5f..25.5f || frameRate in 49f..51f -> 50f
    frameRate in 29f..30.5f || frameRate in 58f..60.5f -> 60f
    else -> null
}

/**
 * The display's mode with [current]'s resolution whose refresh rate is closest to [refreshRate]
 * (59.94 Hz counts for 60), or null when the display offers none within half a hertz.
 */
fun displayModeFor(modes: Array<Display.Mode>, current: Display.Mode, refreshRate: Float): Display.Mode? = modes
    .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
    .filter { abs(it.refreshRate - refreshRate) < 0.5f }
    .minByOrNull { abs(it.refreshRate - refreshRate) }
