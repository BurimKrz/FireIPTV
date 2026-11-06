package com.fireiptv.core.data.channel

/** A list of channels the user can browse and zap through. */
sealed interface ChannelList {
    data object All : ChannelList
    data object Favorites : ChannelList
    data object Recents : ChannelList
    data class Group(val id: Long) : ChannelList

    /** A category the user made. */
    data class Custom(val id: Long) : ChannelList

    /** Short form for navigation arguments; [decode] reads it back. */
    fun encode(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        Recents -> "recents"
        is Group -> "group-$id"
        is Custom -> "mine-$id"
    }

    companion object {
        fun decode(value: String?): ChannelList = when {
            value == "favorites" -> Favorites
            value == "recents" -> Recents
            value?.startsWith("group-") == true -> value.removePrefix("group-").toLongOrNull()?.let(::Group) ?: All
            value?.startsWith("mine-") == true -> value.removePrefix("mine-").toLongOrNull()?.let(::Custom) ?: All
            else -> All
        }
    }
}
