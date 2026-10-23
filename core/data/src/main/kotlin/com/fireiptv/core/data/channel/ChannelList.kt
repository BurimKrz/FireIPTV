package com.fireiptv.core.data.channel

/** A list of channels the user can browse and zap through. */
sealed interface ChannelList {
    data object All : ChannelList
    data object Favorites : ChannelList
    data object Recents : ChannelList
    data class Group(val id: Long) : ChannelList

    /** Short form for navigation arguments; [decode] reads it back. */
    fun encode(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        Recents -> "recents"
        is Group -> "group-$id"
    }

    companion object {
        fun decode(value: String?): ChannelList = when {
            value == "favorites" -> Favorites
            value == "recents" -> Recents
            value?.startsWith("group-") == true -> value.removePrefix("group-").toLongOrNull()?.let(::Group) ?: All
            else -> All
        }
    }
}
