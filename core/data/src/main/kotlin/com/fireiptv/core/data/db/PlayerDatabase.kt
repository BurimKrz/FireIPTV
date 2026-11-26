package com.fireiptv.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        GroupEntity::class,
        ChannelEntity::class,
        ChannelFts::class,
        FavoriteEntity::class,
        RecentEntity::class,
        UserListEntity::class,
        UserListEntryEntity::class,
        HiddenChannelEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        // v4 changed the search tokenizer; Room recreates the index and refills it from the channels.
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        // v6: the user's own categories, and pinned groups.
        AutoMigration(from = 5, to = 6),
        // v7: single hidden channels.
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun groupDao(): GroupDao
    abstract fun channelDao(): ChannelDao
    abstract fun libraryDao(): LibraryDao
    abstract fun userListDao(): UserListDao

    companion object {
        /**
         * FireIPTV Beta up to 1.1.0-beta.4 had movies: v8 and v9 only added these three tables to v7.
         * Dropping them takes such a database back to v7 with its playlists, favourites and lists.
         */
        private val MOVIES_REMOVED = object : Migration(9, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `watch_progress`")
                db.execSQL("DROP TABLE IF EXISTS `movie`")
                db.execSQL("DROP TABLE IF EXISTS `movie_category`")
            }
        }

        fun build(context: Context, inMemory: Boolean = false): PlayerDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java)
            } else {
                Room.databaseBuilder(context, PlayerDatabase::class.java, "player.db")
            }
            return builder
                // Room's default turns WAL off on devices that report low RAM; readers and the importer need it.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MOVIES_REMOVED)
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
