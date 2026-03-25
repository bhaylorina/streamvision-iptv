package com.streamvision.iptv.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Projection used by [PlaylistDao.getAllPlaylistsWithCount] to avoid N+1 queries.
 */
data class PlaylistEntityWithCount(
    @ColumnInfo(name = "id")          val id: Long = 0,
    @ColumnInfo(name = "name")        val name: String = "",
    @ColumnInfo(name = "url")         val url: String = "",
    @ColumnInfo(name = "createdAt")   val createdAt: Long = 0L,
    @ColumnInfo(name = "channelCount") val channelCount: Int = 0
)

@Dao
interface PlaylistDao {

    /** Returns all playlists with accurate channel counts using a single JOIN query. */
    @Query("""
        SELECT p.id, p.name, p.url, p.createdAt, COUNT(c.id) AS channelCount
        FROM playlists p
        LEFT JOIN channels c ON c.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.createdAt DESC
    """)
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistEntityWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    /** Delete by entity object (used internally). */
    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    /** Delete by primary key. */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: Long)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun getChannelCount(playlistId: Long): Int
}
