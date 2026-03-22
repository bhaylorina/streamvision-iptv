package com.streamvision.iptv.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY name ASC")
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<ChannelEntity>>
    
    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>
    
    @Query("SELECT * FROM channels WHERE lastWatched IS NOT NULL ORDER BY lastWatched DESC LIMIT 20")
    fun getRecentChannels(): Flow<List<ChannelEntity>>
    
    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getChannelById(id: Long): ChannelEntity?
    
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchChannels(query: String): Flow<List<ChannelEntity>>
    
    @Query("SELECT DISTINCT groupName FROM channels WHERE playlistId = :playlistId AND groupName IS NOT NULL ORDER BY groupName ASC")
    fun getChannelGroups(playlistId: Long): Flow<List<String>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)
    
    @Update
    suspend fun updateChannel(channel: ChannelEntity)
    
    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :channelId")
    suspend fun updateFavoriteStatus(channelId: Long, isFavorite: Boolean)
    
    @Query("UPDATE channels SET lastWatched = :timestamp WHERE id = :channelId")
    suspend fun updateLastWatched(channelId: Long, timestamp: Long)
    
    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long)
    
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun getChannelCount(playlistId: Long): Int
}
