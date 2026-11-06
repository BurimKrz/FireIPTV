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
import com.fireiptv.core.data.db.UserListEntity
import com.fireiptv.core.data.db.UserListEntryEntity
import com.fireiptv.core.data.db.UserListSummary
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
    private val userListDao = db.userListDao()

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

    /** The user's own categories, oldest first. */
    fun userLists(playlistId: Long): Flow<List<UserListSummary>> = userListDao.observeLists(playlistId)

    /** Ids of the user's categories that hold [channel]. */
    fun listsWith(channel: ChannelEntity): Flow<List<Long>> = userListDao.observeListsWith(channel.playlistId, channel.streamUrl)

    /** Makes a category and returns its id. */
    suspend fun createList(playlistId: Long, title: String): Long =
        userListDao.insertList(UserListEntity(playlistId = playlistId, title = title, createdAt = System.currentTimeMillis()))

    suspend fun renameList(listId: Long, title: String) = userListDao.rename(listId, title)

    suspend fun deleteList(listId: Long) = userListDao.delete(listId)

    /** Adds the channel to the category, or takes it out; returns whether it is in it now. */
    suspend fun toggleInList(listId: Long, channelId: Long): Boolean {
        val channel = channelDao.get(channelId) ?: return false
        val isIn = userListDao.entrySortOrder(listId, channel.streamUrl) != null
        if (isIn) {
            userListDao.removeEntry(listId, channel.streamUrl)
        } else {
            userListDao.addEntry(UserListEntryEntity(listId, channel.streamUrl, channel.name, System.currentTimeMillis()))
        }
        return !isIn
    }

    /** Pins a group or category to the top of home, or unpins it. Other lists can't be pinned. */
    suspend fun setPinned(list: ChannelList, pinned: Boolean) {
        val pinnedAt = if (pinned) System.currentTimeMillis() else null
        when (list) {
            is ChannelList.Group -> groupDao.setPinnedAt(list.id, pinnedAt)
            is ChannelList.Custom -> userListDao.setPinnedAt(list.id, pinnedAt)
            else -> Unit
        }
    }

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
                is ChannelList.Custom -> userListDao.pageEntries(list.id)
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

    suspend fun listTitle(listId: Long): String? = userListDao.title(listId)

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
        if (list is ChannelList.Custom) {
            val position = userListDao.entrySortOrder(list.id, from.streamUrl)
                ?: if (forward) Long.MIN_VALUE else Long.MAX_VALUE
            return if (forward) {
                userListDao.entryAfter(list.id, position) ?: userListDao.entryAfter(list.id, Long.MIN_VALUE)
            } else {
                userListDao.entryBefore(list.id, position) ?: userListDao.entryBefore(list.id, Long.MAX_VALUE)
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
        is ChannelList.Custom -> userListDao.entrySortOrder(list.id, channel.streamUrl)
            ?.let { userListDao.entryPosition(list.id, it) }
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
