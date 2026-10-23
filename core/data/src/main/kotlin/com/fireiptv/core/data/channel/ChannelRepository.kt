package com.fireiptv.core.data.channel

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.fireiptv.core.data.db.ChannelEntity
import com.fireiptv.core.data.db.FavoriteEntity
import com.fireiptv.core.data.db.GroupSetting
import com.fireiptv.core.data.db.ChannelListItem
import com.fireiptv.core.data.db.ChannelSearchItem
import com.fireiptv.core.data.db.GroupSummary
import com.fireiptv.core.data.db.PlayerDatabase
import com.fireiptv.core.data.db.RecentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Read side of the channel tables, for browsing. Everything here is local; nothing touches the network. */
@Singleton
class ChannelRepository @Inject constructor(db: PlayerDatabase) {
    private val groupDao = db.groupDao()
    private val channelDao = db.channelDao()
    private val libraryDao = db.libraryDao()

    /** Groups the user hasn't hidden. */
    fun groups(playlistId: Long): Flow<List<GroupSummary>> = groupDao.observeSummaries(playlistId)

    /** Every group with its hidden flag, for settings. */
    fun groupSettings(playlistId: Long): Flow<List<GroupSetting>> = groupDao.observeSettings(playlistId)

    suspend fun setGroupHidden(groupId: Long, hidden: Boolean) = groupDao.setHidden(groupId, hidden)

    suspend fun setAllGroupsHidden(playlistId: Long, hidden: Boolean) = groupDao.setAllHidden(playlistId, hidden)

    /** The size of "All channels": channels outside hidden groups. */
    fun visibleCount(playlistId: Long): Flow<Int> = channelDao.observeVisibleCount(playlistId)

    fun favoriteCount(playlistId: Long): Flow<Int> = libraryDao.observeFavoriteCount(playlistId)

    fun recentCount(playlistId: Long): Flow<Int> = libraryDao.observeRecentCount(playlistId)

    /** The channels of [list]: playlist order, favourites in the order added, recents newest first. */
    fun channels(playlistId: Long, list: ChannelList): Flow<PagingData<ChannelListItem>> =
        Pager(
            // Placeholders keep the scrollbar honest on a 28k list; the jump threshold skips the
            // pages in between when the user holds the D-pad down.
            PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2, jumpThreshold = PAGE_SIZE * 3),
        ) {
            when (list) {
                ChannelList.All -> channelDao.pageListByPlaylist(playlistId)
                is ChannelList.Group -> channelDao.pageListByGroup(playlistId, list.id)
                ChannelList.Favorites -> libraryDao.pageFavorites(playlistId)
                ChannelList.Recents -> libraryDao.pageRecents(playlistId)
            }
        }.flow

    /**
     * Channels whose name has words starting with each typed word, in playlist order: "bbc 1"
     * finds "UK| BBC 1 HD". Case and accents don't matter.
     */
    fun search(playlistId: Long, text: String): Flow<List<ChannelSearchItem>> {
        val words = text.split(NOT_A_LETTER_OR_DIGIT).filter { it.isNotEmpty() }.take(MAX_SEARCH_WORDS)
        if (words.isEmpty()) return flowOf(emptyList())
        // Only letters and digits reach MATCH, so typed quotes or operators can't break the query.
        return channelDao.search(playlistId, words.joinToString(" ") { "$it*" }, SEARCH_LIMIT)
    }

    suspend fun channel(id: Long): ChannelEntity? = channelDao.get(id)

    suspend fun groupTitle(groupId: Long): String? = groupDao.title(groupId)

    /**
     * The channel after (or before) [from] in [list], wrapping around at either end. Recents
     * reorder as you watch, so they aren't a zap list; callers zap a recent channel's group instead.
     */
    suspend fun neighbour(from: ChannelEntity, list: ChannelList, forward: Boolean): ChannelEntity? {
        val playlistId = from.playlistId
        if (list == ChannelList.Favorites) {
            // A channel just removed from favourites has no place in them: start from either end.
            val position = libraryDao.favoriteSortOrder(playlistId, from.streamUrl)
                ?: if (forward) Long.MIN_VALUE else Long.MAX_VALUE
            return if (forward) {
                libraryDao.favoriteAfter(playlistId, position) ?: libraryDao.favoriteAfter(playlistId, Long.MIN_VALUE)
            } else {
                libraryDao.favoriteBefore(playlistId, position) ?: libraryDao.favoriteBefore(playlistId, Long.MAX_VALUE)
            }
        }
        val groupId = (list as? ChannelList.Group)?.id
        return if (forward) {
            channelDao.after(playlistId, groupId, from.sortOrder) ?: channelDao.first(playlistId, groupId)
        } else {
            channelDao.before(playlistId, groupId, from.sortOrder) ?: channelDao.last(playlistId, groupId)
        }
    }

    /** Index of [channel] in the browse list [list], or null if it isn't in it. */
    suspend fun position(channel: ChannelEntity, list: ChannelList): Int? = when (list) {
        ChannelList.All -> channelDao.position(channel.playlistId, null, channel.sortOrder)
        is ChannelList.Group -> channelDao.position(channel.playlistId, list.id, channel.sortOrder)
        ChannelList.Favorites -> libraryDao.favoriteSortOrder(channel.playlistId, channel.streamUrl)
            ?.let { libraryDao.favoritePosition(channel.playlistId, it) }
        ChannelList.Recents -> libraryDao.watchedAt(channel.playlistId, channel.streamUrl)
            ?.let { libraryDao.recentPosition(channel.playlistId, it) }
    }

    fun observeFavorite(channel: ChannelEntity): Flow<Boolean> =
        libraryDao.observeIsFavorite(channel.playlistId, channel.streamUrl)

    /** Adds the channel to favourites, or removes it; returns whether it is a favourite now. */
    suspend fun toggleFavorite(channelId: Long): Boolean {
        val channel = channelDao.get(channelId) ?: return false
        val isFavorite = libraryDao.favoriteSortOrder(channel.playlistId, channel.streamUrl) != null
        if (isFavorite) {
            libraryDao.removeFavorite(channel.playlistId, channel.streamUrl)
        } else {
            libraryDao.addFavorite(FavoriteEntity(channel.playlistId, channel.streamUrl, channel.name, System.currentTimeMillis()))
        }
        return !isFavorite
    }

    suspend fun recordWatched(channel: ChannelEntity) {
        libraryDao.upsertRecent(RecentEntity(channel.playlistId, channel.streamUrl, channel.name, System.currentTimeMillis()))
        libraryDao.trimRecents(channel.playlistId, keep = RECENTS_KEPT)
    }

    private val lastPlayed = AtomicReference<Long?>(null)

    /** The player records each channel it opens, so the browser can return focus to it. */
    fun setLastPlayed(channelId: Long) = lastPlayed.set(channelId)

    /** The channel last played since the browser last asked, or null. */
    fun takeLastPlayed(): Long? = lastPlayed.getAndSet(null)

    private companion object {
        const val PAGE_SIZE = 60
        const val RECENTS_KEPT = 30
        const val SEARCH_LIMIT = 300
        const val MAX_SEARCH_WORDS = 6
        val NOT_A_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")
    }
}
