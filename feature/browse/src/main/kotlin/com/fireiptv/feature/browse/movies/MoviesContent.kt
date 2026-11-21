package com.fireiptv.feature.browse.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.fireiptv.core.ui.ChannelLogo
import com.fireiptv.core.ui.rememberScrollIdle
import kotlinx.coroutines.delay

/**
 * Home's Movies side: the provider's categories on the left, the open category's movies on the
 * right. It works like Live TV: OK or Right on a category lands on its first movie, and Back steps
 * out one level at a time (movies → their category → the first category → Live TV). While a movie
 * is stopped part-way, Continue watching sits above the provider's categories.
 *
 * Data: the category list downloads when this first shows; a category's movies when focus rests
 * on it (longer than for one already downloaded, so passing over categories fetches nothing) or
 * when it's opened.
 */
@Composable
fun MoviesContent(
    onOpenMovie: (playlistId: Long, streamId: String) -> Unit,
    onBackToLive: () -> Unit,
    viewModel: MoviesViewModel = hiltViewModel(),
) {
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoriesLoad by viewModel.categoriesLoad.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val moviesLoad by viewModel.moviesLoad.collectAsStateWithLifecycle()
    val movies = viewModel.movies.collectAsLazyPagingItems()

    // Coming back from a movie's page puts focus on that movie; otherwise on the first category.
    val returnTarget = remember { viewModel.takeReturn() }
    var focusedRow by remember { mutableStateOf<Long?>(null) }
    var jump by remember { mutableStateOf<CategoryJump?>(null) }
    var jumps by remember { mutableIntStateOf(0) }
    var moviesTopFor by remember { mutableStateOf<Long?>(null) }

    fun jumpTo(categoryId: Long) {
        moviesTopFor = null
        jump = CategoryJump(categoryId, ++jumps)
    }

    val hasCategories = categories.isNotEmpty()
    LaunchedEffect(hasCategories) {
        if (hasCategories && returnTarget == null && focusedRow == null) jumpTo(categories.first().id)
    }

    BackHandler {
        val first = categories.firstOrNull()?.id
        val open = selected?.takeIf { id -> categories.any { it.id == id } }
        when {
            first == null -> onBackToLive()
            focusedRow == null -> jumpTo(open ?: first)
            focusedRow != first -> jumpTo(first)
            else -> onBackToLive()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
        CategoriesColumn(
            categories = categories,
            load = categoriesLoad,
            selected = selected,
            jump = jump,
            onJumpDone = { jump = null },
            onFocusedRowChanged = { focusedRow = it },
            onSelect = viewModel::select,
            onEnter = { row ->
                viewModel.select(row.id)
                moviesTopFor = if (row.loaded && row.movieCount == 0) null else row.id
            },
            onRetry = viewModel::retryCategories,
        )
        MoviesColumn(
            category = categories.firstOrNull { it.id == selected },
            load = selected?.let { moviesLoad[it] },
            movies = movies,
            returnTarget = returnTarget,
            focusTopFor = moviesTopFor,
            onTopFocused = { moviesTopFor = null },
            onOpen = { movie, index ->
                viewModel.rememberOpened(movie.id, index)
                onOpenMovie(movie.playlistId, movie.streamId)
            },
        )
    }
}

@Composable
private fun CategoriesColumn(
    categories: List<MovieCategoryRow>,
    load: Load?,
    selected: Long?,
    jump: CategoryJump?,
    onJumpDone: () -> Unit,
    onFocusedRowChanged: (Long?) -> Unit,
    onSelect: (Long) -> Unit,
    onEnter: (MovieCategoryRow) -> Unit,
    onRetry: () -> Unit,
) {
    if (categories.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(320.dp).padding(top = 8.dp)) {
            when (load) {
                is Load.Failed -> {
                    Text("Couldn't load the movie categories: ${load.message}", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text("Try again") }
                }
                else -> Text("Loading movie categories…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val jumpFocus = remember { FocusRequester() }
    var focusedRow by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(focusedRow) { onFocusedRowChanged(focusedRow) }
    LaunchedEffect(jump) {
        val target = jump?.categoryId ?: return@LaunchedEffect
        val index = categories.indexOfFirst { it.id == target }.coerceAtLeast(0)
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == target }) listState.scrollToItem(index)
        // Coming from outside the column, the focus restorer sends the first request to the
        // category focused last; the next one, from inside, reaches the target.
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (focusedRow == target) {
                onJumpDone()
                return@LaunchedEffect
            }
            if (listState.layoutInfo.visibleItemsInfo.any { it.key == target }) jumpFocus.requestFocus()
        }
        onJumpDone()
    }

    // A category's movies show once focus rests on it; one not downloaded yet waits longer, so
    // passing over categories with the D-pad held fetches nothing.
    var focusedCategory by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(focusedCategory) {
        val id = focusedCategory ?: return@LaunchedEffect
        val loaded = categories.firstOrNull { it.id == id }?.loaded == true
        delay(if (loaded) SETTLE_MS else DOWNLOAD_SETTLE_MS)
        onSelect(id)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .focusRestorer(),
    ) {
        items(categories, key = { it.id }) { row ->
            DenseListItem(
                selected = row.id == selected,
                onClick = { onEnter(row) },
                headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = row.movieCount?.let { count -> { Text("%,d".format(count)) } },
                modifier = Modifier
                    .then(if (row.id == jump?.categoryId) Modifier.focusRequester(jumpFocus) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedRow = row.id
                            focusedCategory = row.id
                        } else if (focusedRow == row.id) {
                            focusedRow = null
                        }
                    }
                    .onKeyEvent { event ->
                        // Right goes to the category's first movie, not the one level with this row.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            onEnter(row)
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

@Composable
private fun MoviesColumn(
    category: MovieCategoryRow?,
    load: Load?,
    movies: LazyPagingItems<MovieRow>,
    returnTarget: MovieReturn?,
    focusTopFor: Long?,
    onTopFocused: () -> Unit,
    onOpen: (movie: MovieRow, index: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Text(
            when {
                category == null -> ""
                category.movieCount == 1 -> "${category.title} · 1 movie"
                category.movieCount != null -> "${category.title} · ${"%,d".format(category.movieCount)} movies"
                else -> category.title
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val message = when {
            category == null -> null
            load is Load.Failed -> "Couldn't load these movies: ${load.message}. Press OK on the category to try again."
            load == Load.Running -> "Loading movies…"
            movies.itemCount == 0 && movies.loadState.refresh is LoadState.Loading -> return@Column
            movies.itemCount == 0 && category.loaded -> "No movies in this category."
            movies.itemCount == 0 -> return@Column
            else -> null
        }
        if (message != null) {
            Text(
                message,
                color = if (load is Load.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        // A fresh list state per category, so a new category starts at its top.
        val listState = remember(category?.id) { LazyListState() }
        val returnFocus = remember { FocusRequester() }
        val topFocus = remember { FocusRequester() }
        val listIdle by rememberScrollIdle(listState)
        LaunchedEffect(returnTarget) {
            val target = returnTarget ?: return@LaunchedEffect
            listState.scrollToItem(target.index)
            repeat(FOCUS_FRAMES) {
                if (listState.layoutInfo.visibleItemsInfo.any { it.key == target.movieId }) {
                    withFrameNanos {}
                    returnFocus.requestFocus()
                    return@LaunchedEffect
                }
                withFrameNanos {}
            }
        }
        LaunchedEffect(focusTopFor, listState) {
            // The open list may still be the previous category for a frame or two after OK/Right.
            if (focusTopFor == null || focusTopFor != category?.id) return@LaunchedEffect
            listState.scrollToItem(0)
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                val first = movies.peek(0)
                if (movies.loadState.refresh is LoadState.NotLoading && first != null &&
                    listState.layoutInfo.visibleItemsInfo.any { it.key == first.id }
                ) {
                    topFocus.requestFocus()
                    onTopFocused()
                    return@LaunchedEffect
                }
            }
            onTopFocused()
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = movies.itemCount, key = movies.itemKey { it.id }) { index ->
                val movie = movies[index]
                if (movie == null) {
                    // Placeholder while its page loads: same height, not focusable.
                    ListItem(selected = false, onClick = {}, enabled = false, headlineContent = { Text("") }, leadingContent = { PosterFrame(null, false) })
                } else {
                    MovieItem(
                        movie,
                        onClick = { onOpen(movie, index) },
                        postersMayDownload = listIdle,
                        modifier = Modifier
                            .then(if (movie.id == returnTarget?.movieId) Modifier.focusRequester(returnFocus) else Modifier)
                            .then(if (index == 0) Modifier.focusRequester(topFocus) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieItem(movie: MovieRow, onClick: () -> Unit, postersMayDownload: Boolean, modifier: Modifier = Modifier) {
    val details = listOfNotNull(movie.year?.toString(), movie.rating?.let { "★ %.1f".format(it) }).joinToString(" · ")
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(movie.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isEmpty()) null else { { Text(details, maxLines = 1) } },
        leadingContent = { PosterFrame(movie.posterUrl, postersMayDownload) },
        modifier = modifier.fillMaxWidth(),
    )
}

/** A small 2:3 poster, with a plain frame while it loads or when there is none. */
@Composable
private fun PosterFrame(url: String?, mayDownload: Boolean) {
    Box(
        Modifier
            .size(width = 36.dp, height = 54.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
    ) {
        ChannelLogo(url, Modifier.fillMaxSize(), mayDownload = mayDownload, contentScale = ContentScale.Crop)
    }
}

/** Back moves focus to a category; [seq] makes each press a new request. */
private data class CategoryJump(val categoryId: Long, val seq: Int)

private const val FOCUS_FRAMES = 60
private const val SETTLE_MS = 250L
private const val DOWNLOAD_SETTLE_MS = 700L
