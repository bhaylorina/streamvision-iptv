package com.streamvision.iptv.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.streamvision.iptv.data.local.PlaylistDao
import com.streamvision.iptv.data.local.PlaylistEntity
import com.streamvision.iptv.data.model.M3UParser
import com.streamvision.iptv.data.model.toDomain
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.repository.ChannelRepository
import com.streamvision.iptv.domain.repository.PlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    private val channelRepository: ChannelRepository,
    @ApplicationContext private val context: Context
) : PlaylistRepository {

    // Single JOIN query — no N+1 per-playlist channel count
    override fun getAllPlaylists(): Flow<List<Playlist>> =
        playlistDao.getAllPlaylistsWithCount().map { rows ->
            rows.map { row ->
                Playlist(
                    id           = row.id,
                    name         = row.name,
                    url          = row.url,
                    channelCount = row.channelCount,
                    createdAt    = row.createdAt
                )
            }
        }

    override suspend fun getPlaylistById(id: Long): Playlist? =
        playlistDao.getPlaylistById(id)?.toDomain()

    override suspend fun addPlaylist(name: String, url: String): Long {
        val entity = PlaylistEntity(name = name, url = url)
        val playlistId = playlistDao.insertPlaylist(entity)

        val channels = fetchAndParsePlaylist(url, playlistId)
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

    override suspend fun refreshPlaylist(id: Long) {
        val playlist = playlistDao.getPlaylistById(id) ?: return
        val channels = fetchAndParsePlaylist(playlist.url, id)
        channelRepository.saveChannels(channels, id)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches and parses a playlist from either a network URL or a content:// / file:// URI.
     * Throws on network / IO error so callers can surface the failure to the user.
     */
    suspend fun fetchAndParsePlaylist(url: String, playlistId: Long): List<Channel> =
        withContext(Dispatchers.IO) {
            val content = when {
                url.startsWith("content://") || url.startsWith("file://") ->
                    readLocalUri(url.toUri())
                else ->
                    fetchUrlContent(url)
            }
            if (!M3UParser.isValidM3U(content)) {
                throw IllegalStateException("The file does not appear to be a valid M3U playlist")
            }
            M3UParser.parse(content, playlistId)
        }

    private fun readLocalUri(uri: Uri): String {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open URI: $uri — file may have been deleted")
            stream.use { BufferedReader(InputStreamReader(it)).readText() }
        } catch (e: SecurityException) {
            throw SecurityException(
                "Permission denied reading file. Please re-add the playlist to grant access.", e
            )
        } catch (e: java.io.FileNotFoundException) {
            throw java.io.FileNotFoundException(
                "File not found: the selected file may have been moved or deleted."
            )
        }
    }

    private fun fetchUrlContent(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "StreamVisionIPTV/1.0")
        connection.connectTimeout = 15_000
        connection.readTimeout    = 15_000
        return try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                throw Exception("HTTP error $code fetching playlist")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Playlist.toEntity() = PlaylistEntity(
        id        = id,
        name      = name,
        url       = url,
        createdAt = createdAt
    )
}
