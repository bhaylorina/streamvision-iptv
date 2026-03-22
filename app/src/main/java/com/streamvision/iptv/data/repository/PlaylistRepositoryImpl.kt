package com.streamvision.iptv.data.repository

import com.streamvision.iptv.data.local.ChannelDao
import com.streamvision.iptv.data.local.PlaylistDao
import com.streamvision.iptv.data.local.PlaylistEntity
import com.streamvision.iptv.data.model.M3UParser
import com.streamvision.iptv.data.model.toDomain
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.repository.ChannelRepository
import com.streamvision.iptv.domain.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val channelRepository: ChannelRepository
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { entity ->
                val count = channelDao.getChannelCount(entity.id)
                entity.toDomain().copy(channelCount = count)
            }
        }
    }

    override suspend fun getPlaylistById(id: Long): Playlist? {
        return playlistDao.getPlaylistById(id)?.toDomain()
    }

    override suspend fun addPlaylist(name: String, url: String): Long {
        // First, fetch and parse the playlist
        val channels = fetchAndParsePlaylist(url)
        
        // Save playlist to database
        val playlistEntity = PlaylistEntity(name = name, url = url)
        val playlistId = playlistDao.insertPlaylist(playlistEntity)
        
        // Save channels
        if (channels.isNotEmpty()) {
            channelRepository.saveChannels(channels, playlistId)
        }
        
        return playlistId
    }

    override suspend fun updatePlaylist(playlist: Playlist) {
        playlistDao.updatePlaylist(playlist.toEntity())
    }

    override suspend fun deletePlaylist(id: Long) {
        channelRepository.deleteChannelsByPlaylist(id)
        playlistDao.deletePlaylistById(id)
    }

    /**
     * Fetch playlist content from URL and parse it
     */
    suspend fun fetchAndParsePlaylist(url: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val content = fetchUrlContent(url)
            if (M3UParser.isValidM3U(content)) {
                // Use a temporary playlist ID (will be updated when actually saved)
                M3UParser.parse(content, playlistId = 0)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Fetch content from URL
     */
    private fun fetchUrlContent(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "StreamVisionIPTV/1.0")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        
        return try {
            connection.responseCode.let { code ->
                if (code == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                } else {
                    throw Exception("HTTP error: $code")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Playlist.toEntity() = PlaylistEntity(
        id = id,
        name = name,
        url = url,
        createdAt = createdAt
    )
}
