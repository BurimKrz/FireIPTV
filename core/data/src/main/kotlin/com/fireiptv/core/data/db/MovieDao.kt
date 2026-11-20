package com.fireiptv.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {

    @Query("SELECT * FROM movie_category WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun observeCategories(playlistId: Long): Flow<List<MovieCategoryEntity>>

    @Query("SELECT COUNT(*) FROM movie_category WHERE playlistId = :playlistId")
    suspend fun categoryCount(playlistId: Long): Int

    @Query("SELECT * FROM movie_category WHERE id = :id")
    suspend fun category(id: Long): MovieCategoryEntity?

    @Insert
    suspend fun insertCategories(categories: List<MovieCategoryEntity>)

    @Query("UPDATE movie_category SET loadedAt = :loadedAt, movieCount = :count WHERE id = :id")
    suspend fun setLoaded(id: Long, loadedAt: Long, count: Int)

    @Insert
    suspend fun insertMovies(movies: List<MovieEntity>)

    @Query("DELETE FROM movie WHERE categoryId = :categoryId")
    suspend fun deleteMovies(categoryId: Long)

    @Query("SELECT * FROM movie WHERE categoryId = :categoryId ORDER BY sortOrder")
    fun pageMovies(categoryId: Long): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movie WHERE id = :id")
    suspend fun movie(id: Long): MovieEntity?

    /** A refresh drops the movie lists, so they download fresh when next opened. */
    @Query("DELETE FROM movie_category WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: Long)
}
