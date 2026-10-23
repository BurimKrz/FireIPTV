package com.fireiptv.core.data.channel

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.fireiptv.core.data.db.ChannelListItem
import com.fireiptv.core.data.db.GroupSummary
import com.fireiptv.core.data.db.PlayerDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Read side of the channel tables, for browsing. Everything here is local; nothing touches the network. */
@Singleton
class ChannelRepository @Inject constructor(db: PlayerDatabase) {
    private val groupDao = db.groupDao()
    private val channelDao = db.channelDao()

    fun groups(playlistId: Long): Flow<List<GroupSummary>> = groupDao.observeSummaries(playlistId)

    /** Channels of one group, or of the whole playlist when [groupId] is null, in playlist order. */
    fun channels(playlistId: Long, groupId: Long?): Flow<PagingData<ChannelListItem>> =
        Pager(
            // Placeholders keep the scrollbar honest on a 28k list; the jump threshold skips the
            // pages in between when the user holds the D-pad down.
            PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2, jumpThreshold = PAGE_SIZE * 3),
        ) {
            if (groupId == null) {
                channelDao.pageListByPlaylist(playlistId)
            } else {
                channelDao.pageListByGroup(playlistId, groupId)
            }
        }.flow

    private companion object {
        const val PAGE_SIZE = 60
    }
}
