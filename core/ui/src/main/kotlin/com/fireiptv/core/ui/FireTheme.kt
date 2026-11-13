package com.fireiptv.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/** The app's few colours. Flat fills only: no gradients, blurs or images to draw on a Fire stick. */
object FireColors {
    /** Brand and highlights: the selected group, the playing channel, favourite stars. */
    val Accent = Color(0xFFFF8A3D)
    val Background = Color(0xFF0C1016)

    /** Panels and cards that sit on [Background]. */
    val Panel = Color(0xFF141A22)

    /** Small tiles on a panel, e.g. behind a channel logo. */
    val Tile = Color(0xFF232C39)
    val Text = Color(0xFFE9EDF2)
    val TextMuted = Color(0xFF98A2B0)
    val Outline = Color(0xFF364252)
    val Error = Color(0xFFFF7A70)
}

val PanelShape = RoundedCornerShape(14.dp)

/** The rounded dark sheet that lists and cards sit on. */
fun Modifier.panel(): Modifier = background(FireColors.Panel, PanelShape)

/** Dark TV theme; also themes the Material 3 text fields used for input. */
@Composable
fun FireTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FireColors.Accent,
            onPrimary = Color(0xFF2B1200),
            background = FireColors.Background,
            onBackground = FireColors.Text,
            surface = FireColors.Background,
            onSurface = FireColors.Text,
            surfaceVariant = FireColors.Panel,
            onSurfaceVariant = FireColors.TextMuted,
            // The focused row: near-white, so it reads from across the room.
            inverseSurface = Color(0xFFF2F4F7),
            inverseOnSurface = Color(0xFF10151C),
            // The selected row: a quiet fill with accent text.
            secondaryContainer = Color(0xFF55657C),
            onSecondaryContainer = FireColors.Accent,
            border = FireColors.Outline,
            error = FireColors.Error,
        ),
    ) {
        M3Theme(
            colorScheme = m3DarkColorScheme(
                primary = FireColors.Accent,
                onPrimary = Color(0xFF2B1200),
                background = FireColors.Background,
                onBackground = FireColors.Text,
                surface = FireColors.Background,
                onSurface = FireColors.Text,
                surfaceVariant = FireColors.Panel,
                onSurfaceVariant = FireColors.TextMuted,
                outline = FireColors.Outline,
                error = FireColors.Error,
            ),
            content = content,
        )
    }
}
