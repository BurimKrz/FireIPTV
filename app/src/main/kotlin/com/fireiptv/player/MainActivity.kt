package com.fireiptv.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Surface
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.channel.ChannelRepository
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.update.UpdateRepository
import com.fireiptv.core.data.usage.DataUsageRepository
import com.fireiptv.core.data.vod.Movie
import com.fireiptv.core.log.AppLog
import com.fireiptv.core.ui.FireTheme
import com.fireiptv.core.ui.LocalLogosEnabled
import com.fireiptv.feature.browse.BrowseScreen
import com.fireiptv.feature.browse.movies.MovieInfoScreen
import com.fireiptv.feature.browse.movies.MovieInfoViewModel
import com.fireiptv.feature.browse.search.SearchScreen
import com.fireiptv.feature.player.PlayerScreen
import com.fireiptv.feature.player.PlayerViewModel
import com.fireiptv.feature.player.movie.MoviePlayerScreen
import com.fireiptv.feature.player.movie.MoviePlayerViewModel
import com.fireiptv.feature.settings.account.AccountScreen
import com.fireiptv.feature.settings.groups.GroupsScreen
import com.fireiptv.feature.settings.hidden.HiddenChannelsScreen
import com.fireiptv.feature.settings.home.SettingsScreen
import com.fireiptv.feature.settings.log.LogScreen
import com.fireiptv.feature.settings.playlists.AddPlaylistScreen
import com.fireiptv.feature.settings.playlists.EditPlaylistScreen
import com.fireiptv.feature.settings.playlists.EditPlaylistViewModel
import com.fireiptv.feature.settings.playlists.PlaylistsScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var channelRepository: ChannelRepository
    @Inject lateinit var appLog: AppLog
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var updateRepository: UpdateRepository
    @Inject lateinit var dataUsage: DataUsageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Typing a long URL with a remote is painful, so it can be sent over adb:
        // adb shell am start -n com.fireiptv.player/.MainActivity --es playlist_url "http://..."
        val prefillUrl = intent.getStringExtra(EXTRA_PLAYLIST_URL)

        // The data counter reads the system's byte count while the app is on screen; nothing downloads otherwise.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    dataUsage.sample()
                    delay(DATA_SAMPLE_MS)
                }
            }
        }

        setContent {
            FireTheme {
                val logosEnabled by settingsRepository.logosEnabled.collectAsStateWithLifecycle()
                val externalPlayer by settingsRepository.externalPlayer.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalLogosEnabled provides logosEnabled) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(playlistRepository, prefillUrl, externalPlayer, ::playInOtherApp)
                    }
                }
            }
        }
    }

    /** Settings → Channel player: Other app. Hands the stream to another installed player, e.g. VLC. */
    private fun playInOtherApp(channelId: Long) {
        lifecycleScope.launch {
            val channel = checkNotNull(channelRepository.channel(channelId)) { "Channel $channelId is not in the database" }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(channel.streamUrl.toUri(), "video/*")
                .putExtra(EXTRA_TITLE, channel.name)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                appLog.error("No other video player is installed to open ${channel.name}")
                val message = "No other video player installed. Install VLC, or set Channel player back to FireIPTV."
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // At most one small request a day; nothing is downloaded until the user asks.
        updateRepository.checkIfDue()
    }

    override fun onStop() {
        dataUsage.sampleSoon()
        super.onStop()
    }

    companion object {
        const val EXTRA_PLAYLIST_URL = "playlist_url"

        // VLC shows this as the stream's name instead of the URL.
        private const val EXTRA_TITLE = "title"
        private const val DATA_SAMPLE_MS = 60_000L
    }
}

private object Routes {
    const val BROWSE = "browse"
    const val PLAYLISTS = "playlists"
    const val ADD_PLAYLIST = "playlists/add"
    const val EDIT_PLAYLIST = "playlists/edit/{${EditPlaylistViewModel.ARG_PLAYLIST_ID}}"

    fun editPlaylist(id: Long) = "playlists/edit/$id"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val GROUPS = "settings/groups"
    const val HIDDEN_CHANNELS = "settings/hidden"
    const val ACCOUNT = "settings/account"
    const val LOG = "settings/log"
    const val PLAYER = "player/{${PlayerViewModel.ARG_CHANNEL_ID}}?list={${PlayerViewModel.ARG_LIST}}"
    const val MOVIE = "movie/{${MovieInfoViewModel.ARG_PLAYLIST_ID}}/{${MovieInfoViewModel.ARG_STREAM_ID}}"
    const val MOVIE_PLAYER =
        "moviePlayer/{${MoviePlayerViewModel.ARG_PLAYLIST_ID}}/{${MoviePlayerViewModel.ARG_STREAM_ID}}?startMs={${MoviePlayerViewModel.ARG_START_MS}}"

