package com.fireiptv.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        GroupEntity::class,
        ChannelEntity::class,
        ChannelFts::class,
        FavoriteEntity::class,
        RecentEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        // v4 changed the search tokenizer; Room recreates the index and refills it from the channels.
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun groupDao(): GroupDao
    abstract fun channelDao(): ChannelDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        fun build(context: Context, inMemory: Boolean = false): PlayerDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java)
            } else {
                Room.databaseBuilder(context, PlayerDatabase::class.java, "player.db")
            }
            return builder
                // Room's default turns WAL off on devices that report low RAM; readers and the importer need it.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(
                    object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            db.query("PRAGMA synchronous = NORMAL").close()
                        }
                    },
                )
                .build()
        }
    }
}
