package com.streamvision.iptv.domain.repository

import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<Channel>>
    fun getFavoriteChannels(): Flow<List<Channel>>
    fun getRecentChannels(): Flow<List<Channel>>
    fun searchChannels(query: String): Flow<List<Channel>>
    fun getChannelGroups(playlistId: Long): Flow<List<String>>
    suspend fun getChannelById(id: Long): Channel?
    suspend fun toggleFavorite(channelId: Long)
    suspend fun updateLastWatched(channelId: Long)
    suspend fun saveChannels(channels: List<Channel>, playlistId: Long)
    suspend fun deleteChannelsByPlaylist(playlistId: Long)
}

interface PlaylistRepository {
    fun getAllPlaylists(): Flow<List<Playlist>>
    suspend fun getPlaylistById(id: Long): Playlist?
    suspend fun addPlaylist(name: String, url: String): Long
    suspend fun updatePlaylist(playlist: Playlist)
    suspend fun deletePlaylist(id: Long)
    /** Re-download and re-parse the playlist, replacing all stored channels. */
    suspend fun refreshPlaylist(id: Long)
}