    fun movie(playlistId: Long, streamId: String) = "movie/$playlistId/${Uri.encode(streamId)}"

    fun moviePlayer(movie: Movie, startMs: Long) = "moviePlayer/${movie.playlistId}/${Uri.encode(movie.streamId)}?startMs=$startMs"

    fun player(channelId: Long, list: ChannelList) = "player/$channelId?list=${list.encode()}"
}

@Composable
private fun AppNavigation(
    repository: PlaylistRepository,
    prefillUrl: String?,
    externalPlayer: Boolean,
    playInOtherApp: (channelId: Long) -> Unit,
) {
    val hasPlaylists by produceState<Boolean?>(initialValue = null) { value = repository.hasPlaylists() }
    val start = when (hasPlaylists) {
        null -> return // first frame only, while the database answers
        true -> if (prefillUrl != null) Routes.ADD_PLAYLIST else Routes.BROWSE
        false -> Routes.ADD_PLAYLIST
    }

    val navController = rememberNavController()
    val playChannel = { channelId: Long, list: ChannelList ->
        if (externalPlayer) playInOtherApp(channelId) else navController.navigate(Routes.player(channelId, list))
    }
    NavHost(navController, startDestination = start) {
        composable(Routes.BROWSE) {
            BrowseScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onAddPlaylist = { navController.navigate(Routes.ADD_PLAYLIST) },
                onEditPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onPlayChannel = playChannel,
                onOpenMovie = { playlistId, streamId -> navController.navigate(Routes.movie(playlistId, streamId)) },
            )
        }
        composable(
            Routes.MOVIE,
            arguments = listOf(
                navArgument(MovieInfoViewModel.ARG_PLAYLIST_ID) { type = NavType.LongType },
                navArgument(MovieInfoViewModel.ARG_STREAM_ID) { type = NavType.StringType },
            ),
        ) {
            MovieInfoScreen(onPlay = { movie, startMs -> navController.navigate(Routes.moviePlayer(movie, startMs)) })
        }
        composable(
            Routes.MOVIE_PLAYER,
            arguments = listOf(
                navArgument(MoviePlayerViewModel.ARG_PLAYLIST_ID) { type = NavType.LongType },
                navArgument(MoviePlayerViewModel.ARG_STREAM_ID) { type = NavType.StringType },
                navArgument(MoviePlayerViewModel.ARG_START_MS) { type = NavType.LongType },
            ),
        ) {
            MoviePlayerScreen(onExit = { navController.popBackStack() })
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument(PlayerViewModel.ARG_CHANNEL_ID) { type = NavType.LongType },
                navArgument(PlayerViewModel.ARG_LIST) {
                    type = NavType.StringType
                    defaultValue = ChannelList.All.encode()
                },
            ),
        ) {
            PlayerScreen()
        }
        composable(Routes.SEARCH) {
            SearchScreen(onPlayChannel = playChannel)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onOpenGroups = { navController.navigate(Routes.GROUPS) },
                onOpenHiddenChannels = { navController.navigate(Routes.HIDDEN_CHANNELS) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
        composable(Routes.GROUPS) {
            GroupsScreen()
        }
        composable(Routes.HIDDEN_CHANNELS) {
            HiddenChannelsScreen()
        }
        composable(Routes.ACCOUNT) {
            AccountScreen()
        }
        composable(Routes.LOG) {
            LogScreen()
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(
                onAddPlaylist = { navController.navigate(Routes.ADD_PLAYLIST) },
                onEditPlaylist = { navController.navigate(Routes.editPlaylist(it)) },
            )
        }
        composable(
            Routes.EDIT_PLAYLIST,
            arguments = listOf(navArgument(EditPlaylistViewModel.ARG_PLAYLIST_ID) { type = NavType.LongType }),
        ) {
            EditPlaylistScreen(onDone = { navController.popBackStack() })
        }
        composable(Routes.ADD_PLAYLIST) {
            AddPlaylistScreen(
                prefillUrl = prefillUrl,
                onAdded = {
                    // Home fills in while the import runs; Back from there leaves the app.
                    navController.navigate(Routes.BROWSE) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
