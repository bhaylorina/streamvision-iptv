package com.streamvision.iptv.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>
    
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long
    
    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)
    
    @Delete
    suspend fun deletePlaylistById(playlist: PlaylistEntity)
    
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: Long)
    
    // ✅ NEW: Channel count update karne ke liye
    @Query("UPDATE playlists SET channelCount = :count WHERE id = :id")
    suspend fun updateChannelCount(id: Long, count: Int)
    
    // ✅ Ye function pehle se hona chahiye (check karna)
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun getChannelCount(playlistId: Long): Int
}
