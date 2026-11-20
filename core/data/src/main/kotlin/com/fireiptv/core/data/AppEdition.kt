package com.fireiptv.core.data

import android.content.Context

/**
 * Which of the two apps this is. FireIPTV Beta is the same code under the package name
 * `com.fireiptv.player.beta`, installed next to FireIPTV with its own data; it takes its updates
 * from GitHub pre-releases and keeps its own backup file.
 */
enum class AppEdition {
    Stable,
    Beta,
    ;

    companion object {
        fun of(context: Context): AppEdition = if (context.packageName.endsWith(".beta")) Beta else Stable
    }
}
