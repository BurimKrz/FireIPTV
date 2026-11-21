package com.fireiptv.feature.browse.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.fireiptv.core.data.vod.Movie
import com.fireiptv.core.ui.ChannelLogo
import com.fireiptv.core.ui.playbackTime

/**
 * A movie's page: what the list already knew (name, year, poster, rating) shows at once; the
 * details (plot, cast, length, format) fill in after one small download.
 */
@Composable
fun MovieInfoScreen(onPlay: (movie: Movie, startMs: Long) -> Unit, viewModel: MovieInfoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val movie = state.movie
    val info = state.info
    val play = remember { FocusRequester() }

    // Overscan-safe margins for TV: 5% of 960×540 dp.
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        if (movie == null) {
            if (!state.loadingMovie) {
                Text("This movie isn't in the list any more. Go back and open it again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Box
        }
        // The first button changes from Play to Resume once a saved position is read (or after
        // watching part of it), so focus goes to whichever is there.
        val resumable = state.resumeFromMs != null
        LaunchedEffect(state.progressRead, resumable) { if (state.progressRead) play.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Box(
                Modifier
                    .size(width = 240.dp, height = 360.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            ) {
                // The details' poster only if the list had none; a medium size, not the provider's big one.
                ChannelLogo(posterUrl(movie.posterUrl ?: info?.posterUrl, PosterSize.Page), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(movie.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    movie.year?.toString() ?: info?.releaseDate?.take(4),
                    info?.durationSecs?.let(::duration),
                    (info?.rating ?: movie.rating)?.let { "★ %.1f".format(it) },
                    info?.genre,
                )
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                info?.format?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val plot = info?.plot
                when {
                    plot != null -> Text(plot, style = MaterialTheme.typography.bodyLarge, maxLines = 7, overflow = TextOverflow.Ellipsis)
                    state.infoError != null -> Text("Couldn't load the details: ${state.infoError}", color = MaterialTheme.colorScheme.error)
                    info == null -> Text("Loading details…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                info?.director?.let { Detail("Director", it) }
                info?.cast?.let { Detail("Cast", it) }
                if (state.progressRead) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                        val resumeFrom = state.resumeFromMs
                        if (resumeFrom != null) {
                            Button(onClick = { onPlay(movie, resumeFrom) }, modifier = Modifier.focusRequester(play)) {
                                Text("Resume from ${playbackTime(resumeFrom)}")
                            }
                            OutlinedButton(onClick = { onPlay(movie, 0) }) { Text("Start over") }
                        } else {
                            Button(onClick = { onPlay(movie, 0) }, modifier = Modifier.focusRequester(play)) { Text("Play") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 6300 → "1 h 45 min". */
private fun duration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
