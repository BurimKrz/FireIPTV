package com.fireiptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Surface
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.core.data.playlist.PlaylistRepository
import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.ui.FireTheme
import com.fireiptv.core.ui.LocalLogosEnabled
import com.fireiptv.feature.browse.BrowseScreen
import com.fireiptv.feature.browse.search.SearchScreen
import com.fireiptv.feature.player.PlayerScreen
import com.fireiptv.feature.player.PlayerViewModel
import com.fireiptv.feature.settings.account.AccountScreen
import com.fireiptv.feature.settings.groups.GroupsScreen
import com.fireiptv.feature.settings.home.SettingsScreen
import com.fireiptv.feature.settings.playlists.AddPlaylistScreen
import com.fireiptv.feature.settings.playlists.PlaylistsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Typing a long URL with a remote is painful, so it can be sent over adb:
        // adb shell am start -n com.fireiptv.player/.MainActivity --es playlist_url "http://..."
        val prefillUrl = intent.getStringExtra(EXTRA_PLAYLIST_URL)

        setContent {
            FireTheme {
                val logosEnabled by settingsRepository.logosEnabled.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalLogosEnabled provides logosEnabled) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(playlistRepository, prefillUrl)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_URL = "playlist_url"
    }
}

private object Routes {
    const val BROWSE = "browse"
    const val PLAYLISTS = "playlists"
    const val ADD_PLAYLIST = "playlists/add"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val GROUPS = "settings/groups"
    const val ACCOUNT = "settings/account"
    const val PLAYER = "player/{${PlayerViewModel.ARG_CHANNEL_ID}}?list={${PlayerViewModel.ARG_LIST}}"

    fun player(channelId: Long, list: ChannelList) = "player/$channelId?list=${list.encode()}"
}

@Composable
private fun AppNavigation(repository: PlaylistRepository, prefillUrl: String?) {
    val hasPlaylists by produceState<Boolean?>(initialValue = null) { value = repository.hasPlaylists() }
    val start = when (hasPlaylists) {
        null -> return // first frame only, while the database answers
        true -> if (prefillUrl != null) Routes.ADD_PLAYLIST else Routes.BROWSE
        false -> Routes.ADD_PLAYLIST
    }

    val navController = rememberNavController()
    NavHost(navController, startDestination = start) {
        composable(Routes.BROWSE) {
            BrowseScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onAddPlaylist = { navController.navigate(Routes.ADD_PLAYLIST) },
                onPlayChannel = { channelId, list -> navController.navigate(Routes.player(channelId, list)) },
            )
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
            SearchScreen(onPlayChannel = { channelId, list -> navController.navigate(Routes.player(channelId, list)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onOpenGroups = { navController.navigate(Routes.GROUPS) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
        composable(Routes.GROUPS) {
            GroupsScreen()
        }
        composable(Routes.ACCOUNT) {
            AccountScreen()
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(onAddPlaylist = { navController.navigate(Routes.ADD_PLAYLIST) })
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
